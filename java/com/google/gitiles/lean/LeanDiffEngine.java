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

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.collect.ImmutableList;
import com.google.gitiles.lean.SequenceAligner.Anchor;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import javax.annotation.Nullable;

/**
 * Semantic Lean 4 diff engine: lossless diff-word tokenization, two-tier code-line alignment,
 * structure-field alignment, declaration diffing, block alignment, and summary computation.
 */
public final class LeanDiffEngine {

  /**
   * Multi-char ASCII operators matched by {@code LEAN_DIFF_WORD_RE} in exact left-to-right regex
   * alternation order.
   */
  private static final ImmutableList<String> DIFF_MULTI_CHAR_OPERATORS =
      ImmutableList.of(
          ":=", "=>", "->", "<-", "<->", "<;>", "|||", "&&&", "^^^", "<<<", ">>>", "|>", "<|");

  private record AlignedFieldsResult(ImmutableList<LeanAlignedField> fields, boolean changed) {}

  private static final class MutableSegment {
    final String text;
    boolean edited;

    MutableSegment(String text, boolean edited) {
      this.text = text;
      this.edited = edited;
    }
  }

  private static final long FILE_BLOCK_INTERVAL_CELL_BUDGET = 10_000L;
  private static final long FILE_HOMOLOGOUS_PAIR_CELL_BUDGET = 40_000L;
  private static final long FILE_HOMOLOGOUS_EDIT_CELL_BUDGET = 1_500_000L;
  private static final long FILE_HOMOLOGOUS_OVERLAP_WORK_BUDGET = 4_000_000L;
  private static final long FILE_INLINE_DIFF_CELL_BUDGET = 2_000_000L;
  private static final long FILE_SINGLETON_PAIR_BUDGET = 20_000L;

  /** Per-file diff cell budgets shared across all intervals and declarations of one diff call. */
  private static final class DiffBudget {
    long remainingBlockIntervalCells = FILE_BLOCK_INTERVAL_CELL_BUDGET;
    long remainingBlockSingletons = FILE_SINGLETON_PAIR_BUDGET;
    @Nullable final SequenceAligner.AbortChecker abort;
    final SequenceAligner.HomologousBudget homologousBudget;
    final SequenceAligner.InlineDiffBudget inlineDiffBudget;

    DiffBudget(@Nullable SequenceAligner.AbortChecker abort) {
      this.abort = abort;
      this.homologousBudget =
          new SequenceAligner.HomologousBudget(
              FILE_HOMOLOGOUS_PAIR_CELL_BUDGET,
              FILE_HOMOLOGOUS_EDIT_CELL_BUDGET,
              FILE_SINGLETON_PAIR_BUDGET,
              FILE_HOMOLOGOUS_OVERLAP_WORK_BUDGET,
              abort);
      this.inlineDiffBudget =
          new SequenceAligner.InlineDiffBudget(FILE_INLINE_DIFF_CELL_BUDGET, abort);
    }
  }

  private LeanDiffEngine() {}

  /**
   * Tokenizes a Lean 4 line for intra-line diffing, preserving Unicode symbols, guillemets,
   * decimal/float/radix literals, and guaranteeing lossless {@code String.join("", tokens).equals(
   * line)} in linear time.
   */
  public static ImmutableList<String> tokenizeLeanDiffWords(String line) {
    return tokenizeLeanDiffWords(line, null);
  }

  private static ImmutableList<String> tokenizeLeanDiffWords(
      String line, @Nullable SequenceAligner.AbortChecker abort) {
    checkNotNull(line, "line");
    if (line.isEmpty()) {
      return ImmutableList.of();
    }
    ImmutableList.Builder<String> tokens = ImmutableList.builder();
    int i = 0;
    int len = line.length();
    while (i < len) {
      SequenceAligner.tickAbort(abort);
      char ch = line.charAt(i);

      // Guillemet-escaped identifier `«[^«»\n]+»`
      if (ch == '«') {
        int guillemetEnd = matchSingleLineGuillemet(line, i);
        if (guillemetEnd > 0) {
          tokens.add(line.substring(i, guillemetEnd));
          i = guillemetEnd;
          continue;
        }
      }

      // Radix or decimal/float literal
      if (ch >= '0' && ch <= '9') {
        int numLen = matchDiffNumberToken(line, i);
        if (numLen > 0) {
          tokens.add(line.substring(i, i + numLen));
          i += numLen;
          continue;
        }
      }

      int cp = line.codePointAt(i);
      int cpLen = Character.charCount(cp);

      // Identifier `[\p{L}_][\p{L}\p{N}_'?!₀-₉⁰-⁹]*`
      if (isDiffIdentStart(cp)) {
        int j = i + cpLen;
        while (j < len) {
          int nextCp = line.codePointAt(j);
          if (!isDiffIdentPart(nextCp)) {
            break;
          }
          j += Character.charCount(nextCp);
        }
        tokens.add(line.substring(i, j));
        i = j;
        continue;
      }

      // Whitespace run `\s+`
      if (SequenceAligner.isWhitespaceCodePoint(cp)) {
        int j = i + cpLen;
        while (j < len) {
          int nextCp = line.codePointAt(j);
          if (!SequenceAligner.isWhitespaceCodePoint(nextCp)) {
            break;
          }
          j += Character.charCount(nextCp);
        }
        tokens.add(line.substring(i, j));
        i = j;
        continue;
      }

      // Unicode operators and bullet `·`
      String chStr = line.substring(i, i + cpLen);
      if (cp == '·' || LeanSyntax.isUnicodeOperator(chStr)) {
        tokens.add(chStr);
        i += cpLen;
        continue;
      }

      // Multi-char ASCII operators
      String matchedOp = matchDiffMultiCharOp(line, i);
      if (matchedOp != null) {
        tokens.add(matchedOp);
        i += matchedOp.length();
        continue;
      }

      // Lossless single code point fallback
      tokens.add(chStr);
      i += cpLen;
    }
    return tokens.build();
  }

  /**
   * Aligns two sequences of Lean code lines with two-tier delta-style inline diff segments while
   * tracking block-comment depths.
   */
  public static ImmutableList<LeanAlignedCodeLine> alignLeanCodeLines(
      List<LeanCodeLine> linesA, List<LeanCodeLine> linesB) {
    return alignLeanCodeLines(linesA, linesB, false, 0, 0, false);
  }

  /**
   * Aligns two sequences of Lean code lines with two-tier delta-style inline diff segments using
   * the given tactic context.
   */
  public static ImmutableList<LeanAlignedCodeLine> alignLeanCodeLines(
      List<LeanCodeLine> linesA, List<LeanCodeLine> linesB, boolean inTacticContext) {
    return alignLeanCodeLines(linesA, linesB, inTacticContext, 0, 0, false);
  }

