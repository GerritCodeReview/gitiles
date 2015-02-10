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

import static org.pegdown.Extensions.ALL;
import static org.pegdown.Extensions.HARDWRAPS;
import static org.pegdown.Extensions.SUPPRESS_ALL_HTML;

import com.google.common.base.Strings;
import com.google.gitiles.GitilesView;

import org.pegdown.ParsingTimeoutException;
import org.pegdown.PegDownProcessor;
import org.pegdown.ast.HeaderNode;
import org.pegdown.ast.Node;
import org.pegdown.ast.RootNode;
import org.pegdown.ast.TextNode;
import org.pegdown.plugins.PegDownPlugins;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MarkdownHelper {
  private static final Logger log = LoggerFactory.getLogger(MarkdownHelper.class);

  // SUPPRESS_ALL_HTML is enabled to permit hosting arbitrary user content
  // while avoiding XSS style HTML, CSS and JavaScript injection attacks.
  //
  // HARDWRAPS is disabled to permit line wrapping within paragraphs to
  // make the source file easier to read in 80 column terminals without
  // this impacting the rendered formatting.
  static final int MD_OPTIONS = (ALL | SUPPRESS_ALL_HTML) & ~(HARDWRAPS);

  /** Check if anchor URL is like {@code /top.md}. */
  public static boolean isAbsolutePathToMarkdown(String url) {
    return url.length() >= 5
        && url.charAt(0) == '/' && url.charAt(1) != '/'
        && url.endsWith(".md");
  }

  static RootNode parseMarkdown(String markdownSource) {
    PegDownPlugins plugins = new PegDownPlugins.Builder()
        .withPlugin(GitilesMarkdown.class)
        .build();
    return new PegDownProcessor(MD_OPTIONS, plugins)
        .parseMarkdown(markdownSource.toCharArray());
  }

  static RootNode parseFile(GitilesView view, String path, String md) {
    if (md == null) {
      return null;
    }

    RootNode docTree;
    try {
      docTree = MarkdownHelper.parseMarkdown(md);
    } catch (ParsingTimeoutException e) {
      log.error("timeout rendering {}/{} at {}",
          view.getRepositoryName(),
          path,
          view.getRevision().getName());
      return null;
    }
    if (docTree == null) {
      log.error("cannot parse {}/{} at {}",
          view.getRepositoryName(),
          path,
          view.getRevision().getName());
    }
    return docTree;
  }

  /** Combine child nodes as string; this must be escaped for HTML. */
  public static String getInnerText(Node node) {
    if (node == null || node.getChildren().isEmpty()) {
      return null;
    }

    StringBuilder b = new StringBuilder();
    appendTextFromChildren(b, node);
    return Strings.emptyToNull(b.toString().trim());
  }

  private static void appendTextFromChildren(StringBuilder b, Node node) {
    for (Node child : node.getChildren()) {
      if (child instanceof TextNode) {
        b.append(((TextNode) child).getText());
      } else {
        appendTextFromChildren(b, child);
      }
    }
  }

  static String getTitle(Node node) {
    if (node instanceof HeaderNode) {
      if (((HeaderNode) node).getLevel() == 1) {
        return getInnerText(node);
      }
      return null;
    }

    for (Node child : node.getChildren()) {
      String title = getTitle(child);
      if (title != null) {
        return title;
      }
    }
    return null;
  }

  private MarkdownHelper() {
  }
}
