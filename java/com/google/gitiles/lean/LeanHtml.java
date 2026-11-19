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
import com.google.gitiles.lean.LeanSecurity.BoundedHtml;
import com.google.gitiles.lean.LeanSecurity.OutputLimitExceeded;
import com.google.gitiles.lean.LeanSecurity.TrojanScan;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.annotation.Nullable;
import org.commonmark.node.HardLineBreak;
import org.commonmark.node.HtmlBlock;
import org.commonmark.node.HtmlInline;
import org.commonmark.node.LinkReferenceDefinition;
import org.commonmark.node.Node;
import org.commonmark.node.Paragraph;
import org.commonmark.node.Text;
import org.eclipse.jgit.lib.Config;

/**
 * Server-side HTML renderer for Lean 4 blobs using {@link
 * com.google.gitiles.doc.html.SoyHtmlBuilder}.
 */
public final class LeanHtml {
  private static final String CLASS_VIEW = "LeanView";
  private static final String CLASS_VIEW_BLOCKS = "LeanView-blocks";
  private static final String CLASS_SUMMARY = "LeanSummary";
  private static final String CLASS_SUMMARY_LEFT = "LeanSummary-left";
  private static final String CLASS_SUMMARY_LANG = "LeanSummary-lang";
  private static final String CLASS_SUMMARY_STATS = "LeanSummary-stats";
  private static final String CLASS_SUMMARY_STAT_SEP = "LeanSummary-statSep";
  private static final String CLASS_PILL = "LeanPill";
  private static final String CLASS_PILL_ERROR = "LeanPill--error";
  private static final String CLASS_PILL_WARNING = "LeanPill--warning";
  private static final String CLASS_PILL_NEUTRAL = "LeanPill--neutral";
  private static final String NO_SORRY_LABEL = "no sorry found (lexical scan)";
  private static final String UNTERMINATED_WARNING_LABEL =
      "unterminated string/comment \u2014 counts may be incomplete";
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
  private static final String CLASS_TROJAN_BANNER = "LeanTrojanBanner";
  private static final String CLASS_TROJAN_BANNER_BADGE = "LeanTrojanBanner-badge";
  private static final String CLASS_TROJAN_CHAR = "LeanTrojanChar";
  private static final int MAX_RENDER_BLOCKS = 25_000;
  private static final int MAX_MARKDOWN_AST_NODES = 8_192;

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
    return renderBlob(byteLength, source, config, view, requestUri, filePath, null);
  }

  /** Renders a Lean 4 blob using an optional caller-supplied {@link LeanRequestBudget}. */
  public static @Nullable SafeHtml renderBlob(
      int byteLength,
      @Nullable String source,
      @Nullable Config config,
      @Nullable GitilesView view,
      @Nullable String requestUri,
      @Nullable String filePath,
      @Nullable LeanRequestBudget budget) {
    if (!LeanRendering.withinInputCaps(byteLength, source)) {
      return null;
    }
    LeanRequestBudget activeBudget = budget != null ? budget : LeanRequestBudget.forSingleFile();
    if (!activeBudget.canAttempt(byteLength, 0) || !activeBudget.beginOperation()) {
      return null;
    }
    int emittedChars = 0;
    boolean rendered = false;
    try {
      MarkdownConfig markdownConfig = LeanSecurity.leanMarkdownConfig(config);
      ImmutableList<LeanBlock> blocks = LeanParser.parse(source);
      if (blocks.size() > MAX_RENDER_BLOCKS || !activeBudget.tryConsumeBlocks(blocks.size())) {
        return null;
      }
      LeanFileCounts counts = LeanHygiene.count(blocks);
      boolean hasUnterminated = LeanHygiene.hasUnterminatedLiteralOrComment(blocks);
      if (activeBudget.isCpuDeadlineExceeded()) {
        return null;
      }
      TrojanScan trojanScan =
          LeanSecurity.scanTrojanRisks(source, null, blocks, ImmutableList.of());
      if (activeBudget.isCpuDeadlineExceeded()) {
        return null;
      }
      Set<Integer> emittedLineIds = new HashSet<>();

      BoundedHtml html =
          new BoundedHtml(activeBudget.remainingOutputChars(), activeBudget, trojanScan);
      html.open("div").attribute("class", CLASS_VIEW);
      renderBlobSummaryBar(html, counts, blocks, hasUnterminated);
      LeanSecurity.renderTrojanBanner(
          html, trojanScan, CLASS_TROJAN_BANNER, CLASS_TROJAN_BANNER_BADGE, CLASS_TROJAN_CHAR);

      if (!blocks.isEmpty()) {
        html.open("div").attribute("class", CLASS_VIEW_BLOCKS);
        for (int i = 0; i < blocks.size(); i++) {
          if ((i & 0x3F) == 0 && activeBudget.isCpuDeadlineExceeded()) {
            return null;
          }
          renderBlock(
              html, blocks.get(i), markdownConfig, view, requestUri, filePath, emittedLineIds);
        }
        html.close("div");
      }

      html.close("div");
      SafeHtml safe = html.toSoy();
      emittedChars = safe.getSafeHtmlString().length();
      rendered = true;
      return safe;
    } catch (OutputLimitExceeded e) {
      return null;
    } finally {
      activeBudget.recordOutcome(byteLength, 0, emittedChars, rendered);
    }
  }

  private static boolean isSummaryClean(LeanFileCounts counts, boolean hasUnterminated) {
    return !hasUnterminated
        && counts.sorryCount() + counts.admitCount() == 0
        && counts.axioms() == 0
        && counts.unsafeCount() == 0
        && counts.nativeDecideCount() == 0
        && counts.partialCount() == 0
        && counts.theorems() > 0;
  }

  private static void renderBlobSummaryBar(
      BoundedHtml html, LeanFileCounts counts, List<LeanBlock> blocks, boolean hasUnterminated) {
    HazardLines hazards = collectHazardLines(blocks);
    int sorryTotal = counts.sorryCount() + counts.admitCount();
    boolean isClean = isSummaryClean(counts, hasUnterminated);

    html.open("div").attribute("class", CLASS_SUMMARY).attribute("role", "status");
    html.open("div").attribute("class", CLASS_SUMMARY_LEFT);
    html.open("span")
        .attribute("class", CLASS_SUMMARY_LANG)
        .appendAndEscape("Lean 4")
        .close("span");

    if (isClean) {
      renderStaticSummaryPill(html, CLASS_PILL_NEUTRAL, NO_SORRY_LABEL);
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
    if (hasUnterminated) {
      renderStaticSummaryPill(html, CLASS_PILL_NEUTRAL, UNTERMINATED_WARNING_LABEL);
    }

    renderSummaryStats(html, counts);
    html.close("div");

    html.close("div");
  }

  private static void renderStaticSummaryPill(
      BoundedHtml html, String modifierClass, String label) {
    html.open("span")
        .attribute("class", CLASS_PILL + " " + modifierClass)
        .appendAndEscape(label)
        .close("span");
  }

  private static void renderSummaryStats(BoundedHtml html, LeanFileCounts counts) {
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
  }

  private static void renderHygienePill(
      BoundedHtml html, String modifierClass, String label, List<Integer> lines) {
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

  private static void renderBlock(
      BoundedHtml html,
      LeanBlock block,
      MarkdownConfig markdownConfig,
      @Nullable GitilesView view,
      @Nullable String requestUri,
      @Nullable String filePath,
      @Nullable Set<Integer> emittedLineIds) {
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
      BoundedHtml html,
      LeanBlock block,
      MarkdownConfig markdownConfig,
      @Nullable GitilesView view,
      @Nullable String requestUri,
      @Nullable String filePath,
      @Nullable Set<Integer> emittedLineIds) {
    html.open("div").attribute("class", CLASS_MODULE_DOC + " " + CLASS_MD);
    emitLineIdIfNew(html, emittedLineIds, block.startLine());
    html.open("div").attribute("class", CLASS_MODULE_DOC_HEADER);
    html.open("span").appendAndEscape("Module Documentation").close("span");
    renderLineRange(html, block.startLine(), block.endLine(), emittedLineIds);
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
      BoundedHtml html, LeanBlock block, @Nullable Set<Integer> emittedLineIds) {
    html.open("div").attribute("class", CLASS_BANNER);
    emitLineIdIfNew(html, emittedLineIds, block.startLine());
    html.open("h3").attribute("class", CLASS_BANNER_TITLE);
    LeanSecurity.appendVisibleText(
        html, Strings.nullToEmpty(block.markdownContent()), CLASS_TROJAN_CHAR);
    html.close("h3");
    renderLineRange(html, block.startLine(), block.endLine(), emittedLineIds);
    html.close("div");
  }

  private static String stripClass(boolean isComment, boolean isLicenseHeader) {
    StringBuilder cls = new StringBuilder(CLASS_STRIP);
    if (isComment) {
      cls.append(' ').append(CLASS_STRIP_COMMENT);
    }
    if (isLicenseHeader) {
      cls.append(' ').append(CLASS_STRIP_LICENSE);
    }
    return cls.toString();
  }

  private static void renderStripBlock(
      BoundedHtml html,
      List<LeanCodeLine> lines,
      boolean isComment,
      boolean isLicenseHeader,
      @Nullable Set<Integer> emittedLineIds) {
    html.open("div").attribute("class", stripClass(isComment, isLicenseHeader));
    renderStaticCodeLines(
        html, lines, false, LeanHighlighter.HighlightState.INITIAL, isComment, emittedLineIds);
    html.close("div");
  }

  private static String declCardClass(LeanDeclaration decl, String declCatClass) {
    LeanDeclHygiene hygiene = decl.hygiene();
    boolean hasHygieneAlert =
        hygiene.hasSorry() || hygiene.hasAdmit() || hygiene.isAxiom() || hygiene.isUnsafe();
    StringBuilder cardCls = new StringBuilder(CLASS_DECL).append(' ').append(declCatClass);
    if (hasHygieneAlert) {
      cardCls.append(' ').append(CLASS_DECL_HYGIENE_ALERT);
    }
    return cardCls.toString();
  }

  private static void renderDeclarationCard(
      BoundedHtml html,
      LeanBlock block,
      LeanDeclaration decl,
      MarkdownConfig markdownConfig,
      @Nullable GitilesView view,
      @Nullable String requestUri,
      @Nullable String filePath,
      @Nullable Set<Integer> emittedLineIds) {
    String category = declCategory(decl.kind());
    String declCatClass = CLASS_DECL + "--" + category;

    if (decl.isCompactDef()
        && block.raw().indexOf('\n') < 0
        && decl.docComment() == null
        && decl.fields() == null
        && decl.constructors() == null
        && decl.derivingClause() == null
        && !html.hasDeclarationHomoglyph(decl)) {
      html.open("div")
          .attribute("class", CLASS_DECL + " " + CLASS_DECL_COMPACT + " " + declCatClass);
      renderStaticCodeLines(
          html,
          ImmutableList.of(new LeanCodeLine(block.startLine(), block.raw())),
          false,
          LeanHighlighter.HighlightState.INITIAL,
          false,
          emittedLineIds);
      html.close("div");
      return;
    }

    int firstSigLine =
        decl.signatureLines().isEmpty() ? -1 : decl.signatureLines().get(0).lineNum();
    Integer docStartLine = decl.docStartLine();
    boolean emitStartOnCard =
        block.startLine() > 0
            && block.startLine() != firstSigLine
            && (docStartLine == null || block.startLine() != docStartLine);

    html.open("div").attribute("class", declCardClass(decl, declCatClass));
    if (emitStartOnCard) {
      emitLineIdIfNew(html, emittedLineIds, block.startLine());
    }

    renderDeclHeader(html, block, decl, category, emittedLineIds);

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
    renderStaticCodeLines(
        html,
        decl.signatureLines(),
        false,
        LeanHighlighter.HighlightState.INITIAL,
        false,
        emittedLineIds);
    html.close("div");

    // Body
    if (hasFields) {
      html.open("div").attribute("class", CLASS_DECL_BODY);
      renderStaticFieldsTable(html, fields, emittedLineIds);
      html.close("div");
    } else if (hasCtors) {
      html.open("div").attribute("class", CLASS_DECL_BODY);
      renderStaticConstructorsTable(html, ctors, emittedLineIds);
      html.close("div");
    } else if (hasCodeBody) {
      LeanHighlighter.HighlightState sigFinalState =
          LeanHighlighter.computeFinalHighlightState(
              decl.signatureLines(), LeanHighlighter.HighlightState.INITIAL);
      html.open("div").attribute("class", CLASS_DECL_BODY);
      renderStaticCodeLines(
          html, decl.bodyLines(), decl.isTacticProof(), sigFinalState, false, emittedLineIds);
      html.close("div");
    }

    renderDerivingClause(html, block.endLine(), decl.derivingClause(), emittedLineIds);
    html.close("div");
  }

  private static void renderDeclHeader(
      BoundedHtml html,
      LeanBlock block,
      LeanDeclaration decl,
      String category,
      @Nullable Set<Integer> emittedLineIds) {
    LeanDeclHygiene hygiene = decl.hygiene();
    html.open("div").attribute("class", CLASS_DECL_HEADER);
    html.open("div").attribute("class", CLASS_DECL_HEADER_MAIN);
    html.open("span")
        .attribute("class", CLASS_DECL_KIND + " " + CLASS_DECL_KIND + "--" + category)
        .appendAndEscape(decl.kind().keyword())
        .close("span");
    if (!decl.name().isEmpty()) {
      html.open("span").attribute("class", CLASS_DECL_NAME);
      LeanSecurity.appendVisibleText(html, decl.name(), CLASS_TROJAN_CHAR);
      html.close("span");
    }
    for (String mod : decl.modifiers()) {
      html.open("span").attribute("class", CLASS_DECL_CHIP + " " + CLASS_DECL_CHIP_META);
      LeanSecurity.appendVisibleText(html, mod, CLASS_TROJAN_CHAR);
      html.close("span");
    }
    for (String attr : decl.attributes()) {
      html.open("span").attribute("class", CLASS_DECL_CHIP + " " + CLASS_DECL_CHIP_ATTR);
      LeanSecurity.appendVisibleText(html, attr, CLASS_TROJAN_CHAR);
      html.close("span");
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
    if (html.hasDeclarationHomoglyph(decl)) {
      renderDeclBadge(html, CLASS_DECL_BADGE_WARNING, "homoglyph");
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
    renderLineRange(html, block.startLine(), block.endLine(), emittedLineIds);
    html.close("div");
    html.close("div");
  }

  private static void renderDerivingClause(
      BoundedHtml html,
      int endLine,
      @Nullable ImmutableList<String> derivingClause,
      @Nullable Set<Integer> emittedLineIds) {
    if (derivingClause == null || derivingClause.isEmpty()) {
      return;
    }
    html.open("div").attribute("class", CLASS_DECL_DERIVING);
    emitLineIdIfNew(html, emittedLineIds, endLine);
    if (endLine > 0) {
      renderLineNumberCell(html, emittedLineIds, endLine);
    }
    html.open("span")
        .attribute("class", CLASS_DECL_DERIVING_LABEL)
        .appendAndEscape("deriving")
        .close("span");
    for (String cls : derivingClause) {
      html.open("span").attribute("class", CLASS_DECL_DERIVING_CHIP);
      LeanSecurity.appendVisibleText(html, cls, CLASS_TROJAN_CHAR);
      html.close("span");
    }
    html.close("div");
  }

  private static void renderDeclBadge(BoundedHtml html, String modifierClass, String label) {
    html.open("span").attribute("class", CLASS_DECL_BADGE + " " + modifierClass);
    LeanSecurity.appendVisibleText(html, label, CLASS_TROJAN_CHAR);
    html.close("span");
  }

  private static void renderStaticCodeLines(
      BoundedHtml html,
      List<LeanCodeLine> lines,
      boolean inTacticContext,
      LeanHighlighter.HighlightState initialState,
      boolean forceComment,
      @Nullable Set<Integer> emittedLineIds) {
    List<LeanHighlighter.HighlightState> states =
        forceComment ? null : LeanHighlighter.computeHighlightStates(lines, initialState);
    html.open("div").attribute("class", CLASS_CODE);
    for (int i = 0; i < lines.size(); i++) {
      LeanCodeLine line = lines.get(i);
      int lineNum = line.lineNum();
      html.open("div").attribute("class", CLASS_CODE_LINE);
      emitLineIdIfNew(html, emittedLineIds, lineNum);
      renderLineNumberCell(html, emittedLineIds, lineNum);
      html.open("span").attribute("class", CLASS_CODE_TEXT);
      LeanHighlighter.HighlightState state =
          states != null ? states.get(i) : LeanHighlighter.HighlightState.INITIAL;
      renderHighlightedLine(html, line.text(), inTacticContext, state, forceComment);
      html.close("span");
      html.close("div");
    }
    html.close("div");
  }

  private static void renderStaticFieldsTable(
      BoundedHtml html, List<LeanStructureField> fields, @Nullable Set<Integer> emittedLineIds) {
    boolean hasNotes = false;
    for (LeanStructureField f : fields) {
      if (!Strings.isNullOrEmpty(f.comment())) {
        hasNotes = true;
        break;
      }
    }
    openFieldTableHeader(html, "Field", "Type", hasNotes);

    html.open("tbody");
    for (LeanStructureField field : fields) {
      renderFieldTableRow(html, field, hasNotes, emittedLineIds);
    }
    html.close("tbody").close("table");
  }

  private static void openFieldTableHeader(
      BoundedHtml html, String col1, String col2, boolean hasNotes) {
    html.open("table").attribute("class", CLASS_TABLE);
    html.open("thead").open("tr");
    html.open("th").attribute("class", CLASS_TABLE_LINE_COL).appendAndEscape("#").close("th");
    html.open("th").appendAndEscape(col1).close("th");
    html.open("th").appendAndEscape(col2).close("th");
    if (hasNotes) {
      html.open("th").appendAndEscape("Notes").close("th");
    }
    html.close("tr").close("thead");
  }

  private static void renderFieldTableRow(
      BoundedHtml html,
      LeanStructureField field,
      boolean hasNotes,
      @Nullable Set<Integer> emittedLineIds) {
    int lineNum = field.lineNum();
    html.open("tr");
    emitLineIdIfNew(html, emittedLineIds, lineNum);
    html.open("td").attribute("class", CLASS_TABLE_LINE_COL);
    renderLineNumberCell(html, emittedLineIds, lineNum);
    html.close("td");
    html.open("td").attribute("class", CLASS_TABLE_NAME);
    LeanSecurity.appendVisibleText(html, field.name(), CLASS_TROJAN_CHAR);
    html.close("td");
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
      html.open("td").attribute("class", CLASS_TABLE_NOTES);
      if (!Strings.isNullOrEmpty(field.comment())) {
        html.open("span").attribute("class", CLASS_TOK_COMMENT);
        LeanSecurity.appendVisibleText(html, field.comment(), CLASS_TROJAN_CHAR);
        html.close("span");
      }
      html.close("td");
    }
    html.close("tr");
  }

  private static void renderStaticConstructorsTable(
      BoundedHtml html, List<LeanInductiveCtor> ctors, @Nullable Set<Integer> emittedLineIds) {
    boolean hasNotes = false;
    for (LeanInductiveCtor c : ctors) {
      if (!Strings.isNullOrEmpty(c.comment())) {
        hasNotes = true;
        break;
      }
    }
    openFieldTableHeader(html, "Constructor", "Signature", hasNotes);

    html.open("tbody");
    for (LeanInductiveCtor ctor : ctors) {
      int lineNum = ctor.lineNum();
      html.open("tr");
      emitLineIdIfNew(html, emittedLineIds, lineNum);
      html.open("td").attribute("class", CLASS_TABLE_LINE_COL);
      renderLineNumberCell(html, emittedLineIds, lineNum);
      html.close("td");
      html.open("td").attribute("class", CLASS_TABLE_NAME);
      LeanSecurity.appendVisibleText(html, "| " + ctor.name(), CLASS_TROJAN_CHAR);
      html.close("td");
      html.open("td").attribute("class", CLASS_TABLE_TYPE);
      if (!ctor.signatureText().isEmpty()) {
        renderHighlightedLine(html, ctor.signatureText(), false, 0, false);
      }
      html.close("td");
      if (hasNotes) {
        html.open("td").attribute("class", CLASS_TABLE_NOTES);
        if (!Strings.isNullOrEmpty(ctor.comment())) {
          html.open("span").attribute("class", CLASS_TOK_COMMENT);
          LeanSecurity.appendVisibleText(html, ctor.comment(), CLASS_TROJAN_CHAR);
          html.close("span");
        }
        html.close("td");
      }
      html.close("tr");
    }
    html.close("tbody").close("table");
  }

  private static void renderHighlightedLine(
      BoundedHtml html,
      String line,
      boolean inTacticContext,
      int initialBlockCommentDepth,
      boolean forceComment) {
    renderHighlightedLine(
        html,
        line,
        inTacticContext,
        LeanHighlighter.HighlightState.forBlockCommentDepth(initialBlockCommentDepth),
        forceComment);
  }

  private static void renderHighlightedLine(
      BoundedHtml html,
      String line,
      boolean inTacticContext,
      LeanHighlighter.HighlightState initialState,
      boolean forceComment) {
    if (line.isEmpty()) {
      return;
    }
    if (forceComment) {
      html.open("span").attribute("class", CLASS_TOK_COMMENT);
      LeanSecurity.appendVisibleText(html, line, CLASS_TROJAN_CHAR);
      html.close("span");
      return;
    }
    ImmutableList<LeanToken> tokens =
        LeanHighlighter.tokenizeLine(line, inTacticContext, initialState);
    for (LeanToken token : tokens) {
      renderTokenPiece(html, token.kind(), token.text(), token.text());
    }
  }

  private static void renderTokenPiece(
      BoundedHtml html, LeanTokenKind kind, String fullTokenText, String piece) {
    if (piece.isEmpty()) {
      return;
    }
    if (kind == LeanTokenKind.PLAIN) {
      LeanSecurity.appendVisibleText(html, piece, CLASS_TROJAN_CHAR);
      return;
    }
    String cls = tokenClass(kind, fullTokenText);
    html.open("span").attribute("class", cls);
    LeanSecurity.appendVisibleText(html, piece, CLASS_TROJAN_CHAR);
    html.close("span");
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
          !text.isEmpty() && text.charAt(0) >= 128 && LeanHighlighter.isUnicodeOperator(text)
              ? CLASS_TOK_OPERATOR + " " + CLASS_TOK_UNICODE_OP
              : CLASS_TOK_OPERATOR;
      case BULLET -> CLASS_TOK_BULLET;
      case ATTRIBUTE -> CLASS_TOK_ATTRIBUTE;
      case NUMBER -> CLASS_TOK_NUMBER;
      case LITERAL -> CLASS_TOK_LITERAL;
    };
  }

  private static void renderMarkdownBlock(
      BoundedHtml html,
      String markdown,
      MarkdownConfig markdownConfig,
      @Nullable GitilesView view,
      @Nullable String requestUri,
      @Nullable String filePath) {
    if (markdown.isEmpty()) {
      return;
    }
    SafeHtml rendered =
        tryRenderMarkdownSafeHtml(
            markdown,
            markdownConfig,
            view,
            requestUri,
            filePath,
            html.remainingChars(),
            html.budget());
    appendMarkdownOrPre(html, markdown, rendered);
  }

  static @Nullable SafeHtml tryRenderMarkdownSafeHtml(
      String markdown,
      MarkdownConfig markdownConfig,
      @Nullable GitilesView view,
      @Nullable String requestUri,
      @Nullable String filePath) {
    return tryRenderMarkdownSafeHtml(
        markdown, markdownConfig, view, requestUri, filePath, Integer.MAX_VALUE, null);
  }

  private static @Nullable SafeHtml tryRenderMarkdownSafeHtml(
      String markdown,
      MarkdownConfig markdownConfig,
      @Nullable GitilesView view,
      @Nullable String requestUri,
      @Nullable String filePath,
      int maxOutputChars,
      @Nullable LeanRequestBudget budget) {
    if (budget != null && budget.isCpuDeadlineExceeded()) {
      throw OutputLimitExceeded.INSTANCE;
    }
    if (markdown.isEmpty()
        || !markdownConfig.render
        || markdown.length() > markdownConfig.inputLimit
        || LeanSecurity.containsHiddenOrSurrogate(markdown)
        || LeanSecurity.exceedsMarkdownNestingLimit(markdown)
        || (budget != null && !budget.tryConsumeMarkdown(markdown.length()))) {
      return null;
    }
    try {
      Node root = GitilesMarkdown.parse(markdownConfig, markdown);
      if (root == null) {
        return null;
      }
      if (budget != null && budget.isCpuDeadlineExceeded()) {
        throw OutputLimitExceeded.INSTANCE;
      }
      if (!rewriteOpaqueMarkdownNodes(root, new int[1])) {
        return null;
      }
      SafeHtml rendered =
          LeanSecurity.renderHardenedMarkdown(
              markdownConfig, view, requestUri, filePath, root, maxOutputChars, budget);
      if (rendered != null
          && !rendered.getSafeHtmlString().isEmpty()
          && !LeanSecurity.containsHiddenOrSurrogate(rendered.getSafeHtmlString())) {
        return rendered;
      }
    } catch (OutputLimitExceeded e) {
      if (budget != null && budget.isCpuDeadlineExceeded()) {
        throw e;
      }
      return null;
    } catch (StackOverflowError | RuntimeException e) {
      // Fall through to escaped preformatted text on deeply nested or invalid Markdown ASTs.
    }
    return null;
  }

  private static void appendMarkdownOrPre(
      BoundedHtml html, String markdown, @Nullable SafeHtml rendered) {
    if (rendered != null) {
      html.append(rendered);
    } else {
      renderMarkdownRawPre(html, markdown);
    }
  }

  private static void renderMarkdownRawPre(BoundedHtml html, String markdown) {
    html.open("pre").open("code");
    LeanSecurity.appendVisibleText(html, markdown, CLASS_TROJAN_CHAR);
    html.close("code").close("pre");
  }

  private static boolean rewriteOpaqueMarkdownNodes(Node root, int[] nodeCount) {
    for (Node child = root.getFirstChild(); child != null; ) {
      if (++nodeCount[0] > MAX_MARKDOWN_AST_NODES) {
        return false;
      }
      Node next = child.getNext();
      if (child instanceof HtmlInline htmlInline) {
        child.insertBefore(new Text(Strings.nullToEmpty(htmlInline.getLiteral())));
        child.unlink();
      } else if (child instanceof HtmlBlock htmlBlock) {
        replaceHtmlBlockWithParagraph(htmlBlock);
      } else if (child instanceof LinkReferenceDefinition def) {
        replaceLinkReferenceDefinition(def);
      } else if (!rewriteOpaqueMarkdownNodes(child, nodeCount)) {
        return false;
      }
      child = next;
    }
    return true;
  }

  private static void replaceHtmlBlockWithParagraph(HtmlBlock htmlBlock) {
    String literal = Strings.nullToEmpty(htmlBlock.getLiteral());
    if (literal.endsWith("\r\n")) {
      literal = literal.substring(0, literal.length() - 2);
    } else if (literal.endsWith("\n")) {
      literal = literal.substring(0, literal.length() - 1);
    }
    if (literal.isEmpty()) {
      htmlBlock.unlink();
      return;
    }
    Paragraph paragraph = new Paragraph();
    int start = 0;
    int len = literal.length();
    while (start <= len) {
      int nl = literal.indexOf('\n', start);
      String line = nl >= 0 ? literal.substring(start, nl) : literal.substring(start);
      if (line.endsWith("\r")) {
        line = line.substring(0, line.length() - 1);
      }
      if (start > 0) {
        paragraph.appendChild(new HardLineBreak());
      }
      if (!line.isEmpty()) {
        paragraph.appendChild(new Text(line));
      }
      if (nl < 0) {
        break;
      }
      start = nl + 1;
    }
    htmlBlock.insertBefore(paragraph);
    htmlBlock.unlink();
  }

  private static void replaceLinkReferenceDefinition(LinkReferenceDefinition def) {
    StringBuilder text = new StringBuilder();
    text.append('[')
        .append(Strings.nullToEmpty(def.getLabel()))
        .append("]: ")
        .append(Strings.nullToEmpty(def.getDestination()));
    if (!Strings.isNullOrEmpty(def.getTitle())) {
      text.append(" \"").append(def.getTitle()).append('"');
    }
    Text textNode = new Text(text.toString());
    if (def.getParent() instanceof Paragraph) {
      def.insertBefore(textNode);
    } else {
      Paragraph paragraph = new Paragraph();
      paragraph.appendChild(textNode);
      def.insertBefore(paragraph);
    }
    def.unlink();
  }

  private static void renderLineRange(
      BoundedHtml html, int startLine, int endLine, @Nullable Set<Integer> emittedLineIds) {
    if (startLine <= 0) {
      return;
    }
    String label = formatLineRange(startLine, endLine);
    if (emittedLineIds != null) {
      html.open("a")
          .attribute("class", CLASS_LINE_RANGE)
          .attribute("href", "#" + startLine)
          .attribute("title", "Jump to line " + startLine)
          .appendAndEscape(label)
          .close("a");
    } else {
      html.open("span").attribute("class", CLASS_LINE_RANGE).appendAndEscape(label).close("span");
    }
  }

  private static void renderLineNumberCell(
      BoundedHtml html, @Nullable Set<Integer> emittedLineIds, int lineNum) {
    if (emittedLineIds != null) {
      html.open("a")
          .attribute("class", CLASS_CODE_LINE_NUM)
          .attribute("href", "#" + lineNum)
          .appendAndEscape(Integer.toString(lineNum))
          .close("a");
    } else {
      html.open("span")
          .attribute("class", CLASS_CODE_LINE_NUM)
          .appendAndEscape(Integer.toString(lineNum))
          .close("span");
    }
  }

  private static String formatLineRange(int startLine, int endLine) {
    if (endLine <= startLine) {
      return "L" + startLine;
    }
    return "L" + startLine + "\u2013L" + endLine;
  }

  private static void emitLineIdIfNew(
      BoundedHtml html, @Nullable Set<Integer> emittedLineIds, int lineNum) {
    if (emittedLineIds != null && lineNum > 0 && emittedLineIds.add(lineNum)) {
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
            LeanHygiene.extractLeanIdentifiers(
                LeanLexer.stripLeanCommentsAndStringsForHygiene(block.raw()));
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