  /**
   * Aligns two sequences of Lean code lines with two-tier delta-style inline diff segments while
   * propagating nested block-comment depths.
   */
  public static ImmutableList<LeanAlignedCodeLine> alignLeanCodeLines(
      List<LeanCodeLine> linesA,
      List<LeanCodeLine> linesB,
      boolean inTacticContext,
      int initialLeftCommentDepth,
      int initialRightCommentDepth,
      boolean forceComment) {
    return alignLeanCodeLines(
        linesA,
        linesB,
        inTacticContext,
        initialLeftCommentDepth,
        initialRightCommentDepth,
        forceComment,
        null);
  }

  private static ImmutableList<LeanAlignedCodeLine> alignLeanCodeLines(
      List<LeanCodeLine> linesA,
      List<LeanCodeLine> linesB,
      boolean inTacticContext,
      int initialLeftCommentDepth,
      int initialRightCommentDepth,
      boolean forceComment,
      @Nullable DiffBudget budget) {
    SequenceAligner.AbortChecker abort = budget != null ? budget.abort : null;
    SequenceAligner.checkAbortNow(abort);
    ImmutableList<LeanCodeLine> listA = ImmutableList.copyOf(checkNotNull(linesA, "linesA"));
    ImmutableList<LeanCodeLine> listB = ImmutableList.copyOf(checkNotNull(linesB, "linesB"));

    List<String> textsA = new ArrayList<>(listA.size());
    for (LeanCodeLine cl : listA) {
      SequenceAligner.tickAbort(abort);
      textsA.add(cl.text());
    }
    List<String> textsB = new ArrayList<>(listB.size());
    for (LeanCodeLine cl : listB) {
      SequenceAligner.tickAbort(abort);
      textsB.add(cl.text());
    }

    ImmutableList<AlignedHomologousItem> aligned =
        budget != null
            ? SequenceAligner.alignHomologousItems(
                textsA,
                textsB,
                line -> tokenizeLeanDiffWords(line, abort),
                SequenceAligner.DEFAULT_MAX_HOMOLOGOUS_DISTANCE,
                LeanSyntax::isLeanContentToken,
                budget.homologousBudget)
            : SequenceAligner.alignHomologousItems(
                textsA,
                textsB,
                LeanDiffEngine::tokenizeLeanDiffWords,
                SequenceAligner.DEFAULT_MAX_HOMOLOGOUS_DISTANCE,
                LeanSyntax::isLeanContentToken);

    @Nullable
    int[] depthsA =
        forceComment
            ? null
            : LeanHighlighter.computeBlockCommentDepths(listA, initialLeftCommentDepth);
    @Nullable
    int[] depthsB =
        forceComment
            ? null
            : LeanHighlighter.computeBlockCommentDepths(listB, initialRightCommentDepth);

    ImmutableList.Builder<LeanAlignedCodeLine> result = ImmutableList.builder();
    for (AlignedHomologousItem item : aligned) {
      SequenceAligner.tickAbort(abort);
      switch (item.type()) {
        case UNCHANGED -> {
          int aIdx = checkNotNull(item.aIdx(), "aIdx");
          int bIdx = checkNotNull(item.bIdx(), "bIdx");
          LeanCodeLine leftLine = listA.get(aIdx);
          LeanCodeLine rightLine = listB.get(bIdx);
          int leftDepth = depthsA != null ? depthsA[aIdx] : 0;
          int rightDepth = depthsB != null ? depthsB[bIdx] : 0;
          result.add(
              new LeanAlignedCodeLine(
                  DiffBlockStatus.UNCHANGED,
                  leftLine,
                  rightLine,
                  wholeLineSegments(leftLine.text(), /* edited= */ false),
                  wholeLineSegments(rightLine.text(), /* edited= */ false),
                  leftDepth,
                  rightDepth,
                  inTacticContext,
                  forceComment));
        }
        case DELETED -> {
          int aIdx = checkNotNull(item.aIdx(), "aIdx");
          LeanCodeLine leftLine = listA.get(aIdx);
          int leftDepth = depthsA != null ? depthsA[aIdx] : 0;
          result.add(
              new LeanAlignedCodeLine(
                  DiffBlockStatus.DELETED,
                  leftLine,
                  null,
                  wholeLineSegments(leftLine.text(), /* edited= */ false),
                  ImmutableList.of(),
                  leftDepth,
                  0,
                  inTacticContext,
                  forceComment));
        }
        case ADDED -> {
          int bIdx = checkNotNull(item.bIdx(), "bIdx");
          LeanCodeLine rightLine = listB.get(bIdx);
          int rightDepth = depthsB != null ? depthsB[bIdx] : 0;
          result.add(
              new LeanAlignedCodeLine(
                  DiffBlockStatus.ADDED,
                  null,
                  rightLine,
                  ImmutableList.of(),
                  wholeLineSegments(rightLine.text(), /* edited= */ false),
                  0,
                  rightDepth,
                  inTacticContext,
                  forceComment));
        }
        case MODIFIED -> {
          int aIdx = checkNotNull(item.aIdx(), "aIdx");
          int bIdx = checkNotNull(item.bIdx(), "bIdx");
          LeanCodeLine leftLine = listA.get(aIdx);
          LeanCodeLine rightLine = listB.get(bIdx);
          int leftDepth = depthsA != null ? depthsA[aIdx] : 0;
          int rightDepth = depthsB != null ? depthsB[bIdx] : 0;

          ImmutableList<String> wordsA = tokenizeLeanDiffWords(leftLine.text(), abort);
          ImmutableList<String> wordsB = tokenizeLeanDiffWords(rightLine.text(), abort);
          ImmutableList<InlineDiffSegment> rawDiff =
              SequenceAligner.computeSequenceDiff(
                  wordsA, wordsB, budget != null ? budget.inlineDiffBudget : null);
          int commonNonWs = 0;
          for (InlineDiffSegment seg : rawDiff) {
            if (seg.type() == InlineDiffSegment.Type.COMMON
                && !SequenceAligner.isAllWhitespace(seg.text())) {
              commonNonWs++;
            }
          }

          ImmutableList<InlineEditSegment> leftSegments;
          ImmutableList<InlineEditSegment> rightSegments;
          if (commonNonWs < 2) {
            leftSegments = wholeLineSegments(leftLine.text(), /* edited= */ true);
            rightSegments = wholeLineSegments(rightLine.text(), /* edited= */ true);
          } else {
            leftSegments = buildSideInlineSegments(rawDiff, InlineDiffSegment.Type.DELETED);
            rightSegments = buildSideInlineSegments(rawDiff, InlineDiffSegment.Type.ADDED);
          }

          result.add(
              new LeanAlignedCodeLine(
                  DiffBlockStatus.MODIFIED,
                  leftLine,
                  rightLine,
                  leftSegments,
                  rightSegments,
                  leftDepth,
                  rightDepth,
                  inTacticContext,
                  forceComment));
        }
      }
    }
    return result.build();
  }

