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

package com.google.gitiles.doc;

import com.google.common.base.Strings;

import org.pegdown.Printer;
import org.pegdown.ast.HeaderNode;
import org.pegdown.ast.Node;
import org.pegdown.ast.RootNode;
import org.pegdown.ast.TextNode;
import org.pegdown.ast.Visitor;
import org.pegdown.plugins.ToHtmlSerializerPlugin;

import java.util.List;

/** Formats table of contents into HTML at {@code [TOC]} block. */
class TocSerializer implements ToHtmlSerializerPlugin {
  private final RootNode root;
  private final int maxLevel;

  private Boolean includeH1;
  private int level;

  TocSerializer(RootNode root, int maxLevel) {
    this.root = root;
    this.maxLevel = maxLevel;
  }

  boolean getIncludeH1() {
    if (includeH1 == null) {
      includeH1 = countH1(root) > 1;
    }
    return includeH1;
  }

  @Override
  public boolean visit(Node node, Visitor visitor, Printer printer) {
    if (node instanceof TocNode) {
      int startLevel = getIncludeH1() ? 1 : 2;
      level = startLevel;

      printer.print("<div class=\"toc\" role=\"navigation\">");
      printer.print("<h2>Contents</h2>");
      printer.print("<ul>\n").indent(2);
      outline(root, printer);
      while (level >= startLevel) {
        printer.indent(-2).print("</ul>\n");
        level--;
      }
      printer.print("</div>");
      return true;
    }
    return false;
  }

  private void outline(Node node, Printer printer) {
    if (node instanceof HeaderNode) {
      HeaderNode h = (HeaderNode) node;
      if (h.getLevel() > maxLevel
          || (!includeH1 && h.getLevel() == 1)) {
        return;
      }

      String title = getText(h);
      if (Strings.isNullOrEmpty(title)) {
        return;
      }

      while (level > h.getLevel()) {
        printer.indent(-2).print("</ul>");
        level--;
      }
      while (level < h.getLevel()) {
        printer.indent(2).print("<ul>");
        level++;
      }

      String id = idFromTitle(title);
      printer.print("<li><a href=\"#" + id + "\">");
      printer.printEncoded(title);
      printer.print("</a></li>\n");
      return;
    }

    List<Node> ch = node.getChildren();
    if (ch != null) {
      for (Node n : ch) {
        outline(n, printer);
      }
    }
  }

  static String idFromTitle(String title) {
    boolean lastWasUnderscore = false;
    StringBuilder b = new StringBuilder(title.length());
    for (char c : title.toCharArray()) {
      if (('a' <= c && c <= 'z')
          || ('A' <= c && c <= 'Z')
          || ('0' <= c && c <= '9')
          || (c == '-')) {
        b.append(c);
        lastWasUnderscore = false;
      } else if (!lastWasUnderscore) {
        b.append('_');
        lastWasUnderscore = true;
      }
    }
    if (lastWasUnderscore) {
      b.setLength(b.length() - 1);
    }
    return b.toString();
  }

  static String getText(HeaderNode h) {
    List<Node> ch = h.getChildren();
    if (ch == null || ch.isEmpty()) {
      return null;
    }

    StringBuilder b = new StringBuilder();
    for (Node n : ch) {
      if (n instanceof TextNode) {
        b.append(((TextNode) n).getText());
      }
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
