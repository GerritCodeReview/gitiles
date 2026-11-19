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
import com.google.gitiles.lean.LeanSecurity.BoundedHtml;
import com.google.gitiles.lean.LeanSecurity.OutputLimitExceeded;
import com.google.gitiles.lean.LeanSecurity.TrojanScan;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import javax.annotation.Nullable;
import org.eclipse.jgit.lib.Config;

/**
 * Server-side HTML renderer for Lean 4 blobs and diffs using {@link
 * com.google.gitiles.doc.html.SoyHtmlBuilder}.
 */
public final class LeanHtml {
  private static final String CLASS_VIEW = "LeanView";
  private static final String CLASS_VIEW_BLOCKS = "LeanView-blocks";
  private static final String CLASS_DIFF = "LeanDiff";
  private static final String CLASS_DIFF_BLOCKS = "LeanDiff-blocks";
  private static final String CLASS_DIFF_BLOCK = "LeanDiff-block";
  private static final String CLASS_DIFF_BLOCK_ADDED = "LeanDiff-block--added";
  private static final String CLASS_DIFF_BLOCK_DELETED = "LeanDiff-block--deleted";
  private static final String CLASS_DIFF_COLLAPSED = "LeanDiff-collapsed";
  private static final String CLASS_DIFF_SUBROW = "LeanDiff-subrow";
  private static final String CLASS_DIFF_SUBROW_ADDED = "LeanDiff-subrow--added";
  private static final String CLASS_DIFF_SUBROW_DELETED = "LeanDiff-subrow--deleted";
  private static final String CLASS_DIFF_INLINE_ADD = "LeanDiff-inlineAdd";
  private static final String CLASS_DIFF_INLINE_DEL = "LeanDiff-inlineDel";
  private static final String CLASS_SUMMARY = "LeanSummary";
  private static final String CLASS_SUMMARY_LEFT = "LeanSummary-left";
  private static final String CLASS_SUMMARY_LANG = "LeanSummary-lang";
  private static final String CLASS_SUMMARY_STATS = "LeanSummary-stats";
  private static final String CLASS_SUMMARY_STAT_SEP = "LeanSummary-statSep";
  private static final String CLASS_SUMMARY_DELTAS = "LeanSummary-deltas";
  private static final String CLASS_SUMMARY_DELTA_ADD = "LeanSummary-delta--add";
  private static final String CLASS_SUMMARY_DELTA_DEL = "LeanSummary-delta--del";
  private static final String CLASS_SUMMARY_DELTA_MOD = "LeanSummary-delta--mod";
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
  private static final String CLASS_CODE_LINE_ADDED = "LeanCode-line--added";
  private static final String CLASS_CODE_LINE_DELETED = "LeanCode-line--deleted";
  private static final String CLASS_CODE_LINE_NUM = "LeanCode-lineNum";
  private static final String CLASS_CODE_TEXT = "LeanCode-text";
  private static final String CLASS_TABLE = "LeanTable";
  private static final String CLASS_TABLE_ROW_ADDED = "LeanTable-row--added";
  private static final String CLASS_TABLE_ROW_DELETED = "LeanTable-row--deleted";
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
      if (activeBudget.isCpuDeadlineExceeded()) {
        return null;
      }
      LeanFileCounts counts = LeanHygiene.count(blocks);
      boolean hasUnterminated = LeanHygiene.hasUnterminatedLiteralOrComment(blocks);
      TrojanScan trojanScan =
          LeanSecurity.scanTrojanRisks(source, null, blocks, ImmutableList.of());
      if (activeBudget.isCpuDeadlineExceeded()) {
        return null;
      }
      Set<Integer> emittedLineIds = new HashSet<>();

      BoundedHtml html = new BoundedHtml(activeBudget.remainingOutputChars(), activeBudget);
      html.open("div").attribute("class", CLASS_VIEW);
      renderBlobSummaryBar(html, counts, blocks, hasUnterminated);
      LeanSecurity.renderTrojanBanner(
          html, trojanScan, CLASS_TROJAN_BANNER, CLASS_TROJAN_BANNER_BADGE, CLASS_TROJAN_CHAR);

