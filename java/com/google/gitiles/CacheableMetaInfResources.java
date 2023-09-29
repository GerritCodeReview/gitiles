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
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * Replicates 'META-INF/resources' deployment feature defined in servlet specification 3.1 to allow
 * deployment to a custom path.
 */
class CacheableMetaInfResources extends HttpServlet {

  @Override
  protected void doGet(HttpServletRequest req, HttpServletResponse resp)
      throws ServletException, IOException {
    String path = "/META-INF/resources" + req.getPathInfo();
    try (InputStream resource = getClass().getResourceAsStream(path)) {
      if (resource == null) {
        resp.setStatus(HttpServletResponse.SC_NOT_FOUND);
        return;
      }
      resp.setContentType(MimeTypes.getMimeType(path));
      resp.setHeader("Cache-Control", "max-age=" + Duration.ofDays(365).toSeconds());
      resource.transferTo(resp.getOutputStream());
    }
  }
}
