// Copyright (C) 2026 Google Inc. All Rights Reserved.
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

package com.google.gitiles.lean;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.html.types.SafeHtml;
import com.google.gitiles.GitilesView;
import com.google.gitiles.doc.GitilesMarkdown;
import com.google.gitiles.doc.MarkdownConfig;
import com.google.gitiles.doc.MarkdownToHtml;
import com.google.gitiles.doc.html.HtmlBuilder;
import com.google.gitiles.doc.html.SoyHtmlBuilder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.annotation.Nullable;
import org.eclipse.jgit.lib.Config;

/** Server-side HTML renderer for Lean 4 blobs using {@link SoyHtmlBuilder}. */
public final class LeanHtml {
  private static final String CLASS_VIEW = "LeanView";
  private static final String CLASS_VIEW_BLOCKS = "LeanView-blocks";
  private static final String CLASS_SUMMARY = "LeanSummary";
  private static final String CLASS_SUMMARY_LEFT = "LeanSummary-left";
  private static final String CLASS_SUMMARY_RIGHT = "LeanSummary-right";
  private static final String CLASS_SUMMARY_LANG = "LeanSummary-lang";
  private static final String CLASS_SUMMARY_STATS = "LeanSummary-stats";
  private static final String CLASS_SUMMARY_STAT_SEP = "LeanSummary-statSep";
  private static final String CLASS_SUMMARY_SOURCE = "LeanSummary-source";
  private static final String CLASS_PILL = "LeanPill";
  private static final String CLASS_PILL_VERIFIED = "LeanPill--verified";
  private static final String CLASS_PILL_ERROR = "LeanPill--error";
  private static final String CLASS_PILL_WARNING = "LeanPill--warning";
  private static final String CLASS_MODULE_DOC = "LeanModuleDoc";
  private static final String CLASS_MODULE_DOC_HEADER = "LeanModuleDoc-header";
  private static final String CLASS_BANNER = "LeanBanner";
  private static final String CLASS_BANNER_TITLE = "LeanBanner-title";
  private static final String CLASS_STRIP = "LeanStrip";
  private static final String CLASS_STRIP_COMMENT = "LeanStrip--comment";
  private static final String CLASS_STRIP_LICENSE = "LeanStrip--license";
  private static final String CLASS_DECL = "LeanDecl";
  private static final String CLASS_DECL_COMPACT = "LeanDecl--compact";
  private static final String CLASS_DECL_HYGIENE_ALERT = "LeanDecl--hygieneAlert";
  private static final String CLASS_DECL_HEADER = "LeanDecl-header";
  private static final String CLASS_DECL_HEADER_MAIN = "LeanDecl-headerMain";
  private static final String CLASS_DECL_HEADER_META = "LeanDecl-headerMeta";
  private static final String CLASS_DECL_KIND = "LeanDecl-kind";
  private static final String CLASS_DECL_NAME = "LeanDecl-name";
  private static final String CLASS_DECL_CHIP = "LeanDecl-chip";
  private static final String CLASS_DECL_CHIP_META = "LeanDecl-chip--meta";
  private static final String CLASS_DECL_CHIP_ATTR = "LeanDecl-chip--attr";
  private static final String CLASS_DECL_CHIP_STRATEGY = "LeanDecl-chip--strategy";
  private static final String CLASS_DECL_CHIP_DECIDE = "LeanDecl-chip--decide";
  private static final String CLASS_DECL_BADGE = "LeanDecl-badge";
  private static final String CLASS_DECL_BADGE_ERROR = "LeanDecl-badge--error";
  private static final String CLASS_DECL_BADGE_WARNING = "LeanDecl-badge--warning";
  private static final String CLASS_DECL_DOC = "LeanDecl-doc";
  private static final String CLASS_DECL_SIG = "LeanDecl-signature";
  private static final String CLASS_DECL_SIG_HAS_BODY = "LeanDecl-signature--hasBody";
  private static final String CLASS_DECL_BODY = "LeanDecl-body";
  private static final String CLASS_DECL_DERIVING = "LeanDecl-deriving";
  private static final String CLASS_DECL_DERIVING_LABEL = "LeanDecl-derivingLabel";
  private static final String CLASS_DECL_DERIVING_CHIP = "LeanDecl-derivingChip";
  private static final String CLASS_LINE_RANGE = "LeanLineRange";
  private static final String CLASS_CODE = "LeanCode";
  private static final String CLASS_CODE_LINE = "LeanCode-line";
  private static final String CLASS_CODE_LINE_NUM = "LeanCode-lineNum";
  private static final String CLASS_CODE_TEXT = "LeanCode-text";
  private static final String CLASS_TABLE = "LeanTable";
  private static final String CLASS_TABLE_LINE_COL = "LeanTable-lineCol";
  private static final String CLASS_TABLE_NAME = "LeanTable-name";
  private static final String CLASS_TABLE_TYPE = "LeanTable-type";
  private static final String CLASS_TABLE_DEFAULT = "LeanTable-default";
  private static final String CLASS_TABLE_NOTES = "LeanTable-notes";
  private static final String CLASS_MD = "LeanMd";
  private static final String CLASS_TOK_COMMENT = "LeanTok--comment";
  private static final String CLASS_TOK_STRING = "LeanTok--string";
  private static final String CLASS_TOK_KEYWORD = "LeanTok--keyword";
  private static final String CLASS_TOK_TACTIC = "LeanTok--tactic";
  private static final String CLASS_TOK_SORRY = "LeanTok--sorry";
  private static final String CLASS_TOK_TYPE = "LeanTok--type";
  private static final String CLASS_TOK_OPERATOR = "LeanTok--operator";
  private static final String CLASS_TOK_UNICODE_OP = "LeanTok--unicodeOp";
  private static final String CLASS_TOK_BULLET = "LeanTok--bullet";
  private static final String CLASS_TOK_ATTRIBUTE = "LeanTok--attribute";
  private static final String CLASS_TOK_NUMBER = "LeanTok--number";
  private static final String CLASS_TOK_LITERAL = "LeanTok--literal";

