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

import static com.google.common.base.Preconditions.checkState;
import static com.google.gitiles.doc.MarkdownHelper.getInnerText;

import com.google.common.base.Function;
import com.google.gitiles.doc.TocNode;
import com.google.gitiles.doc.Visitor;
import com.google.template.soy.data.SanitizedContent;

import org.pegdown.ast.AbbreviationNode;
import org.pegdown.ast.AutoLinkNode;
import org.pegdown.ast.BlockQuoteNode;
import org.pegdown.ast.BulletListNode;
import org.pegdown.ast.CodeNode;
import org.pegdown.ast.DefinitionListNode;
import org.pegdown.ast.DefinitionNode;
import org.pegdown.ast.DefinitionTermNode;
import org.pegdown.ast.ExpImageNode;
import org.pegdown.ast.ExpLinkNode;
import org.pegdown.ast.HeaderNode;
import org.pegdown.ast.HtmlBlockNode;
import org.pegdown.ast.InlineHtmlNode;
import org.pegdown.ast.ListItemNode;
import org.pegdown.ast.MailLinkNode;
import org.pegdown.ast.Node;
import org.pegdown.ast.OrderedListNode;
import org.pegdown.ast.ParaNode;
import org.pegdown.ast.QuotedNode;
import org.pegdown.ast.RefImageNode;
import org.pegdown.ast.RefLinkNode;
import org.pegdown.ast.ReferenceNode;
import org.pegdown.ast.RootNode;
import org.pegdown.ast.SimpleNode;
import org.pegdown.ast.SpecialTextNode;
import org.pegdown.ast.StrikeNode;
import org.pegdown.ast.StrongEmphSuperNode;
import org.pegdown.ast.SuperNode;
import org.pegdown.ast.TableBodyNode;
import org.pegdown.ast.TableCaptionNode;
import org.pegdown.ast.TableCellNode;
import org.pegdown.ast.TableColumnNode;
import org.pegdown.ast.TableHeaderNode;
import org.pegdown.ast.TableNode;
import org.pegdown.ast.TableRowNode;
import org.pegdown.ast.TextNode;
import org.pegdown.ast.VerbatimNode;
import org.pegdown.ast.WikiLinkNode;

/**
 * Formats parsed markdown AST into HTML.
 * <p>
 * Callers must create a new instance for each RootNode.
 */
public class MarkdownToHtml extends HtmlBuilder implements Visitor {
  private final ReferenceMap references = new ReferenceMap();
  private final TocFormatter toc = new TocFormatter(this, 3);
  private TableState table;

  public MarkdownToHtml(Function<String, String> pathResolver) {
    super(pathResolver);
  }

  /** Render the document AST to sanitized HTML. */
  public SanitizedContent toSoyHtml(RootNode node) {
    if (node == null) {
      return null;
    }

    toc.setRoot(node);
    node.accept(this);
    return toSoy();
  }

  @Override
  public void visit(RootNode node) {
    references.add(node);
    visitChildren(node);
  }

  @Override
  public void visit(TocNode node) {
    toc.format();
  }

  @Override
  public void visit(HeaderNode node) {
    String tag = "h" + node.getLevel();
    open(tag);
    if (toc.include(node)) {
      attribute("id", toc.idFromHeader(node));
    }
    visitChildren(node);
    close(tag);
  }

  @Override
  public void visit(ParaNode node) {
    wrapChildren("p", node);
  }

  @Override
  public void visit(BlockQuoteNode node) {
    wrapChildren("blockquote", node);
  }

  @Override
  public void visit(OrderedListNode node) {
    wrapChildren("ol", node);
  }

  @Override
  public void visit(BulletListNode node) {
    wrapChildren("ul", node);
  }

  @Override
  public void visit(ListItemNode node) {
    wrapChildren("li", node);
  }

  @Override
  public void visit(DefinitionListNode node) {
    wrapChildren("dl", node);
  }

  @Override
  public void visit(DefinitionNode node) {
    wrapChildren("dd", node);
  }

  @Override
  public void visit(DefinitionTermNode node) {
    wrapChildren("dt", node);
  }

  @Override
  public void visit(VerbatimNode node) {
    open("pre");
    open("code");
    String text = node.getText();
    while (text.startsWith("\n")) {
      open("br");
      text = text.substring(1);
    }
    appendAndEscape(text);
    close("code");
    close("pre");
  }

  @Override
  public void visit(CodeNode node) {
    wrapText("code", node);
  }

  @Override
  public void visit(StrikeNode node) {
    wrapChildren("del", node);
  }

  @Override
  public void visit(StrongEmphSuperNode node) {
    if (node.isClosed()) {
      wrapChildren(node.isStrong() ? "strong" : "em", node);
    } else {
      // Unclosed (or unmatched) sequence is plain text.
      appendAndEscape(node.getChars());
      visitChildren(node);
    }
  }

  @Override
  public void visit(AutoLinkNode node) {
    String url = node.getText();
    open("a").attribute("href", url)
      .appendAndEscape(url)
      .close("a");
  }

