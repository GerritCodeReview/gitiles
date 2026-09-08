// Copyright (C) 2016 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.gitiles.doc;

import com.google.gitiles.doc.html.HtmlBuilder;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.annotation.Nullable;
import org.commonmark.Extension;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.Code;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.HardLineBreak;
import org.commonmark.node.HtmlBlock;
import org.commonmark.node.HtmlInline;
import org.commonmark.node.Node;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.node.Text;
import org.commonmark.node.ThematicBreak;
import org.commonmark.parser.Parser;
import org.commonmark.parser.Parser.ParserExtension;
import org.commonmark.parser.PostProcessor;

/**
 * Convert some {@link HtmlInline} and {@link HtmlBlock} to safe types.
 *
 * <p>Gitiles style Markdown accepts only a very small subset of HTML that is safe for use within
 * the document. This {@code PostProcessor} scans parsed nodes and converts them to safer types for
 * rendering:
 *
 * <ul>
 *   <li>{@link HardLineBreak}
 *   <li>{@link ThematicBreak}
 *   <li>{@link NamedAnchor}
 *   <li>{@link IframeBlock}
 *   <li>{@link TableCodeBlock}
 * </ul>
 */
public class GitilesHtmlExtension implements ParserExtension {
  private static final Pattern BREAK = Pattern.compile("<(hr|br)\\s*/?>", Pattern.CASE_INSENSITIVE);

  private static final Pattern ANCHOR_OPEN =
      Pattern.compile(
          "<a\\s+[^>]*(?:name|id)=([\"'])([^\"'\\s]+)\\1[^>]*>", Pattern.CASE_INSENSITIVE);
  private static final Pattern ANCHOR_CLOSE = Pattern.compile("</[aA]>");

  private static final Pattern PRE_OPEN =
      Pattern.compile("<pre(?:\s+[^>]*)?>", Pattern.CASE_INSENSITIVE);
  private static final Pattern PRE_CLOSE = Pattern.compile("</pre\\s*>", Pattern.CASE_INSENSITIVE);
  private static final Pattern CODE_OPEN =
      Pattern.compile("<code(?:\s+[^>]*)?>", Pattern.CASE_INSENSITIVE);
  private static final Pattern CODE_CLOSE =
      Pattern.compile("</code\\s*>", Pattern.CASE_INSENSITIVE);

  private static final Pattern LANG_ATTR =
      Pattern.compile(
          "(?:class=[\"'](?:language-|lang-)?([a-zA-Z0-9_-]+)[\"']|lang=[\"']([a-zA-Z0-9_-]+)[\"'])",
          Pattern.CASE_INSENSITIVE);

  private static final Pattern PRE_BLOCK =
      Pattern.compile("^\\s*<pre(?:\s+[^>]*)?>([\\s\\S]*?)</pre>\\s*$", Pattern.CASE_INSENSITIVE);
  private static final Pattern CODE_BLOCK_INNER =
      Pattern.compile("^\\s*<code(?:\s+[^>]*)?>([\\s\\S]*?)</code>\\s*$", Pattern.CASE_INSENSITIVE);

  private static final Pattern IFRAME_OPEN =
      Pattern.compile("<iframe\\s+", Pattern.CASE_INSENSITIVE);
  private static final Pattern IFRAME_CLOSE =
      Pattern.compile("(?:/?>|</iframe>)", Pattern.CASE_INSENSITIVE);

  private static final Pattern ATTR =
      Pattern.compile(
          "\\s+([a-z-]+)\\s*=\\s*([^\\s\"'=<>`]+|'[^']*'|\"[^\"]*\")", Pattern.CASE_INSENSITIVE);

  public static Extension create() {
    return new GitilesHtmlExtension();
  }

  private GitilesHtmlExtension() {}

  @Override
  public void extend(Parser.Builder builder) {
    builder.postProcessor(new HtmlProcessor());
  }

