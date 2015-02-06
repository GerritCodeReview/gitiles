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

import static java.nio.charset.StandardCharsets.UTF_8;
import static javax.servlet.http.HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
import static javax.servlet.http.HttpServletResponse.SC_NOT_FOUND;
import static javax.servlet.http.HttpServletResponse.SC_NOT_MODIFIED;
import static org.eclipse.jgit.lib.Constants.OBJ_BLOB;
import static org.eclipse.jgit.lib.Constants.OBJ_COMMIT;
import static org.eclipse.jgit.lib.Constants.OBJ_TREE;
import static org.eclipse.jgit.lib.FileMode.TYPE_FILE;
import static org.eclipse.jgit.lib.FileMode.TYPE_MASK;
import static org.eclipse.jgit.lib.FileMode.TYPE_TREE;
import static org.eclipse.jgit.util.HttpSupport.ENCODING_GZIP;

import com.google.common.base.MoreObjects;
import com.google.common.base.Strings;
import com.google.common.net.HttpHeaders;
import com.google.gitiles.BaseServlet;
import com.google.gitiles.FormatType;
import com.google.gitiles.GitilesAccess;
import com.google.gitiles.GitilesView;
import com.google.gitiles.Renderer;
import com.google.gitiles.ViewFilter;

import org.eclipse.jgit.http.server.ServletUtils;
import org.eclipse.jgit.lib.Config;
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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public class DocServlet extends BaseServlet {
  private static final long serialVersionUID = 1L;
  private static final Logger log = LoggerFactory.getLogger(DocServlet.class);

  // Version number for ETag generation; must change if Doc.soy changes.
  private static final String GEN = "1";

  private static final String INDEX_MD = "index.md";
  private static final String NAVBAR_MD = "navbar.md";

  public DocServlet(GitilesAccess.Factory accessFactory, Renderer renderer) {
    super(renderer, accessFactory);
  }

  @Override
  protected void doGetHtml(HttpServletRequest req, HttpServletResponse res)
      throws IOException {
    Config cfg = getAccess(req).getConfig();
    if (!cfg.getBoolean("markdown", "render", true)) {
      res.setStatus(SC_NOT_FOUND);
      return;
    }

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

      SourceFile navmd = findFile(rw, root, NAVBAR_MD);
      String reqEtag = req.getHeader(HttpHeaders.IF_NONE_MATCH);
      String curEtag = etag(srcmd, navmd);
      if (reqEtag != null && reqEtag.equals(curEtag)) {
        res.setStatus(SC_NOT_MODIFIED);
        return;
      }

      view = view.toBuilder().setPathPart(srcmd.path).build();

      int inputLimit = cfg.getInt("markdown", "inputLimit", 5 << 20);
      srcmd.read(rw.getObjectReader(), inputLimit);
      if (navmd != null) {
        navmd.read(rw.getObjectReader(), inputLimit);
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

      res.setHeader(HttpHeaders.ETAG, curEtag);
      showDoc(req, res, view, fmt, nav, doc);
    } finally {
      rw.release();
    }
  }

  private static String etag(SourceFile srcmd, SourceFile navmd) {
    StringBuilder p = new StringBuilder(GEN.length() + 40 * 2 + 2);
    p.append(GEN).append('-').append(srcmd.id.name());
    if (navmd != null) {
      p.append('-').append(navmd.id.name());
    }
    return p.toString();
  }

  @Override
  protected void setCacheHeaders(HttpServletResponse res) {
    long now = System.currentTimeMillis();
    res.setDateHeader(HttpHeaders.EXPIRES, now);
    res.setDateHeader(HttpHeaders.DATE, now);
    res.setHeader(HttpHeaders.CACHE_CONTROL, "private, max-age=0, must-revalidate");
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
    data.put("sourceUrl", GitilesView.path().copyFrom(view).toUrl());
    data.put("logUrl", GitilesView.log().copyFrom(view).toUrl());
    data.put("blameUrl", GitilesView.blame().copyFrom(view).toUrl());
    data.put("navbarHtml", nav != null ? "{{navbarHtml}}" : null);
    data.put("bodyHtml", "{{bodyHtml}}");

    String page = renderer.render("gitiles.markdownDoc", data);
    page = insertRenderedMarkdown(page, "{{navbarHtml}}", fmt, nav);
    page = insertRenderedMarkdown(page, "{{bodyHtml}}", fmt, doc);

    byte[] raw = page.getBytes(UTF_8);
    res.setContentType(FormatType.HTML.getMimeType());
    res.setCharacterEncoding(UTF_8.name());
    setCacheHeaders(res);
    if (acceptsGzipEncoding(req)) {
      res.setHeader(HttpHeaders.CONTENT_ENCODING, "gzip");
      raw = gzip(raw);
    }
    res.setContentLength(raw.length);
    res.setStatus(HttpServletResponse.SC_OK);
    res.getOutputStream().write(raw);
  }

  private String insertRenderedMarkdown(String page, String marker,
      MarkdownHelper fmt, RootNode node) {
    if (node == null) {
      return page;
    }
    int p = page.indexOf(marker);
    if (p > 0) {
      String html = fmt.renderHTML(node);
      return page.substring(0, p) + html + page.substring(p + marker.length());
    }
    return page;
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

  private static boolean acceptsGzipEncoding(HttpServletRequest req) {
    String accepts = req.getHeader(HttpHeaders.ACCEPT_ENCODING);
    if (accepts == null) {
      return false;
    }
    for (int b = 0; b < accepts.length();) {
      int comma = accepts.indexOf(',', b);
      int e = 0 <= comma ? comma : accepts.length();
      String term = accepts.substring(b, e).trim();
      if (term.equals(ENCODING_GZIP)) {
        return true;
      }
      b = e + 1;
    }
    return false;
  }

  private static byte[] gzip(byte[] raw) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
      gz.write(raw);
    }
    return out.toByteArray();
  }

  private static class SourceFile {
    final String path;
    final ObjectId id;
    String text;

    SourceFile(String path, ObjectId id) {
      this.path = path;
      this.id = id;
    }

    void read(ObjectReader reader, int inputLimit) throws IOException {
      ObjectLoader obj = reader.open(id, OBJ_BLOB);
      byte[] raw = obj.getCachedBytes(inputLimit);
      text = RawParseUtils.decode(raw);
    }
  }
}
