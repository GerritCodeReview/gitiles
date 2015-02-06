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

import com.google.common.collect.ImmutableList;

import org.pegdown.LinkRenderer;
import org.pegdown.ToHtmlSerializer;
import org.pegdown.ast.HeaderNode;
import org.pegdown.plugins.ToHtmlSerializerPlugin;

class HtmlFormatter extends ToHtmlSerializer {
  private final TocSerializer toc;

  HtmlFormatter(LinkRenderer linkRenderer, TocSerializer toc) {
    super(linkRenderer, ImmutableList.<ToHtmlSerializerPlugin> of(toc));
    this.toc = toc;
  }

  @Override
  public void visit(HeaderNode node) {
    String tag = "h" + node.getLevel();
    String title = TocSerializer.getText(node);
    boolean anchor = title != null
        && (node.getLevel() > 1 || toc.getIncludeH1());

    printer.print('<').print(tag).print('>');
    if (anchor) {
      String id = TocSerializer.idFromTitle(title);
      printer.print("<a name=\"").print(id).print("\">");
    }
    visitChildren(node);
    if (anchor) {
      printer.print("</a>");
    }
    printer.print('<').print('/').print(tag).print('>');
  }
}
