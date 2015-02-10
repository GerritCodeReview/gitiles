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

import org.pegdown.ast.Node;
import org.pegdown.ast.TextNode;

import java.util.List;

public class MarkdownHelper {
  /** Combine child nodes as string; this must be escaped for HTML. */
  public static String getInnerText(Node h) {
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
    return Strings.emptyToNull(b.toString().trim());
  }

  private MarkdownHelper() {
  }
}