      if (!blocks.isEmpty()) {
        html.open("div").attribute("class", CLASS_VIEW_BLOCKS);
        for (LeanBlock block : blocks) {
          renderBlock(html, block, markdownConfig, view, requestUri, filePath, emittedLineIds);
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

  /**
   * Renders a unified semantic Lean 4 diff as sanitized {@link SafeHtml}, or {@code null} if either
   * input is null/oversize, both sides contain no Lean blocks, or the output exceeds bounds.
   */
  public static @Nullable SafeHtml renderDiff(
      @Nullable String leftSource,
      @Nullable String rightSource,
      @Nullable Config config,
      @Nullable GitilesView view,
      @Nullable String requestUri,
      @Nullable String filePath) {
    if (leftSource == null || rightSource == null) {
      return null;
    }
    return renderDiff(
        utf8ByteLength(leftSource),
        leftSource,
        utf8ByteLength(rightSource),
        rightSource,
        config,
        view,
        requestUri,
        filePath);
  }

  /**
   * Renders a unified semantic Lean 4 diff as sanitized {@link SafeHtml}, or {@code null} if either
   * input is null/oversize, both sides contain no Lean blocks, or the output exceeds bounds.
   */
  public static @Nullable SafeHtml renderDiff(
      int leftByteLength,
      @Nullable String leftSource,
      int rightByteLength,
      @Nullable String rightSource,
      @Nullable Config config,
      @Nullable GitilesView view,
      @Nullable String requestUri,
      @Nullable String filePath) {
    return renderDiff(
        leftByteLength,
        leftSource,
        rightByteLength,
        rightSource,
        config,
        view,
        requestUri,
        filePath,
        null);
  }

  /** Renders a unified semantic Lean 4 diff under an optional {@link LeanRequestBudget}. */
  public static @Nullable SafeHtml renderDiff(
      int leftByteLength,
      @Nullable String leftSource,
      int rightByteLength,
      @Nullable String rightSource,
      @Nullable Config config,
      @Nullable GitilesView view,
      @Nullable String requestUri,
      @Nullable String filePath,
      @Nullable LeanRequestBudget budget) {
    if (!LeanRendering.withinInputCaps(leftByteLength, leftSource)
        || !LeanRendering.withinInputCaps(rightByteLength, rightSource)) {
      return null;
    }
    LeanRequestBudget activeBudget = budget != null ? budget : LeanRequestBudget.forSingleFile();
    if (!activeBudget.canAttempt(leftByteLength, rightByteLength)
        || !activeBudget.beginOperation()) {
      return null;
    }
    int emittedChars = 0;
    boolean rendered = false;
    try {
      MarkdownConfig markdownConfig = LeanSecurity.leanMarkdownConfig(config);
      ImmutableList<LeanBlock> blocksA = LeanParser.parse(leftSource);
      ImmutableList<LeanBlock> blocksB = LeanParser.parse(rightSource);
      if (blocksA.isEmpty() && blocksB.isEmpty()) {
        return null;
      }
      if (activeBudget.isCpuDeadlineExceeded()) {
        return null;
      }

      ImmutableList<AlignedLeanRow> alignedRows =
          LeanDiffEngine.alignLeanBlocks(blocksA, blocksB, activeBudget::isCpuDeadlineExceeded);
      if (alignedRows.isEmpty() || activeBudget.isCpuDeadlineExceeded()) {
        return null;
      }
      if (allRowsUnchanged(alignedRows) && !leftSource.equals(rightSource)) {
        return null;
      }
      LeanDiffSummary summary = LeanDiffEngine.computeLeanSummary(blocksA, blocksB, alignedRows);
      boolean hasUnterminated =
          LeanHygiene.hasUnterminatedLiteralOrComment(summary.hasRevision() ? blocksB : blocksA);
      TrojanScan trojanScan =
          LeanSecurity.scanTrojanRisks(leftSource, rightSource, blocksA, blocksB);
      if (activeBudget.isCpuDeadlineExceeded()) {
        return null;
      }

      BoundedHtml html = new BoundedHtml(activeBudget.remainingOutputChars(), activeBudget);
      html.open("div").attribute("class", CLASS_DIFF);
      renderDiffSummaryBar(html, summary, hasUnterminated);
      LeanSecurity.renderTrojanBanner(
          html, trojanScan, CLASS_TROJAN_BANNER, CLASS_TROJAN_BANNER_BADGE, CLASS_TROJAN_CHAR);
      renderDiffBlocks(html, alignedRows, markdownConfig, view, requestUri, filePath);
      html.close("div");
      SafeHtml safe = html.toSoy();
      emittedChars = safe.getSafeHtmlString().length();
      rendered = true;
      return safe;
    } catch (OutputLimitExceeded e) {
      return null;
    } finally {
      activeBudget.recordOutcome(leftByteLength, rightByteLength, emittedChars, rendered);
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

  private static void renderDiffSummaryBar(
      BoundedHtml html, LeanDiffSummary summary, boolean hasUnterminated) {
    LeanFileCounts counts = summary.hasRevision() ? summary.revision() : summary.base();
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
      renderStaticSummaryPill(html, CLASS_PILL_ERROR, sorryTotal + " sorry");
    }
    if (counts.axioms() > 0) {
      renderStaticSummaryPill(
          html, CLASS_PILL_WARNING, formatPlural(counts.axioms(), "axiom", "axioms"));
    }
    if (counts.nativeDecideCount() > 0) {
      renderStaticSummaryPill(
          html, CLASS_PILL_WARNING, counts.nativeDecideCount() + " native_decide");
    }
    if (counts.partialCount() > 0) {
      renderStaticSummaryPill(html, CLASS_PILL_WARNING, counts.partialCount() + " partial");
    }
    if (counts.unsafeCount() > 0) {
      renderStaticSummaryPill(html, CLASS_PILL_ERROR, counts.unsafeCount() + " unsafe");
    }
    if (hasUnterminated) {
      renderStaticSummaryPill(html, CLASS_PILL_NEUTRAL, UNTERMINATED_WARNING_LABEL);
    }

    renderSummaryStats(html, counts);

    if (summary.hasBase()
        && summary.hasRevision()
        && (summary.addedDeclarations() > 0
            || summary.modifiedDeclarations() > 0
            || summary.deletedDeclarations() > 0)) {
      html.open("span").attribute("class", CLASS_SUMMARY_DELTAS);
      if (summary.addedDeclarations() > 0) {
        html.open("span")
            .attribute("class", CLASS_SUMMARY_DELTA_ADD)
            .appendAndEscape("+" + summary.addedDeclarations())
            .close("span");
      }
      if (summary.modifiedDeclarations() > 0) {
        html.open("span")
            .attribute("class", CLASS_SUMMARY_DELTA_MOD)
            .appendAndEscape("~" + summary.modifiedDeclarations())
            .close("span");
      }
      if (summary.deletedDeclarations() > 0) {
        html.open("span")
            .attribute("class", CLASS_SUMMARY_DELTA_DEL)
            .appendAndEscape("-" + summary.deletedDeclarations())
            .close("span");
      }
      html.close("span");
    }
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

  private static boolean allRowsUnchanged(List<AlignedLeanRow> rows) {
    for (AlignedLeanRow row : rows) {
      if (row.status() != DiffBlockStatus.UNCHANGED) {
        return false;
      }
    }
    return true;
  }

  private static boolean allLinesUnchanged(List<LeanAlignedCodeLine> lines) {
    for (LeanAlignedCodeLine line : lines) {
      if (line.status() != DiffBlockStatus.UNCHANGED) {
        return false;
      }
    }
    return true;
  }

  private static void renderDiffBlocks(
      BoundedHtml html,
      List<AlignedLeanRow> rows,
      MarkdownConfig markdownConfig,
      @Nullable GitilesView view,
      @Nullable String requestUri,
      @Nullable String filePath) {
    html.open("div").attribute("class", CLASS_DIFF_BLOCKS);
    int i = 0;
    while (i < rows.size()) {
      AlignedLeanRow row = rows.get(i);
      if (row.status() == DiffBlockStatus.UNCHANGED) {
        int j = i;
        int declCount = 0;
        while (j < rows.size() && rows.get(j).status() == DiffBlockStatus.UNCHANGED) {
          AlignedLeanRow unchanged = rows.get(j);
          LeanBlock blk =
              unchanged.rightBlock() != null ? unchanged.rightBlock() : unchanged.leftBlock();
          if (blk != null && blk.type() == LeanBlockType.DECLARATION) {
            declCount++;
          }
          j++;
        }
        int totalCount = j - i;
        html.open("div")
            .attribute("class", CLASS_DIFF_COLLAPSED)
            .appendAndEscape(formatCollapsedLabel(declCount, totalCount))
            .close("div");
        i = j;
        continue;
      }

      switch (row.status()) {
        case ADDED -> {
          if (row.rightBlock() != null) {
            html.open("div").attribute("class", CLASS_DIFF_BLOCK + " " + CLASS_DIFF_BLOCK_ADDED);
            renderBlock(html, row.rightBlock(), markdownConfig, view, requestUri, filePath, null);
            html.close("div");
          }
        }
        case DELETED -> {
          if (row.leftBlock() != null) {
            html.open("div").attribute("class", CLASS_DIFF_BLOCK + " " + CLASS_DIFF_BLOCK_DELETED);
            renderBlock(html, row.leftBlock(), markdownConfig, view, requestUri, filePath, null);
            html.close("div");
          }
        }
        case MODIFIED -> renderModifiedRow(html, row, markdownConfig, view, requestUri, filePath);
        case UNCHANGED -> {}
      }
      i++;
    }
    html.close("div");
  }

  private static String formatCollapsedLabel(int declCount, int blockCount) {
    if (declCount == blockCount) {
      return formatPlural(declCount, "unchanged declaration", "unchanged declarations");
    }
    if (declCount == 0) {
      return formatPlural(blockCount, "unchanged block", "unchanged blocks");
    }
    int otherCount = blockCount - declCount;
    return formatPlural(declCount, "unchanged declaration", "unchanged declarations")
        + " \u00b7 "
        + formatPlural(otherCount, "other unchanged block", "other unchanged blocks");
  }

  private static void renderModifiedRow(
      BoundedHtml html,
      AlignedLeanRow row,
      MarkdownConfig markdownConfig,
      @Nullable GitilesView view,
      @Nullable String requestUri,
      @Nullable String filePath) {
    LeanBlock left = row.leftBlock();
    LeanBlock right = row.rightBlock();

    if (row.moduleDocDiff() != null && right != null) {
      TextDiffData docDiff = row.moduleDocDiff();
      if (left != null
          && docDiff.leftText().equals(docDiff.rightText())
          && !left.raw().equals(right.raw())) {
        renderRawCodeStripDiff(html, left, right);
        return;
      }
      html.open("div").attribute("class", CLASS_DIFF_BLOCK);
      html.open("div").attribute("class", CLASS_MODULE_DOC + " " + CLASS_MD);
      html.open("div").attribute("class", CLASS_MODULE_DOC_HEADER);
      html.open("span").appendAndEscape("Module Documentation").close("span");
      renderLineRange(html, right.startLine(), right.endLine(), null);
      html.close("div");
      if (docDiff.changed()) {
        if (!docDiff.leftText().isEmpty()) {
          html.open("div").attribute("class", CLASS_DIFF_SUBROW + " " + CLASS_DIFF_SUBROW_DELETED);
          renderMarkdownBlock(html, docDiff.leftText(), markdownConfig, view, requestUri, filePath);
          html.close("div");
        }
        if (!docDiff.rightText().isEmpty()) {
          html.open("div").attribute("class", CLASS_DIFF_SUBROW + " " + CLASS_DIFF_SUBROW_ADDED);
          renderMarkdownBlock(
              html, docDiff.rightText(), markdownConfig, view, requestUri, filePath);
          html.close("div");
        }
      } else {
        renderMarkdownBlock(html, docDiff.rightText(), markdownConfig, view, requestUri, filePath);
      }
      html.close("div");
      html.close("div");
      return;
    }

    if (row.bannerDiff() != null && right != null) {
      TextDiffData bannerDiff = row.bannerDiff();
      if (left != null
          && bannerDiff.leftText().equals(bannerDiff.rightText())
          && !left.raw().equals(right.raw())) {
        renderRawCodeStripDiff(html, left, right);
        return;
      }
      html.open("div").attribute("class", CLASS_DIFF_BLOCK);
      html.open("div").attribute("class", CLASS_BANNER);
      if (bannerDiff.changed()) {
        html.open("div");
        if (!bannerDiff.leftText().isEmpty()) {
          html.open("div").attribute("class", CLASS_DIFF_SUBROW + " " + CLASS_DIFF_SUBROW_DELETED);
          html.open("h3").attribute("class", CLASS_BANNER_TITLE);
          LeanSecurity.appendVisibleText(html, bannerDiff.leftText(), CLASS_TROJAN_CHAR);
          html.close("h3");
          html.close("div");
        }
        if (!bannerDiff.rightText().isEmpty()) {
          html.open("div").attribute("class", CLASS_DIFF_SUBROW + " " + CLASS_DIFF_SUBROW_ADDED);
          html.open("h3").attribute("class", CLASS_BANNER_TITLE);
          LeanSecurity.appendVisibleText(html, bannerDiff.rightText(), CLASS_TROJAN_CHAR);
          html.close("h3");
          html.close("div");
        }
        html.close("div");
      } else {
        html.open("h3").attribute("class", CLASS_BANNER_TITLE);
        LeanSecurity.appendVisibleText(html, bannerDiff.rightText(), CLASS_TROJAN_CHAR);
        html.close("h3");
      }
      renderLineRange(html, right.startLine(), right.endLine(), null);
      html.close("div");
      html.close("div");
      return;
    }

    if (row.lineDiff() != null) {
      if (left != null
          && right != null
          && allLinesUnchanged(row.lineDiff())
          && !left.raw().equals(right.raw())) {
        renderRawCodeStripDiff(html, left, right);
        return;
      }
      LeanBlock active = right != null ? right : left;
      boolean isComment = active != null && active.type() == LeanBlockType.COMMENT;
      boolean isLicense =
          (right != null && right.isLicenseHeader()) || (left != null && left.isLicenseHeader());
      html.open("div").attribute("class", CLASS_DIFF_BLOCK);
      html.open("div").attribute("class", stripClass(isComment, isLicense));
      renderAlignedCodeLines(html, row.lineDiff());
      html.close("div");
      html.close("div");
      return;
    }

    if (row.declDiff() != null && right != null && right.decl() != null) {
      if (hasVisibleDeclChange(left, right, row.declDiff())) {
        html.open("div").attribute("class", CLASS_DIFF_BLOCK);
        renderDiffDeclarationCard(
            html,
            left,
            right,
            right.decl(),
            row.declDiff(),
            markdownConfig,
            view,
            requestUri,
            filePath);
        html.close("div");
        return;
      }
      if (left != null && !left.raw().equals(right.raw())) {
        renderRawCodeStripDiff(html, left, right);
        return;
      }
    }

    if (left != null) {
      html.open("div").attribute("class", CLASS_DIFF_BLOCK + " " + CLASS_DIFF_BLOCK_DELETED);
      renderBlock(html, left, markdownConfig, view, requestUri, filePath, null);
      html.close("div");
    }
    if (right != null) {
      html.open("div").attribute("class", CLASS_DIFF_BLOCK + " " + CLASS_DIFF_BLOCK_ADDED);
      renderBlock(html, right, markdownConfig, view, requestUri, filePath, null);
      html.close("div");
    }
  }

  private static boolean hasVisibleDeclChange(
      @Nullable LeanBlock leftBlock, LeanBlock rightBlock, LeanDeclarationDiffData declDiff) {
    LeanDeclaration leftDecl = leftBlock != null ? leftBlock.decl() : null;
    LeanDeclaration rightDecl = rightBlock.decl();
    if (declDiff.docChanged()
        || declDiff.signatureChanged()
        || declDiff.bodyChanged()
        || declDiff.fieldsChanged()) {
      return true;
    }
    if (leftDecl != null && rightDecl != null) {
      if (!leftDecl.name().equals(rightDecl.name())
          || leftDecl.kind() != rightDecl.kind()
          || !Objects.equals(leftDecl.derivingClause(), rightDecl.derivingClause())
          || !leftDecl.modifiers().equals(rightDecl.modifiers())
          || !leftDecl.attributes().equals(rightDecl.attributes())) {
        return true;
      }
    }
    return leftBlock == null || leftBlock.raw().equals(rightBlock.raw());
  }

  private static void renderRawCodeStripDiff(BoundedHtml html, LeanBlock left, LeanBlock right) {
    boolean isComment =
        right.type() == LeanBlockType.COMMENT || left.type() == LeanBlockType.COMMENT;
    boolean isLicense = right.isLicenseHeader() || left.isLicenseHeader();
    html.open("div").attribute("class", CLASS_DIFF_BLOCK);
    html.open("div").attribute("class", stripClass(isComment, isLicense));
    html.open("div").attribute("class", CLASS_CODE);
    renderRawLinesWithClass(
        html,
        left.raw(),
        left.startLine(),
        CLASS_CODE_LINE + " " + CLASS_CODE_LINE_DELETED,
        isComment);
    renderRawLinesWithClass(
        html,
        right.raw(),
        right.startLine(),
        CLASS_CODE_LINE + " " + CLASS_CODE_LINE_ADDED,
        isComment);
    html.close("div");
    html.close("div");
    html.close("div");
  }

  private static void renderRawLinesWithClass(
      BoundedHtml html, String raw, int startLine, String lineClass, boolean forceComment) {
    String normalized = LeanSecurity.normalizeLineEndings(raw);
    int lineNum = Math.max(1, startLine);
    int start = 0;
    int len = normalized.length();
    while (start <= len) {
      int nl = normalized.indexOf('\n', start);
      String text = nl >= 0 ? normalized.substring(start, nl) : normalized.substring(start);
      if (nl < 0 && start == len && len > 0) {
        break;
      }
      renderDiffCodeLine(html, lineClass, lineNum, text, null, null, false, 0, forceComment);
      lineNum++;
      if (nl < 0) {
        break;
      }
      start = nl + 1;
    }
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
    renderStaticCodeLines(html, lines, false, 0, isComment, emittedLineIds);
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
        && decl.derivingClause() == null) {
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

    renderDeclHeader(html, block, decl, category, emittedLineIds, null);

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
      renderStaticFieldsTable(html, fields, emittedLineIds);
      html.close("div");
    } else if (hasCtors) {
      html.open("div").attribute("class", CLASS_DECL_BODY);
      renderStaticConstructorsTable(html, ctors, emittedLineIds);
      html.close("div");
    } else if (hasCodeBody) {
      int sigFinalDepth = LeanHighlighter.computeFinalBlockCommentDepth(decl.signatureLines(), 0);
      html.open("div").attribute("class", CLASS_DECL_BODY);
      renderStaticCodeLines(
          html, decl.bodyLines(), decl.isTacticProof(), sigFinalDepth, false, emittedLineIds);
      html.close("div");
    }

    renderDerivingClause(html, block.endLine(), decl.derivingClause(), emittedLineIds);
    html.close("div");
  }

  private static void renderDiffDeclarationCard(
      BoundedHtml html,
      @Nullable LeanBlock leftBlock,
      LeanBlock rightBlock,
      LeanDeclaration rightDecl,
      LeanDeclarationDiffData declDiff,
      MarkdownConfig markdownConfig,
      @Nullable GitilesView view,
      @Nullable String requestUri,
      @Nullable String filePath) {
    String category = declCategory(rightDecl.kind());
    String declCatClass = CLASS_DECL + "--" + category;
    LeanDeclaration leftDecl = leftBlock != null ? leftBlock.decl() : null;
    String renamedFrom = null;
    if (leftDecl != null
        && (!leftDecl.name().equals(rightDecl.name()) || leftDecl.kind() != rightDecl.kind())) {
      renamedFrom =
          "was "
              + leftDecl.kind().keyword()
              + (leftDecl.name().isEmpty() ? "" : " " + leftDecl.name());
    }

    html.open("div").attribute("class", declCardClass(rightDecl, declCatClass));
    renderDeclHeader(html, rightBlock, rightDecl, category, null, renamedFrom);

    // Docstring diff
    if (declDiff.docChanged()) {
      if (declDiff.leftDoc() != null || declDiff.rightDoc() != null) {
        html.open("div").attribute("class", CLASS_DECL_DOC + " " + CLASS_MD);
        if (declDiff.leftDoc() != null) {
          html.open("div").attribute("class", CLASS_DIFF_SUBROW + " " + CLASS_DIFF_SUBROW_DELETED);
          renderMarkdownBlock(html, declDiff.leftDoc(), markdownConfig, view, requestUri, filePath);
          html.close("div");
        }
        if (declDiff.rightDoc() != null) {
          html.open("div").attribute("class", CLASS_DIFF_SUBROW + " " + CLASS_DIFF_SUBROW_ADDED);
          renderMarkdownBlock(
              html, declDiff.rightDoc(), markdownConfig, view, requestUri, filePath);
          html.close("div");
        }
        html.close("div");
      }
    } else if (declDiff.rightDoc() != null) {
      html.open("div").attribute("class", CLASS_DECL_DOC + " " + CLASS_MD);
      renderMarkdownBlock(html, declDiff.rightDoc(), markdownConfig, view, requestUri, filePath);
      html.close("div");
    }

    ImmutableList<LeanAlignedField> alignedFields = declDiff.fields();
    boolean hasFields = alignedFields != null && !alignedFields.isEmpty();
    boolean hasCodeBody = !hasFields && !declDiff.bodyLines().isEmpty();
    boolean hasBody = hasFields || hasCodeBody;

    html.open("div")
        .attribute(
            "class", hasBody ? CLASS_DECL_SIG + " " + CLASS_DECL_SIG_HAS_BODY : CLASS_DECL_SIG);
    renderAlignedCodeLines(html, declDiff.signatureLines());
    html.close("div");

    if (hasFields) {
      html.open("div").attribute("class", CLASS_DECL_BODY);
      renderAlignedFieldsTable(html, alignedFields);
      html.close("div");
    } else if (hasCodeBody) {
      html.open("div").attribute("class", CLASS_DECL_BODY);
      renderAlignedCodeLines(html, declDiff.bodyLines());
      html.close("div");
    }

    @Nullable
    ImmutableList<String> leftDeriving =
        leftBlock != null && leftBlock.decl() != null ? leftBlock.decl().derivingClause() : null;
    @Nullable ImmutableList<String> rightDeriving = rightDecl.derivingClause();
    if (Objects.equals(leftDeriving, rightDeriving)) {
      renderDerivingClause(html, rightBlock.endLine(), rightDeriving, null);
    } else {
      if (leftDeriving != null && !leftDeriving.isEmpty()) {
        html.open("div").attribute("class", CLASS_DIFF_SUBROW + " " + CLASS_DIFF_SUBROW_DELETED);
        renderDerivingClause(html, leftBlock != null ? leftBlock.endLine() : 0, leftDeriving, null);
        html.close("div");
      }
      if (rightDeriving != null && !rightDeriving.isEmpty()) {
        html.open("div").attribute("class", CLASS_DIFF_SUBROW + " " + CLASS_DIFF_SUBROW_ADDED);
        renderDerivingClause(html, rightBlock.endLine(), rightDeriving, null);
        html.close("div");
      }
    }
    html.close("div");
  }

  private static void renderDeclHeader(
      BoundedHtml html,
      LeanBlock block,
      LeanDeclaration decl,
      String category,
      @Nullable Set<Integer> emittedLineIds,
      @Nullable String renamedFrom) {
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
    if (LeanSecurity.isHomoglyphDeclName(decl.name())) {
      renderDeclBadge(html, CLASS_DECL_BADGE_WARNING, "homoglyph");
    }
    if (renamedFrom != null) {
      renderDeclBadge(html, CLASS_DECL_BADGE_WARNING, renamedFrom);
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
      int initialBlockCommentDepth,
      boolean forceComment,
      @Nullable Set<Integer> emittedLineIds) {
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
      renderLineNumberCell(html, emittedLineIds, lineNum);
      html.open("span").attribute("class", CLASS_CODE_TEXT);
      int depth = depths != null ? depths[i] : 0;
      renderHighlightedLine(html, line.text(), inTacticContext, depth, forceComment);
      html.close("span");
      html.close("div");
    }
    html.close("div");
  }

  private static void renderAlignedCodeLines(
      BoundedHtml html, List<LeanAlignedCodeLine> alignedLines) {
    html.open("div").attribute("class", CLASS_CODE);
    for (LeanAlignedCodeLine line : alignedLines) {
      switch (line.status()) {
        case UNCHANGED -> {
          LeanCodeLine right = line.rightLine();
          if (right != null) {
            renderDiffCodeLine(
                html,
                CLASS_CODE_LINE,
                right.lineNum(),
                right.text(),
                null,
                null,
                line.inTacticContext(),
                line.rightBlockCommentDepth(),
                line.forceComment());
          }
        }
        case DELETED -> {
          LeanCodeLine left = line.leftLine();
          if (left != null) {
            renderDiffCodeLine(
                html,
                CLASS_CODE_LINE + " " + CLASS_CODE_LINE_DELETED,
                left.lineNum(),
                left.text(),
                null,
                null,
                line.inTacticContext(),
                line.leftBlockCommentDepth(),
                line.forceComment());
          }
        }
        case ADDED -> {
          LeanCodeLine right = line.rightLine();
          if (right != null) {
            renderDiffCodeLine(
                html,
                CLASS_CODE_LINE + " " + CLASS_CODE_LINE_ADDED,
                right.lineNum(),
                right.text(),
                null,
                null,
                line.inTacticContext(),
                line.rightBlockCommentDepth(),
                line.forceComment());
          }
        }
        case MODIFIED -> {
          LeanCodeLine left = line.leftLine();
          if (left != null) {
            renderDiffCodeLine(
                html,
                CLASS_CODE_LINE + " " + CLASS_CODE_LINE_DELETED,
                left.lineNum(),
                left.text(),
                line.leftSegments(),
                CLASS_DIFF_INLINE_DEL,
                line.inTacticContext(),
                line.leftBlockCommentDepth(),
                line.forceComment());
          }
          LeanCodeLine right = line.rightLine();
          if (right != null) {
            renderDiffCodeLine(
                html,
                CLASS_CODE_LINE + " " + CLASS_CODE_LINE_ADDED,
                right.lineNum(),
                right.text(),
                line.rightSegments(),
                CLASS_DIFF_INLINE_ADD,
                line.inTacticContext(),
                line.rightBlockCommentDepth(),
                line.forceComment());
          }
        }
      }
    }
    html.close("div");
  }

  private static void renderDiffCodeLine(
      BoundedHtml html,
      String lineClass,
      int lineNum,
      String text,
      @Nullable List<InlineEditSegment> segments,
      @Nullable String inlineClass,
      boolean inTacticContext,
      int blockCommentDepth,
      boolean forceComment) {
    html.open("div").attribute("class", lineClass);
    renderLineNumberCell(html, null, lineNum);
    html.open("span").attribute("class", CLASS_CODE_TEXT);
    if (segments != null && inlineClass != null) {
      renderHighlightedSegments(
          html, text, segments, inlineClass, inTacticContext, blockCommentDepth, forceComment);
    } else {
      renderHighlightedLine(html, text, inTacticContext, blockCommentDepth, forceComment);
    }
    html.close("span");
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
      renderFieldTableRow(html, null, field, null, null, hasNotes, emittedLineIds);
    }
    html.close("tbody").close("table");
  }

  private static void renderAlignedFieldsTable(
      BoundedHtml html, List<LeanAlignedField> alignedFields) {
    if (hasWhitespaceOnlyFieldChange(alignedFields)) {
      html.open("div").attribute("class", CLASS_CODE);
      for (LeanAlignedField af : alignedFields) {
        switch (af.status()) {
          case UNCHANGED -> {
            if (af.rightField() != null) {
              renderDiffCodeLine(
                  html,
                  CLASS_CODE_LINE,
                  af.rightField().lineNum(),
                  af.rightField().rawLine(),
                  null,
                  null,
                  false,
                  0,
                  false);
            }
          }
          case DELETED -> {
            if (af.leftField() != null) {
              renderDiffCodeLine(
                  html,
                  CLASS_CODE_LINE + " " + CLASS_CODE_LINE_DELETED,
                  af.leftField().lineNum(),
                  af.leftField().rawLine(),
                  null,
                  null,
                  false,
                  0,
                  false);
            }
          }
          case ADDED -> {
            if (af.rightField() != null) {
              renderDiffCodeLine(
                  html,
                  CLASS_CODE_LINE + " " + CLASS_CODE_LINE_ADDED,
                  af.rightField().lineNum(),
                  af.rightField().rawLine(),
                  null,
                  null,
                  false,
                  0,
                  false);
            }
          }
          case MODIFIED -> {
            if (af.leftField() != null) {
              renderDiffCodeLine(
                  html,
                  CLASS_CODE_LINE + " " + CLASS_CODE_LINE_DELETED,
                  af.leftField().lineNum(),
                  af.leftField().rawLine(),
                  null,
                  null,
                  false,
                  0,
                  false);
            }
            if (af.rightField() != null) {
              renderDiffCodeLine(
                  html,
                  CLASS_CODE_LINE + " " + CLASS_CODE_LINE_ADDED,
                  af.rightField().lineNum(),
                  af.rightField().rawLine(),
                  null,
                  null,
                  false,
                  0,
                  false);
            }
          }
        }
      }
      html.close("div");
      return;
    }

    boolean hasNotes = false;
    for (LeanAlignedField af : alignedFields) {
      if ((af.leftField() != null && !Strings.isNullOrEmpty(af.leftField().comment()))
          || (af.rightField() != null && !Strings.isNullOrEmpty(af.rightField().comment()))) {
        hasNotes = true;
        break;
      }
    }
    openFieldTableHeader(html, "Field", "Type", hasNotes);

    html.open("tbody");
    for (LeanAlignedField af : alignedFields) {
      switch (af.status()) {
        case UNCHANGED -> {
          if (af.rightField() != null) {
            renderFieldTableRow(html, null, af.rightField(), null, null, hasNotes, null);
          }
        }
        case DELETED -> {
          if (af.leftField() != null) {
            renderFieldTableRow(
                html, CLASS_TABLE_ROW_DELETED, af.leftField(), null, null, hasNotes, null);
          }
        }
        case ADDED -> {
          if (af.rightField() != null) {
            renderFieldTableRow(
                html, CLASS_TABLE_ROW_ADDED, af.rightField(), null, null, hasNotes, null);
          }
        }
        case MODIFIED -> {
          if (af.leftField() != null) {
            renderFieldTableRow(
                html,
                CLASS_TABLE_ROW_DELETED,
                af.leftField(),
                af.leftTypeSegments(),
                CLASS_DIFF_INLINE_DEL,
                hasNotes,
                null);
          }
          if (af.rightField() != null) {
            renderFieldTableRow(
                html,
                CLASS_TABLE_ROW_ADDED,
                af.rightField(),
                af.rightTypeSegments(),
                CLASS_DIFF_INLINE_ADD,
                hasNotes,
                null);
          }
        }
      }
    }
    html.close("tbody").close("table");
  }

  private static boolean hasWhitespaceOnlyFieldChange(List<LeanAlignedField> alignedFields) {
    for (LeanAlignedField af : alignedFields) {
      if (af.status() == DiffBlockStatus.MODIFIED
          && af.leftField() != null
          && af.rightField() != null) {
        LeanStructureField l = af.leftField();
        LeanStructureField r = af.rightField();
        if (l.name().equals(r.name())
            && l.typeText().equals(r.typeText())
            && Objects.equals(l.defaultValue(), r.defaultValue())
            && Objects.equals(l.comment(), r.comment())
            && !l.rawLine().equals(r.rawLine())) {
          return true;
        }
      }
    }
    return false;
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
      @Nullable String rowClass,
      LeanStructureField field,
      @Nullable List<InlineEditSegment> typeSegments,
      @Nullable String inlineClass,
      boolean hasNotes,
      @Nullable Set<Integer> emittedLineIds) {
    int lineNum = field.lineNum();
    html.open("tr");
    if (rowClass != null) {
      html.attribute("class", rowClass);
    }
    emitLineIdIfNew(html, emittedLineIds, lineNum);
    html.open("td").attribute("class", CLASS_TABLE_LINE_COL);
    renderLineNumberCell(html, emittedLineIds, lineNum);
    html.close("td");
    html.open("td").attribute("class", CLASS_TABLE_NAME);
    LeanSecurity.appendVisibleText(html, field.name(), CLASS_TROJAN_CHAR);
    html.close("td");
    html.open("td").attribute("class", CLASS_TABLE_TYPE);
    if (typeSegments != null && inlineClass != null) {
      renderHighlightedSegments(html, field.typeText(), typeSegments, inlineClass, false, 0, false);
    } else {
      renderHighlightedLine(html, field.typeText(), false, 0, false);
    }
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
        LeanHighlighter.tokenizeLine(line, inTacticContext, initialBlockCommentDepth);
    for (LeanToken token : tokens) {
      renderTokenPiece(html, token.kind(), token.text(), token.text());
    }
  }

  private static void renderHighlightedSegments(
      BoundedHtml html,
      String line,
      List<InlineEditSegment> segments,
      String inlineClass,
      boolean inTacticContext,
      int initialBlockCommentDepth,
      boolean forceComment) {
    if (line.isEmpty()) {
      return;
    }
    int segTotal = 0;
    boolean anyEdited = false;
    for (InlineEditSegment seg : segments) {
      segTotal += seg.text().length();
      if (seg.edited()) {
        anyEdited = true;
      }
    }
    if (!anyEdited || segTotal != line.length()) {
      renderHighlightedLine(html, line, inTacticContext, initialBlockCommentDepth, forceComment);
      return;
    }

    List<LeanToken> tokens =
        forceComment
            ? ImmutableList.of(new LeanToken(line, LeanTokenKind.COMMENT))
            : LeanHighlighter.tokenizeLine(line, inTacticContext, initialBlockCommentDepth);
    int tokTotal = 0;
    for (LeanToken tok : tokens) {
      tokTotal += tok.text().length();
    }
    if (tokTotal != line.length()) {
      for (InlineEditSegment seg : segments) {
        if (seg.text().isEmpty()) {
          continue;
        }
        if (seg.edited()) {
          html.open("span").attribute("class", inlineClass);
          LeanSecurity.appendVisibleText(html, seg.text(), CLASS_TROJAN_CHAR);
          html.close("span");
        } else {
          LeanSecurity.appendVisibleText(html, seg.text(), CLASS_TROJAN_CHAR);
        }
      }
      return;
    }

    int tokIdx = 0;
    int tokOffset = 0;
    for (InlineEditSegment seg : segments) {
      String segText = seg.text();
      if (segText.isEmpty()) {
        continue;
      }
      if (seg.edited()) {
        html.open("span").attribute("class", inlineClass);
      }
      int segOffset = 0;
      int segLen = segText.length();
      while (segOffset < segLen && tokIdx < tokens.size()) {
        LeanToken tok = tokens.get(tokIdx);
        int tokRemaining = tok.text().length() - tokOffset;
        int segRemaining = segLen - segOffset;
        int take = Math.min(tokRemaining, segRemaining);
        if (take > 0) {
          String piece = tok.text().substring(tokOffset, tokOffset + take);
          renderTokenPiece(html, tok.kind(), tok.text(), piece);
          tokOffset += take;
          segOffset += take;
        }
        if (tokOffset >= tok.text().length()) {
          tokIdx++;
          tokOffset = 0;
        }
      }
      if (seg.edited()) {
        html.close("span");
      }
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
    if (markdownConfig.render
        && markdown.length() <= markdownConfig.inputLimit
        && !LeanSecurity.containsHiddenOrSurrogate(markdown)
        && !LeanSecurity.exceedsMarkdownNestingLimit(markdown)) {
      try {
        SafeHtml rendered =
            MarkdownToHtml.builder()
                .setConfig(markdownConfig)
                .setGitilesView(view)
                .setRequestUri(requestUri)
                .setFilePath(filePath)
                .build()
                .toSoyHtml(GitilesMarkdown.parse(markdownConfig, markdown));
        if (rendered != null
            && !LeanSecurity.containsHiddenOrSurrogate(rendered.getSafeHtmlString())) {
          html.append(rendered);
          return;
        }
      } catch (StackOverflowError | RuntimeException e) {
        // Fall through to escaped preformatted text on deeply nested or invalid Markdown ASTs.
      }
    }
    html.open("pre").open("code");
    LeanSecurity.appendVisibleText(html, markdown, CLASS_TROJAN_CHAR);
    html.close("code").close("pre");
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