  /**
   * Aligns base (A) and revision (B) Lean 4 semantic blocks into diff rows using prefix/suffix
   * trimming, exact-match anchors, and homologous interval DP.
   */
  public static ImmutableList<AlignedLeanRow> alignLeanBlocks(
      List<LeanBlock> blocksA, List<LeanBlock> blocksB) {
    return alignLeanBlocks(blocksA, blocksB, null);
  }

  /**
   * Aligns base (A) and revision (B) Lean 4 semantic blocks into diff rows while honoring an
   * optional cooperative abort hook.
   *
   * <p>When {@code shouldAbort} returns {@code true}, alignment aborts promptly across interval,
   * homologous, and inline DP loops and returns an empty list rather than partial rows.
   */
  public static ImmutableList<AlignedLeanRow> alignLeanBlocks(
      List<LeanBlock> blocksA, List<LeanBlock> blocksB, @Nullable BooleanSupplier shouldAbort) {
    checkNotNull(blocksA, "blocksA");
    checkNotNull(blocksB, "blocksB");
    SequenceAligner.AbortChecker abort =
        shouldAbort != null ? new SequenceAligner.AbortChecker(shouldAbort) : null;
    if (abort != null) {
      try {
        return alignLeanBlocksInternal(blocksA, blocksB, new DiffBudget(abort));
      } catch (SequenceAligner.AbortException aborted) {
        return ImmutableList.of();
      }
    }
    return alignLeanBlocksInternal(blocksA, blocksB, new DiffBudget(null));
  }

  private static ImmutableList<AlignedLeanRow> alignLeanBlocksInternal(
      List<LeanBlock> blocksA, List<LeanBlock> blocksB, DiffBudget budget) {
    SequenceAligner.checkAbortNow(budget.abort);
    ImmutableList<LeanBlock> listA = ImmutableList.copyOf(blocksA);
    ImmutableList<LeanBlock> listB = ImmutableList.copyOf(blocksB);
    int m = listA.size();
    int n = listB.size();
    if (m == 0 && n == 0) {
      return ImmutableList.of();
    }
    if (m == 0) {
      ImmutableList.Builder<AlignedLeanRow> added = ImmutableList.builder();
      for (LeanBlock b : listB) {
        SequenceAligner.tickAbort(budget.abort);
        added.add(makeAddedRow(b));
      }
      return added.build();
    }
    if (n == 0) {
      ImmutableList.Builder<AlignedLeanRow> deleted = ImmutableList.builder();
      for (LeanBlock a : listA) {
        SequenceAligner.tickAbort(budget.abort);
        deleted.add(makeDeletedRow(a));
      }
      return deleted.build();
    }

    // Fast-path common prefix and suffix trimming for large proofs.
    int prefixLen = 0;
    while (prefixLen < m
        && prefixLen < n
        && listA.get(prefixLen).raw().equals(listB.get(prefixLen).raw())) {
      SequenceAligner.tickAbort(budget.abort);
      prefixLen++;
    }
    int suffixLen = 0;
    while (suffixLen < m - prefixLen
        && suffixLen < n - prefixLen
        && listA.get(m - 1 - suffixLen).raw().equals(listB.get(n - 1 - suffixLen).raw())) {
      SequenceAligner.tickAbort(budget.abort);
      suffixLen++;
    }

    ImmutableList.Builder<AlignedLeanRow> rows = ImmutableList.builder();
    for (int p = 0; p < prefixLen; p++) {
      SequenceAligner.tickAbort(budget.abort);
      rows.add(makeUnchangedRow(listA.get(p), listB.get(p)));
    }

    ImmutableList<LeanBlock> midA = listA.subList(prefixLen, m - suffixLen);
    ImmutableList<LeanBlock> midB = listB.subList(prefixLen, n - suffixLen);
    int mm = midA.size();
    int nn = midB.size();

    if (mm > 0 || nn > 0) {
      List<Anchor> anchorPairs;
      if ((long) mm * nn <= SequenceAligner.MAX_SEQUENCE_DIFF_CELLS) {
        // Exact-match LCS DP on middle region using flat int[].
        int cols = nn + 1;
        int[] dp = new int[(mm + 1) * cols];
        for (int i = 0; i < mm; i++) {
          String rawA = midA.get(i).raw();
          int rowOffset = i * cols;
          int nextRowOffset = (i + 1) * cols;
          for (int j = 0; j < nn; j++) {
            SequenceAligner.tickAbort(budget.abort);
            if (rawA.equals(midB.get(j).raw())) {
              dp[nextRowOffset + j + 1] = dp[rowOffset + j] + 1;
            } else {
              int left = dp[nextRowOffset + j];
              int up = dp[rowOffset + j + 1];
              dp[nextRowOffset + j + 1] = Math.max(left, up);
            }
          }
        }
        anchorPairs = new ArrayList<>();
        int i = mm;
        int j = nn;
        while (i > 0 && j > 0) {
          SequenceAligner.tickAbort(budget.abort);
          if (midA.get(i - 1).raw().equals(midB.get(j - 1).raw())) {
            anchorPairs.add(new Anchor(i - 1, j - 1));
            i--;
            j--;
          } else if (dp[i * cols + (j - 1)] >= dp[(i - 1) * cols + j]) {
            j--;
          } else {
            i--;
          }
        }
        Collections.reverse(anchorPairs);
      } else {
        // Linear-time Patience-style anchor matching for very large diffs.
        List<String> keysA = new ArrayList<>(mm);
        for (LeanBlock b : midA) {
          SequenceAligner.tickAbort(budget.abort);
          keysA.add(b.raw());
        }
        List<String> keysB = new ArrayList<>(nn);
        for (LeanBlock b : midB) {
          SequenceAligner.tickAbort(budget.abort);
          keysB.add(b.raw());
        }
        anchorPairs = SequenceAligner.computeLinearExactAnchors(keysA, keysB, budget.abort);
      }

      int lastA = 0;
      int lastB = 0;
      for (Anchor anchor : anchorPairs) {
        SequenceAligner.checkAbortNow(budget.abort);
        processBlockInterval(midA, midB, lastA, anchor.aIdx(), lastB, anchor.bIdx(), rows, budget);
        rows.add(makeUnchangedRow(midA.get(anchor.aIdx()), midB.get(anchor.bIdx())));
        lastA = anchor.aIdx() + 1;
        lastB = anchor.bIdx() + 1;
      }
      processBlockInterval(midA, midB, lastA, mm, lastB, nn, rows, budget);
    }

    for (int s = 0; s < suffixLen; s++) {
      SequenceAligner.tickAbort(budget.abort);
      rows.add(makeUnchangedRow(listA.get(m - suffixLen + s), listB.get(n - suffixLen + s)));
    }

    return rows.build();
  }