  private LeanHtml() {}

  /**
   * Renders a Lean 4 blob as sanitized {@link SafeHtml}, or {@code null} if {@code source} is null
   * or exceeds input/output bounds.
   */
  public static @Nullable SafeHtml renderBlob(
      @Nullable String source,
      @Nullable Config config,
      @Nullable GitilesView view,
      @Nullable String requestUri,
      @Nullable String filePath) {
    if (source == null) {
      return null;
    }
    return renderBlob(utf8ByteLength(source), source, config, view, requestUri, filePath);
  }

  /**
   * Renders a Lean 4 blob as sanitized {@link SafeHtml}, or {@code null} if {@code source} is null
   * or exceeds input/output bounds.
   */
  public static @Nullable SafeHtml renderBlob(
      int byteLength,
      @Nullable String source,
      @Nullable Config config,
      @Nullable GitilesView view,
      @Nullable String requestUri,
      @Nullable String filePath) {
    if (!LeanRendering.withinInputCaps(byteLength, source)) {
      return null;
    }
    MarkdownConfig markdownConfig = MarkdownConfig.get(config != null ? config : new Config());
    ImmutableList<LeanBlock> blocks = LeanParser.parse(source);
    LeanFileCounts counts = LeanHygiene.count(blocks);
    Set<Integer> emittedLineIds = new HashSet<>();

    SoyHtmlBuilder html = new SoyHtmlBuilder();
    html.open("div").attribute("class", CLASS_VIEW);
    renderBlobSummaryBar(html, counts, blocks, view);

    if (!blocks.isEmpty()) {
      html.open("div").attribute("class", CLASS_VIEW_BLOCKS);
      for (LeanBlock block : blocks) {
        renderBlock(html, block, markdownConfig, view, requestUri, filePath, emittedLineIds);
      }
      html.close("div");
    }

    html.close("div");

    SafeHtml result = html.toSoy();
    return LeanRendering.withinOutputCap(result.getSafeHtmlString().length()) ? result : null;
  }

