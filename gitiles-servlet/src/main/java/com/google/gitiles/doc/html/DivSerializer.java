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

import com.google.gitiles.doc.DivNode;

import org.pegdown.Printer;
import org.pegdown.ast.Node;
import org.pegdown.ast.Visitor;
import org.pegdown.plugins.ToHtmlSerializerPlugin;

/** Wraps a {@code *** note} block in {@code &lt;div class="note"&gt;}. */
class DivSerializer implements ToHtmlSerializerPlugin {
  @Override
  public boolean visit(Node node, Visitor visitor, Printer sb) {
    if (node instanceof DivNode) {
      DivNode div = (DivNode) node;
      sb.print("<div")
        .print(" class=\"").printEncoded(div.getStyleName()).print("\"")
        .print(">");
      for (Node n : div.getChildren()) {
        n.accept(visitor);
      }
      sb.print("</div>");
      return true;
    }
    return false;
  }
}
