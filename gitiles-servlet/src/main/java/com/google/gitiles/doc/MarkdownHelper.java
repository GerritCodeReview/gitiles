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

import com.google.gitiles.GitilesView;

import org.pegdown.PegDownProcessor;
import org.pegdown.ast.HeaderNode;
import org.pegdown.ast.Node;
import org.pegdown.ast.RootNode;
import org.pegdown.plugins.PegDownPlugins;

import java.util.List;

class MarkdownHelper {
  // SUPPRESS_ALL_HTML is enabled to permit hosting arbitrary user content
  // while avoiding XSS style HTML, CSS and JavaScript injection attacks.
  //
  // HARDWRAPS is disabled to permit line wrapping within paragraphs to
  // make the source file easier to read in 80 column terminals without
  // this impacting the rendered formatting.
  static final int MD_OPTIONS = (ALL | SUPPRESS_ALL_HTML) & ~(HARDWRAPS);

  private final GitLinkRenderer links;

  MarkdownHelper(GitilesView view) {
    links = new GitLinkRenderer(view);
  }

  RootNode parseMarkdown(String md) {
    PegDownPlugins plugins = new PegDownPlugins.Builder()
        .withPlugin(GitilesMarkdown.class)
        .build();
    return new PegDownProcessor(MD_OPTIONS, plugins)
        .parseMarkdown(md.toCharArray());
  }

  String renderHTML(RootNode root)  {
    return new HtmlFormatter(links, new TocSerializer(root, 3)).toHtml(root);
  }

  String getTitle(Node root) {
    if (root instanceof HeaderNode) {
      HeaderNode h = (HeaderNode) root;
      if (h.getLevel() == 1) {
        return TocSerializer.getText(h);
      }
    }
    List<Node> ch = root.getChildren();
    if (ch != null) {
      for (Node n : ch) {
        String title = getTitle(n);
        if (title != null) {
          return title;
        }
      }
    }
    return null;
  }
}
