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

import com.google.gitiles.doc.html.MarkdownToHtml;

import org.pegdown.ast.AbstractNode;
import org.pegdown.ast.Node;
import org.pegdown.ast.Visitor;

import java.util.List;

/**
 * Multi-column layout delineated by {@code |||---|||}.
 * <p>
 * Each header within the layout creates a new column in the HTML.
 */
public class ColsNode extends AbstractNode {
  private final List<Node> children;

  ColsNode(List<Node> children) {
    this.children = children;
  }

  @Override
  public void accept(Visitor visitor) {
    ((MarkdownToHtml) visitor).visit(this);
  }

  @Override
  public List<Node> getChildren() {
    return children;
  }
}
