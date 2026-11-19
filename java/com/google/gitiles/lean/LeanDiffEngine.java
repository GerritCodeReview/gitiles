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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

  private LeanDiffEngine() {}

  /**
   * Tokenizes a Lean 4 line for intra-line diffing, preserving Unicode symbols, guillemets,
   * decimal/float/radix literals, and guaranteeing lossless {@code String.join("", tokens).equals(
   * line)} in linear time.
   */
  public static ImmutableList<String> tokenizeLeanDiffWords(String line) {
    checkNotNull(line, "line");
    if (line.isEmpty()) {
      return ImmutableList.of();
    }
    ImmutableList.Builder<String> tokens = ImmutableList.builder();
    int i = 0;
    int len = line.length();
    while (i < len) {
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
    ImmutableList<LeanCodeLine> listA = ImmutableList.copyOf(checkNotNull(linesA, "linesA"));
    ImmutableList<LeanCodeLine> listB = ImmutableList.copyOf(checkNotNull(linesB, "linesB"));

    List<String> textsA = new ArrayList<>(listA.size());
    for (LeanCodeLine cl : listA) {
      textsA.add(cl.text());
    }
    List<String> textsB = new ArrayList<>(listB.size());
    for (LeanCodeLine cl : listB) {
      textsB.add(cl.text());
    }

    ImmutableList<AlignedHomologousItem> aligned =
        SequenceAligner.alignHomologousItems(
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

          ImmutableList<String> wordsA = tokenizeLeanDiffWords(leftLine.text());
          ImmutableList<String> wordsB = tokenizeLeanDiffWords(rightLine.text());
          ImmutableList<InlineDiffSegment> rawDiff =
              SequenceAligner.computeSequenceDiff(wordsA, wordsB);
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
    ImmutableList<LeanBlock> listA = ImmutableList.copyOf(checkNotNull(blocksA, "blocksA"));
    ImmutableList<LeanBlock> listB = ImmutableList.copyOf(checkNotNull(blocksB, "blocksB"));
    int m = listA.size();
    int n = listB.size();
    if (m == 0 && n == 0) {
      return ImmutableList.of();
    }
    if (m == 0) {
      ImmutableList.Builder<AlignedLeanRow> added = ImmutableList.builder();
      for (LeanBlock b : listB) {
        added.add(makeAddedRow(b));
      }
      return added.build();
    }
    if (n == 0) {
      ImmutableList.Builder<AlignedLeanRow> deleted = ImmutableList.builder();
      for (LeanBlock a : listA) {
        deleted.add(makeDeletedRow(a));
      }
      return deleted.build();
    }

    // Fast-path common prefix and suffix trimming for large proofs.
    int prefixLen = 0;
    while (prefixLen < m
        && prefixLen < n
        && listA.get(prefixLen).raw().equals(listB.get(prefixLen).raw())) {
      prefixLen++;
    }
    int suffixLen = 0;
    while (suffixLen < m - prefixLen
        && suffixLen < n - prefixLen
        && listA.get(m - 1 - suffixLen).raw().equals(listB.get(n - 1 - suffixLen).raw())) {
      suffixLen++;
    }

    ImmutableList.Builder<AlignedLeanRow> rows = ImmutableList.builder();
    for (int p = 0; p < prefixLen; p++) {
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
        java.util.Collections.reverse(anchorPairs);
      } else {
        // Linear-time Patience-style anchor matching for very large diffs.
        List<String> keysA = new ArrayList<>(mm);
        for (LeanBlock b : midA) {
          keysA.add(b.raw());
        }
        List<String> keysB = new ArrayList<>(nn);
        for (LeanBlock b : midB) {
          keysB.add(b.raw());
        }
        anchorPairs = SequenceAligner.computeLinearExactAnchors(keysA, keysB);
      }

      int lastA = 0;
      int lastB = 0;
      for (Anchor anchor : anchorPairs) {
        processBlockInterval(midA, midB, lastA, anchor.aIdx(), lastB, anchor.bIdx(), rows);
        rows.add(makeUnchangedRow(midA.get(anchor.aIdx()), midB.get(anchor.bIdx())));
        lastA = anchor.aIdx() + 1;
        lastB = anchor.bIdx() + 1;
      }
      processBlockInterval(midA, midB, lastA, mm, lastB, nn, rows);
    }

    for (int s = 0; s < suffixLen; s++) {
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
    return alignStructureFields(fieldsA, fieldsB).fields();
  }

  static LeanDeclarationDiffData buildDeclarationDiffData(
      LeanDeclaration declA, LeanDeclaration declB) {
    String docA = declA.docComment() != null ? declA.docComment() : "";
    String docB = declB.docComment() != null ? declB.docComment() : "";
    boolean docChanged = !docA.equals(docB);
    @Nullable String leftDoc = docA.isEmpty() ? null : docA;
    @Nullable String rightDoc = docB.isEmpty() ? null : docB;

    int sigFinalDepthA = LeanHighlighter.computeFinalBlockCommentDepth(declA.signatureLines(), 0);
    int sigFinalDepthB = LeanHighlighter.computeFinalBlockCommentDepth(declB.signatureLines(), 0);
    ImmutableList<LeanAlignedCodeLine> signatureLines =
        alignLeanCodeLines(declA.signatureLines(), declB.signatureLines(), false);
    boolean signatureChanged = hasAnyLineChanged(signatureLines);

    boolean inTactic = declA.isTacticProof() || declB.isTacticProof();
    ImmutableList<LeanAlignedCodeLine> bodyLines =
        alignLeanCodeLines(
            declA.bodyLines(), declB.bodyLines(), inTactic, sigFinalDepthA, sigFinalDepthB, false);
    boolean bodyChanged = hasAnyLineChanged(bodyLines);

    @Nullable ImmutableList<LeanAlignedField> fields = null;
    boolean fieldsChanged = false;
    if (declA.fields() != null && declB.fields() != null) {
      AlignedFieldsResult alignedFields = alignStructureFields(declA.fields(), declB.fields());
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
    String rowKey = "mod-" + blockA.id() + "-" + blockB.id();
    @Nullable TextDiffData moduleDocDiff = null;
    @Nullable TextDiffData bannerDiff = null;
    @Nullable ImmutableList<LeanAlignedCodeLine> lineDiff = null;
    @Nullable LeanDeclarationDiffData declDiff = null;

    if (blockA.type() == LeanBlockType.MODULE_DOC && blockB.type() == LeanBlockType.MODULE_DOC) {
      String leftText = blockA.markdownContent() != null ? blockA.markdownContent() : "";
      String rightText = blockB.markdownContent() != null ? blockB.markdownContent() : "";
      moduleDocDiff = new TextDiffData(leftText, rightText, !leftText.equals(rightText));
    } else if (blockA.type() == LeanBlockType.SECTION_BANNER
        && blockB.type() == LeanBlockType.SECTION_BANNER) {
      String leftText = blockA.markdownContent() != null ? blockA.markdownContent() : "";
      String rightText = blockB.markdownContent() != null ? blockB.markdownContent() : "";
      bannerDiff = new TextDiffData(leftText, rightText, !leftText.equals(rightText));
    } else if (blockA.type() == LeanBlockType.IMPORTS && blockB.type() == LeanBlockType.IMPORTS) {
      lineDiff = alignLeanCodeLines(blockA.importLines(), blockB.importLines(), false);
    } else if (blockA.type() == LeanBlockType.DIRECTIVE
        && blockB.type() == LeanBlockType.DIRECTIVE) {
      lineDiff = alignLeanCodeLines(blockA.directiveLines(), blockB.directiveLines(), false);
    } else if (blockA.type() == LeanBlockType.COMMENT && blockB.type() == LeanBlockType.COMMENT) {
      lineDiff =
          alignLeanCodeLines(blockA.commentLines(), blockB.commentLines(), false, 0, 0, true);
    } else if (blockA.type() == LeanBlockType.DECLARATION
        && blockB.type() == LeanBlockType.DECLARATION
        && blockA.decl() != null
        && blockB.decl() != null) {
      declDiff = buildDeclarationDiffData(blockA.decl(), blockB.decl());
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
    double dist =
        SequenceAligner.computeNormalizedEditDistance(
            wordsA, wordsB, LeanSyntax::isLeanContentToken);
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
      List<LeanStructureField> fieldsA, List<LeanStructureField> fieldsB) {
    List<String> keysA = new ArrayList<>(fieldsA.size());
    for (LeanStructureField f : fieldsA) {
      keysA.add(structureFieldKey(f));
    }
    List<String> keysB = new ArrayList<>(fieldsB.size());
    for (LeanStructureField f : fieldsB) {
      keysB.add(structureFieldKey(f));
    }
    ImmutableList<AlignedHomologousItem> aligned =
        SequenceAligner.alignHomologousItems(
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
          out.add(
              new LeanAlignedField(
                  DiffBlockStatus.UNCHANGED,
                  leftField,
                  rightField,
                  wholeLineSegments(leftField.typeText(), /* edited= */ false),
                  wholeLineSegments(rightField.typeText(), /* edited= */ false),
                  false));
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

          ImmutableList<InlineEditSegment> leftTypeSegments;
          ImmutableList<InlineEditSegment> rightTypeSegments;
          if (leftField.typeText().equals(rightField.typeText())) {
            leftTypeSegments = wholeLineSegments(leftField.typeText(), /* edited= */ false);
            rightTypeSegments = wholeLineSegments(rightField.typeText(), /* edited= */ false);
          } else {
            ImmutableList<InlineDiffSegment> segs =
                SequenceAligner.computeSequenceDiff(
                    tokenizeLeanDiffWords(leftField.typeText()),
                    tokenizeLeanDiffWords(rightField.typeText()));
            leftTypeSegments = buildSideInlineSegments(segs, InlineDiffSegment.Type.DELETED);
            rightTypeSegments = buildSideInlineSegments(segs, InlineDiffSegment.Type.ADDED);
          }

          String leftComment = leftField.comment() != null ? leftField.comment() : "";
          String rightComment = rightField.comment() != null ? rightField.comment() : "";
          boolean commentChanged = !leftComment.equals(rightComment);

          out.add(
              new LeanAlignedField(
                  DiffBlockStatus.MODIFIED,
                  leftField,
                  rightField,
                  leftTypeSegments,
                  rightTypeSegments,
                  commentChanged));
        }
      }
    }

    return new AlignedFieldsResult(out.build(), changed);
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
      ImmutableList.Builder<AlignedLeanRow> rows) {
    ImmutableList<LeanBlock> unA = midA.subList(startA, endA);
    ImmutableList<LeanBlock> unB = midB.subList(startB, endB);
    int uA = unA.size();
    int uB = unB.size();
    if (uA == 0 && uB == 0) {
      return;
    }
    if (uA == 0) {
      for (LeanBlock b : unB) {
        rows.add(makeAddedRow(b));
      }
      return;
    }
    if (uB == 0) {
      for (LeanBlock a : unA) {
        rows.add(makeDeletedRow(a));
      }
      return;
    }

    // Guard quadratic interval pairing for huge completely-rewritten sections.
    if ((long) uA * uB > SequenceAligner.MAX_ALIGN_ITEMS_CELLS) {
      Map<String, Integer> bByKey = new HashMap<>();
      for (int b = 0; b < uB; b++) {
        @Nullable String key = getDeclarationIdentityKey(unB.get(b));
        if (key != null) {
          bByKey.putIfAbsent(key, b);
        }
      }
      int bCursor = 0;
      for (int a = 0; a < uA; a++) {
        @Nullable String key = getDeclarationIdentityKey(unA.get(a));
        @Nullable Integer matchedB = key != null ? bByKey.get(key) : null;
        if (matchedB != null && matchedB >= bCursor) {
          while (bCursor < matchedB) {
            rows.add(makeAddedRow(unB.get(bCursor++)));
          }
          rows.add(buildPairedRow(unA.get(a), unB.get(matchedB)));
          bCursor = matchedB + 1;
        } else {
          rows.add(makeDeletedRow(unA.get(a)));
        }
      }
      while (bCursor < uB) {
        rows.add(makeAddedRow(unB.get(bCursor++)));
      }
      return;
    }

    List<ImmutableList<String>> wordsA = new ArrayList<>(uA);
    for (LeanBlock b : unA) {
      wordsA.add(tokenizeLeanDiffWords(b.raw()));
    }
    List<ImmutableList<String>> wordsB = new ArrayList<>(uB);
    for (LeanBlock b : unB) {
      wordsB.add(tokenizeLeanDiffWords(b.raw()));
    }
    boolean isSingleton = uA == 1 && uB == 1;

    double[][] pairScore = new double[uA][uB];
    for (int a = 0; a < uA; a++) {
      for (int b = 0; b < uB; b++) {
        pairScore[a][b] =
            blockPairScore(unA.get(a), unB.get(b), wordsA.get(a), wordsB.get(b), isSingleton);
      }
    }

    double[][] subDp = new double[uA + 1][uB + 1];
    for (int a = 0; a < uA; a++) {
      for (int b = 0; b < uB; b++) {
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
      if (a > 0
          && b > 0
          && pairScore[a - 1][b - 1] >= 0
          && Math.abs(subDp[a][b] - (subDp[a - 1][b - 1] + pairScore[a - 1][b - 1])) < 1e-9) {
        intervalRows.add(buildPairedRow(unA.get(a - 1), unB.get(b - 1)));
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
