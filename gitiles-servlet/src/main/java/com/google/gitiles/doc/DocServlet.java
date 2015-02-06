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
import static javax.servlet.http.HttpServletResponse.SC_NOT_MODIFIED;
import static org.eclipse.jgit.lib.Constants.OBJ_BLOB;
import static org.eclipse.jgit.lib.Constants.OBJ_COMMIT;
import static org.eclipse.jgit.lib.Constants.OBJ_TREE;
import static org.eclipse.jgit.lib.FileMode.TYPE_FILE;
import static org.eclipse.jgit.lib.FileMode.TYPE_MASK;
import static org.eclipse.jgit.lib.FileMode.TYPE_TREE;

import com.google.common.base.Joiner;
import com.google.common.base.MoreObjects;
import com.google.common.base.Strings;
import com.google.common.net.HttpHeaders;
import com.google.gitiles.BaseServlet;
import com.google.gitiles.GitilesAccess;
import com.google.gitiles.GitilesView;
import com.google.gitiles.Renderer;
import com.google.gitiles.ViewFilter;

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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public class DocServlet extends BaseServlet {
  private static final long serialVersionUID = 1L;
  private static final Logger log = LoggerFactory.getLogger(DocServlet.class);

  private static final String GEN = "1";
  private static final String INDEX_MD = "index.md";
  private static final int MAX_MD_BYTES = 5 << 20;

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
      RevTree root = getRoot(view, rw);
      if (root == null) {
        res.setStatus(SC_NOT_FOUND);
        return;
      }

      SourceFile srcmd = findFile(rw, root, path);
      if (srcmd == null) {
        res.setStatus(SC_NOT_FOUND);
        return;
      }

      SourceFile navmd = findFile(rw, root, "navbar.md");
      String etag = req.getHeader(HttpHeaders.IF_NONE_MATCH);
      if (etag != null && etag.equals(etag(srcmd, navmd))) {
        res.setStatus(SC_NOT_MODIFIED);
        return;
      }

      view = view.toBuilder().setPathPart(srcmd.path).build();
      srcmd.read(rw.getObjectReader());
      if (navmd != null) {
        navmd.read(rw.getObjectReader());
      }

      MarkdownHelper fmt = new MarkdownHelper(view);
      RootNode doc = parseFile(view, fmt, srcmd);
      if (doc == null) {
        res.setStatus(SC_INTERNAL_SERVER_ERROR);
        return;
      }

      RootNode nav = navmd != null
          ? parseFile(view, fmt, navmd)
          : null;

      res.setHeader(HttpHeaders.ETAG, etag(srcmd, navmd));
      showDoc(req, res, view, fmt, nav, doc);
    } finally {
      rw.release();
    }
  }

  private static String etag(SourceFile srcmd, SourceFile navmd) {
    List<String> p = new ArrayList<>(3);
    p.add(GEN);
    p.add(srcmd.id.name());
    if (navmd != null) {
      p.add(navmd.id.name());
    }
    return Joiner.on('-').join(p);
  }

  @Override
  protected void setCacheHeaders(HttpServletResponse res) {
    long now = System.currentTimeMillis();
    res.setDateHeader("Expires", now);
    res.setDateHeader("Date", now);
    res.setHeader("Cache-Control", "private, max-age=0, must-revalidate");
  }

  private void showDoc(HttpServletRequest req, HttpServletResponse res,
      GitilesView view, MarkdownHelper fmt, RootNode nav, RootNode doc) throws IOException {
    String title = MoreObjects.firstNonNull(
        Strings.emptyToNull(fmt.getTitle(doc)),
        view.getPathPart());

    Map<String, Object> data = new HashMap<>();
    data.put("breadcrumbs", null);
    data.put("repositoryName", null);
    data.put("title", title);
    data.put("navbarHtml", fmt.renderHTML(nav));
    data.put("bodyHtml", fmt.renderHTML(doc));
    data.put("sourceUrl", GitilesView.path()
        .copyFrom(view)
        .setRevision(view.getRevision().getId().getName())
        .toUrl());
    data.put("logUrl", GitilesView.log()
        .copyFrom(view)
        .setRevision(view.getRevision().getId().getName())
        .toUrl());
    data.put("blameUrl", GitilesView.blame()
        .copyFrom(view)
        .setRevision(view.getRevision().getId().getName())
        .toUrl());
    renderHtml(req, res, "gitiles.markdownDoc", data);
  }

  private RootNode parseFile(GitilesView view, MarkdownHelper fmt, SourceFile md) {
    RootNode docTree;
    try {
      docTree = fmt.parseMarkdown(md.text);
    } catch (ParsingTimeoutException e) {
      log.error("timeout rendering {}/{} at {}",
          view.getRepositoryName(),
          md.path,
          view.getRevision().getName());
      return null;
    }
    if (docTree == null) {
      log.error("cannot parse {}/{} at {}",
          view.getRepositoryName(),
          md.path,
          view.getRevision().getName());
    }
    return docTree;
  }

  private static SourceFile findFile(RevWalk rw, RevTree root, String path) throws IOException {
    if (Strings.isNullOrEmpty(path)) {
      path = INDEX_MD;
    }

    ObjectReader reader = rw.getObjectReader();
    TreeWalk tw = TreeWalk.forPath(reader, path, root);
    if (tw == null) {
      return null;
    }
    if ((tw.getRawMode(0) & TYPE_MASK) == TYPE_TREE) {
      if (findIndexFile(tw)) {
        path = tw.getPathString();
      } else {
        return null;
      }
    }
    if ((tw.getRawMode(0) & TYPE_MASK) == TYPE_FILE) {
      if (!path.endsWith(".md")) {
        return null;
      }
      return new SourceFile(path, tw.getObjectId(0));
    }
    return null;
  }

  private static boolean findIndexFile(TreeWalk tw) throws IOException {
    tw.enterSubtree();
    while (tw.next()) {
      if ((tw.getRawMode(0) & TYPE_MASK) == TYPE_FILE
          && INDEX_MD.equals(tw.getNameString())) {
        return true;
      }
    }
    return false;
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

  private static class SourceFile {
    final String path;
    final ObjectId id;
    String text;

    SourceFile(String path, ObjectId id) {
      this.path = path;
      this.id = id;
    }

    void read(ObjectReader reader) throws IOException {
      ObjectLoader obj = reader.open(id, OBJ_BLOB);
      byte[] raw = obj.getCachedBytes(MAX_MD_BYTES);
      text = RawParseUtils.decode(raw);
    }
  }
}