  private static void renderBlobSummaryBar(
      HtmlBuilder html, LeanFileCounts counts, List<LeanBlock> blocks, @Nullable GitilesView view) {
    HazardLines hazards = collectHazardLines(blocks);
    int sorryTotal = counts.sorryCount() + counts.admitCount();
    boolean isClean =
        sorryTotal == 0
            && counts.axioms() == 0
            && counts.unsafeCount() == 0
            && counts.nativeDecideCount() == 0
            && counts.partialCount() == 0
            && counts.theorems() > 0;

    html.open("div").attribute("class", CLASS_SUMMARY).attribute("role", "status");
    html.open("div").attribute("class", CLASS_SUMMARY_LEFT);
    html.open("span")
        .attribute("class", CLASS_SUMMARY_LANG)
        .appendAndEscape("Lean 4")
        .close("span");

    if (isClean) {
      html.open("span")
          .attribute("class", CLASS_PILL + " " + CLASS_PILL_VERIFIED)
          .appendAndEscape("0 sorry")
          .close("span");
    }
    if (sorryTotal > 0) {
      renderHygienePill(html, CLASS_PILL_ERROR, sorryTotal + " sorry", hazards.sorryLines);
    }
    if (counts.axioms() > 0) {
      renderHygienePill(
          html,
          CLASS_PILL_WARNING,
          formatPlural(counts.axioms(), "axiom", "axioms"),
          hazards.axiomLines);
    }
    if (counts.nativeDecideCount() > 0) {
      renderHygienePill(
          html,
          CLASS_PILL_WARNING,
          counts.nativeDecideCount() + " native_decide",
          hazards.nativeDecideLines);
    }
    if (counts.partialCount() > 0) {
      renderHygienePill(
          html, CLASS_PILL_WARNING, counts.partialCount() + " partial", hazards.partialLines);
    }
    if (counts.unsafeCount() > 0) {
      renderHygienePill(
          html, CLASS_PILL_ERROR, counts.unsafeCount() + " unsafe", hazards.unsafeLines);
    }

    List<String> stats = buildSummaryStats(counts);
    if (!stats.isEmpty()) {
      html.open("span").attribute("class", CLASS_SUMMARY_STATS);
      for (int i = 0; i < stats.size(); i++) {
        if (i > 0) {
          html.open("span")
              .attribute("class", CLASS_SUMMARY_STAT_SEP)
              .appendAndEscape("·")
              .close("span");
        }
        html.open("span").appendAndEscape(stats.get(i)).close("span");
      }
      html.close("span");
    }
    html.close("div");

    html.open("div").attribute("class", CLASS_SUMMARY_RIGHT);
    html.open("span").attribute("class", CLASS_SUMMARY_SOURCE);
    html.appendAndEscape("[");
    html.open("a")
        .attribute("href", sourceViewUrl(view))
        .attribute("title", "View plain source")
        .appendAndEscape("source")
        .close("a");
    html.appendAndEscape("]");
    html.close("span");
    html.close("div");

    html.close("div");
  }

  private static void renderHygienePill(
      HtmlBuilder html, String modifierClass, String label, List<Integer> lines) {
    String pillClass = CLASS_PILL + " " + modifierClass;
    if (lines.size() == 1) {
      int line = lines.get(0);
      html.open("a")
          .attribute("class", pillClass)
          .attribute("href", "#" + line)
          .attribute("title", "Jump to line " + line)
          .appendAndEscape(label)
          .close("a");
      return;
    }
    html.open("span").attribute("class", pillClass);
    html.open("span").appendAndEscape(label).close("span");
    for (int line : lines) {
      html.space();
      html.open("a")
          .attribute("href", "#" + line)
          .attribute("title", "Jump to line " + line)
          .appendAndEscape("L" + line)
          .close("a");
    }
    html.close("span");
  }

  private static List<String> buildSummaryStats(LeanFileCounts counts) {
    List<String> stats = new ArrayList<>(4);
    if (counts.theorems() > 0) {
      stats.add(formatPlural(counts.theorems(), "theorem", "theorems"));
    }
    if (counts.types() > 0) {
      stats.add(formatPlural(counts.types(), "type", "types"));
    }
    if (counts.definitions() > 0) {
      stats.add(formatPlural(counts.definitions(), "def", "defs"));
    }
    if (counts.instances() > 0) {
      stats.add(formatPlural(counts.instances(), "instance", "instances"));
    }
    return stats;
  }

  private static String sourceViewUrl(@Nullable GitilesView view) {
    if (view == null) {
      return "?" + LeanRendering.LEAN_PARAM + "=" + LeanRendering.NO_LEAN_VALUE;
    }
    return view.toBuilder()
        .replaceParam(LeanRendering.LEAN_PARAM, LeanRendering.NO_LEAN_VALUE)
        .build()
        .toUrl();
  }

  private static void renderBlock(
      HtmlBuilder html,
      LeanBlock block,
      MarkdownConfig markdownConfig,
      @Nullable GitilesView view,
      @Nullable String requestUri,
      @Nullable String filePath,
      Set<Integer> emittedLineIds) {
    switch (block.type()) {
      case MODULE_DOC ->
          renderModuleDoc(html, block, markdownConfig, view, requestUri, filePath, emittedLineIds);
      case SECTION_BANNER -> renderSectionBanner(html, block, emittedLineIds);
      case IMPORTS -> renderStripBlock(html, block.importLines(), false, false, emittedLineIds);
      case DIRECTIVE ->
          renderStripBlock(html, block.directiveLines(), false, false, emittedLineIds);
      case COMMENT ->
          renderStripBlock(
              html, block.commentLines(), true, block.isLicenseHeader(), emittedLineIds);
      case DECLARATION -> {
        if (block.decl() != null) {
          renderDeclarationCard(
              html,
              block,
              block.decl(),
              markdownConfig,
              view,
              requestUri,
              filePath,
              emittedLineIds);
        }
      }
    }
  }