  @Override
  public void visit(MailLinkNode node) {
    String addr = node.getText();
    open("a").attribute("href", "mailto:" + addr)
      .appendAndEscape(addr)
      .close("a");
  }

  @Override
  public void visit(WikiLinkNode node) {
    String text = node.getText();
    String path = text.replace(' ', '-') + ".md";
    open("a").attribute("href", path)
      .appendAndEscape(text)
      .close("a");
  }

  @Override
  public void visit(ExpLinkNode node) {
    open("a").attribute("href", node.url).attribute("title", node.title);
    visitChildren(node);
    close("a");
  }

  @Override
  public void visit(RefLinkNode node) {
    ReferenceNode ref = references.get(node.referenceKey, getInnerText(node));
    if (ref != null) {
      open("a").attribute("href", ref.getUrl()).attribute("title", ref.getTitle());
      visitChildren(node);
      close("a");
    } else {
      // Treat a broken RefLink as plain text.
      visitChildren(node);
    }
  }

  @Override
  public void visit(ExpImageNode node) {
    open("img")
      .attribute("src", node.url)
      .attribute("title", node.title)
      .attribute("alt", getInnerText(node));
  }

  @Override
  public void visit(RefImageNode node) {
    String alt = getInnerText(node);
    String url, title = alt;
    ReferenceNode ref = references.get(node.referenceKey, alt);
    if (ref != null) {
      url = ref.getUrl();
      title = ref.getTitle();
    } else {
      // If reference is missing, insert a broken image.
      url = IMAGE_DATA.getInnocuousOutput();
    }
    open("img")
      .attribute("src", url)
      .attribute("title", title)
      .attribute("alt", alt);
  }

  @Override
  public void visit(TableNode node) {
    table = new TableState(node);
    wrapChildren("table", node);
    table = null;
  }

  @Override
  public void visit(TableHeaderNode node) {
    checkState(table != null, "%s must be in table", node);
    table.inHeader = true;
    wrapChildren("thead", node);
    table.inHeader = false;
  }

  @Override
  public void visit(TableBodyNode node) {
    wrapChildren("tbody", node);
  }

  @Override
  public void visit(TableCaptionNode node) {
    wrapChildren("caption", node);
  }

  @Override
  public void visit(TableRowNode node) {
    checkState(table != null, "%s must be in table", node);
    table.startRow();
    wrapChildren("tr", node);
  }

  @Override
  public void visit(TableCellNode node) {
    checkState(table != null, "%s must be in table", node);
    String tag = table.inHeader ? "th" : "td";
    open(tag);
    attribute("align", table.getAlign());
    if (node.getColSpan() > 1) {
      attribute("colspan", Integer.toString(node.getColSpan()));
    }
    visitChildren(node);
    close(tag);
    table.done(node);
  }

  @Override
  public void visit(TableColumnNode node) {
    // Not for output; should not be in the Visitor API.
  }

  @Override
  public void visit(TextNode node) {
    appendAndEscape(node.getText());
    // TODO(sop) printWithAbbreviations
  }

  @Override
  public void visit(SpecialTextNode node) {
    appendAndEscape(node.getText());
  }

  @Override
  public void visit(QuotedNode node) {
    switch (node.getType()) {
      case DoubleAngle:
        entity("&laquo;");
        visitChildren(node);
        entity("&raquo;");
        break;
      case Double:
        entity("&ldquo;");
        visitChildren(node);
        entity("&rdquo;");
        break;
      case Single:
        entity("&lsquo;");
        visitChildren(node);
        entity("&rsquo;");
        break;
      default:
        checkState(false, "unsupported quote %s", node.getType());
    }
  }

  @Override
  public void visit(SimpleNode node) {
    switch (node.getType()) {
      case Apostrophe:
        entity("&rsquo;");
        break;
      case Ellipsis:
        entity("&hellip;");
        break;
      case Emdash:
        entity("&mdash;");
        break;
      case Endash:
        entity("&ndash;");
        break;
      case HRule:
        open("hr");
        break;
      case Linebreak:
        open("br");
        break;
      case Nbsp:
        entity("&nbsp;");
        break;
      default:
        checkState(false, "unsupported node %s", node.getType());
    }
  }

  @Override
  public void visit(SuperNode node) {
    visitChildren(node);
  }

  @Override
  public void visit(Node node) {
    checkState(false, "node %s unsupported", node.getClass());
  }

  @Override
  public void visit(HtmlBlockNode node) {
    // Drop all HTML nodes.
  }

  @Override
  public void visit(InlineHtmlNode node) {
    // Drop all HTML nodes.
  }

  @Override
  public void visit(ReferenceNode node) {
    // Reference nodes are not printed; they only declare an item.
  }

  @Override
  public void visit(AbbreviationNode node) {
    // Abbreviation nodes are not printed; they only declare an item.
  }

  private void wrapText(String tag, TextNode node) {
    open(tag).appendAndEscape(node.getText()).close(tag);
  }

  private void wrapChildren(String tag, SuperNode node) {
    open(tag);
    visitChildren(node);
    close(tag);
  }

  private void visitChildren(Node node) {
    for (Node child : node.getChildren()) {
      child.accept(this);
    }
  }
}
