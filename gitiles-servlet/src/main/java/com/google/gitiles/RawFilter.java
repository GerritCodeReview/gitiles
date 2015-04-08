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

import com.google.common.base.Strings;
import com.google.gitiles.GitilesAccess.Factory;

import org.eclipse.jgit.http.server.glue.WrappedRequest;

import java.io.IOException;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/** Handles requests on the configured raw hostname.  Removes the host
 *  component from the path and stores it in a request attribute. */
public class RawFilter extends AbstractHttpFilter {
  private static final String HOST_IN_PATH_ATTRIBUTE = RawFilter.class.getName() + "/HostInPath";

  public static String getHostInPath(HttpServletRequest req) {
    return (String) req.getAttribute(HOST_IN_PATH_ATTRIBUTE);
  }

  static void setHostInPath(HttpServletRequest req, String host) {
    req.setAttribute(HOST_IN_PATH_ATTRIBUTE, host);
  }

  private Factory accessFactory;

  public RawFilter(GitilesAccess.Factory accessFactory) {
    this.accessFactory = accessFactory;
  }

  @Override
  public void doFilter(HttpServletRequest req, HttpServletResponse res,
      FilterChain chain) throws IOException, ServletException {
    GitilesAccess access = accessFactory.forRequest(req);
    String rawFileHostName = RawServlet.rawFileHostName(access.getConfig());

    // Don't do anything to requests that aren't on the raw file hostname.
    if (Strings.isNullOrEmpty(rawFileHostName) || !req.getServerName().equals(rawFileHostName)) {
      chain.doFilter(req, res);
      return;
    }

    // The real host will be the first path component.
    final String path = ViewFilter.trimLeadingSlash(req.getPathInfo());
    final int firstSlash = path.indexOf('/');
    if (firstSlash == -1) {
      chain.doFilter(req, res);
      return;
    }

    // Store the real host in an attribute and remove it from the path so that subsequent filters
    // will see the repo name as the first path component.
    final String host = path.substring(0, firstSlash);
    setHostInPath(req, host);

    // Replace the repo name matched by the regex as well.
    String repo = ViewFilter.getRegexGroup(req, 1);
    repo = repo.substring(host.length() + 1);
    ViewFilter.setRegexGroup(req, 1, repo);

    final String newPath = path.substring(firstSlash);
    chain.doFilter(new WrappedRequest(req, req.getServletPath(), newPath), res);
  }

}