  private static void renderModuleDoc(
      HtmlBuilder html,
      LeanBlock block,
      MarkdownConfig markdownConfig,
      @Nullable GitilesView view,
      @Nullable String requestUri,
      @Nullable String filePath,
      Set<Integer> emittedLineIds) {
    html.open("div").attribute("class", CLASS_MODULE_DOC + " " + CLASS_MD);
    emitLineIdIfNew(html, emittedLineIds, block.startLine());
    html.open("div").attribute("class", CLASS_MODULE_DOC_HEADER);
    html.open("span").appendAndEscape("Module Documentation").close("span");
    renderLineRangeLink(html, block.startLine(), block.endLine());
    html.close("div");
    renderMarkdownBlock(
        html,
        Strings.nullToEmpty(block.markdownContent()),
        markdownConfig,
        view,
        requestUri,
        filePath);
    html.close("div");
  }

  private static void renderSectionBanner(
      HtmlBuilder html, LeanBlock block, Set<Integer> emittedLineIds) {
    html.open("div").attribute("class", CLASS_BANNER);
    emitLineIdIfNew(html, emittedLineIds, block.startLine());
    html.open("h3")
        .attribute("class", CLASS_BANNER_TITLE)
        .appendAndEscape(Strings.nullToEmpty(block.markdownContent()))
        .close("h3");
    renderLineRangeLink(html, block.startLine(), block.endLine());
    html.close("div");
  }

  private static void renderStripBlock(
      HtmlBuilder html,
      List<LeanCodeLine> lines,
      boolean isComment,
      boolean isLicenseHeader,
      Set<Integer> emittedLineIds) {
    StringBuilder cls = new StringBuilder(CLASS_STRIP);
    if (isComment) {
      cls.append(' ').append(CLASS_STRIP_COMMENT);
    }
    if (isLicenseHeader) {
      cls.append(' ').append(CLASS_STRIP_LICENSE);
    }
    html.open("div").attribute("class", cls.toString());
    renderStaticCodeLines(html, lines, false, 0, isComment, emittedLineIds);
    html.close("div");
  }