  /** Computes declaration and proof-hygiene summary across base and revision blocks. */
  public static LeanDiffSummary computeLeanSummary(
      List<LeanBlock> blocksA, List<LeanBlock> blocksB, List<AlignedLeanRow> alignedRows) {
    ImmutableList<LeanBlock> listA = ImmutableList.copyOf(checkNotNull(blocksA, "blocksA"));
    ImmutableList<LeanBlock> listB = ImmutableList.copyOf(checkNotNull(blocksB, "blocksB"));
    checkNotNull(alignedRows, "alignedRows");

    int addedDeclarations = 0;
    int deletedDeclarations = 0;
    int modifiedDeclarations = 0;
    for (AlignedLeanRow row : alignedRows) {
      if (row.status() == DiffBlockStatus.ADDED
          && row.rightBlock() != null
          && row.rightBlock().type() == LeanBlockType.DECLARATION) {
        addedDeclarations++;
      } else if (row.status() == DiffBlockStatus.DELETED
          && row.leftBlock() != null
          && row.leftBlock().type() == LeanBlockType.DECLARATION) {
        deletedDeclarations++;
      } else if (row.status() == DiffBlockStatus.MODIFIED
          && ((row.leftBlock() != null && row.leftBlock().type() == LeanBlockType.DECLARATION)
              || (row.rightBlock() != null
                  && row.rightBlock().type() == LeanBlockType.DECLARATION))) {
        modifiedDeclarations++;
      }
    }

    return new LeanDiffSummary(
        LeanHygiene.count(listA),
        LeanHygiene.count(listB),
        !listA.isEmpty(),
        !listB.isEmpty(),
        addedDeclarations,
        deletedDeclarations,
        modifiedDeclarations);
  }

  static ImmutableList<InlineEditSegment> buildSideInlineSegments(
      List<InlineDiffSegment> segments, InlineDiffSegment.Type editType) {
    List<MutableSegment> sideSegs = new ArrayList<>();
    boolean hasPending = false;
    boolean pendingEdited = false;
    StringBuilder pendingText = new StringBuilder();

    for (InlineDiffSegment seg : segments) {
      boolean segEdited;
      if (seg.type() == InlineDiffSegment.Type.COMMON) {
        segEdited = false;
      } else if (seg.type() == editType) {
        segEdited = true;
      } else {
        continue;
      }
      if (seg.text().isEmpty()) {
        continue;
      }
      if (!hasPending) {
        hasPending = true;
        pendingEdited = segEdited;
        pendingText.append(seg.text());
      } else if (pendingEdited == segEdited) {
        pendingText.append(seg.text());
      } else {
        sideSegs.add(new MutableSegment(pendingText.toString(), pendingEdited));
        pendingText.setLength(0);
        pendingEdited = segEdited;
        pendingText.append(seg.text());
      }
    }
    if (hasPending) {
      sideSegs.add(new MutableSegment(pendingText.toString(), pendingEdited));
    }

    // Coalesce pure-whitespace common runs sandwiched between two edits on this side.
    for (int idx = 1; idx + 1 < sideSegs.size(); idx++) {
      MutableSegment cur = sideSegs.get(idx);
      if (!cur.edited
          && SequenceAligner.isAllWhitespace(cur.text)
          && sideSegs.get(idx - 1).edited
          && sideSegs.get(idx + 1).edited) {
        cur.edited = true;
      }
    }

    return coalesceMutableSegments(sideSegs);
  }

  static ImmutableList<LeanAlignedField> alignStructureFieldsForTesting(
      List<LeanStructureField> fieldsA, List<LeanStructureField> fieldsB) {
    return alignStructureFields(fieldsA, fieldsB, null).fields();
  }

  static LeanDeclarationDiffData buildDeclarationDiffData(
      LeanDeclaration declA, LeanDeclaration declB) {
    return buildDeclarationDiffData(declA, declB, null);
  }

  private static LeanDeclarationDiffData buildDeclarationDiffData(
      LeanDeclaration declA, LeanDeclaration declB, @Nullable DiffBudget budget) {
    String docA = declA.docComment() != null ? declA.docComment() : "";
    String docB = declB.docComment() != null ? declB.docComment() : "";
    boolean docChanged = !docA.equals(docB);
    @Nullable String leftDoc = docA.isEmpty() ? null : docA;
    @Nullable String rightDoc = docB.isEmpty() ? null : docB;

    int sigFinalDepthA = LeanHighlighter.computeFinalBlockCommentDepth(declA.signatureLines(), 0);
    int sigFinalDepthB = LeanHighlighter.computeFinalBlockCommentDepth(declB.signatureLines(), 0);
    ImmutableList<LeanAlignedCodeLine> signatureLines =
        alignLeanCodeLines(
            declA.signatureLines(), declB.signatureLines(), false, 0, 0, false, budget);
    boolean signatureChanged = hasAnyLineChanged(signatureLines);

    boolean inTactic = declA.isTacticProof() || declB.isTacticProof();
    ImmutableList<LeanAlignedCodeLine> bodyLines =
        alignLeanCodeLines(
            declA.bodyLines(),
            declB.bodyLines(),
            inTactic,
            sigFinalDepthA,
            sigFinalDepthB,
            false,
            budget);
    boolean bodyChanged = hasAnyLineChanged(bodyLines);

    @Nullable ImmutableList<LeanAlignedField> fields = null;
    boolean fieldsChanged = false;
    if (declA.fields() != null && declB.fields() != null) {
      AlignedFieldsResult alignedFields =
          alignStructureFields(declA.fields(), declB.fields(), budget);
      fields = alignedFields.fields();
      fieldsChanged = alignedFields.changed();
    }

    return new LeanDeclarationDiffData(
        leftDoc,
        rightDoc,
        docChanged,
        signatureLines,
        signatureChanged,
        bodyLines,
        bodyChanged,
        fields,
        fieldsChanged);
  }

  static AlignedLeanRow buildPairedRow(LeanBlock blockA, LeanBlock blockB) {
    return buildPairedRow(blockA, blockB, null);
  }