  private static class HtmlProcessor implements PostProcessor {
    @Override
    public Node process(Node node) {
      node.accept(new HtmlVisitor());
      return node;
    }
  }

  private static class HtmlVisitor extends AbstractVisitor {
    @Override
    protected void visitChildren(Node parent) {
      Node node = parent.getFirstChild();
      while (node != null) {
        if (node instanceof HtmlInline) {
          node = inline((HtmlInline) node);
        } else if (node instanceof HtmlBlock) {
          node = block((HtmlBlock) node);
        } else {
          node.accept(this);
          node = node.getNext();
        }
      }
    }

    @Override
    public void visit(HtmlInline node) {
      // Handled in visitChildren to safely support node replacements.
    }

    @Override
    public void visit(HtmlBlock node) {
      // Handled in visitChildren to safely support node replacements.
    }
  }

  private static @Nullable Node inline(HtmlInline curr) {
    String html = curr.getLiteral();
    Matcher m = BREAK.matcher(html);
    if (m.matches()) {
      Node br = "br".equalsIgnoreCase(m.group(1)) ? new HardLineBreak() : new ThematicBreak();
      curr.insertBefore(br);
      Node next = curr.getNext();
      curr.unlink();
      return next;
    }

    m = ANCHOR_OPEN.matcher(html);
    if (m.matches()) {
      String name = m.group(2);
      Node next = curr.getNext();

      if (isAnchorClose(next)) {
        NamedAnchor anchor = new NamedAnchor();
        anchor.setName(name);
        curr.insertBefore(anchor);
        MarkdownUtil.trimPreviousWhitespace(anchor);
        Node afterClose = next.getNext();
        curr.unlink();
        next.unlink();
        return afterClose;
      }
    }

    m = PRE_OPEN.matcher(html);
    if (m.matches()) {
      Node afterPre = processPre(curr);
      if (afterPre != null) {
        return afterPre;
      }
    }

    return curr.getNext();
  }

  @SuppressWarnings("ReferenceEquality") // commonmark AST nodes compared by identity.
  private static @Nullable Node processPre(HtmlInline preOpen) {
    Node preClose = null;
    for (Node s = preOpen.getNext(); s != null; s = s.getNext()) {
      if (s instanceof HtmlInline && PRE_CLOSE.matcher(((HtmlInline) s).getLiteral()).matches()) {
        preClose = s;
        break;
      }
    }
    if (preClose == null) {
      return null;
    }

    String lang = extractLang(preOpen.getLiteral());

    Node codeOpen = null;
    Node first = preOpen.getNext();
    if (first != preClose && first instanceof HtmlInline) {
      String firstHtml = ((HtmlInline) first).getLiteral();
      if (CODE_OPEN.matcher(firstHtml).matches()) {
        codeOpen = first;
        String codeLang = extractLang(firstHtml);
        if (codeLang != null) {
          lang = codeLang;
        }
      }
    }

    Node codeClose = null;
    Node last = preClose.getPrevious();
    if (last != preOpen && last != codeOpen && last instanceof HtmlInline) {
      if (CODE_CLOSE.matcher(((HtmlInline) last).getLiteral()).matches()) {
        codeClose = last;
      }
    }

    StringBuilder text = new StringBuilder();
    Node start = (codeOpen != null) ? codeOpen.getNext() : preOpen.getNext();
    Node end = (codeClose != null) ? codeClose : preClose;
    for (Node c = start; c != null && c != end; c = c.getNext()) {
      appendCodeContent(text, c);
    }

    TableCodeBlock block = new TableCodeBlock();
    block.setInfo(lang);
    block.setLiteral(text.toString());

    preOpen.insertBefore(block);

    Node afterPre = preClose.getNext();
    Node c = preOpen;
    while (c != null) {
      Node toUnlink = c;
      c = (c == preClose) ? null : c.getNext();
      toUnlink.unlink();
    }

    return afterPre;
  }