  private static void renderDeclarationCard(
      HtmlBuilder html,
      LeanBlock block,
      LeanDeclaration decl,
      MarkdownConfig markdownConfig,
      @Nullable GitilesView view,
      @Nullable String requestUri,
      @Nullable String filePath,
      Set<Integer> emittedLineIds) {
    String category = declCategory(decl.kind());
    String declCatClass = CLASS_DECL + "--" + category;

    if (decl.isCompactDef()) {
      html.open("div")
          .attribute("class", CLASS_DECL + " " + CLASS_DECL_COMPACT + " " + declCatClass);
      renderStaticCodeLines(
          html,
          ImmutableList.of(new LeanCodeLine(block.startLine(), block.raw())),
          false,
          0,
          false,
          emittedLineIds);
      html.close("div");
      return;
    }

    LeanDeclHygiene hygiene = decl.hygiene();
    boolean hasHygieneAlert =
        hygiene.hasSorry() || hygiene.hasAdmit() || hygiene.isAxiom() || hygiene.isUnsafe();
    StringBuilder cardCls = new StringBuilder(CLASS_DECL).append(' ').append(declCatClass);
    if (hasHygieneAlert) {
      cardCls.append(' ').append(CLASS_DECL_HYGIENE_ALERT);
    }

    int firstSigLine =
        decl.signatureLines().isEmpty() ? -1 : decl.signatureLines().get(0).lineNum();
    Integer docStartLine = decl.docStartLine();
    boolean emitStartOnCard =
        block.startLine() > 0
            && block.startLine() != firstSigLine
            && (docStartLine == null || block.startLine() != docStartLine);

    html.open("div").attribute("class", cardCls.toString());
    if (emitStartOnCard) {
      emitLineIdIfNew(html, emittedLineIds, block.startLine());
    }

    // Header
    html.open("div").attribute("class", CLASS_DECL_HEADER);
    html.open("div").attribute("class", CLASS_DECL_HEADER_MAIN);
    html.open("span")
        .attribute("class", CLASS_DECL_KIND + " " + CLASS_DECL_KIND + "--" + category)
        .appendAndEscape(decl.kind().keyword())
        .close("span");
    if (!decl.name().isEmpty()) {
      html.open("span")
          .attribute("class", CLASS_DECL_NAME)
          .appendAndEscape(decl.name())
          .close("span");
    }
    for (String mod : decl.modifiers()) {
      html.open("span")
          .attribute("class", CLASS_DECL_CHIP + " " + CLASS_DECL_CHIP_META)
          .appendAndEscape(mod)
          .close("span");
    }
    for (String attr : decl.attributes()) {
      html.open("span")
          .attribute("class", CLASS_DECL_CHIP + " " + CLASS_DECL_CHIP_ATTR)
          .appendAndEscape(attr)
          .close("span");
    }
    if (decl.proofStrategy() == LeanProofStrategy.DECIDE) {
      html.open("span")
          .attribute(
              "class",
              CLASS_DECL_CHIP + " " + CLASS_DECL_CHIP_STRATEGY + " " + CLASS_DECL_CHIP_DECIDE)
          .appendAndEscape("decide")
          .close("span");
    } else if (decl.proofStrategy() == LeanProofStrategy.TACTIC) {
      html.open("span")
          .attribute("class", CLASS_DECL_CHIP + " " + CLASS_DECL_CHIP_STRATEGY)
          .appendAndEscape("by tactic")
          .close("span");
    }
    if (hygiene.hasSorry()) {
      renderDeclBadge(html, CLASS_DECL_BADGE_ERROR, "sorry");
    }
    if (hygiene.hasAdmit()) {
      renderDeclBadge(html, CLASS_DECL_BADGE_ERROR, "admit");
    }
    if (hygiene.hasNativeDecide()) {
      renderDeclBadge(html, CLASS_DECL_BADGE_WARNING, "native_decide");
    }
    if (hygiene.isPartial()) {
      renderDeclBadge(html, CLASS_DECL_BADGE_WARNING, "partial");
    }
    if (hygiene.isAxiom() && decl.kind() != LeanDeclKind.AXIOM) {
      renderDeclBadge(html, CLASS_DECL_BADGE_ERROR, "axiom");
    }
    if (hygiene.isUnsafe()) {
      renderDeclBadge(html, CLASS_DECL_BADGE_ERROR, "unsafe");
    }
    html.close("div");

    html.open("div").attribute("class", CLASS_DECL_HEADER_META);
    renderLineRangeLink(html, block.startLine(), block.endLine());
    html.close("div");
    html.close("div");

    // Docstring
    if (decl.docComment() != null) {
      html.open("div").attribute("class", CLASS_DECL_DOC + " " + CLASS_MD);
      if (docStartLine != null && docStartLine != firstSigLine) {
        emitLineIdIfNew(html, emittedLineIds, docStartLine);
      }
      renderMarkdownBlock(html, decl.docComment(), markdownConfig, view, requestUri, filePath);
      html.close("div");
    }

    ImmutableList<LeanStructureField> fields = decl.fields();
    ImmutableList<LeanInductiveCtor> ctors = decl.constructors();
    boolean hasFields = fields != null && !fields.isEmpty();
    boolean hasCtors = ctors != null && !ctors.isEmpty();
    boolean hasCodeBody = !hasFields && !hasCtors && !decl.bodyLines().isEmpty();
    boolean hasBody = hasFields || hasCtors || hasCodeBody;

    // Signature
    html.open("div")
        .attribute(
            "class", hasBody ? CLASS_DECL_SIG + " " + CLASS_DECL_SIG_HAS_BODY : CLASS_DECL_SIG);
    renderStaticCodeLines(html, decl.signatureLines(), false, 0, false, emittedLineIds);
    html.close("div");

    // Body
    if (hasFields) {
      html.open("div").attribute("class", CLASS_DECL_BODY);
      renderStaticFieldsTable(
          html, fields, markdownConfig, view, requestUri, filePath, emittedLineIds);
      html.close("div");
    } else if (hasCtors) {
      html.open("div").attribute("class", CLASS_DECL_BODY);
      renderStaticConstructorsTable(
          html, ctors, markdownConfig, view, requestUri, filePath, emittedLineIds);
      html.close("div");
    } else if (hasCodeBody) {
      int sigFinalDepth = LeanHighlighter.computeFinalBlockCommentDepth(decl.signatureLines(), 0);
      html.open("div").attribute("class", CLASS_DECL_BODY);
      renderStaticCodeLines(
          html, decl.bodyLines(), decl.isTacticProof(), sigFinalDepth, false, emittedLineIds);
      html.close("div");
    }

    // Deriving clause
    ImmutableList<String> derivingClause = decl.derivingClause();
    if (derivingClause != null && !derivingClause.isEmpty()) {
      html.open("div").attribute("class", CLASS_DECL_DERIVING);
      emitLineIdIfNew(html, emittedLineIds, block.endLine());
      if (block.endLine() > 0) {
        html.open("a")
            .attribute("class", CLASS_CODE_LINE_NUM)
            .attribute("href", "#" + block.endLine())
            .appendAndEscape(Integer.toString(block.endLine()))
            .close("a");
      }
      html.open("span")
          .attribute("class", CLASS_DECL_DERIVING_LABEL)
          .appendAndEscape("deriving")
          .close("span");
      for (String cls : derivingClause) {
        html.open("span")
            .attribute("class", CLASS_DECL_DERIVING_CHIP)
            .appendAndEscape(cls)
            .close("span");
      }
      html.close("div");
    }

    html.close("div");
  }