  private static AlignedLeanRow buildPairedRow(
      LeanBlock blockA, LeanBlock blockB, @Nullable DiffBudget budget) {
    String rowKey = "mod-" + blockA.id() + "-" + blockB.id();
    @Nullable TextDiffData moduleDocDiff = null;
    @Nullable TextDiffData bannerDiff = null;
    @Nullable ImmutableList<LeanAlignedCodeLine> lineDiff = null;
    @Nullable LeanDeclarationDiffData declDiff = null;
    boolean rawDiffers = !blockA.raw().equals(blockB.raw());

    if (blockA.type() == LeanBlockType.MODULE_DOC && blockB.type() == LeanBlockType.MODULE_DOC) {
      String leftText = blockA.markdownContent() != null ? blockA.markdownContent() : "";
      String rightText = blockB.markdownContent() != null ? blockB.markdownContent() : "";
      if (rawDiffers && leftText.equals(rightText)) {
        lineDiff =
            alignLeanCodeLines(
                splitBlockRawLines(blockA), splitBlockRawLines(blockB), false, 0, 0, false, budget);
      } else {
        moduleDocDiff = new TextDiffData(leftText, rightText, !leftText.equals(rightText));
      }
    } else if (blockA.type() == LeanBlockType.SECTION_BANNER
        && blockB.type() == LeanBlockType.SECTION_BANNER) {
      String leftText = blockA.markdownContent() != null ? blockA.markdownContent() : "";
      String rightText = blockB.markdownContent() != null ? blockB.markdownContent() : "";
      if (rawDiffers && leftText.equals(rightText)) {
        lineDiff =
            alignLeanCodeLines(
                splitBlockRawLines(blockA), splitBlockRawLines(blockB), false, 0, 0, false, budget);
      } else {
        bannerDiff = new TextDiffData(leftText, rightText, !leftText.equals(rightText));
      }
    } else if (blockA.type() == LeanBlockType.IMPORTS && blockB.type() == LeanBlockType.IMPORTS) {
      lineDiff =
          alignCodeBlockWithRawFallback(
              blockA, blockB, blockA.importLines(), blockB.importLines(), false, budget);
    } else if (blockA.type() == LeanBlockType.DIRECTIVE
        && blockB.type() == LeanBlockType.DIRECTIVE) {
      lineDiff =
          alignCodeBlockWithRawFallback(
              blockA, blockB, blockA.directiveLines(), blockB.directiveLines(), false, budget);
    } else if (blockA.type() == LeanBlockType.COMMENT && blockB.type() == LeanBlockType.COMMENT) {
      lineDiff =
          alignCodeBlockWithRawFallback(
              blockA, blockB, blockA.commentLines(), blockB.commentLines(), true, budget);
    } else if (blockA.type() == LeanBlockType.DECLARATION
        && blockB.type() == LeanBlockType.DECLARATION
        && blockA.decl() != null
        && blockB.decl() != null) {
      LeanDeclaration declA = blockA.decl();
      LeanDeclaration declB = blockB.decl();
      if (!declA.attributes().equals(declB.attributes())) {
        // Standalone @[...] attributes are omitted from LeanDeclarationDiffData; skip
        // structured alignment and fall back directly to raw line diffing.
        lineDiff =
            alignLeanCodeLines(
                splitBlockRawLines(blockA),
                splitBlockRawLines(blockB),
                declA.isTacticProof() || declB.isTacticProof(),
                0,
                0,
                false,
                budget);
      } else {
        LeanDeclarationDiffData candidate = buildDeclarationDiffData(declA, declB, budget);
        boolean headerMetaChanged =
            !declA.modifiers().equals(declB.modifiers()) || declA.kind() != declB.kind();
        boolean derivingChanged = !normalizedDeriving(declA).equals(normalizedDeriving(declB));
        boolean visibleDeclChange =
            candidate.docChanged()
                || candidate.signatureChanged()
                || candidate.bodyChanged()
                || candidate.fieldsChanged()
                || derivingChanged;
        if ((headerMetaChanged && !candidate.signatureChanged())
            || (rawDiffers && !visibleDeclChange)) {
          // Header metadata edits omitted from signatureLines or whitespace/comment edits not
          // surfaced by LeanDeclarationDiffData fall back to raw line alignment so every source
          // change stays visible.
          lineDiff =
              alignLeanCodeLines(
                  splitBlockRawLines(blockA),
                  splitBlockRawLines(blockB),
                  declA.isTacticProof() || declB.isTacticProof(),
                  0,
                  0,
                  false,
                  budget);
        } else {
          declDiff = candidate;
        }
      }
    } else if (rawDiffers) {
      lineDiff =
          alignLeanCodeLines(
              splitBlockRawLines(blockA), splitBlockRawLines(blockB), false, 0, 0, false, budget);
    }

    return new AlignedLeanRow(
        rowKey,
        DiffBlockStatus.MODIFIED,
        blockA,
        blockB,
        blockA.startLine(),
        blockA.endLine(),
        blockB.startLine(),
        blockB.endLine(),
        moduleDocDiff,
        bannerDiff,
        lineDiff,
        declDiff);
  }

  static AlignedLeanRow makeUnchangedRow(LeanBlock blockA, LeanBlock blockB) {
    return new AlignedLeanRow(
        "eq-" + blockA.id() + "-" + blockB.id(),
        DiffBlockStatus.UNCHANGED,
        blockA,
        blockB,
        blockA.startLine(),
        blockA.endLine(),
        blockB.startLine(),
        blockB.endLine(),
        null,
        null,
        null,
        null);
  }

  static AlignedLeanRow makeDeletedRow(LeanBlock blockA) {
    return new AlignedLeanRow(
        "del-" + blockA.id(),
        DiffBlockStatus.DELETED,
        blockA,
        null,
        blockA.startLine(),
        blockA.endLine(),
        null,
        null,
        null,
        null,
        null,
        null);
  }

  static AlignedLeanRow makeAddedRow(LeanBlock blockB) {
    return new AlignedLeanRow(
        "add-" + blockB.id(),
        DiffBlockStatus.ADDED,
        null,
        blockB,
        null,
        null,
        blockB.startLine(),
        blockB.endLine(),
        null,
        null,
        null,
        null);
  }

  static @Nullable String getDeclarationIdentityKey(LeanBlock block) {
    if (block.type() == LeanBlockType.DECLARATION
        && block.decl() != null
        && !block.decl().name().isEmpty()) {
      return block.decl().kind().keyword() + ":" + block.decl().name();
    }
    return null;
  }

  static double blockPairScore(
      LeanBlock blockA,
      LeanBlock blockB,
      List<String> wordsA,
      List<String> wordsB,
      boolean isSingletonInterval) {
    return blockPairScore(
        blockA,
        blockB,
        SequenceAligner.filterTokens(wordsA, LeanSyntax::isLeanContentToken),
        SequenceAligner.filterTokens(wordsB, LeanSyntax::isLeanContentToken),
        isSingletonInterval,
        null,
        null,
        -1,
        -1);
  }