  private static void appendCodeContent(StringBuilder text, Node n) {
    if (n instanceof Text) {
      text.append(((Text) n).getLiteral());
    } else if (n instanceof SoftLineBreak || n instanceof HardLineBreak) {
      text.append('\n');
    } else if (n instanceof Code) {
      text.append(((Code) n).getLiteral());
    } else if (n instanceof HtmlInline) {
      String literal = ((HtmlInline) n).getLiteral();
      if (BREAK.matcher(literal).matches()) {
        text.append('\n');
      } else if (CODE_OPEN.matcher(literal).matches() || CODE_CLOSE.matcher(literal).matches()) {
        // Redundant code tags are ignored.
      } else {
        text.append(literal);
      }
    } else {
      for (Node child = n.getFirstChild(); child != null; child = child.getNext()) {
        appendCodeContent(text, child);
      }
    }
  }

  private static @Nullable String extractLang(String html) {
    Matcher m = LANG_ATTR.matcher(html);
    if (m.find()) {
      String l = m.group(1) != null ? m.group(1) : m.group(2);
      if (!"code".equalsIgnoreCase(l) && !"prettyprint".equalsIgnoreCase(l)) {
        return l;
      }
    }
    return null;
  }

  private static boolean isAnchorClose(@Nullable Node n) {
    return n instanceof HtmlInline && ANCHOR_CLOSE.matcher(((HtmlInline) n).getLiteral()).matches();
  }

  private static @Nullable Node block(HtmlBlock curr) {
    String html = curr.getLiteral();
    Matcher m = IFRAME_OPEN.matcher(html);
    if (m.find()) {
      int start = m.end() - 1 /* leave whitespace */;
      m = IFRAME_CLOSE.matcher(html.substring(start));
      if (m.find()) {
        int end = start + m.start();
        IframeBlock f = iframe(html.substring(start, end));
        if (f != null) {
          curr.insertBefore(f);
          Node next = curr.getNext();
          curr.unlink();
          return next;
        }
      }
    }

    m = PRE_BLOCK.matcher(html.trim());
    if (m.matches()) {
      String inner = m.group(1);
      String lang = extractLang(html);
      Matcher cm = CODE_BLOCK_INNER.matcher(inner);
      if (cm.matches()) {
        String codeLang = extractLang(inner);
        if (codeLang != null) {
          lang = codeLang;
        }
        inner = cm.group(1);
      }
      inner = org.apache.commons.text.StringEscapeUtils.unescapeHtml4(inner);
      FencedCodeBlock fcb = new FencedCodeBlock();
      fcb.setInfo(lang);
      fcb.setLiteral(inner);
      curr.insertBefore(fcb);
      Node next = curr.getNext();
      curr.unlink();
      return next;
    }

    return curr.getNext();
  }

  private static @Nullable IframeBlock iframe(String html) {
    IframeBlock iframe = new IframeBlock();
    Matcher m = ATTR.matcher(html);
    while (m.find()) {
      String att = m.group(1).toLowerCase();
      String val = attributeValue(m);
      switch (att) {
        case "src":
          if (!HtmlBuilder.isValidHttpUri(val)) {
            return null;
          }
          iframe.src = val;
          break;

        case "height":
          if (!HtmlBuilder.isValidCssDimension(val)) {
            return null;
          }
          iframe.height = val;
          break;

        case "width":
          if (!HtmlBuilder.isValidCssDimension(val)) {
            return null;
          }
          iframe.width = val;
          break;

        case "frameborder":
          iframe.border = !"0".equals(val);
          break;
      }
    }
    return iframe.src != null ? iframe : null;
  }

  private static String attributeValue(Matcher m) {
    String val = m.group(2);
    if (val.length() >= 2 && (val.charAt(0) == '\'' || val.charAt(0) == '"')) {
      // Capture group includes the opening and closing quotation marks if the
      // attribute value was quoted in the source document. Trim these.
      val = val.substring(1, val.length() - 1);
    }
    return val;
  }
}
