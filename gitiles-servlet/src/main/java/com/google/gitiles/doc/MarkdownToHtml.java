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

import static com.google.common.base.Preconditions.checkState;
import static com.google.gitiles.doc.MarkdownUtil.getInnerText;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;
import com.google.gitiles.GitilesView;
import com.google.gitiles.ThreadSafePrettifyParser;
import com.google.gitiles.doc.html.HtmlBuilder;
import com.google.template.soy.data.SanitizedContent;
import com.google.template.soy.shared.restricted.EscapingConventions.FilterNormalizeUri;

import org.commonmark.ext.gfm.strikethrough.Strikethrough;
import org.commonmark.ext.gfm.tables.TableBlock;
import org.commonmark.ext.gfm.tables.TableBody;
import org.commonmark.ext.gfm.tables.TableCell;
import org.commonmark.ext.gfm.tables.TableHead;
import org.commonmark.ext.gfm.tables.TableRow;
import org.commonmark.node.Block;
import org.commonmark.node.BlockQuote;
import org.commonmark.node.BulletList;
import org.commonmark.node.Code;
import org.commonmark.node.CustomBlock;
import org.commonmark.node.CustomNode;
import org.commonmark.node.Document;
import org.commonmark.node.Emphasis;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.HardLineBreak;
import org.commonmark.node.Heading;
import org.commonmark.node.HtmlBlock;
import org.commonmark.node.HtmlInline;
import org.commonmark.node.Image;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.Link;
import org.commonmark.node.ListBlock;
import org.commonmark.node.ListItem;
import org.commonmark.node.Node;
import org.commonmark.node.OrderedList;
import org.commonmark.node.Paragraph;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.node.StrongEmphasis;
import org.commonmark.node.Text;
import org.commonmark.node.ThematicBreak;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.util.StringUtils;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import prettify.parser.Prettify;
import syntaxhighlight.ParseResult;

/**
 * Formats parsed Markdown AST into HTML.
 * <p>
 * Callers must create a new instance for each document.
 */
public class MarkdownToHtml implements Visitor {
  private final HtmlBuilder html = new HtmlBuilder();
  private final TocFormatter toc = new TocFormatter(html, 3);
  private final GitilesView view;
  private final Config cfg;
  private final String filePath;
  private ImageLoader imageLoader;
  private TableState table;
  private boolean outputNamedAnchor = true;

  /**
   * Initialize a Markdown to HTML converter.
   *
   * @param view view used to access this Markdown on the web. Some elements of
   *        the view may be used to generate hyperlinks to other files, e.g.
   *        repository name and revision.
   * @param cfg
   * @param filePath actual path of the Markdown file in the Git repository. This must
   *        always be a file, e.g. {@code doc/README.md}. The path is used to
   *        resolve relative links within the repository.
   */
  public MarkdownToHtml(GitilesView view, Config cfg, String filePath) {
    this.view = view;
    this.cfg = cfg;
    this.filePath = filePath;
  }

  public MarkdownToHtml setImageLoader(ImageLoader img) {
    imageLoader = img;
    return this;
  }

  /** Render the document AST to sanitized HTML. */
  public SanitizedContent toSoyHtml(Node node) {
    if (node == null) {
      return null;
    }

    toc.setRoot(node);
    node.accept(this);
    return html.toSoy();
  }

  @Override
  public void visit(Document node) {
    visitChildren(node);
  }

  @Override
  public void visit(TocBlock node) {
    toc.format();
  }

  @Override
  public void visit(BlockNote node) {
    html.open("div").attribute("class", node.getClassName());
    if (node.getFirstChild() == node.getLastChild() && node.getFirstChild() instanceof Paragraph) {
      // Avoid <p> inside <div> if there is only one <p>.
      visitChildren(node.getFirstChild());
    } else {
      visitChildren(node);
    }
    html.close("div");
  }

  @Override
  public void visit(MultiColumnBlock node) {
    html.open("div").attribute("class", "cols");
    visitChildren(node);
    html.close("div");
  }

  @Override
  public void visit(MultiColumnBlock.Column node) {
    if (1 <= node.span && node.span <= MultiColumnBlock.GRID_WIDTH) {
      html.open("div").attribute("class", "col-" + node.span);
      visitChildren(node);
      html.close("div");
    }
  }

  @Override
  public void visit(IframeBlock node) {
    if (HtmlBuilder.isValidHttpUri(node.src)
        && HtmlBuilder.isValidCssDimension(node.height)
        && HtmlBuilder.isValidCssDimension(node.width)
        && canRender(node)) {
      html.open("iframe")
          .attribute("src", node.src)
          .attribute("height", node.height)
          .attribute("width", node.width);
      if (!node.border) {
        html.attribute("class", "noborder");
      }
      html.close("iframe");
    }
  }

