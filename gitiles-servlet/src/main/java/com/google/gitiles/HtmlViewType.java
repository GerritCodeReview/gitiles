// Copyright 2014 Google Inc. All Rights Reserved.
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
import com.google.common.collect.ImmutableMap;
import com.google.common.net.HttpHeaders;

import java.util.Map;

import javax.servlet.http.HttpServletRequest;

/** Type of view to compose in the response to the client. */
public enum HtmlViewType {
  EMBED("views/embed.css", ImmutableMap.of("linkTarget", "_blank")),
  DEFAULT();

  private static final String HTML_VIEW_TYPE_ATTRIBUTE = HtmlViewType.class.getName();

  /**
   * Load the HTML view type from the request.
   *
   * If a view type is specified, it will be retained in the request's
   * {@link HTML_VIEW_TYPE_ATTRIBUTE} attribute.
   *
   * @param req in-progress request.
   * @return the specified {@link HtmlViewType}, or <b>null</b> if no view type is specified.
   * @throws IllegalArgumentException if an invalid view type is specified.
   */
  public static HtmlViewType getHtmlViewType(HttpServletRequest req) {
    HtmlViewType result = (HtmlViewType) req.getAttribute(HTML_VIEW_TYPE_ATTRIBUTE);
    if (result != null) {
      return result;
    }

    String htmlView = req.getParameter("view");
    if (htmlView != null) {
      for (HtmlViewType type : HtmlViewType.values()) {
        if (htmlView.equalsIgnoreCase(type.name())) {
          return set(req, type);
        }
      }
      throw new IllegalArgumentException("Invalid view " + htmlView);
    }

    return set(req, DEFAULT);
  }

  private static HtmlViewType set(HttpServletRequest req, HtmlViewType view) {
    req.setAttribute(HTML_VIEW_TYPE_ATTRIBUTE, view);
    return view;
  }

  private final String cssPath;
  private final ImmutableMap<String, String> templateVars;

  private HtmlViewType() {
    this(null, null);
  }

  private HtmlViewType(String cssPath, Map<String, String> templateVars) {
    this.cssPath = cssPath;
    if (templateVars == null) {
      this.templateVars = ImmutableMap.of();
    } else {
      this.templateVars = ImmutableMap.copyOf(templateVars);
    }
  }

  public String getCSSPath() {
    return cssPath;
  }

  public ImmutableMap<String, String> getTemplateVars() {
    return templateVars;
  }
}