  private static void renderDeclBadge(HtmlBuilder html, String modifierClass, String label) {
    html.open("span")
        .attribute("class", CLASS_DECL_BADGE + " " + modifierClass)
        .appendAndEscape(label)
        .close("span");
  }

  private static void renderStaticCodeLines(
      HtmlBuilder html,
      List<LeanCodeLine> lines,
      boolean inTacticContext,
      int initialBlockCommentDepth,
      boolean forceComment,
      Set<Integer> emittedLineIds) {
    int[] depths =
        forceComment
            ? null
            : LeanHighlighter.computeBlockCommentDepths(lines, initialBlockCommentDepth);
    html.open("div").attribute("class", CLASS_CODE);
    for (int i = 0; i < lines.size(); i++) {
      LeanCodeLine line = lines.get(i);
      int lineNum = line.lineNum();
      html.open("div").attribute("class", CLASS_CODE_LINE);
      emitLineIdIfNew(html, emittedLineIds, lineNum);
      html.open("a")
          .attribute("class", CLASS_CODE_LINE_NUM)
          .attribute("href", "#" + lineNum)
          .appendAndEscape(Integer.toString(lineNum))
          .close("a");
      html.open("span").attribute("class", CLASS_CODE_TEXT);
      int depth = depths != null ? depths[i] : 0;
      renderHighlightedLine(html, line.text(), inTacticContext, depth, forceComment);
      html.close("span");
      html.close("div");
    }
    html.close("div");
  }

  private static void renderStaticFieldsTable(
      HtmlBuilder html,
      List<LeanStructureField> fields,
      MarkdownConfig markdownConfig,
      @Nullable GitilesView view,
      @Nullable String requestUri,
      @Nullable String filePath,
      Set<Integer> emittedLineIds) {
    boolean hasNotes = false;
    for (LeanStructureField f : fields) {
      if (!Strings.isNullOrEmpty(f.comment())) {
        hasNotes = true;
        break;
      }
    }
    html.open("table").attribute("class", CLASS_TABLE);
    html.open("thead").open("tr");
    html.open("th").attribute("class", CLASS_TABLE_LINE_COL).appendAndEscape("#").close("th");
    html.open("th").appendAndEscape("Field").close("th");
    html.open("th").appendAndEscape("Type").close("th");
    if (hasNotes) {
      html.open("th").appendAndEscape("Notes").close("th");
    }
    html.close("tr").close("thead");

    html.open("tbody");
    for (LeanStructureField field : fields) {
      int lineNum = field.lineNum();
      html.open("tr");
      emitLineIdIfNew(html, emittedLineIds, lineNum);
      html.open("td").attribute("class", CLASS_TABLE_LINE_COL);
      html.open("a")
          .attribute("class", CLASS_CODE_LINE_NUM)
          .attribute("href", "#" + lineNum)
          .appendAndEscape(Integer.toString(lineNum))
          .close("a");
      html.close("td");
      html.open("td")
          .attribute("class", CLASS_TABLE_NAME)
          .appendAndEscape(field.name())
          .close("td");
      html.open("td").attribute("class", CLASS_TABLE_TYPE);
      renderHighlightedLine(html, field.typeText(), false, 0, false);
      if (field.defaultValue() != null) {
        html.open("span").attribute("class", CLASS_TABLE_DEFAULT);
        html.appendAndEscape(" := ");
        renderHighlightedLine(html, field.defaultValue(), false, 0, false);
        html.close("span");
      }
      html.close("td");
      if (hasNotes) {
        html.open("td").attribute("class", CLASS_TABLE_NOTES + " " + CLASS_MD);
        if (!Strings.isNullOrEmpty(field.comment())) {
          renderMarkdownBlock(html, field.comment(), markdownConfig, view, requestUri, filePath);
        }
        html.close("td");
      }
      html.close("tr");
    }
    html.close("tbody").close("table");
  }