  private boolean canRender(IframeBlock node) {
    String[] ok = cfg.getStringList("markdown", null, "allowiframe");
    if (ok.length == 1 && StringUtils.toBooleanOrNull(ok[0]) == Boolean.TRUE) {
      return true;
    }
    for (String m : ok) {
      if (m.equals(node.src) || (m.endsWith("/") && node.src.startsWith(m))) {
        return true;
      }
    }
    return false; // By default do not render iframe.
  }

  @Override
  public void visit(Heading node) {
    outputNamedAnchor = false;
    String tag = "h" + node.getLevel();
    html.open(tag);
    String id = toc.idFromHeader(node);
    if (id != null) {
      html.open("a")
          .attribute("class", "h")
          .attribute("name", id)
          .attribute("href", "#" + id)
          .open("span")
          .close("span")
          .close("a");
    }
    visitChildren(node);
    html.close(tag);
    outputNamedAnchor = true;
  }

  @Override
  public void visit(NamedAnchor node) {
    if (outputNamedAnchor) {
      html.open("a").attribute("name", node.getName()).close("a");
    }
  }

  @Override
  public void visit(Paragraph node) {
    if (isInTightList(node)) {
      // Avoid unnecessary <p> tags within <ol><li> structures.
      visitChildren(node);
    } else {
      wrapChildren("p", node);
    }
  }

  private static boolean isInTightList(Paragraph c) {
    Block b = c.getParent(); // b is probably a ListItem
    if (b != null) {
      Block a = b.getParent();
      return a instanceof ListBlock && ((ListBlock) a).isTight();
    }
    return false;
  }

  @Override
  public void visit(BlockQuote node) {
    wrapChildren("blockquote", node);
  }

  @Override
  public void visit(OrderedList node) {
    html.open("ol");
    if (node.getStartNumber() != 1) {
      html.attribute("start", Integer.toString(node.getStartNumber()));
    }
    visitChildren(node);
    html.close("ol");
  }

  @Override
  public void visit(BulletList node) {
    wrapChildren("ul", node);
  }

  @Override
  public void visit(ListItem node) {
    wrapChildren("li", node);
  }

  @Override
  public void visit(FencedCodeBlock node) {
    pre(node.getInfo(), node.getLiteral());
  }

  @Override
  public void visit(IndentedCodeBlock node) {
    pre(null, node.getLiteral());
  }

  private void pre(String lang, String text) {
    html.open("pre").attribute("class", "code");
    text = printLeadingBlankLines(text);
    List<ParseResult> parsed = parse(lang, text);
    if (parsed != null) {
      int last = 0;
      for (ParseResult r : parsed) {
        span(null, text, last, r.getOffset());
        last = r.getOffset() + r.getLength();
        span(r.getStyleKeysString(), text, r.getOffset(), last);
      }
      if (last < text.length()) {
        span(null, text, last, text.length());
      }
    } else {
      html.appendAndEscape(text);
    }
    html.close("pre");
  }

  private String printLeadingBlankLines(String text) {
    int i = 0;
    while (i < text.length() && text.charAt(i) == '\n') {
      html.open("br");
      i++;
    }
    return text.substring(i);
  }

  private void span(String classes, String s, int start, int end) {
    if (end - start > 0) {
      if (Strings.isNullOrEmpty(classes)) {
        classes = Prettify.PR_PLAIN;
      }
      html.open("span").attribute("class", classes);
      html.appendAndEscape(s.substring(start, end));
      html.close("span");
    }
  }

  private List<ParseResult> parse(String lang, String text) {
    if (Strings.isNullOrEmpty(lang)) {
      return null;
    }
    try {
      return ThreadSafePrettifyParser.INSTANCE.parse(lang, text);
    } catch (StackOverflowError e) {
      return null;
    }
  }

  @Override
  public void visit(Code node) {
    wrapText("code", node.getLiteral());
  }

  public void visit(Strikethrough node) {
    wrapChildren("del", node);
  }

  @Override
  public void visit(Emphasis node) {
    wrapChildren("em", node);
  }

  @Override
  public void visit(StrongEmphasis node) {
    wrapChildren("strong", node);
  }

  @Override
  public void visit(Link node) {
    html.open("a")
        .attribute("href", href(node.getDestination()))
        .attribute("title", node.getTitle());
    visitChildren(node);
    html.close("a");
  }

  @VisibleForTesting
  String href(String target) {
    if (target.startsWith("#") || HtmlBuilder.isValidHttpUri(target)) {
      return target;
    }

    String anchor = "";
    int hash = target.indexOf('#');
    if (hash >= 0) {
      anchor = target.substring(hash);
      target = target.substring(0, hash);
    }

    if (target.startsWith("/")) {
      return toPath(target) + anchor;
    }

    String dir = trimLastComponent(filePath);
    while (!target.isEmpty()) {
      if (target.startsWith("../") || target.equals("..")) {
        if (dir.isEmpty()) {
          return FilterNormalizeUri.INSTANCE.getInnocuousOutput();
        }
        dir = trimLastComponent(dir);
        target = target.equals("..") ? "" : target.substring(3);
      } else if (target.startsWith("./")) {
        target = target.substring(2);
      } else if (target.equals(".")) {
        target = "";
      } else {
        break;
      }
    }

    return toPath(dir + '/' + target) + anchor;
  }

