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

import com.google.gitiles.GitilesView;
import com.google.template.soy.data.SanitizedContent;
import com.google.template.soy.data.SanitizedContent.ContentKind;
import com.google.template.soy.data.UnsafeSanitizedContentOrdainer;

import org.pegdown.LinkRenderer;
import org.pegdown.ToHtmlSerializer;
import org.pegdown.ast.HeaderNode;
import org.pegdown.ast.HtmlBlockNode;
import org.pegdown.ast.InlineHtmlNode;
import org.pegdown.ast.RootNode;
import org.pegdown.plugins.ToHtmlSerializerPlugin;

import java.util.ArrayList;

/** Formats markdown AST into HTML. */
public class HtmlFormatter extends ToHtmlSerializer {
  private final TocSerializer toc;

  public HtmlFormatter(GitilesView view) {
    super(new GitLinkRenderer(view), new ArrayList<ToHtmlSerializerPlugin>());

    toc = new TocSerializer();
    plugins.add(toc);
    plugins.add(new DivSerializer());
  }

  /** Render the document AST to blessed as safe HTML. */
  public SanitizedContent toSoyHtml(RootNode root) {
    if (root == null) {
      return null;
    }

    toc.setRoot(root);
    return UnsafeSanitizedContentOrdainer.ordainAsSafe(
        toHtml(root),
        ContentKind.HTML);
  }

  @Override
  public void visit(HeaderNode node) {
    String tag = "h" + node.getLevel();
    printer.print('<').print(tag);
    if (toc.include(node)) {
      String id = toc.idFromHeader(node);
      if (id != null) {
        printer.print(" id=\"").printEncoded(id).print('"');
      }
    }
    printer.print('>');
    visitChildren(node);
    printer.print("</").print(tag).print('>');
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

  @Override
  public void visit(HtmlBlockNode node) {
    // Drop all HTML nodes.
  }

  @Override
  public void visit(InlineHtmlNode node) {
    // Drop all HTML nodes.
  }
}