  private static void renderStaticConstructorsTable(
      HtmlBuilder html,
      List<LeanInductiveCtor> ctors,
      MarkdownConfig markdownConfig,
      @Nullable GitilesView view,
      @Nullable String requestUri,
      @Nullable String filePath,
      Set<Integer> emittedLineIds) {
    boolean hasNotes = false;
    for (LeanInductiveCtor c : ctors) {
      if (!Strings.isNullOrEmpty(c.comment())) {
        hasNotes = true;
        break;
      }
    }
    html.open("table").attribute("class", CLASS_TABLE);
    html.open("thead").open("tr");
    html.open("th").attribute("class", CLASS_TABLE_LINE_COL).appendAndEscape("#").close("th");
    html.open("th").appendAndEscape("Constructor").close("th");
    html.open("th").appendAndEscape("Signature").close("th");
    if (hasNotes) {
      html.open("th").appendAndEscape("Notes").close("th");
    }
    html.close("tr").close("thead");

    html.open("tbody");
    for (LeanInductiveCtor ctor : ctors) {
      int lineNum = ctor.lineNum();
      html.open("tr");
      emitLineIdIfNew(html, emittedLineIds, lineNum);
      html.open("td").attribute("class", CLASS_TABLE_LINE_COL);
      html.open("a")
          .attribute("class", CLASS_CODE_LINE_NUM)
          .attribute("href", "#" + lineNum)
          .appendAndEscape(Integer.toString(lineNum))
          .close("a");
      html.close("td");
      html.open("td")
          .attribute("class", CLASS_TABLE_NAME)
          .appendAndEscape("| " + ctor.name())
          .close("td");
      html.open("td").attribute("class", CLASS_TABLE_TYPE);
      if (!ctor.signatureText().isEmpty()) {
        renderHighlightedLine(html, ctor.signatureText(), false, 0, false);
      }
      html.close("td");
      if (hasNotes) {
        html.open("td").attribute("class", CLASS_TABLE_NOTES + " " + CLASS_MD);
        if (!Strings.isNullOrEmpty(ctor.comment())) {
          renderMarkdownBlock(html, ctor.comment(), markdownConfig, view, requestUri, filePath);
        }
        html.close("td");
      }
      html.close("tr");
    }
    html.close("tbody").close("table");
  }

  private static void renderHighlightedLine(
      HtmlBuilder html,
      String line,
      boolean inTacticContext,
      int initialBlockCommentDepth,
      boolean forceComment) {
    if (line.isEmpty()) {
      return;
    }
    if (forceComment) {
      html.open("span").attribute("class", CLASS_TOK_COMMENT).appendAndEscape(line).close("span");
      return;
    }
    ImmutableList<LeanToken> tokens =
        LeanHighlighter.tokenizeLine(line, inTacticContext, initialBlockCommentDepth);
    for (LeanToken token : tokens) {
      renderToken(html, token);
    }
  }

  private static void renderToken(HtmlBuilder html, LeanToken token) {
    LeanTokenKind kind = token.kind();
    String text = token.text();
    if (kind == LeanTokenKind.PLAIN) {
      html.appendAndEscape(text);
      return;
    }
    String cls = tokenClass(kind, text);
    html.open("span").attribute("class", cls).appendAndEscape(text).close("span");
  }

  private static String tokenClass(LeanTokenKind kind, String text) {
    return switch (kind) {
      case PLAIN -> "";
      case COMMENT -> CLASS_TOK_COMMENT;
      case STRING -> CLASS_TOK_STRING;
      case KEYWORD -> CLASS_TOK_KEYWORD;
      case TACTIC -> CLASS_TOK_TACTIC;
      case SORRY -> CLASS_TOK_SORRY;
      case TYPE -> CLASS_TOK_TYPE;
      case OPERATOR ->
          LeanHighlighter.isUnicodeOperator(text)
              ? CLASS_TOK_OPERATOR + " " + CLASS_TOK_UNICODE_OP
              : CLASS_TOK_OPERATOR;
      case BULLET -> CLASS_TOK_BULLET;
      case ATTRIBUTE -> CLASS_TOK_ATTRIBUTE;
      case NUMBER -> CLASS_TOK_NUMBER;
      case LITERAL -> CLASS_TOK_LITERAL;
    };
  }

