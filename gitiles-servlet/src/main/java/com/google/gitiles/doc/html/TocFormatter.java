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

package com.google.gitiles.doc.html;

import com.google.gitiles.doc.MarkdownHelper;

import org.apache.commons.lang3.StringUtils;
import org.pegdown.ast.HeaderNode;
import org.pegdown.ast.Node;
import org.pegdown.ast.RootNode;

import java.util.List;

/** Formats table of contents into HTML at {@code [TOC]} block. */
class TocFormatter {
  private final HtmlBuilder html;
  private final int maxLevel;

  private RootNode root;
  private Boolean includeH1;

  private int level;

  TocFormatter(HtmlBuilder html, int maxLevel) {
    this.html = html;
    this.maxLevel = maxLevel;
  }

  void setRoot(RootNode doc) {
    root = doc;
    includeH1 = null;
  }

  private boolean getIncludeH1() {
    if (includeH1 == null) {
      includeH1 = countH1(root) > 1;
    }
    return includeH1;
  }

  boolean include(HeaderNode h) {
    if (h.getLevel() == 1) {
      return getIncludeH1();
    }
    return h.getLevel() <= maxLevel;
  }

  void format() {
    int startLevel = getIncludeH1() ? 1 : 2;
    level = startLevel;

    html.open("div")
        .attribute("class", "toc")
        .attribute("role", "navigation")
      .open("h2").appendAndEscape("Contents").close("h2")
      .open("div").attribute("class", "toc-aux")
      .open("ul");
    outline(root);
    while (level >= startLevel) {
      html.close("ul");
      level--;
    }
    html.close("div").close("div");
  }

  private void outline(Node node) {
    if (node instanceof HeaderNode) {
      outline((HeaderNode) node);
    } else if (node.getChildren() != null) {
      for (Node n : node.getChildren()) {
        outline(n);
      }
    }
  }

  private void outline(HeaderNode h) {
    if (!include(h)) {
      return;
    }

    String title = MarkdownHelper.getInnerText(h);
    if (title == null) {
      return;
    }

    while (level > h.getLevel()) {
      html.close("ul");
      level--;
    }
    while (level < h.getLevel()) {
      html.open("ul");
      level++;
    }

    html.open("li")
      .open("a")
      .attribute("href", "#" + idFromHeader(h))
      .appendAndEscape(title)
      .close("a")
      .close("li");
  }

  String idFromHeader(HeaderNode header) {
    String title = MarkdownHelper.getInnerText(header);
    if (title == null) {
      return null;
    }

    StringBuilder b = new StringBuilder(title.length());
    for (char c : StringUtils.stripAccents(title).toCharArray()) {
      if (('a' <= c && c <= 'z')
          || ('A' <= c && c <= 'Z')
          || ('0' <= c && c <= '9')) {
        b.append(c);
      } else if (c == ' ') {
        if (b.length() > 0
            && b.charAt(b.length() - 1) != '-'
            && b.charAt(b.length() - 1) != '_') {
          b.append('-');
        }
      } else if (b.length() > 0
          && b.charAt(b.length() - 1) != '-'
          && b.charAt(b.length() - 1) != '_') {
        b.append('_');
      }
    }
    while (b.length() > 0) {
      char c = b.charAt(b.length() - 1);
      if (c == '-' || c == '_') {
        b.setLength(b.length() - 1);
        continue;
      }
      break;
    }
    return b.toString();
  }

  private static int countH1(Node node) {
    if (node instanceof HeaderNode) {
      HeaderNode h = (HeaderNode) node;
      return h.getLevel() == 1 ? 1 : 0;
    }

    List<Node> ch = node.getChildren();
    if (ch == null) {
      return 0;
    }

    int count = 0;
    for (Node n : ch) {
      count += countH1(n);
      if (count >= 2) {
        break;
      }
    }
    return count;
  }
}
