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
import java.time.Duration;
import java.util.Objects;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * Replicates 'META-INF/resources' deployment feature defined in servlet specification 3.1 to allow
 * deployment to a custom path.
 */
public class CacheableMetaInfResources extends AbstractHttpFilter {

  public static final String PATH_PREFIX = "/+cmir";

  /**
   * @param metaInfResourcePath The path to use to locate the resource under '/META-INF/resources'.
   *     e.g. 'foo.js' if the resource is to be found at '/META-INF/resources/foo.js'
   * @return The URL path where a resource will be accessible by an HTTP client.
   */
  public static String createResourceUrlPath(String metaInfResourcePath) {
    Objects.requireNonNull(metaInfResourcePath);
    if (metaInfResourcePath.startsWith("/")) {
      metaInfResourcePath = metaInfResourcePath.substring(1);
    }
    return PATH_PREFIX + "/" + metaInfResourcePath;
  }

  @Override
  public void doFilter(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws IOException, ServletException {

    String pathInfo = req.getPathInfo();
    if (pathInfo == null || !pathInfo.startsWith(PATH_PREFIX + "/")) {
      chain.doFilter(req, res);
      return;
    }

    String path = "/META-INF/resources" + pathInfo.substring(PATH_PREFIX.length());
    try (InputStream resource = getClass().getResourceAsStream(path)) {
      if (resource == null) {
        res.setStatus(HttpServletResponse.SC_NOT_FOUND);
        return;
      }
      res.setContentType(MimeTypes.getMimeType(path));
      res.setHeader("Cache-Control", "max-age=" + Duration.ofDays(365).toSeconds());
      resource.transferTo(res.getOutputStream());
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
