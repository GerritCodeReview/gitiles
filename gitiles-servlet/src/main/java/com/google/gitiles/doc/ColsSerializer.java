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

import org.pegdown.Printer;
import org.pegdown.ast.HeaderNode;
import org.pegdown.ast.Node;
import org.pegdown.ast.Visitor;
import org.pegdown.plugins.ToHtmlSerializerPlugin;

class ColsSerializer implements ToHtmlSerializerPlugin {
  @Override
  public boolean visit(Node node, Visitor visitor, Printer printer) {
    if (node instanceof ColsNode) {
      ColsNode cols = (ColsNode) node;
      printer.println().print("<div class=\"cols\">").indent(2);
      boolean open = false;
      for (Node n : cols.getChildren()) {
        if (n instanceof HeaderNode || n instanceof DivNode) {
          if (open) {
            printer.print("</div>");
          }
          printer.println().print("<div class=\"col-3\">");
          open = true;
        }
        n.accept(visitor);
      }
      if (open) {
        printer.print("</div>");
      }
      printer.indent(-2).println().print("</div>").println();
      return true;
    }
    return false;
  }
}