  private static double blockPairScore(
      LeanBlock blockA,
      LeanBlock blockB,
      SequenceAligner.FilteredTokens filteredA,
      SequenceAligner.FilteredTokens filteredB,
      boolean isSingletonInterval,
      @Nullable DiffBudget budget,
      @Nullable SequenceAligner.GapEditScorer gapScorer,
      int idxA,
      int idxB) {
    if (blockA.type() != blockB.type()) {
      return -1.0;
    }
    // Same declaration kind + name strongly pairs even if the proof body was rewritten.
    @Nullable String idA = getDeclarationIdentityKey(blockA);
    @Nullable String idB = getDeclarationIdentityKey(blockB);
    if (idA != null && idA.equals(idB)) {
      return 1.9;
    }
    // Same declaration name across kind change (e.g. lemma -> theorem).
    if (blockA.type() == LeanBlockType.DECLARATION
        && blockB.type() == LeanBlockType.DECLARATION
        && blockA.decl() != null
        && blockB.decl() != null
        && !blockA.decl().name().isEmpty()
        && blockA.decl().name().equals(blockB.decl().name())) {
      return 1.7;
    }
    double maxDistance =
        (isSingletonInterval
                || blockA.type() == LeanBlockType.MODULE_DOC
                || blockA.type() == LeanBlockType.IMPORTS)
            ? 1.0
            : SequenceAligner.DEFAULT_MAX_HOMOLOGOUS_DISTANCE;
    double dist =
        SequenceAligner.computeNormalizedEditDistance(
            filteredA,
            filteredB,
            maxDistance,
            budget != null ? budget.homologousBudget : null,
            gapScorer,
            idxA,
            idxB);
    if (dist <= SequenceAligner.DEFAULT_MAX_HOMOLOGOUS_DISTANCE) {
      return 1.0 + (1.0 - dist);
    }
    // Heavily-edited module docstrings or import blocks in the same diff interval should still
    // pair side-by-side for structured inner diffing.
    if (blockA.type() == LeanBlockType.MODULE_DOC || blockA.type() == LeanBlockType.IMPORTS) {
      return 0.3 + 0.5 * (1.0 - dist);
    }
    if (isSingletonInterval
        && (blockA.type() != LeanBlockType.DECLARATION
            || (blockA.decl() != null
                && blockB.decl() != null
                && blockA.decl().kind() == blockB.decl().kind()
                && blockA.decl().name().isEmpty()
                && blockB.decl().name().isEmpty()))) {
      return 0.01 + 0.1 * (1.0 - dist);
    }
    return -1.0;
  }

  private static AlignedFieldsResult alignStructureFields(
      List<LeanStructureField> fieldsA,
      List<LeanStructureField> fieldsB,
      @Nullable DiffBudget budget) {
    SequenceAligner.AbortChecker abort = budget != null ? budget.abort : null;
    SequenceAligner.checkAbortNow(abort);
    List<String> keysA = new ArrayList<>(fieldsA.size());
    for (LeanStructureField f : fieldsA) {
      SequenceAligner.tickAbort(abort);
      keysA.add(structureFieldKey(f));
    }
    List<String> keysB = new ArrayList<>(fieldsB.size());
    for (LeanStructureField f : fieldsB) {
      SequenceAligner.tickAbort(abort);
      keysB.add(structureFieldKey(f));
    }
    ImmutableList<AlignedHomologousItem> aligned =
        budget != null
            ? SequenceAligner.alignHomologousItems(
                keysA,
                keysB,
                text -> tokenizeLeanDiffWords(text, abort),
                0.75,
                LeanSyntax::isLeanContentToken,
                budget.homologousBudget)
            : SequenceAligner.alignHomologousItems(
                keysA,
                keysB,
                LeanDiffEngine::tokenizeLeanDiffWords,
                0.75,
                LeanSyntax::isLeanContentToken);

    ImmutableList.Builder<LeanAlignedField> out = ImmutableList.builder();
    boolean changed = false;

    for (AlignedHomologousItem item : aligned) {
      switch (item.type()) {
        case UNCHANGED -> {
          LeanStructureField leftField = fieldsA.get(checkNotNull(item.aIdx(), "aIdx"));
          LeanStructureField rightField = fieldsB.get(checkNotNull(item.bIdx(), "bIdx"));
          if (!leftField.rawLine().equals(rightField.rawLine())
              || !Objects.equals(leftField.defaultValue(), rightField.defaultValue())
              || !Objects.equals(leftField.comment(), rightField.comment())
              || !leftField.name().equals(rightField.name())
              || !leftField.typeText().equals(rightField.typeText())) {
            changed = true;
            out.add(buildModifiedField(leftField, rightField, budget));
          } else {
            out.add(
                new LeanAlignedField(
                    DiffBlockStatus.UNCHANGED,
                    leftField,
                    rightField,
                    wholeLineSegments(leftField.typeText(), /* edited= */ false),
                    wholeLineSegments(rightField.typeText(), /* edited= */ false),
                    false));
          }
        }
        case DELETED -> {
          changed = true;
          LeanStructureField leftField = fieldsA.get(checkNotNull(item.aIdx(), "aIdx"));
          out.add(
              new LeanAlignedField(
                  DiffBlockStatus.DELETED,
                  leftField,
                  null,
                  wholeLineSegments(leftField.typeText(), /* edited= */ true),
                  ImmutableList.of(),
                  leftField.comment() != null && !leftField.comment().isEmpty()));
        }
        case ADDED -> {
          changed = true;
          LeanStructureField rightField = fieldsB.get(checkNotNull(item.bIdx(), "bIdx"));
          out.add(
              new LeanAlignedField(
                  DiffBlockStatus.ADDED,
                  null,
                  rightField,
                  ImmutableList.of(),
                  wholeLineSegments(rightField.typeText(), /* edited= */ true),
                  rightField.comment() != null && !rightField.comment().isEmpty()));
        }
        case MODIFIED -> {
          changed = true;
          LeanStructureField leftField = fieldsA.get(checkNotNull(item.aIdx(), "aIdx"));
          LeanStructureField rightField = fieldsB.get(checkNotNull(item.bIdx(), "bIdx"));
          out.add(buildModifiedField(leftField, rightField, budget));
        }
      }
    }

    return new AlignedFieldsResult(out.build(), changed);
  }

  private static LeanAlignedField buildModifiedField(
      LeanStructureField leftField, LeanStructureField rightField, @Nullable DiffBudget budget) {
    ImmutableList<InlineEditSegment> leftTypeSegments;
    ImmutableList<InlineEditSegment> rightTypeSegments;
    if (leftField.typeText().equals(rightField.typeText())) {
      leftTypeSegments = wholeLineSegments(leftField.typeText(), /* edited= */ false);
      rightTypeSegments = wholeLineSegments(rightField.typeText(), /* edited= */ false);
    } else {
      SequenceAligner.AbortChecker abort = budget != null ? budget.abort : null;
      ImmutableList<InlineDiffSegment> segs =
          SequenceAligner.computeSequenceDiff(
              tokenizeLeanDiffWords(leftField.typeText(), abort),
              tokenizeLeanDiffWords(rightField.typeText(), abort),
              budget != null ? budget.inlineDiffBudget : null);
      leftTypeSegments = buildSideInlineSegments(segs, InlineDiffSegment.Type.DELETED);
      rightTypeSegments = buildSideInlineSegments(segs, InlineDiffSegment.Type.ADDED);
    }

    String leftComment = leftField.comment() != null ? leftField.comment() : "";
    String rightComment = rightField.comment() != null ? rightField.comment() : "";
    boolean commentChanged = !leftComment.equals(rightComment);

    return new LeanAlignedField(
        DiffBlockStatus.MODIFIED,
        leftField,
        rightField,
        leftTypeSegments,
        rightTypeSegments,
        commentChanged);
  }

