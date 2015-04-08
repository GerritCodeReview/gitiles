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

import static javax.servlet.http.HttpServletResponse.SC_NOT_FOUND;

import org.eclipse.jgit.lib.Config;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/** Serves a redirect to the raw file contents. */
public class RawRedirectServlet extends BaseServlet {
  private static final long serialVersionUID = 1L;

  private GitilesUrls urls;

  public RawRedirectServlet(GitilesAccess.Factory accessFactory, GitilesUrls urls) {
    super(null, accessFactory);
    this.urls = urls;
  }

  public static String rawFileHostName(Config cfg) {
    return cfg.getString("gitiles", null, "rawFileHostName");
  }

  protected String getRedirectTarget(String hostname, HttpServletRequest req) {
    GitilesView redirectView = ViewFilter.getView(req);
    GitilesView contentView = GitilesView.rawContent()
        .copyFrom(redirectView)
        .setHostName(urls.getHostName(req))
        .build();

    StringBuilder url = new StringBuilder();
    url.append(req.getScheme()).append("://")
        .append(hostname)
        .append(":").append(req.getServerPort())
        .append(contentView.toUrl());
    return url.toString();
  }

  @Override
  protected void doGet(HttpServletRequest req, HttpServletResponse res)
      throws IOException, ServletException {
    Config cfg = getAccess(req).getConfig();
    String hostname = rawFileHostName(cfg);

    // Raw file serving is disabled if the raw file hostname is not set.
    if (hostname == null || hostname.isEmpty()) {
      res.sendError(SC_NOT_FOUND);
      return;
    }

    res.sendRedirect(getRedirectTarget(hostname, req));
  }
}
