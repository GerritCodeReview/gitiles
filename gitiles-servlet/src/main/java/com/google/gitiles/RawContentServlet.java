// Copyright 2016 Google Inc. All Rights Reserved.
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

import static java.nio.charset.StandardCharsets.UTF_8;
import static javax.servlet.http.HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
import static javax.servlet.http.HttpServletResponse.SC_NOT_FOUND;
import static javax.servlet.http.HttpServletResponse.SC_NOT_MODIFIED;
import static org.eclipse.jgit.lib.Constants.OBJ_BLOB;

import com.google.common.base.Optional;
import com.google.common.base.Strings;
import com.google.common.collect.Iterables;
import com.google.common.net.HttpHeaders;
import com.google.gitiles.PathServlet.WalkResult;

import org.eclipse.jgit.http.server.ServletUtils;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.util.QuotedString;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.List;
import java.util.zip.GZIPOutputStream;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * Serves raw user content under a {@code /+raw/} request.
 * <p>
 * This servlet provides two functions:
 * <p>
 * <b>On the standard Gitiles domain</b> the servlet accepts {@code /+raw/}
 * requests and replies with an uncacheable redirect to the raw content domain.
 * Browsers follow this redirect to load the potentially untrusted content from
 * the associated raw content domain.
 * <p>
 * <b>On the raw content domain</b> the servlet verifies the authentication key
 * query parameter and returns the content. If the key is invalid it redirects
 * to the standard domain to construct an updated key.
 * <p>
 * Embedding applications may need to recognize the request is arriving on the
 * raw content domain and verify the {@link RawUrls#PARAM_KEY} instead of running
 * the standard user authentication.
 */
public class RawContentServlet extends HttpServlet {
  private static final long serialVersionUID = 1L;

  private final GitilesAccess.Factory accessFactory;

  public RawContentServlet(GitilesAccess.Factory accessFactory) {
    this.accessFactory = accessFactory;
  }

  @Override
  protected void doGet(HttpServletRequest req, HttpServletResponse res) throws IOException {
    Optional<RawUrls> rawUrlsOpt = accessFactory.forRequest(req).getRawUrls();
    if (!rawUrlsOpt.isPresent()) {
      res.setStatus(SC_NOT_FOUND);
      return;
    }

    RawUrls rawUrls = rawUrlsOpt.get();
    GitilesView view = ViewFilter.getView(req);

    if (rawUrls.isRawDomain()) {
      doGetRawContent(req, res, rawUrls, view);
    } else {
      redirectToRawDomain(res, rawUrls, view);
    }
  }

  private void redirectToRawDomain(HttpServletResponse res, RawUrls rawUrls, GitilesView view)
      throws IOException {
    if (!viewHasCommit(view)) {
      ObjectId id = view.getRevision().getId();
      view = view.toBuilder().setRevision(id.name()).build();
    }

    // Do not cache the raw content domain URL.
    BaseServlet.setNotCacheable(res);
    res.sendRedirect(rawUrls.createRawUrl(view));
  }

  private void doGetRawContent(
      HttpServletRequest req, HttpServletResponse res, RawUrls rawUrls, GitilesView view)
      throws IOException {
    List<String> authKey = view.getParameters().get(RawUrls.PARAM_KEY);
    if (!authKey.isEmpty()) {
      view = removeAuthKey(view);
    }
    if (!rawUrls.isValidAuthKey(view, Iterables.getFirst(authKey, null))) {
      res.sendRedirect(rawUrls.createRedirectUrl(view));
      return;
    }

    try (RevWalk rw = new RevWalk(ServletUtils.getRepository(req));
        WalkResult wr = WalkResult.forPath(rw, view, false)) {
      if (wr == null) {
        res.setStatus(SC_NOT_FOUND);
        return;
      }

      String etag = wr.id.name();
      if (etagMatch(req, etag)) {
        res.setStatus(SC_NOT_MODIFIED);
        return;
      }

      switch (wr.type) {
        case EXECUTABLE_FILE:
        case REGULAR_FILE:
          addHeaders(res, wr, etag);
          rawBlob(req, res, wr);
          break;

        case SYMLINK:
          addHeaders(res, wr, etag);
          rawSymlink(req, res, wr);
          break;

        case GITLINK:
          addHeaders(res, wr, etag);
          rawGitLink(req, res, wr);
          break;

        case TREE:
          addHeaders(res, wr, etag);
          rawTree(req, res, wr);
          break;

        default:
          res.setStatus(SC_INTERNAL_SERVER_ERROR);
          break;
      }
    }
  }

  private static GitilesView removeAuthKey(GitilesView view) {
    GitilesView.Builder b = view.toBuilder();
    b.getParams().removeAll(RawUrls.PARAM_KEY);
    return b.build();
  }

  private static void addHeaders(HttpServletResponse res, WalkResult wr, String etag) {
    // TODO(sop) allow some raw resources to be cached.
    long now = System.currentTimeMillis();
    res.setDateHeader(HttpHeaders.EXPIRES, now);
    res.setDateHeader(HttpHeaders.DATE, now);
    res.setHeader(HttpHeaders.CACHE_CONTROL, "private, max-age=0, must-revalidate");
    res.setHeader(HttpHeaders.ETAG, etag);

    PathServlet.setTypeHeader(res, wr.type.mode.getObjectType());
    PathServlet.setModeHeader(res, wr.type);
  }

  private static void rawBlob(HttpServletRequest req, HttpServletResponse res, WalkResult wr)
      throws IOException {
    res.setContentType(MimeTypes.getMimeType(wr.path));
    writeBlob(req, res, wr);
  }

  private static void rawSymlink(HttpServletRequest req, HttpServletResponse res, WalkResult wr)
      throws IOException {
    res.setContentType(MimeTypes.ANY);
    writeBlob(req, res, wr);
  }

  private static void writeBlob(HttpServletRequest req, HttpServletResponse res, WalkResult wr)
      throws IOException {
    ObjectLoader data = wr.getObjectReader().open(wr.id, OBJ_BLOB);
    if (data.isLarge()) {
      try (OutputStream out = output(req, res)) {
        data.copyTo(out);
      }
    } else {
      write(req, res, data.getCachedBytes());
    }
  }

  private static void rawGitLink(HttpServletRequest req, HttpServletResponse res, WalkResult wr)
      throws IOException {
    res.setContentType("text/plain");
    res.setCharacterEncoding(UTF_8.name());
    write(req, res, (wr.id.name() + '\n').getBytes(UTF_8));
  }

  private static void rawTree(HttpServletRequest req, HttpServletResponse res, WalkResult wr)
      throws IOException {
    res.setContentType("text/plain");
    res.setCharacterEncoding(UTF_8.name());
    try (OutputStream stream = output(req, res);
        Writer out = new OutputStreamWriter(stream, UTF_8)) {
      TreeWalk tw = wr.tw;
      while (tw.next()) {
        // Match git ls-tree format.
        FileMode mode = tw.getFileMode(0);
        out.write(String.format("%06o", mode.getBits()));
        out.write(' ');
        out.write(Constants.typeString(mode.getObjectType()));
        out.write(' ');
        tw.getObjectId(0).copyTo(out);
        out.write('\t');
        out.write(QuotedString.GIT_PATH.quote(tw.getNameString()));
        out.write('\n');
      }
    }
  }

  private static void write(HttpServletRequest req, HttpServletResponse res, byte[] data)
      throws IOException {
    if (BaseServlet.acceptsGzipEncoding(req)) {
      byte[] gz = BaseServlet.gzip(data);
      if (gz.length < data.length) {
        data = gz;
        res.setHeader(HttpHeaders.CONTENT_ENCODING, "gzip");
      }
    }

    res.setContentLength(data.length);
    try (OutputStream w = res.getOutputStream()) {
      w.write(data);
    }
  }

  private static OutputStream output(HttpServletRequest req, HttpServletResponse res)
      throws IOException {
    if (BaseServlet.acceptsGzipEncoding(req)) {
      res.setHeader(HttpHeaders.CONTENT_ENCODING, "gzip");
      return new GZIPOutputStream(res.getOutputStream());
    }
    return res.getOutputStream();
  }

  private static boolean etagMatch(HttpServletRequest req, String etag) {
    return etag.equals(req.getHeader(HttpHeaders.IF_NONE_MATCH));
  }

  private static boolean viewHasCommit(GitilesView view) {
    return view.getRevision().getName().length() == Constants.OBJECT_ID_STRING_LENGTH
        && view.getRevision().nameIsId();
  }
}
