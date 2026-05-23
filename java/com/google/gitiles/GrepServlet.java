// Copyright 2026 Google Inc. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.gitiles;

import com.google.common.base.Strings;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.gitiles.GitilesRequestFailureException.FailureReason;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.util.List;
import javax.annotation.Nullable;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.errors.LargeObjectException;
import org.eclipse.jgit.http.server.ServletUtils;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevObject;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.util.RawParseUtils;

/** Serves file-content search results for a repository tree. */
public class GrepServlet extends BaseServlet {
  private static final long serialVersionUID = 1L;

  private static final String QUERY_PARAM = "q";
  private static final int MAX_MATCHES = 1000;
  private static final int MAX_BLOB_SIZE = 1 << 20; // 1 MB

  protected GrepServlet(GitilesAccess.Factory accessFactory) {
    super(null, accessFactory);
  }

  @Override
  protected void doGetJson(HttpServletRequest req, HttpServletResponse res) throws IOException {
    GitilesView view = ViewFilter.getView(req);
    String query = Iterables.getFirst(view.getParameters().get(QUERY_PARAM), null);
    if (Strings.isNullOrEmpty(query)) {
      throw new GitilesRequestFailureException(FailureReason.INCORRECT_PARAMETER)
          .withPublicErrorMessage("missing q parameter");
    }

    GrepResult result = grep(ServletUtils.getRepository(req), view, query);
    renderJson(req, res, result, new TypeToken<GrepResult>() {}.getType());
  }

  private static GrepResult grep(Repository repo, GitilesView view, String query)
      throws IOException {
    List<Match> matches = Lists.newArrayList();
    boolean completed = true;

    try (RevWalk rw = new RevWalk(repo)) {
      RevTree root = getRoot(view, rw);
      if (root == null) {
        throw new GitilesRequestFailureException(FailureReason.INCORRECT_OBJECT_TYPE)
            .withPublicErrorMessage("The object specified by the URL is not suitable for the view");
      }

      String path = Strings.nullToEmpty(view.getPathPart());
      if (path.isEmpty()) {
        completed = grepTree(rw.getObjectReader(), root, "", query, matches);
      } else {
        completed = grepPath(rw.getObjectReader(), root, path, query, matches);
      }
    }

    return new GrepResult(matches, !completed);
  }

  private static boolean grepPath(
      ObjectReader reader, RevTree root, String path, String query, List<Match> matches)
      throws IOException {
    try (TreeWalk tw = TreeWalk.forPath(reader, path, root)) {
      if (tw == null) {
        throw new GitilesRequestFailureException(FailureReason.OBJECT_NOT_FOUND);
      }

      FileMode mode = tw.getFileMode(0);
      ObjectId id = tw.getObjectId(0);
      if (mode.getObjectType() == Constants.OBJ_BLOB) {
        return grepBlob(reader, id, path, query, matches);
      }
      if (mode.getObjectType() != Constants.OBJ_TREE) {
        return true;
      }

      try (TreeWalk subtree = new TreeWalk(reader)) {
        subtree.addTree(id);
        subtree.setRecursive(true);
        return grepTree(subtree, path + "/", query, matches);
      }
    }
  }

  private static boolean grepTree(
      ObjectReader reader, RevTree root, String pathPrefix, String query, List<Match> matches)
      throws IOException {
    try (TreeWalk tw = new TreeWalk(reader)) {
      tw.addTree(root);
      tw.setRecursive(true);
      return grepTree(tw, pathPrefix, query, matches);
    }
  }

  private static boolean grepTree(TreeWalk tw, String pathPrefix, String query, List<Match> matches)
      throws IOException {
    while (tw.next()) {
      if (tw.getFileMode(0).getObjectType() != Constants.OBJ_BLOB) {
        continue;
      }
      if (!grepBlob(
          tw.getObjectReader(),
          tw.getObjectId(0),
          pathPrefix + tw.getPathString(),
          query,
          matches)) {
        return false;
      }
    }
    return true;
  }

  private static boolean grepBlob(
      ObjectReader reader, ObjectId id, String path, String query, List<Match> matches)
      throws IOException {
    ObjectLoader loader = reader.open(id, Constants.OBJ_BLOB);
    if (loader.getSize() > MAX_BLOB_SIZE) {
      return true;
    }

    byte[] raw;
    try {
      raw = loader.getCachedBytes(MAX_BLOB_SIZE);
    } catch (LargeObjectException e) {
      return true;
    }
    if (RawText.isBinary(raw)) {
      return true;
    }

    String[] lines = RawParseUtils.decode(raw).split("\n", -1);
    for (int i = 0; i < lines.length; i++) {
      String line =
          lines[i].endsWith("\r") ? lines[i].substring(0, lines[i].length() - 1) : lines[i];
      if (line.contains(query)) {
        matches.add(new Match(path, i + 1, line));
        if (matches.size() >= MAX_MATCHES) {
          return false;
        }
      }
    }
    return true;
  }

  private static @Nullable RevTree getRoot(GitilesView view, RevWalk rw) throws IOException {
    RevObject obj = rw.peel(rw.parseAny(view.getRevision().getId()));
    switch (obj.getType()) {
      case Constants.OBJ_COMMIT:
        return ((RevCommit) obj).getTree();
      case Constants.OBJ_TREE:
        return (RevTree) obj;
      default:
        return null;
    }
  }

  static class GrepResult {
    List<Match> matches;
    boolean truncated;

    GrepResult(List<Match> matches, boolean truncated) {
      this.matches = matches;
      this.truncated = truncated;
    }
  }

  static class Match {
    String path;
    int lineNumber;
    String line;

    Match(String path, int lineNumber, String line) {
      this.path = path;
      this.lineNumber = lineNumber;
      this.line = line;
    }
  }
}