  private static ImmutableList<LeanAlignedCodeLine> alignCodeBlockWithRawFallback(
      LeanBlock blockA,
      LeanBlock blockB,
      List<LeanCodeLine> linesA,
      List<LeanCodeLine> linesB,
      boolean forceComment,
      @Nullable DiffBudget budget) {
    ImmutableList<LeanAlignedCodeLine> aligned =
        alignLeanCodeLines(linesA, linesB, false, 0, 0, forceComment, budget);
    if (!blockA.raw().equals(blockB.raw()) && !hasAnyLineChanged(aligned)) {
      return alignLeanCodeLines(
          splitBlockRawLines(blockA),
          splitBlockRawLines(blockB),
          false,
          0,
          0,
          forceComment,
          budget);
    }
    return aligned;
  }

  private static ImmutableList<String> normalizedDeriving(LeanDeclaration decl) {
    ImmutableList<String> clause = decl.derivingClause();
    return clause != null ? clause : ImmutableList.of();
  }

  private static ImmutableList<LeanCodeLine> splitBlockRawLines(LeanBlock block) {
    String raw = block.raw();
    if (raw.isEmpty()) {
      return ImmutableList.of();
    }
    ImmutableList.Builder<LeanCodeLine> lines = ImmutableList.builder();
    int startLine = Math.max(1, block.startLine());
    int lineOffset = 0;
    int pos = 0;
    int len = raw.length();
    while (pos <= len) {
      int nl = raw.indexOf('\n', pos);
      String lineText = nl >= 0 ? raw.substring(pos, nl) : raw.substring(pos);
      lines.add(new LeanCodeLine(startLine + lineOffset, lineText));
      if (nl < 0) {
        break;
      }
      pos = nl + 1;
      lineOffset++;
    }
    return lines.build();
  }

  private static String structureFieldKey(LeanStructureField field) {
    StringBuilder sb = new StringBuilder();
    sb.append(field.name()).append(" : ").append(field.typeText());
    if (field.defaultValue() != null && !field.defaultValue().isEmpty()) {
      sb.append(" := ").append(field.defaultValue());
    }
    sb.append(' ').append(field.comment() != null ? field.comment() : "");
    return sb.toString();
  }

  private static void processBlockInterval(
      ImmutableList<LeanBlock> midA,
      ImmutableList<LeanBlock> midB,
      int startA,
      int endA,
      int startB,
      int endB,
      ImmutableList.Builder<AlignedLeanRow> rows,
      DiffBudget budget) {
    SequenceAligner.checkAbortNow(budget.abort);
    ImmutableList<LeanBlock> unA = midA.subList(startA, endA);
    ImmutableList<LeanBlock> unB = midB.subList(startB, endB);
    int uA = unA.size();
    int uB = unB.size();
    if (uA == 0 && uB == 0) {
      return;
    }
    if (uA == 0) {
      for (LeanBlock b : unB) {
        SequenceAligner.tickAbort(budget.abort);
        rows.add(makeAddedRow(b));
      }
      return;
    }
    if (uB == 0) {
      for (LeanBlock a : unA) {
        SequenceAligner.tickAbort(budget.abort);
        rows.add(makeDeletedRow(a));
      }
      return;
    }

    long intervalCells = (long) uA * uB;
    boolean allowQuadratic =
        intervalCells <= SequenceAligner.MAX_ALIGN_ITEMS_CELLS
            && (intervalCells <= budget.remainingBlockIntervalCells
                || (intervalCells == 1 && budget.remainingBlockSingletons > 0));
    if (!allowQuadratic) {
      // Guard quadratic interval pairing for huge completely-rewritten or multi-interval files.
      Map<String, Integer> bByKey = new HashMap<>();
      for (int b = 0; b < uB; b++) {
        SequenceAligner.tickAbort(budget.abort);
        @Nullable String key = getDeclarationIdentityKey(unB.get(b));
        if (key != null) {
          bByKey.putIfAbsent(key, b);
        }
      }
      int bCursor = 0;
      for (int a = 0; a < uA; a++) {
        SequenceAligner.tickAbort(budget.abort);
        @Nullable String key = getDeclarationIdentityKey(unA.get(a));
        @Nullable Integer matchedB = key != null ? bByKey.get(key) : null;
        if (matchedB != null && matchedB >= bCursor) {
          while (bCursor < matchedB) {
            SequenceAligner.tickAbort(budget.abort);
            rows.add(makeAddedRow(unB.get(bCursor++)));
          }
          SequenceAligner.checkAbortNow(budget.abort);
          rows.add(buildPairedRow(unA.get(a), unB.get(matchedB), budget));
          bCursor = matchedB + 1;
        } else {
          rows.add(makeDeletedRow(unA.get(a)));
        }
      }
      while (bCursor < uB) {
        SequenceAligner.tickAbort(budget.abort);
        rows.add(makeAddedRow(unB.get(bCursor++)));
      }
      return;
    }
    if (intervalCells <= budget.remainingBlockIntervalCells) {
      budget.remainingBlockIntervalCells -= intervalCells;
    } else {
      budget.remainingBlockSingletons--;
    }

    List<SequenceAligner.FilteredTokens> filteredA = new ArrayList<>(Collections.nCopies(uA, null));
    List<SequenceAligner.FilteredTokens> filteredB = new ArrayList<>(Collections.nCopies(uB, null));
    SequenceAligner.GapEditScorer gapScorer =
        (uA > 0 && uB > 0 && (uA > 1 || uB > 1)) ? new SequenceAligner.GapEditScorer(uA, uB) : null;
    boolean isSingleton = uA == 1 && uB == 1;

    double[][] pairScore = new double[uA][uB];
    for (int a = 0; a < uA; a++) {
      SequenceAligner.checkAbortNow(budget.abort);
      LeanBlock blockA = unA.get(a);
      for (int b = 0; b < uB; b++) {
        SequenceAligner.tickAbort(budget.abort);
        LeanBlock blockB = unB.get(b);
        pairScore[a][b] =
            scoreIntervalPair(
                blockA,
                blockB,
                a,
                b,
                filteredA,
                filteredB,
                unA,
                unB,
                isSingleton,
                budget,
                gapScorer);
      }
    }

    double[][] subDp = new double[uA + 1][uB + 1];
    for (int a = 0; a < uA; a++) {
      for (int b = 0; b < uB; b++) {
        SequenceAligner.tickAbort(budget.abort);
        double best = Math.max(subDp[a][b + 1], subDp[a + 1][b]);
        if (pairScore[a][b] >= 0) {
          best = Math.max(best, subDp[a][b] + pairScore[a][b]);
        }
        subDp[a + 1][b + 1] = best;
      }
    }

    List<AlignedLeanRow> intervalRows = new ArrayList<>();
    int a = uA;
    int b = uB;
    while (a > 0 || b > 0) {
      SequenceAligner.tickAbort(budget.abort);
      if (a > 0
          && b > 0
          && pairScore[a - 1][b - 1] >= 0
          && Math.abs(subDp[a][b] - (subDp[a - 1][b - 1] + pairScore[a - 1][b - 1])) < 1e-9) {
        SequenceAligner.checkAbortNow(budget.abort);
        intervalRows.add(buildPairedRow(unA.get(a - 1), unB.get(b - 1), budget));
        a--;
        b--;
      } else if (b > 0 && (a == 0 || subDp[a][b - 1] >= subDp[a - 1][b])) {
        intervalRows.add(makeAddedRow(unB.get(b - 1)));
        b--;
      } else {
        intervalRows.add(makeDeletedRow(unA.get(a - 1)));
        a--;
      }
    }
    for (int k = intervalRows.size() - 1; k >= 0; k--) {
      rows.add(intervalRows.get(k));
    }
  }

