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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.gitiles.GitilesRequestFailureException.FailureReason;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.util.List;
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

  private static final String SUBSTRING_PARAM = "s";
  @VisibleForTesting static final int MAX_MATCHES = 1000;
  private static final int MAX_BLOB_SIZE = 1 << 20; // 1 MB

  protected GrepServlet(GitilesAccess.Factory accessFactory) {
    super(null, accessFactory);
  }

  @Override
  protected void doGetJson(HttpServletRequest req, HttpServletResponse res) throws IOException {
    GitilesView view = ViewFilter.getView(req);
    String substring = Iterables.getFirst(view.getParameters().get(SUBSTRING_PARAM), null);
    if (Strings.isNullOrEmpty(substring)) {
      throw new GitilesRequestFailureException(FailureReason.INCORRECT_PARAMETER)
          .withPublicErrorMessage("missing s parameter");
    }

    GrepResult result = grep(ServletUtils.getRepository(req), view, substring);
    renderJson(req, res, result, new TypeToken<GrepResult>() {}.getType());
  }

  private static GrepResult grep(Repository repo, GitilesView view, String substring)
      throws IOException {
    List<Match> matches = Lists.newArrayList();

    try (RevWalk rw = new RevWalk(repo)) {
      RevTree root = getRoot(view, rw);
      String path = Strings.nullToEmpty(view.getPathPart());
      if (path.isEmpty()) {
        grepTree(rw.getObjectReader(), root, "", substring, matches);
      } else {
        grepPath(rw.getObjectReader(), root, path, substring, matches);
      }
    }

    return new GrepResult(matches);
  }

  private static void grepPath(
      ObjectReader reader, RevTree root, String path, String substring, List<Match> matches)
      throws IOException {
    try (TreeWalk tw = TreeWalk.forPath(reader, path, root)) {
      if (tw == null) {
        throw new GitilesRequestFailureException(FailureReason.OBJECT_NOT_FOUND);
      }

      FileMode mode = tw.getFileMode(0);
      ObjectId id = tw.getObjectId(0);
      if (mode.getObjectType() == Constants.OBJ_BLOB) {
        grepBlob(reader, id, path, substring, matches);
        return;
      }
      if (mode.getObjectType() != Constants.OBJ_TREE) {
        return;
      }

      try (TreeWalk subtree = new TreeWalk(reader)) {
        subtree.addTree(id);
        subtree.setRecursive(true);
        grepTree(subtree, path + "/", substring, matches);
      }
    }
  }

  private static void grepTree(
      ObjectReader reader, RevTree root, String pathPrefix, String substring, List<Match> matches)
      throws IOException {
    try (TreeWalk tw = new TreeWalk(reader)) {
      tw.addTree(root);
      tw.setRecursive(true);
      grepTree(tw, pathPrefix, substring, matches);
    }
  }

  private static void grepTree(
      TreeWalk tw, String pathPrefix, String substring, List<Match> matches) throws IOException {
    while (tw.next()) {
      if (matches.size() >= MAX_MATCHES) {
        return;
      }
      if (tw.getFileMode(0).getObjectType() != Constants.OBJ_BLOB) {
        continue;
      }
      grepBlob(
          tw.getObjectReader(),
          tw.getObjectId(0),
          pathPrefix + tw.getPathString(),
          substring,
          matches);
      if (matches.size() >= MAX_MATCHES) {
        return;
      }
    }
  }

  private static void grepBlob(
      ObjectReader reader, ObjectId id, String path, String substring, List<Match> matches)
      throws IOException {
    ObjectLoader loader = reader.open(id, Constants.OBJ_BLOB);
    if (loader.getSize() > MAX_BLOB_SIZE) {
      return;
    }

    byte[] raw;
    try {
      raw = loader.getCachedBytes(MAX_BLOB_SIZE);
    } catch (LargeObjectException e) {
      return;
    }
    if (RawText.isBinary(raw)) {
      return;
    }

    String[] lines = RawParseUtils.decode(raw).split("\n", -1);
    for (int i = 0; i < lines.length; i++) {
      if (matches.size() >= MAX_MATCHES) {
        return;
      }
      String line =
          lines[i].endsWith("\r") ? lines[i].substring(0, lines[i].length() - 1) : lines[i];
      if (line.contains(substring)) {
        matches.add(new Match(path, i + 1, line));
        if (matches.size() >= MAX_MATCHES) {
          return;
        }
      }
    }
  }

  private static RevTree getRoot(GitilesView view, RevWalk rw) throws IOException {
    RevObject obj = rw.peel(rw.parseAny(view.getRevision().getId()));
    switch (obj.getType()) {
      case Constants.OBJ_COMMIT:
        return ((RevCommit) obj).getTree();
      case Constants.OBJ_TREE:
        return (RevTree) obj;
      default:
        throw new GitilesRequestFailureException(FailureReason.INCORRECT_OBJECT_TYPE)
            .withPublicErrorMessage("The specified object is not a tree-ish.");
    }
  }

  static class GrepResult {
    List<Match> matches;

    GrepResult(List<Match> matches) {
      this.matches = matches;
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
