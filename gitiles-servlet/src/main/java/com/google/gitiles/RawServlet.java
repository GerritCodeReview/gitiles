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

import static javax.servlet.http.HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
import static javax.servlet.http.HttpServletResponse.SC_NOT_FOUND;

import com.google.common.base.Strings;

import org.eclipse.jgit.errors.LargeObjectException;
import org.eclipse.jgit.http.server.ServletUtils;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevWalk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/** Handles the +raw command either by redirecting to the raw file hostname, or
 *  by serving the raw file contents directly. */
public class RawServlet extends BaseServlet {
  private static final long serialVersionUID = 1L;
  private static final Logger log = LoggerFactory.getLogger(RawServlet.class);

  private GitilesUrls urls;
  private MimeTypeFinder mimeTypeFinder;

  public RawServlet(GitilesAccess.Factory accessFactory, GitilesUrls urls,
      MimeTypeFinder mimeTypeFinder) {
    super(null, accessFactory);
    this.urls = urls;
    this.mimeTypeFinder = mimeTypeFinder;
  }

  public static String rawFileHostName(Config cfg) {
    return cfg.getString("gitiles", null, "rawFileHostName");
  }

  protected String getRedirectTarget(String hostname, HttpServletRequest req) {
    GitilesView redirectView = ViewFilter.getView(req);
    GitilesView contentView = GitilesView.raw()
        .copyFrom(redirectView)
        .setHostNameInPath(urls.getHostName(req))
        .build();

    StringBuilder url = new StringBuilder();
    url.append(req.getScheme()).append("://").append(hostname);

    if (!(req.getScheme().equals("http") && req.getServerPort() == 80 ||
          req.getScheme().equals("https") && req.getServerPort() == 443)) {
      url.append(":").append(req.getServerPort());
    }
    return url.append(contentView.toUrl()).toString();
  }

  private void writeRawBlob(HttpServletRequest req, HttpServletResponse res) throws IOException {
    GitilesView view = ViewFilter.getView(req);
    Repository repo = ServletUtils.getRepository(req);

    RevWalk rw = new RevWalk(repo);
    PathServlet.WalkResult wr = null;
    try {
      wr = PathServlet.WalkResult.forPath(rw, view);
      if (wr == null) {
        res.setStatus(SC_NOT_FOUND);
        return;
      }

      switch (wr.type) {
        case SYMLINK:
        case REGULAR_FILE:
        case EXECUTABLE_FILE:
          PathServlet.setTypeHeader(res, wr.type.mode.getObjectType());
          PathServlet.setModeHeader(res, wr.type);
          ObjectLoader loader = wr.getObjectReader().open(wr.id);
          res.setContentLength((int) loader.getSize());
          loader.copyTo(res.getOutputStream());
          break;
        default:
          res.setStatus(SC_NOT_FOUND);
          break;
      }
    } catch (LargeObjectException e) {
      res.setStatus(SC_INTERNAL_SERVER_ERROR);
      log.warn("Cannot serve raw file contents for " + view.getPathPart(), e);
    } finally {
      if (wr != null) {
        wr.release();
      }
      rw.release();
    }
  }

  @Override
  protected void doGet(HttpServletRequest req, HttpServletResponse res)
      throws IOException, ServletException {
    final Config cfg = getAccess(req).getConfig();
    final String hostname = rawFileHostName(cfg);
    GitilesView view = ViewFilter.getView(req);

    // Raw file serving is disabled if the raw file hostname is not set.
    if (Strings.isNullOrEmpty(hostname)) {
      res.sendError(SC_NOT_FOUND);
      return;
    }

    String hostInPath = view.getHostNameInPath();
    if (hostInPath == null) {
      // This request was made on a normal host, so serve a redirect to the
      // raw hostname.
      res.sendRedirect(getRedirectTarget(hostname, req));
      return;
    }

    // Otherwise serve the raw file content.
    String mimeType = mimeTypeFinder.guessFromFileExtension(view.getPathPart());
    if (mimeType != null) {
      res.setContentType(mimeType);
    }

    res.setStatus(HttpServletResponse.SC_OK);
    writeRawBlob(req, res);
  }
}
