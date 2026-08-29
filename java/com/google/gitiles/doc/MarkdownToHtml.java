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

import static com.google.gitiles.doc.MarkdownUtil.getInnerText;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Ascii;
import com.google.common.base.MoreObjects;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.html.types.LegacyConversions;
import com.google.common.html.types.SafeHtml;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.gitiles.GitilesView;
import com.google.gitiles.ThreadSafePrettifyParser;
import com.google.gitiles.doc.html.HtmlBuilder;
import com.google.gitiles.doc.html.SoyHtmlBuilder;
import java.util.List;
import java.util.Optional;
import javax.annotation.Nullable;
import org.commonmark.ext.front.matter.YamlFrontMatterBlock;
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
import org.commonmark.node.LinkReferenceDefinition;
import org.commonmark.node.ListBlock;
import org.commonmark.node.ListItem;
import org.commonmark.node.Node;
import org.commonmark.node.OrderedList;
import org.commonmark.node.Paragraph;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.node.StrongEmphasis;
import org.commonmark.node.Text;
import org.commonmark.node.ThematicBreak;
import org.commonmark.node.Visitor;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.revwalk.RevTree;
import prettify.parser.Prettify;
import syntaxhighlight.ParseResult;

/**
 * Formats parsed Markdown AST into HTML.
 *
 * <p>Callers must create a new instance for each document.
 */
public class MarkdownToHtml implements Visitor {
  public static Builder builder() {
    return new Builder();
  }

  /** A builder for {@link MarkdownToHtml}. */
  public static class Builder {
    private String requestUri;
    private GitilesView view;
    private MarkdownConfig config;
    private String filePath;
    private ObjectReader reader;
    private RevTree root;
    private HtmlSanitizer htmlSanitizer = HtmlSanitizer.DISABLED;

    Builder() {}