  private static void renderMarkdownBlock(
      HtmlBuilder html,
      String markdown,
      MarkdownConfig markdownConfig,
      @Nullable GitilesView view,
      @Nullable String requestUri,
      @Nullable String filePath) {
    if (markdown.isEmpty()) {
      return;
    }
    if (markdownConfig.render && markdown.length() <= markdownConfig.inputLimit) {
      SafeHtml rendered =
          MarkdownToHtml.builder()
              .setConfig(markdownConfig)
              .setGitilesView(view)
              .setRequestUri(requestUri)
              .setFilePath(filePath)
              .build()
              .toSoyHtml(GitilesMarkdown.parse(markdownConfig, markdown));
      if (rendered != null) {
        html.append(rendered);
        return;
      }
    }
    html.open("pre").open("code").appendAndEscape(markdown).close("code").close("pre");
  }

  private static void renderLineRangeLink(HtmlBuilder html, int startLine, int endLine) {
    if (startLine <= 0) {
      return;
    }
    String label = formatLineRange(startLine, endLine);
    html.open("a")
        .attribute("class", CLASS_LINE_RANGE)
        .attribute("href", "#" + startLine)
        .attribute("title", "Jump to line " + startLine)
        .appendAndEscape(label)
        .close("a");
  }

  private static String formatLineRange(int startLine, int endLine) {
    if (endLine <= startLine) {
      return "L" + startLine;
    }
    return "L" + startLine + "\u2013L" + endLine;
  }

  private static void emitLineIdIfNew(HtmlBuilder html, Set<Integer> emittedLineIds, int lineNum) {
    if (lineNum > 0 && emittedLineIds.add(lineNum)) {
      html.attribute("id", Integer.toString(lineNum));
    }
  }

  private static String declCategory(LeanDeclKind kind) {
    return switch (kind) {
      case THEOREM, LEMMA, EXAMPLE -> "theorem";
      case STRUCTURE, CLASS, INDUCTIVE, COINDUCTIVE -> "type";
      case INSTANCE -> "instance";
      case AXIOM -> "axiom";
      case DEF, ABBREV, OPAQUE -> "def";
    };
  }

  private static String formatPlural(int count, String singular, String plural) {
    return count + " " + (count == 1 ? singular : plural);
  }

  private static int utf8ByteLength(String s) {
    int bytes = 0;
    int len = s.length();
    for (int i = 0; i < len; i++) {
      char c = s.charAt(i);
      if (c <= 0x7F) {
        bytes++;
      } else if (c <= 0x7FF) {
        bytes += 2;
      } else if (Character.isHighSurrogate(c)
          && i + 1 < len
          && Character.isLowSurrogate(s.charAt(i + 1))) {
        bytes += 4;
        i++;
      } else {
        bytes += 3;
      }
    }
    return bytes;
  }

  private static HazardLines collectHazardLines(List<LeanBlock> blocks) {
    List<Integer> sorryLines = new ArrayList<>();
    List<Integer> axiomLines = new ArrayList<>();
    List<Integer> nativeDecideLines = new ArrayList<>();
    List<Integer> partialLines = new ArrayList<>();
    List<Integer> unsafeLines = new ArrayList<>();

    for (LeanBlock block : blocks) {
      if (block.type() == LeanBlockType.DECLARATION && block.decl() != null) {
        LeanDeclHygiene h = block.decl().hygiene();
        if (h.hasSorry() || h.hasAdmit()) {
          sorryLines.add(block.startLine());
        }
        if (h.isAxiom()) {
          axiomLines.add(block.startLine());
        }
        if (h.hasNativeDecide()) {
          nativeDecideLines.add(block.startLine());
        }
        if (h.isPartial()) {
          partialLines.add(block.startLine());
        }
        if (h.isUnsafe()) {
          unsafeLines.add(block.startLine());
        }
      } else if (block.type() == LeanBlockType.DIRECTIVE) {
        ImmutableSet<String> ids =
            LeanHygiene.extractLeanIdentifiers(LeanLexer.stripLeanCommentsAndStrings(block.raw()));
        if (ids.contains("sorry") || ids.contains("sorryAx") || ids.contains("admit")) {
          sorryLines.add(block.startLine());
        }
        if (ids.contains("axiom")) {
          axiomLines.add(block.startLine());
        }
        if (ids.contains("native_decide")
            || ids.contains("ofReduceBool")
            || ids.contains("ofReduceNat")) {
          nativeDecideLines.add(block.startLine());
        }
        if (ids.contains("partial")) {
          partialLines.add(block.startLine());
        }
        if (ids.contains("unsafe")) {
          unsafeLines.add(block.startLine());
        }
      }
    }
    return new HazardLines(sorryLines, axiomLines, nativeDecideLines, partialLines, unsafeLines);
  }

  private record HazardLines(
      List<Integer> sorryLines,
      List<Integer> axiomLines,
      List<Integer> nativeDecideLines,
      List<Integer> partialLines,
      List<Integer> unsafeLines) {}
}
