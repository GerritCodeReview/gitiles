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

import static com.google.gitiles.doc.MarkdownHelper.getInnerText;

import org.pegdown.ast.ReferenceNode;
import org.pegdown.ast.RootNode;
import org.pegdown.ast.SuperNode;

import java.util.HashMap;
import java.util.Map;

class ReferenceMap {
  private final Map<String, ReferenceNode> references = new HashMap<>();

  void add(RootNode node) {
    for (ReferenceNode ref : node.getReferences()) {
      String id = getInnerText(ref);
      references.put(key(id), ref);
    }
  }

  ReferenceNode get(SuperNode keyNode, String text) {
    String id = keyNode != null ? getInnerText(keyNode) : text;
    return references.get(key(id));
  }

  private static String key(String in) {
    StringBuilder r = new StringBuilder(in.length());
    for (int i = 0; i < in.length(); i++) {
      char c = in.charAt(i);
      if (!Character.isWhitespace(c)) {
        r.append(Character.toLowerCase(c));
      }
    }
    return r.toString();
  }
}