  private static double scoreIntervalPair(
      LeanBlock blockA,
      LeanBlock blockB,
      int idxA,
      int idxB,
      List<SequenceAligner.FilteredTokens> filteredA,
      List<SequenceAligner.FilteredTokens> filteredB,
      List<LeanBlock> unA,
      List<LeanBlock> unB,
      boolean isSingleton,
      DiffBudget budget,
      @Nullable SequenceAligner.GapEditScorer gapScorer) {
    if (blockA.type() != blockB.type()) {
      return -1.0;
    }
    @Nullable String idA = getDeclarationIdentityKey(blockA);
    @Nullable String idB = getDeclarationIdentityKey(blockB);
    if (idA != null && idA.equals(idB)) {
      return 1.9;
    }
    if (blockA.type() == LeanBlockType.DECLARATION
        && blockB.type() == LeanBlockType.DECLARATION
        && blockA.decl() != null
        && blockB.decl() != null
        && !blockA.decl().name().isEmpty()
        && blockA.decl().name().equals(blockB.decl().name())) {
      return 1.7;
    }
    SequenceAligner.FilteredTokens tokA = filteredA.get(idxA);
    if (tokA == null) {
      tokA =
          SequenceAligner.filterTokens(
              tokenizeLeanDiffWords(unA.get(idxA).raw(), budget.abort),
              LeanSyntax::isLeanContentToken);
      filteredA.set(idxA, tokA);
    }
    SequenceAligner.FilteredTokens tokB = filteredB.get(idxB);
    if (tokB == null) {
      tokB =
          SequenceAligner.filterTokens(
              tokenizeLeanDiffWords(unB.get(idxB).raw(), budget.abort),
              LeanSyntax::isLeanContentToken);
      filteredB.set(idxB, tokB);
    }
    return blockPairScore(blockA, blockB, tokA, tokB, isSingleton, budget, gapScorer, idxA, idxB);
  }

  private static boolean hasAnyLineChanged(List<LeanAlignedCodeLine> lines) {
    for (LeanAlignedCodeLine line : lines) {
      if (line.status() != DiffBlockStatus.UNCHANGED) {
        return true;
      }
    }
    return false;
  }

  private static ImmutableList<InlineEditSegment> wholeLineSegments(String text, boolean edited) {
    if (text.isEmpty()) {
      return ImmutableList.of();
    }
    return ImmutableList.of(new InlineEditSegment(text, edited));
  }

  private static ImmutableList<InlineEditSegment> coalesceMutableSegments(
      List<MutableSegment> segments) {
    if (segments.isEmpty()) {
      return ImmutableList.of();
    }
    ImmutableList.Builder<InlineEditSegment> out = ImmutableList.builder();
    boolean pendingEdited = segments.get(0).edited;
    StringBuilder pendingText = new StringBuilder(segments.get(0).text);
    for (int i = 1; i < segments.size(); i++) {
      MutableSegment cur = segments.get(i);
      if (cur.edited == pendingEdited) {
        pendingText.append(cur.text);
      } else {
        out.add(new InlineEditSegment(pendingText.toString(), pendingEdited));
        pendingEdited = cur.edited;
        pendingText.setLength(0);
        pendingText.append(cur.text);
      }
    }
    out.add(new InlineEditSegment(pendingText.toString(), pendingEdited));
    return out.build();
  }

  private static int matchSingleLineGuillemet(String line, int start) {
    int len = line.length();
    int j = start + 1;
    while (j < len) {
      char c = line.charAt(j);
      if (c == '«' || c == '\n') {
        return -1;
      }
      if (c == '»') {
        return j > start + 1 ? j + 1 : -1;
      }
      j++;
    }
    return -1;
  }

  private static int matchDiffNumberToken(String line, int start) {
    int len = line.length();
    char first = line.charAt(start);
    if (first == '0' && start + 2 < len) {
      int base = radixForPrefix(line.charAt(start + 1));
      if (base > 0 && isRadixDigitOrUnderscore(line.charAt(start + 2), base)) {
        int j = start + 3;
        while (j < len && isRadixDigitOrUnderscore(line.charAt(j), base)) {
          j++;
        }
        return j - start;
      }
    }
    int j = start + 1;
    while (j < len && isRadixDigitOrUnderscore(line.charAt(j), 10)) {
      j++;
    }
    if (j + 1 < len && line.charAt(j) == '.' && isRadixDigitOrUnderscore(line.charAt(j + 1), 10)) {
      j += 2;
      while (j < len && isRadixDigitOrUnderscore(line.charAt(j), 10)) {
        j++;
      }
    }
    return j - start;
  }

  private static int radixForPrefix(char prefix) {
    return switch (prefix) {
      case 'x', 'X' -> 16;
      case 'b', 'B' -> 2;
      case 'o', 'O' -> 8;
      default -> 0;
    };
  }

  private static boolean isRadixDigitOrUnderscore(char c, int base) {
    if (c == '_') {
      return true;
    }
    return switch (base) {
      case 2 -> c == '0' || c == '1';
      case 8 -> c >= '0' && c <= '7';
      case 10 -> c >= '0' && c <= '9';
      case 16 -> (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
      default -> false;
    };
  }

  private static @Nullable String matchDiffMultiCharOp(String line, int index) {
    for (String op : DIFF_MULTI_CHAR_OPERATORS) {
      if (line.startsWith(op, index)) {
        return op;
      }
    }
    return null;
  }

  private static boolean isDiffIdentStart(int cp) {
    return Character.isLetter(cp) || cp == '_';
  }

  private static boolean isDiffIdentPart(int cp) {
    int type = Character.getType(cp);
    return Character.isLetter(cp)
        || type == Character.DECIMAL_DIGIT_NUMBER
        || type == Character.LETTER_NUMBER
        || type == Character.OTHER_NUMBER
        || cp == '_'
        || cp == '\''
        || cp == '?'
        || cp == '!'
        || (cp >= 0x2080 && cp <= 0x2089)
        || (cp >= 0x2070 && cp <= 0x2079);
  }
}
