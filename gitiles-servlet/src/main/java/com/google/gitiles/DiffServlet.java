// Copyright 2012 Google Inc. All Rights Reserved.
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

import static com.google.common.base.Preconditions.checkNotNull;
import static javax.servlet.http.HttpServletResponse.SC_NOT_FOUND;

import com.google.common.base.Charsets;
import com.google.gitiles.CommitData.Field;
import com.google.gitiles.DateFormatterBuilder.DateFormatter;
import com.google.gitiles.DateFormatterBuilder.Format;

import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.errors.IncorrectObjectTypeException;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.http.server.ServletUtils;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.EmptyTreeIterator;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.PathFilter;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/** Serves an HTML page with all the diffs for a commit. */
public class DiffServlet extends BaseServlet {
  private static final long serialVersionUID = 1L;
  private static final String PLACEHOLDER = "id=\"DIFF_OUTPUT_BLOCK\"";

  private final DateFormatterBuilder dfb;
  private final Linkifier linkifier;

  public DiffServlet(DateFormatterBuilder dfb, GitilesAccess.Factory accessFactory,
      Renderer renderer, Linkifier linkifier) {
    super(renderer, accessFactory);
    this.dfb = checkNotNull(dfb, "dfb");
    this.linkifier = checkNotNull(linkifier, "linkifier");
  }

  @Override
  protected void doGet(HttpServletRequest req, HttpServletResponse res) throws IOException {
    GitilesView view = ViewFilter.getView(req);
    Repository repo = ServletUtils.getRepository(req);

    RevWalk walk = new RevWalk(repo);
    try {
      boolean showCommit, isFile;
      AbstractTreeIterator oldTree;
      AbstractTreeIterator newTree;
      TreeWalk tw = newTreeWalk(walk, view);
      if (tw == null) {
        res.setStatus(SC_NOT_FOUND);
        return;
      }
      try {
        // If we are viewing the diff between a commit and one of its parents,
        // include the commit detail in the rendered page.
        showCommit = isParentOf(walk, view.getOldRevision(), view.getRevision());
        isFile = showCommit ? isFile(tw) : false;
        oldTree = getTreeIterator(walk, view.getOldRevision().getId());
        newTree = getTreeIterator(walk, view.getRevision().getId());
      } catch (MissingObjectException | IncorrectObjectTypeException e) {
        res.setStatus(SC_NOT_FOUND);
        return;
      } finally {
        tw.release();
      }

      Map<String, Object> data = getData(req);
      data.put("title", "Diff - " + view.getRevisionRange());
      if (showCommit) {
        Set<Field> fs = CommitSoyData.DEFAULT_FIELDS;
        if (isFile) {
          fs = Field.setOf(fs, Field.PARENT_BLAME_URL);
        }
        DateFormatter df = dfb.create(Format.DEFAULT);
        data.put("commit", new CommitSoyData()
            .setLinkifier(linkifier)
            .setArchiveFormat(getArchiveFormat(getAccess(req)))
            .toSoyData(req, walk.parseCommit(view.getRevision().getId()), fs, df));
      }
      if (!data.containsKey("repositoryName") && (view.getRepositoryName() != null)) {
        data.put("repositoryName", view.getRepositoryName());
      }
      if (!data.containsKey("breadcrumbs")) {
        data.put("breadcrumbs", view.getBreadcrumbs());
      }

      String[] html = renderAndSplit(data);
      res.setStatus(HttpServletResponse.SC_OK);
      res.setContentType(FormatType.HTML.getMimeType());
      res.setCharacterEncoding(Charsets.UTF_8.name());
      setCacheHeaders(res);

      OutputStream out = res.getOutputStream();
      try {
        out.write(html[0].getBytes(Charsets.UTF_8));
        formatHtmlDiff(out, view, repo, oldTree, newTree, view.getPathPart());
        out.write(html[1].getBytes(Charsets.UTF_8));
      } finally {
        out.close();
      }
    } finally {
      walk.release();
    }
  }

  private static TreeWalk newTreeWalk(RevWalk walk, GitilesView view) throws IOException {
    if (view.getPathPart().isEmpty()) {
      TreeWalk tw = new TreeWalk(walk.getObjectReader());
      tw.reset(walk.parseTree(view.getRevision().getId()));
      tw.setRecursive(false);
      return tw;
    }
    return TreeWalk.forPath(
        walk.getObjectReader(),
        view.getPathPart(),
        walk.parseTree(view.getRevision().getId()));
  }

  private static boolean isParentOf(RevWalk walk, Revision oldRevision, Revision newRevision)
      throws MissingObjectException, IncorrectObjectTypeException, IOException {
    RevCommit newCommit = walk.parseCommit(newRevision.getId());
    if (newCommit.getParentCount() > 0) {
      return Arrays.asList(newCommit.getParents()).contains(oldRevision.getId());
    } else {
      return oldRevision == Revision.NULL;
    }
  }

  private static boolean isFile(TreeWalk tw) {
    return (tw.getRawMode(0) & FileMode.TYPE_FILE) > 0;
  }

  private String[] renderAndSplit(Map<String, Object> data) {
    String html = renderer.newRenderer("gitiles.diffDetail")
        .setData(data)
        .render();
    int id = html.indexOf(PLACEHOLDER);
    if (id < 0) {
      throw new IllegalStateException("Template must contain " + PLACEHOLDER);
    }

    int lt = html.lastIndexOf('<', id);
    int gt = html.indexOf('>', id + PLACEHOLDER.length());
    return new String[] {html.substring(0, lt), html.substring(gt + 1)};
  }

  private void formatHtmlDiff(OutputStream out, GitilesView view,
      Repository repo, AbstractTreeIterator oldTree,
      AbstractTreeIterator newTree, String path)
      throws IOException {
    DiffFormatter diff = new HtmlDiffFormatter(renderer, view, out);
    try {
      if (!path.equals("")) {
        diff.setPathFilter(PathFilter.create(path));
      }
      diff.setRepository(repo);
      diff.setDetectRenames(true);
      diff.format(oldTree, newTree);
    } finally {
      diff.release();
    }
  }

  private static AbstractTreeIterator getTreeIterator(RevWalk walk, ObjectId id)
      throws IOException {
    if (!id.equals(ObjectId.zeroId())) {
      CanonicalTreeParser p = new CanonicalTreeParser();
      p.reset(walk.getObjectReader(), walk.parseTree(id));
      return p;
    } else {
      return new EmptyTreeIterator();
    }
  }
}
