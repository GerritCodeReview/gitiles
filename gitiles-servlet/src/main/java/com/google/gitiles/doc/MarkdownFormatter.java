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
import static org.pegdown.Extensions.WIKILINKS;

import com.google.gitiles.GitilesView;
import com.google.template.soy.data.SanitizedContent;
import com.google.template.soy.data.SanitizedContent.ContentKind;
import com.google.template.soy.data.UnsafeSanitizedContentOrdainer;

import org.pegdown.PegDownProcessor;
import org.pegdown.ToHtmlSerializer;
import org.pegdown.ast.HeaderNode;
import org.pegdown.ast.Node;
import org.pegdown.ast.RootNode;
import org.pegdown.ast.TextNode;

class MarkdownFormatter {
  private static final int MD_OPTIONS = (ALL | SUPPRESS_ALL_HTML)
      & ~(HARDWRAPS | WIKILINKS);

  private final GitLinkRenderer links;

  MarkdownFormatter(GitilesView view) {
    links = new GitLinkRenderer(view);
  }

  RootNode parseMarkdown(String md) {
    return new PegDownProcessor(MD_OPTIONS).parseMarkdown(md.toCharArray());
  }

  SanitizedContent renderHTML(RootNode root)  {
    if (root == null) {
      return null;
    }

    return UnsafeSanitizedContentOrdainer.ordainAsSafe(
        new ToHtmlSerializer(links).toHtml(root),
        ContentKind.HTML);
  }

  String getTitle(Node root) {
    if (root instanceof HeaderNode) {
      HeaderNode h = (HeaderNode) root;
      if (h.getLevel() == 1
          && h.getChildren() != null
          && !h.getChildren().isEmpty()) {
        StringBuilder b = new StringBuilder();
        for (Node n : root.getChildren()) {
          if (n instanceof TextNode) {
            b.append(((TextNode) n).getText());
          }
        }
        return b.toString();
      }
    }
    for (Node n : root.getChildren()) {
      String title = getTitle(n);
      if (title != null) {
        return title;
      }
    }
    return null;
  }
}