  private static String trimLastComponent(String path) {
    int slash = path.lastIndexOf('/');
    return slash < 0 ? "" : path.substring(0, slash);
  }

  private String toPath(String path) {
    GitilesView.Builder b;
    if (view.getType() == GitilesView.Type.ROOTED_DOC) {
      b = GitilesView.rootedDoc();
    } else {
      b = GitilesView.path();
    }
    return b.copyFrom(view).setPathPart(path).build().toUrl();
  }

  @Override
  public void visit(Image node) {
    html.open("img")
        .attribute("src", resolveImageUrl(node.getDestination()))
        .attribute("title", node.getTitle())
        .attribute("alt", getInnerText(node));
  }

  private String resolveImageUrl(String url) {
    if (imageLoader == null
        || url.startsWith("https://")
        || url.startsWith("http://")
        || url.startsWith("data:")) {
      return url;
    }
    return imageLoader.loadImage(url);
  }

  public void visit(TableBlock node) {
    table = new TableState();
    wrapChildren("table", node);
    table = null;
  }

  private void mustBeInsideTable(Node node) {
    checkState(table != null, "%s must be in table", node);
  }

  public void visit(TableHead node) {
    mustBeInsideTable(node);
    wrapChildren("thead", node);
  }

  public void visit(TableBody node) {
    wrapChildren("tbody", node);
  }

  public void visit(TableRow node) {
    mustBeInsideTable(node);
    table.startRow();
    wrapChildren("tr", node);
  }

  public void visit(TableCell cell) {
    mustBeInsideTable(cell);
    String tag = cell.isHeader() ? "th" : "td";
    html.open(tag);

    TableCell.Alignment alignment = cell.getAlignment();
    if (alignment != null) {
      html.attribute("align", alignment.toString().toLowerCase());
    }
    // TODO(sop) colspan
    // if (node.getColSpan() > 1) {
    //   html.attribute("colspan", Integer.toString(node.getColSpan()));
    // }
    visitChildren(cell);
    html.close(tag);
    table.done(cell);
  }

  @Override
  public void visit(SmartQuoted node) {
    switch (node.getType()) {
      case DOUBLE:
        html.entity("&ldquo;");
        visitChildren(node);
        html.entity("&rdquo;");
        break;
      case SINGLE:
        html.entity("&lsquo;");
        visitChildren(node);
        html.entity("&rsquo;");
        break;
      default:
        checkState(false, "unsupported quote %s", node.getType());
    }
  }

  private static final Pattern PRETTY = Pattern.compile("('|[.]{3}|-{2,3})");

  @Override
  public void visit(Text node) {
    String text = node.getLiteral();
    Matcher pretty = PRETTY.matcher(text);
    int i = 0;
    while (pretty.find()) {
      int s = pretty.start();
      if (i < s) {
        html.appendAndEscape(text.substring(i, s));
      }
      switch (pretty.group(0)) {
        case "'":
          html.entity("&rsquo;");
          break;
        case "...":
          html.entity("&hellip;");
          break;
        case "--":
          html.entity("&ndash;");
          break;
        case "---":
          html.entity("&mdash;");
          break;
      }
      i = pretty.end();
    }
    if (i < text.length()) {
      html.appendAndEscape(text.substring(i));
    }
  }

  @Override
  public void visit(SoftLineBreak node) {
    html.space();
  }

  @Override
  public void visit(HardLineBreak node) {
    html.open("br");
  }

  @Override
  public void visit(ThematicBreak thematicBreak) {
    html.open("hr");
  }

  @Override
  public void visit(HtmlInline node) {
    // Discard all HTML.
  }

  @Override
  public void visit(HtmlBlock node) {
    // Discard all HTML.
  }

  private void wrapText(String tag, String text) {
    html.open(tag).appendAndEscape(text).close(tag);
  }

  private void wrapChildren(String tag, Node node) {
    html.open(tag);
    visitChildren(node);
    html.close(tag);
  }

  private void visitChildren(Node node) {
    for (Node c = node.getFirstChild(); c != null; c = c.getNext()) {
      c.accept(this);
    }
  }

  @Override
  public void visit(CustomBlock node) {
    if (node instanceof TableBlock) {
      visit((TableBlock) node);
    } else {
      checkState(false, "cannot render %s", node.getClass());
    }
  }

  @Override
  public void visit(CustomNode node) {
    if (node instanceof Strikethrough) {
      visit((Strikethrough) node);
    } else if (node instanceof TableHead) {
      visit((TableHead) node);
    } else if (node instanceof TableBody) {
      visit((TableBody) node);
    } else if (node instanceof TableRow) {
      visit((TableRow) node);
    } else if (node instanceof TableCell) {
      visit((TableCell) node);
    } else {
      checkState(false, "cannot render %s", node.getClass());
    }
  }
}
