// Copyright 2015 Google Inc. All Rights Reserved.
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

package com.google.gitiles.doc;

import static javax.servlet.http.HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
import static javax.servlet.http.HttpServletResponse.SC_NOT_FOUND;
import static org.eclipse.jgit.lib.Constants.OBJ_BLOB;
import static org.eclipse.jgit.lib.Constants.OBJ_COMMIT;
import static org.eclipse.jgit.lib.Constants.OBJ_TREE;
import static org.eclipse.jgit.lib.FileMode.TYPE_FILE;
import static org.eclipse.jgit.lib.FileMode.TYPE_MASK;
import static org.eclipse.jgit.lib.FileMode.TYPE_TREE;

import com.google.common.base.MoreObjects;
import com.google.common.base.Strings;
import com.google.gitiles.BaseServlet;
import com.google.gitiles.GitilesAccess;
import com.google.gitiles.GitilesView;
import com.google.gitiles.Renderer;
import com.google.gitiles.ViewFilter;

import org.eclipse.jgit.errors.CorruptObjectException;
import org.eclipse.jgit.errors.IncorrectObjectTypeException;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.http.server.ServletUtils;
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
import org.pegdown.ParsingTimeoutException;
import org.pegdown.ast.RootNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public class DocServlet extends BaseServlet {
  private static final long serialVersionUID = 1L;
  private static final Logger log = LoggerFactory.getLogger(DocServlet.class);

  private static final String INDEX_MD = "index.md";
  private static final int MAX_MARKDOWN_BYTES = 5 << 20;

  public DocServlet(GitilesAccess.Factory accessFactory, Renderer renderer) {
    super(renderer, accessFactory);
  }

  @Override
  protected void doGetHtml(HttpServletRequest req, HttpServletResponse res)
      throws IOException {
    GitilesView view = ViewFilter.getView(req);
    Repository repo = ServletUtils.getRepository(req);
    RevWalk rw = new RevWalk(repo);
    try {
      String path = view.getPathPart();
      if (Strings.isNullOrEmpty(path)) {
        path = INDEX_MD;
      } else if (path.endsWith("/")) {
        path += INDEX_MD;
      }
      if (!path.endsWith(".md")) {
        res.setStatus(SC_NOT_FOUND);
        return;
      }

      RevTree root = getRoot(view, rw);
      if (root == null) {
        res.setStatus(SC_NOT_FOUND);
        return;
      }

      String srcmd = readFile(rw, root, path);
      if (srcmd == null) {
        res.setStatus(SC_NOT_FOUND);
        return;
      }

      MarkdownFormatter fmt = new MarkdownFormatter(view);
      RootNode doc = parseFile(view, path, fmt, srcmd);
      if (doc == null) {
        res.setStatus(SC_INTERNAL_SERVER_ERROR);
        return;
      }

      String navmd = readFile(rw, root, "navbar.md");
      RootNode nav = navmd != null
          ? parseFile(view, "navbar.md", fmt, navmd)
          : null;
      showDoc(req, res, fmt, nav, doc);
    } finally {
      rw.release();
    }
  }

  private void showDoc(HttpServletRequest req, HttpServletResponse res,
      MarkdownFormatter fmt, RootNode nav, RootNode doc) throws IOException {
    String title = MoreObjects.firstNonNull(
        Strings.emptyToNull(fmt.getTitle(doc)),
        ViewFilter.getView(req).getPathPart());

    Map<String, Object> data = new HashMap<>();
    data.put("breadcrumbs", null);
    data.put("repositoryName", null);
    data.put("title", title);
    data.put("navbarHtml", fmt.renderHTML(nav));
    data.put("bodyHtml", fmt.renderHTML(doc));
    renderHtml(req, res, "gitiles.markdownDoc", data);
  }

  private RootNode parseFile(GitilesView view, String path,
      MarkdownFormatter fmt, String md) {
    RootNode docTree;
    try {
      docTree = fmt.parseMarkdown(md);
    } catch (ParsingTimeoutException e) {
      log.error("timeout rendering {}/{} at {}",
          view.getRepositoryName(),
          path,
          view.getRevision().getName());
      return null;
    }
    if (docTree == null) {
      log.error("cannot parse {}/{} at {}",
          view.getRepositoryName(),
          path,
          view.getRevision().getName());
    }
    return docTree;
  }

  private static String readFile(RevWalk rw, RevTree root, String path)
      throws MissingObjectException, IncorrectObjectTypeException,
      CorruptObjectException, IOException {
    ObjectReader reader = rw.getObjectReader();
    TreeWalk tw = TreeWalk.forPath(reader, path, root);
    if (tw == null) {
      return null;
    }

    if (tw.getRawMode(0) == TYPE_TREE) {
      tw = TreeWalk.forPath(reader, INDEX_MD, tw.getObjectId(0));
      if (tw == null) {
        return null;
      }
    }

    if ((tw.getRawMode(0) & TYPE_MASK) == TYPE_FILE) {
      ObjectId id = tw.getObjectId(0);
      ObjectLoader obj = reader.open(id, OBJ_BLOB);
      byte[] raw = obj.getCachedBytes(MAX_MARKDOWN_BYTES);
      return RawParseUtils.decode(raw);
    }
    return null;
  }

  private static RevTree getRoot(GitilesView view, RevWalk rw) throws IOException {
    RevObject obj = rw.peel(rw.parseAny(view.getRevision().getId()));
    switch (obj.getType()) {
      case OBJ_COMMIT:
        return ((RevCommit) obj).getTree();
      case OBJ_TREE:
        return (RevTree) obj;
      default:
        return null;
    }
  }
}
