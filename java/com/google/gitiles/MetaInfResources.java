// Copyright 2023 Google Inc. All Rights Reserved.
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

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public class MetaInfResources implements Filter {

  private static final String PATH_PREFIX = "/+resources";

  /**
   * @param metaInfResourcesLocation The path to use to locate the resource under
   *     '/META-INF/resources'. e.g. 'foo.js' if the resource is to be found at
   *     '/META-INF/resources/foo.js'
   * @return The URL path under which a cached resource will be accessible by an HTTP client.
   */
  public static String createUrlPath(String metaInfResourcesLocation) {
    Objects.requireNonNull(metaInfResourcesLocation);
    if (metaInfResourcesLocation.startsWith("/")) {
      metaInfResourcesLocation = metaInfResourcesLocation.substring(1);
    }
    return PATH_PREFIX + "/" + metaInfResourcesLocation;
  }

  @Override
  public void doFilter(
      ServletRequest servletRequest, ServletResponse servletResponse, FilterChain chain)
      throws IOException, ServletException {
    if (!(servletRequest instanceof HttpServletRequest)
        || !(servletResponse instanceof HttpServletResponse)) {
      chain.doFilter(servletRequest, servletResponse);
      return;
    }

    HttpServletRequest req = (HttpServletRequest) servletRequest;
    HttpServletResponse resp = (HttpServletResponse) servletResponse;

    String pathInfo = req.getPathInfo();
    if (pathInfo == null || !pathInfo.startsWith(PATH_PREFIX + "/")) {
      chain.doFilter(servletRequest, servletResponse);
      return;
    }

    String path = "/META-INF/resources" + pathInfo.substring(PATH_PREFIX.length());
    try (InputStream resource = getClass().getResourceAsStream(path)) {
      if (resource == null) {
        resp.setStatus(HttpServletResponse.SC_NOT_FOUND);
        return;
      }
      resp.setContentType(MimeTypes.getMimeType(path));
      resource.transferTo(resp.getOutputStream());
    }
  }

  @Override
  public void init(FilterConfig filterConfig) throws ServletException {
    // Nothing to do
  }

  @Override
  public void destroy() {
    // Nothing to do
  }
}