    @CanIgnoreReturnValue
    public Builder setRequestUri(@Nullable String uri) {
      requestUri = uri;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder setGitilesView(@Nullable GitilesView view) {
      this.view = view;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder setConfig(@Nullable MarkdownConfig config) {
      this.config = config;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder setFilePath(@Nullable String filePath) {
      this.filePath = Strings.emptyToNull(filePath);
      return this;
    }

    @CanIgnoreReturnValue
    public Builder setReader(ObjectReader reader) {
      this.reader = reader;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder setRootTree(RevTree tree) {
      this.root = tree;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder setHtmlSanitizer(HtmlSanitizer htmlSanitizer) {
      this.htmlSanitizer = MoreObjects.firstNonNull(htmlSanitizer, HtmlSanitizer.DISABLED);
      return this;
    }

    public MarkdownToHtml build() {
      return new MarkdownToHtml(this);
    }
  }

  private HtmlBuilder html;
  private TocFormatter toc;
  private final String requestUri;
  private final GitilesView view;
  private final MarkdownConfig config;
  private final String filePath;
  private final HtmlSanitizer htmlSanitizer;
  private final ImageLoader imageLoader;
  private boolean outputNamedAnchor = true;

  protected MarkdownToHtml(Builder b) {
    requestUri = b.requestUri;
    view = b.view;
    config = b.config;
    filePath = b.filePath;
    htmlSanitizer = b.htmlSanitizer;
    imageLoader = newImageLoader(b);
  }

  protected HtmlBuilder html() {
    return html;
  }

  @Nullable
  private static ImageLoader newImageLoader(Builder b) {
    if (b.reader != null && b.view != null && b.config != null && b.root != null) {
      return new ImageLoader(b.reader, b.view, b.config, b.root);
    }
    return null;
  }

  /** Render the document AST to sanitized HTML. */
  public void renderToHtml(HtmlBuilder out, Node node) {
    if (node != null) {
      html = out;
      toc = new TocFormatter(html, 3);
      toc.setRoot(node);
      node.accept(this);
      html.finish();
      html = null;
      toc = null;
    }
  }

  /** Render the document AST to sanitized HTML. */
  @Nullable
  public SafeHtml toSoyHtml(Node node) {
    if (node != null) {
      SoyHtmlBuilder out = new SoyHtmlBuilder();
      renderToHtml(out, node);
      return out.toSoy();
    }
    return null;
  }

  @Override
  public void visit(Document node) {
    visitChildren(node);
  }

  @SuppressWarnings("ReferenceEquality") // commonmark AST nodes compared by identity.
  private void visit(BlockNote node) {
    html.open("div").attribute("class", node.getClassName());
    Node f = node.getFirstChild();
    if (f == node.getLastChild() && f instanceof Paragraph) {
      // Avoid <p> inside <div> if there is only one <p>.
      visitChildren(f);
    } else {
      visitChildren(node);
    }
    html.close("div");
  }

  private void visit(MultiColumnBlock node) {
    html.open("div").attribute("class", "cols");
    visitChildren(node);
    html.close("div");
  }

  private void visit(MultiColumnBlock.Column node) {
    if (1 <= node.span && node.span <= MultiColumnBlock.GRID_WIDTH) {
      html.open("div").attribute("class", "col-" + node.span);
      visitChildren(node);
      html.close("div");
    }
  }

  private void visit(IframeBlock node) {
    if (HtmlBuilder.isValidHttpUri(node.src)
        && HtmlBuilder.isValidCssDimension(node.height)
        && HtmlBuilder.isValidCssDimension(node.width)
        && config != null
        && config.isIFrameAllowed(node.src)) {
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
      // github markdown compatibility
      String lowerId = Ascii.toLowerCase(id);
      if (!id.equals(lowerId)) {
        html.open("a")
            .attribute("class", "h")
            .attribute("name", lowerId)
            .attribute("href", "#" + lowerId)
            .open("span")
            .close("span")
            .close("a");
      }
    }
    visitChildren(node);
    html.close(tag);
    outputNamedAnchor = true;
  }

  private void visit(NamedAnchor node) {
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

  @Override
  public void visit(BlockQuote node) {
    wrapChildren("blockquote", node);
  }

  @Override
  public void visit(OrderedList node) {
    html.open("ol");
    if (node.getMarkerStartNumber() != 1) {
      html.attribute("start", Integer.toString(node.getMarkerStartNumber()));
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
    if (config != null && config.mermaid && isMermaid(node.getInfo())) {
      Optional<String> svg = SimpleMermaidRenderer.renderToSvg(node.getLiteral());
      if (svg.isPresent()) {
        html.open("div").attribute("class", "mermaid-container");
        html.append(LegacyConversions.riskilyAssumeSafeHtml(svg.get()));
        html.close("div");
        return;
      }
    }
    codeInPre(node.getInfo(), node.getLiteral());
  }

  @Override
  public void visit(IndentedCodeBlock node) {
    codeInPre(null, node.getLiteral());
  }

  @Override
  public void visit(Code node) {
    html.open("code").attribute("class", "code").appendAndEscape(node.getLiteral()).close("code");
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

  @Override
  public void visit(LinkReferenceDefinition node) {
    // Ignored in rendered output
  }

  @Override
  public void visit(Image node) {
    html.open("img")
        .attribute("src", image(node.getDestination()))
        .attribute("title", node.getTitle())
        .attribute("alt", getInnerText(node));
  }

  public void visit(TableBlock node) {
    wrapChildren("table", node);
  }

  private void visit(TableRow node) {
    wrapChildren("tr", node);
  }

  private void visit(TableCell cell) {
    String tag = cell.isHeader() ? "th" : "td";
    html.open(tag);
    TableCell.Alignment alignment = cell.getAlignment();
    if (alignment != null) {
      html.attribute("align", toHtml(alignment));
    }
    visitChildren(cell);
    html.close(tag);
  }

  private void visit(SmartQuoted node) {
    switch (node.getType()) {
      case DOUBLE -> {
        html.entity("&ldquo;");
        visitChildren(node);
        html.entity("&rdquo;");
      }
      case SINGLE -> {
        html.entity("&lsquo;");
        visitChildren(node);
        html.entity("&rsquo;");
      }
    }
  }

  @Override
  public void visit(Text node) {
    html.appendAndEscape(node.getLiteral());
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
    // Discard inline HTML, as it's always partial tags.
  }

  @Override
  public void visit(HtmlBlock node) {
    html.append(htmlSanitizer.sanitize(node.getLiteral()));
  }

  @Override
  public void visit(CustomNode node) {
    switch (node) {
      case NamedAnchor na -> visit(na);
      case SmartQuoted sq -> visit(sq);
      case Strikethrough st -> wrapChildren("del", st);
      case TableBody tb -> wrapChildren("tbody", tb);
      case TableCell tc -> visit(tc);
      case TableHead th -> wrapChildren("thead", th);
      case TableRow tr -> visit(tr);
      default -> throw new IllegalArgumentException("cannot render " + node.getClass());
    }
  }

  @Override
  public void visit(CustomBlock node) {
    switch (node) {
      case BlockNote bn -> visit(bn);
      case IframeBlock ib -> visit(ib);
      case MultiColumnBlock mcb -> visit(mcb);
      case MultiColumnBlock.Column col -> visit(col);
      case TableBlock tb -> visit(tb);
      case TocBlock tb -> toc.format();
      case YamlFrontMatterBlock yfmb -> {
        // YAML front matter is document metadata: omit the whole block. We
        // intentionally do not recurse into it, so its YamlFrontMatterNode
        // children are never visited and need no visit(CustomNode) handling.
      }
      default -> throw new IllegalArgumentException("cannot render " + node.getClass());
    }
  }

  private static boolean isInTightList(Paragraph c) {
    Block b = c.getParent(); // b is probably a ListItem
    if (b != null) {
      Block a = b.getParent();
      return a instanceof ListBlock listBlock && listBlock.isTight();
    }
    return false;
  }

  private static boolean isMermaid(@Nullable String info) {
    return info != null && Ascii.equalsIgnoreCase("mermaid", info.trim());
  }

  private void codeInPre(String lang, String text) {
    html.open("pre").attribute("class", "code");
    text = printLeadingBlankLines(text);
    List<ParseResult> parsed = parse(lang, text);
    if (!parsed.isEmpty()) {
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

  private List<ParseResult> parse(@Nullable String lang, String text) {
    if (Strings.isNullOrEmpty(lang)) {
      return ImmutableList.of();
    }
    try {
      return ThreadSafePrettifyParser.INSTANCE.parse(lang, text);
    } catch (StackOverflowError e) {
      return ImmutableList.of();
    }
  }

  @VisibleForTesting
  String href(String target) {
    if (target.startsWith("#")
        || HtmlBuilder.isValidHttpUri(target)
        || HtmlBuilder.isValidMailtoUri(target)) {
      return target;
    } else if (target.startsWith("git:")) {
      if (HtmlBuilder.isValidGitUri(target)) {
        return target;
      }
      return SoyConstants.NORMAL_URI_INNOCUOUS_OUTPUT;
    }

    String anchor = "";
    int hash = target.indexOf('#');
    if (hash >= 0) {
      anchor = target.substring(hash);
      target = target.substring(0, hash);
    }

    String dest = PathResolver.resolve(filePath, target);
    if (dest == null || view == null) {
      return SoyConstants.NORMAL_URI_INNOCUOUS_OUTPUT;
    }

    GitilesView.Builder b;
    if (view.getType() == GitilesView.Type.ROOTED_DOC) {
      b = GitilesView.rootedDoc();
    } else {
      b = GitilesView.path();
    }
    dest = b.copyFrom(view).setPathPart(dest).build().toUrl();

    return PathResolver.relative(requestUri, dest) + anchor;
  }

  String image(String dest) {
    if (HtmlBuilder.isValidHttpUri(dest) || HtmlBuilder.isImageDataUri(dest)) {
      return dest;
    } else if (imageLoader != null) {
      return imageLoader.inline(filePath, dest);
    }
    return SoyConstants.IMAGE_URI_INNOCUOUS_OUTPUT;
  }

  private static String toHtml(TableCell.Alignment alignment) {
    return switch (alignment) {
      case LEFT -> "left";
      case CENTER -> "center";
      case RIGHT -> "right";
    };
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
}
