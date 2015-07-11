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

package com.google.gitiles;

import static com.google.gitiles.PathServlet.setModeHeader;
import static com.google.gitiles.PathServlet.setTypeHeader;
import static javax.servlet.http.HttpServletResponse.SC_METHOD_NOT_ALLOWED;
import static javax.servlet.http.HttpServletResponse.SC_NOT_FOUND;
import static javax.servlet.http.HttpServletResponse.SC_NOT_MODIFIED;
import static org.eclipse.jgit.lib.Constants.OBJECT_ID_STRING_LENGTH;
import static org.eclipse.jgit.lib.Constants.OBJ_BLOB;

import com.google.common.io.ByteStreams;
import com.google.common.net.HttpHeaders;
import com.google.gitiles.PathServlet.WalkResult;

import org.eclipse.jgit.errors.IncorrectObjectTypeException;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.http.server.ServletUtils;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.ObjectStream;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevWalk;

import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * Serves raw file contents direct to the browser.
 * <p>
 * <b>This servlet is not safe.</b> It should only be run on a cookieless domain
 * whose address is dedicated to serving unsafe, user-supplied content.
 * <p>
 * To use this servlet configure a separate instance of Gitiles on a host with
 * no cookies and pass it in as a replacement for {@link PathSevlet} when
 * creating a {@link GitilesFilter}.
 */
// TODO(dborowitz): Handle non-UTF-8 names.
public class RawServlet extends HttpServlet {
  private static final long serialVersionUID = 1L;

  private final PathServlet pathServlet;

  public RawServlet() {
    pathServlet = null;
  }

  public RawServlet(PathServlet pathServlet) {
    this.pathServlet = pathServlet;
  }

  @Override
  protected void service(HttpServletRequest req, HttpServletResponse res)
      throws IOException, ServletException {
    if (!"GET".equals(req.getMethod()) && !isHead(req)) {
      res.sendError(SC_METHOD_NOT_ALLOWED);
      return;
    }

    GitilesView view = ViewFilter.getView(req);
    Repository repo = ServletUtils.getRepository(req);
    try (RevWalk rw = new RevWalk(repo);
        WalkResult wr = WalkResult.forPath(rw, view)) {
      if (wr == null) {
        res.setStatus(SC_NOT_FOUND);
        return;
      }

      String reqEtag = req.getHeader(HttpHeaders.IF_NONE_MATCH);
      if (reqEtag != null && reqEtag.equals(wr.id.name())) {
        res.setStatus(SC_NOT_MODIFIED);
        return;
      }

      switch (wr.type) {
        case REGULAR_FILE:
        case EXECUTABLE_FILE:
        case SYMLINK:
          addObjectHeaders(res, wr);
          res.setContentType(typeOf(view.getPathPart(), wr));
          if (!isHead(req)) {
            sendBlob(res, rw.getObjectReader(), wr);
          }
          break;

        case GITLINK:
          addObjectHeaders(res, wr);
          res.setContentType("text/plain");
          res.setCharacterEncoding("UTF-8");
          res.setContentLength(OBJECT_ID_STRING_LENGTH + 1);
          if (!isHead(req)) {
            try (OutputStream out = res.getOutputStream()) {
              wr.id.copyTo(out);
              out.write('\n');
            }
          }
          break;

        case TREE:
          if (pathServlet != null) {
            if (!isHead(req)) {
              pathServlet.showTree(req, res, wr);
            }
            break;
          }
        default:
          res.setStatus(SC_NOT_FOUND);
          break;
      }
    }
  }

  private static boolean isHead(HttpServletRequest req) {
    return "HEAD".equals(req.getMethod());
  }

  private static void sendBlob(HttpServletResponse res, ObjectReader reader, WalkResult wr)
      throws MissingObjectException, IncorrectObjectTypeException, IOException {
    ObjectLoader ldr = reader.open(wr.id, OBJ_BLOB);
    if (ldr.isLarge()) {
      try (ObjectStream in = ldr.openStream();
          OutputStream out = res.getOutputStream()) {
        long sz = in.getSize();
        res.setHeader(HttpHeaders.CONTENT_LENGTH, Long.toString(sz));
        if (ByteStreams.copy(in, out) != sz) {
            throw new EOFException();
        }
      }
    } else {
      byte[] raw = ldr.getCachedBytes();
      res.setContentLength(raw.length);
      try (OutputStream out = res.getOutputStream()) {
        out.write(raw);
      }
    }
  }

  private static String typeOf(String path, WalkResult wr) {
    if (wr.type == PathServlet.FileType.SYMLINK) {
      return "text/plain";
    }
    return "application/octet-stream";
  }

  private static void addObjectHeaders(HttpServletResponse res, WalkResult wr) {
    long now = System.currentTimeMillis();
    res.setDateHeader(HttpHeaders.EXPIRES, now);
    res.setDateHeader(HttpHeaders.DATE, now);
    res.setHeader(HttpHeaders.CACHE_CONTROL, "private, max-age=0, must-revalidate");
    res.setHeader(HttpHeaders.ETAG, wr.id.name());
    setTypeHeader(res, wr.type.mode.getObjectType());
    setModeHeader(res, wr.type);
  }
}
