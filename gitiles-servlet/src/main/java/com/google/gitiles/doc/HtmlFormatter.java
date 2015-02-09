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
    super(linkRenderer, ImmutableList.<ToHtmlSerializerPlugin> of(
        toc,
        new DivSerializer(),
        new ColsSerializer()));
    this.toc = toc;
  }

  @Override
  public void visit(HeaderNode node) {
    String tag = "h" + node.getLevel();
    printer.print('<').print(tag);
    if (toc.include(node)) {
      String id = toc.idFromHeader(node);
      if (id != null) {
        printer.print(" id=\"").print(id).print('"');
      }
    }
    printer.print('>');
    visitChildren(node);
    printer.print('<').print('/').print(tag).print('>');
  }

  @Override
  protected void printImageTag(LinkRenderer.Rendering rendering) {
      printer.print("<img");
      printAttribute("src", rendering.href);
      printAttribute("alt", rendering.text);
      for (LinkRenderer.Attribute attr : rendering.attributes) {
          printAttribute(attr.name, attr.value);
      }
      printer.print(" />");
  }

  private void printAttribute(String n, String v) {
    printer.print(' ').print(n).print('=').print('"').print(v).print('"');
  }
}
