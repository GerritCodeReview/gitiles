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

import org.eclipse.jgit.errors.LargeObjectException;
import org.eclipse.jgit.http.server.ServletUtils;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevWalk;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/** Serves a redirect to the raw file contents. */
public class RawContentServlet extends BaseServlet {
  private static final long serialVersionUID = 1L;

  private MimeTypeFinder mimeTypeFinder;

  public RawContentServlet(GitilesAccess.Factory accessFactory, MimeTypeFinder mimeTypeFinder) {
    super(null, accessFactory);
    this.mimeTypeFinder = mimeTypeFinder;
  }

  @Override
  protected void doGet(HttpServletRequest req, HttpServletResponse res)
      throws IOException, ServletException {
    GitilesView view = ViewFilter.getView(req);
    Config cfg = getAccess(req).getConfig();
    String hostname = RawRedirectServlet.rawFileHostName(cfg);

    // Raw file serving is disabled if the raw file hostname is not set, and
    // raw files must not be served at all on other hostnames.
    if (hostname == null || hostname.isEmpty() || !hostname.equals(req.getServerName())) {
      res.sendError(SC_NOT_FOUND);
      return;
    }

    // Serve the content.
    String mimeType = mimeTypeFinder.guessFromFileExtension(view.getPathPart());
    if (mimeType != null) {
      res.setContentType(mimeType);
    }

    res.setStatus(HttpServletResponse.SC_OK);
    writeRawBlob(req, res);
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
          wr.getObjectReader().open(wr.id).copyTo(res.getOutputStream());
          break;
        default:
          renderTextError(req, res, SC_NOT_FOUND, "Not a file");
          break;
      }
    } catch (LargeObjectException e) {
      res.setStatus(SC_INTERNAL_SERVER_ERROR);
    } finally {
      if (wr != null) {
        wr.release();
      }
      rw.release();
    }
  }
}
