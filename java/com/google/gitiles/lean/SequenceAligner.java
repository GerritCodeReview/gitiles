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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.collect.ImmutableList;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Predicate;
import javax.annotation.Nullable;

/**
 * Bounded sequence diffing, normalized token edit distance, Patience exact-anchor alignment, and
 * homologous item alignment utilities.
 */
public final class SequenceAligner {
  /**
   * Maximum normalized edit distance (0.0 = identical, 1.0 = completely disjoint) for two items to
   * be inferred as homologous modifications of one another.
   */
  public static final double DEFAULT_MAX_HOMOLOGOUS_DISTANCE = 0.6;

  public static final int MAX_SEQUENCE_DIFF_CELLS = 250_000;
  public static final int MAX_EDIT_DISTANCE_CELLS = 40_000;
  public static final int MAX_ALIGN_ITEMS_CELLS = 10_000;
  public static final int MAX_PATIENCE_DEPTH = 32;
  static final long DEFAULT_HOMOLOGOUS_EDIT_CELL_BUDGET = 1_500_000L;
  static final long DEFAULT_HOMOLOGOUS_OVERLAP_WORK_BUDGET = 4_000_000L;

  static final class AbortException extends RuntimeException {
    AbortException() {
      super(null, null, false, false);
    }
  }

  static final class AbortChecker {
    private final BooleanSupplier shouldAbort;
    private int ticks;

    AbortChecker(BooleanSupplier shouldAbort) {
      this.shouldAbort = shouldAbort;
    }

    void checkNow() {
      if (shouldAbort.getAsBoolean()) {
        throw new AbortException();
      }
    }

    void tick() {
      if ((++ticks & 1023) == 0 && shouldAbort.getAsBoolean()) {
        throw new AbortException();
      }
    }
  }

  static void checkAbortNow(@Nullable AbortChecker abort) {
    if (abort != null) {
      abort.checkNow();
    }
  }

  static void tickAbort(@Nullable AbortChecker abort) {
    if (abort != null) {
      abort.tick();
    }
  }

  static final class InlineDiffBudget {
    long remainingCells;
    @Nullable final AbortChecker abort;

    InlineDiffBudget(long remainingCells) {
      this(remainingCells, null);
    }

    InlineDiffBudget(long remainingCells, @Nullable AbortChecker abort) {
      this.remainingCells = remainingCells;
      this.abort = abort;
    }

    boolean tryConsume(long cells) {
      if (cells <= remainingCells) {
        remainingCells -= cells;
        return true;
      }
      return false;
    }
  }

  static final class HomologousBudget {
    long remainingPairCells;
    long remainingEditCells;
    long remainingSingletonPairs;
    long remainingOverlapWork;
    @Nullable final AbortChecker abort;

    HomologousBudget(
        long remainingPairCells,
        long remainingEditCells,
        long remainingSingletonPairs,
        long remainingOverlapWork) {
      this(
          remainingPairCells,
          remainingEditCells,
          remainingSingletonPairs,
          remainingOverlapWork,
          null);
    }

    HomologousBudget(
        long remainingPairCells,
        long remainingEditCells,
        long remainingSingletonPairs,
        long remainingOverlapWork,
        @Nullable AbortChecker abort) {
      this.remainingPairCells = remainingPairCells;
      this.remainingEditCells = remainingEditCells;
      this.remainingSingletonPairs = remainingSingletonPairs;
      this.remainingOverlapWork = remainingOverlapWork;
      this.abort = abort;
    }

    boolean tryConsumeEditCells(long cells) {
      if (cells <= remainingEditCells) {
        remainingEditCells -= cells;
        return true;
      }
      return false;
    }

    boolean tryConsumeOverlapWork(long work) {
      if (work <= remainingOverlapWork) {
        remainingOverlapWork -= work;
        return true;
      }
      return false;
    }
  }

  /** Matched index pair produced by {@link #computeLinearExactAnchors}. */
  public record Anchor(int aIdx, int bIdx) {
    public Anchor {
      checkArgument(aIdx >= 0, "aIdx must be non-negative: %s", aIdx);
      checkArgument(bIdx >= 0, "bIdx must be non-negative: %s", bIdx);
    }
  }

  private sealed interface StackEntry permits EmitEntry, GapEntry {}

  private record EmitEntry(List<Anchor> pairs) implements StackEntry {}

  private record GapEntry(int aStart, int aEnd, int bStart, int bEnd, int depth)
      implements StackEntry {}

  private SequenceAligner() {}

  /** Computes an LCS-based diff between two token/line sequences, bounded by cell budget. */
  public static ImmutableList<InlineDiffSegment> computeSequenceDiff(
      List<String> seqA, List<String> seqB) {
    return computeSequenceDiff(seqA, seqB, null);
  }

  static ImmutableList<InlineDiffSegment> computeSequenceDiff(
      List<String> seqA, List<String> seqB, InlineDiffBudget budget) {
    AbortChecker abort = budget != null ? budget.abort : null;
    checkAbortNow(abort);
    ImmutableList<String> listA = ImmutableList.copyOf(checkNotNull(seqA, "seqA"));
    ImmutableList<String> listB = ImmutableList.copyOf(checkNotNull(seqB, "seqB"));
    int m = listA.size();
    int n = listB.size();
    if (m == 0 && n == 0) {
      return ImmutableList.of();
    }

    // Trim exact common prefix and suffix to keep near-identical large inputs O(N).
    int prefixLen = 0;
    while (prefixLen < m && prefixLen < n && listA.get(prefixLen).equals(listB.get(prefixLen))) {
      tickAbort(abort);
      prefixLen++;
    }
    int suffixLen = 0;
    while (suffixLen < m - prefixLen
        && suffixLen < n - prefixLen
        && listA.get(m - 1 - suffixLen).equals(listB.get(n - 1 - suffixLen))) {
      tickAbort(abort);
      suffixLen++;
    }

    ImmutableList.Builder<InlineDiffSegment> segments = ImmutableList.builder();
    for (int p = 0; p < prefixLen; p++) {
      tickAbort(abort);
      segments.add(new InlineDiffSegment(listA.get(p), InlineDiffSegment.Type.COMMON));
    }

    int midLenA = m - prefixLen - suffixLen;
    int midLenB = n - prefixLen - suffixLen;
    long cells = (long) midLenA * midLenB;

    if (midLenA == 0) {
      for (int j = 0; j < midLenB; j++) {
        tickAbort(abort);
        segments.add(new InlineDiffSegment(listB.get(prefixLen + j), InlineDiffSegment.Type.ADDED));
      }
    } else if (midLenB == 0) {
      for (int i = 0; i < midLenA; i++) {
        tickAbort(abort);
        segments.add(
            new InlineDiffSegment(listA.get(prefixLen + i), InlineDiffSegment.Type.DELETED));
      }
    } else if (cells > MAX_SEQUENCE_DIFF_CELLS || (budget != null && !budget.tryConsume(cells))) {
      // Bounded linear fallback for adversarial/huge unaligned sequences.
      for (int i = 0; i < midLenA; i++) {
        tickAbort(abort);
        segments.add(
            new InlineDiffSegment(listA.get(prefixLen + i), InlineDiffSegment.Type.DELETED));
      }
      for (int j = 0; j < midLenB; j++) {
        tickAbort(abort);
        segments.add(new InlineDiffSegment(listB.get(prefixLen + j), InlineDiffSegment.Type.ADDED));
      }
    } else {
      int cols = midLenB + 1;
      int[] dp = new int[(midLenA + 1) * cols];

      for (int i = 0; i < midLenA; i++) {
        String aVal = listA.get(prefixLen + i);
        int rowOffset = i * cols;
        int nextRowOffset = (i + 1) * cols;
        for (int j = 0; j < midLenB; j++) {
          tickAbort(abort);
          if (aVal.equals(listB.get(prefixLen + j))) {
            dp[nextRowOffset + j + 1] = dp[rowOffset + j] + 1;
          } else {
            int left = dp[nextRowOffset + j];
            int up = dp[rowOffset + j + 1];
            dp[nextRowOffset + j + 1] = Math.max(left, up);
          }
        }
      }

      List<InlineDiffSegment> midSegments = new ArrayList<>();
      int i = midLenA;
      int j = midLenB;
      while (i > 0 || j > 0) {
        tickAbort(abort);
        if (i > 0 && j > 0 && listA.get(prefixLen + i - 1).equals(listB.get(prefixLen + j - 1))) {
          midSegments.add(
              new InlineDiffSegment(listA.get(prefixLen + i - 1), InlineDiffSegment.Type.COMMON));
          i--;
          j--;
        } else if (j > 0 && (i == 0 || dp[i * cols + (j - 1)] >= dp[(i - 1) * cols + j])) {
          midSegments.add(
              new InlineDiffSegment(listB.get(prefixLen + j - 1), InlineDiffSegment.Type.ADDED));
          j--;
        } else {
          midSegments.add(
              new InlineDiffSegment(listA.get(prefixLen + i - 1), InlineDiffSegment.Type.DELETED));
          i--;
        }
      }
      for (int k = midSegments.size() - 1; k >= 0; k--) {
        tickAbort(abort);
        segments.add(midSegments.get(k));
      }
    }

    for (int s = 0; s < suffixLen; s++) {
      tickAbort(abort);
      segments.add(
          new InlineDiffSegment(listA.get(m - suffixLen + s), InlineDiffSegment.Type.COMMON));
    }

    return segments.build();
  }

  /**
   * Computes normalized token edit distance in {@code [0.0, 1.0]} using {@link
   * #defaultIsContentToken(String)}.
   */
  public static double computeNormalizedEditDistance(List<String> seqA, List<String> seqB) {
    return computeNormalizedEditDistance(seqA, seqB, SequenceAligner::defaultIsContentToken);
  }

  /**
   * Computes the normalized token edit distance in {@code [0.0, 1.0]} between two token sequences,
   * ignoring pure-whitespace and non-content punctuation tokens so spaces and punctuation do not
   * artificially inflate similarity between unrelated sequences.
   */
  public static double computeNormalizedEditDistance(
      List<String> seqA, List<String> seqB, Predicate<String> isContentToken) {
    return computeNormalizedEditDistance(seqA, seqB, isContentToken, null);
  }

  static double computeNormalizedEditDistance(
      List<String> seqA,
      List<String> seqB,
      Predicate<String> isContentToken,
      HomologousBudget budget) {
    checkNotNull(seqA, "seqA");
    checkNotNull(seqB, "seqB");
    checkNotNull(isContentToken, "isContentToken");

    FilteredTokens filteredA = filterTokens(seqA, isContentToken);
    FilteredTokens filteredB = filterTokens(seqB, isContentToken);
    if (filteredA.nonWs().isEmpty() && filteredB.nonWs().isEmpty()) {
      return 0.0;
    }
    if (filteredA.nonWs().isEmpty() || filteredB.nonWs().isEmpty()) {
      return 1.0;
    }

    return computeDistanceFromFiltered(filteredA, filteredB, 1.0, budget, null, -1, -1);
  }

  static double computeNormalizedEditDistance(
      FilteredTokens filteredA,
      FilteredTokens filteredB,
      double maxDistance,
      HomologousBudget budget,
      GapEditScorer scorer,
      int rowIdx,
      int colIdx) {
    return computeDistanceFromFiltered(
        filteredA, filteredB, maxDistance, budget, scorer, rowIdx, colIdx);
  }

  private static double computeDistanceFromFiltered(
      FilteredTokens filteredA,
      FilteredTokens filteredB,
      double maxDistance,
      HomologousBudget budget,
      GapEditScorer scorer,
      int rowIdx,
      int colIdx) {
    if (filteredA.nonWs().isEmpty() && filteredB.nonWs().isEmpty()) {
      return 0.0;
    }
    if (filteredA.nonWs().isEmpty() || filteredB.nonWs().isEmpty()) {
      return 1.0;
    }
    boolean useWords = !filteredA.words().isEmpty() || !filteredB.words().isEmpty();
    List<String> contentA = useWords ? filteredA.words() : filteredA.nonWs();
    List<String> contentB = useWords ? filteredB.words() : filteredB.nonWs();
    return computeDistanceFromContent(
        contentA, contentB, maxDistance, budget, scorer, rowIdx, colIdx);
  }

  record FilteredTokens(List<String> nonWs, List<String> words) {}

  static FilteredTokens filterTokens(List<String> seq, Predicate<String> isContentToken) {
    List<String> nonWs = new ArrayList<>(seq.size());
    List<String> words = new ArrayList<>(seq.size());
    for (String tok : seq) {
      checkNotNull(tok, "token");
      if (!isAllWhitespace(tok)) {
        nonWs.add(tok);
        if (isContentToken.test(tok)) {
          words.add(tok);
        }
      }
    }
    return new FilteredTokens(nonWs, words);
  }

  private static double computeDistanceFromContent(
      List<String> contentA,
      List<String> contentB,
      double maxDistance,
      HomologousBudget budget,
      GapEditScorer scorer,
      int rowIdx,
      int colIdx) {
    int lenA = contentA.size();
    int lenB = contentB.size();
    if (lenA == 0 || lenB == 0) {
      return 1.0;
    }

    int minLen = Math.min(lenA, lenB);
    int maxLen = Math.max(lenA, lenB);
    if (maxDistance < 1.0) {
      boolean canExtend = minLen >= 3 && maxLen <= (long) minLen * 5;
      if (!canExtend) {
        double maxPossibleDice = 1.0 - (2.0 * minLen) / ((double) lenA + lenB);
        if (maxPossibleDice > maxDistance) {
          return 1.0;
        }
      }
    }

    int prefix = 0;
    while (prefix < lenA && prefix < lenB && contentA.get(prefix).equals(contentB.get(prefix))) {
      prefix++;
    }
    int suffix = 0;
    while (suffix < lenA - prefix
        && suffix < lenB - prefix
        && contentA.get(lenA - 1 - suffix).equals(contentB.get(lenB - 1 - suffix))) {
      suffix++;
    }

    int midA = lenA - prefix - suffix;
    int midB = lenB - prefix - suffix;
    int commonCount;
    if (midA == 0 || midB == 0) {
      commonCount = prefix + suffix;
    } else {
      long cells = (long) midA * midB;
      AbortChecker abort = budget != null ? budget.abort : null;
      if (cells <= MAX_EDIT_DISTANCE_CELLS
          && (budget == null || budget.tryConsumeEditCells(cells))) {
        commonCount =
            prefix
                + suffix
                + computeLcsLength(contentA, prefix, midA, contentB, prefix, midB, abort);
      } else if (scorer != null && rowIdx >= 0 && colIdx >= 0) {
        int overlap = scorer.overlap(rowIdx, contentA, colIdx, contentB, budget);
        if (overlap < 0) {
          return 1.0;
        }
        commonCount = overlap;
      } else {
        commonCount =
            prefix
                + suffix
                + computeMultisetOverlap(contentA, prefix, midA, contentB, prefix, midB, abort);
      }
    }
    return scoreDistance(lenA, lenB, minLen, maxLen, commonCount);
  }

  private static double scoreDistance(int lenA, int lenB, int minLen, int maxLen, int commonCount) {
    double diceDistance = 1.0 - (2.0 * commonCount) / ((double) lenA + lenB);
    double overlapDistance = 1.0 - ((double) commonCount) / minLen;
    // If one sequence is an extension of the other (high overlap with the shorter sequence,
    // bounded length ratio, and at least 3 shared content tokens), treat them as homologous so
    // shared prefixes/clauses remain unhighlighted.
    if (commonCount >= 3 && maxLen <= (long) minLen * 5 && overlapDistance <= 0.4) {
      return Math.min(diceDistance, overlapDistance);
    }
    return diceDistance;
  }

  private static int computeLcsLength(
      List<String> seqA,
      int offA,
      int lenA,
      List<String> seqB,
      int offB,
      int lenB,
      @Nullable AbortChecker abort) {
    List<String> longList = lenA >= lenB ? seqA : seqB;
    int longOff = lenA >= lenB ? offA : offB;
    int rows = Math.max(lenA, lenB);
    List<String> shortList = lenA >= lenB ? seqB : seqA;
    int shortOff = lenA >= lenB ? offB : offA;
    int cols = Math.min(lenA, lenB);

    int[] prev = new int[cols + 1];
    int[] curr = new int[cols + 1];
    for (int i = 0; i < rows; i++) {
      String a = longList.get(longOff + i);
      for (int j = 0; j < cols; j++) {
        tickAbort(abort);
        if (a.equals(shortList.get(shortOff + j))) {
          curr[j + 1] = prev[j] + 1;
        } else {
          curr[j + 1] = Math.max(prev[j + 1], curr[j]);
        }
      }
      int[] tmp = prev;
      prev = curr;
      curr = tmp;
    }
    return prev[cols];
  }

  private static int computeMultisetOverlap(
      List<String> seqA,
      int offA,
      int lenA,
      List<String> seqB,
      int offB,
      int lenB,
      @Nullable AbortChecker abort) {
    List<String> shortSeq = lenA <= lenB ? seqA : seqB;
    int shortOff = lenA <= lenB ? offA : offB;
    int shortLen = Math.min(lenA, lenB);
    List<String> longSeq = lenA <= lenB ? seqB : seqA;
    int longOff = lenA <= lenB ? offB : offA;
    int longLen = Math.max(lenA, lenB);

    Map<String, Integer> counts = new HashMap<>();
    for (int i = 0; i < shortLen; i++) {
      tickAbort(abort);
      counts.merge(shortSeq.get(shortOff + i), 1, Integer::sum);
    }
    int common = 0;
    for (int j = 0; j < longLen && common < shortLen; j++) {
      tickAbort(abort);
      String tok = longSeq.get(longOff + j);
      Integer rem = counts.get(tok);
      if (rem != null && rem > 0) {
        common++;
        counts.put(tok, rem - 1);
      }
    }
    return common;
  }

  /**
   * Patience-style exact-match anchor finder for large sequences where {@code O(m * n)} DP exceeds
   * the cell budget.
   */
  public static ImmutableList<Anchor> computeLinearExactAnchors(
      List<String> keysA, List<String> keysB) {
    return computeLinearExactAnchors(keysA, keysB, null);
  }

  static ImmutableList<Anchor> computeLinearExactAnchors(
      List<String> keysA, List<String> keysB, @Nullable AbortChecker abort) {
    checkAbortNow(abort);
    ImmutableList<String> listA = ImmutableList.copyOf(checkNotNull(keysA, "keysA"));
    ImmutableList<String> listB = ImmutableList.copyOf(checkNotNull(keysB, "keysB"));
    int m = listA.size();
    int n = listB.size();
    if (m == 0 || n == 0) {
      return ImmutableList.of();
    }

    ImmutableList.Builder<Anchor> anchors = ImmutableList.builder();
    Deque<StackEntry> stack = new ArrayDeque<>();
    stack.push(new GapEntry(0, m, 0, n, 0));

    while (!stack.isEmpty()) {
      tickAbort(abort);
      StackEntry entry = stack.pop();
      if (entry instanceof EmitEntry emit) {
        anchors.addAll(emit.pairs());
        continue;
      }

      GapEntry gap = (GapEntry) entry;
      int aStart = gap.aStart();
      int aEnd = gap.aEnd();
      int bStart = gap.bStart();
      int bEnd = gap.bEnd();
      int depth = gap.depth();

      while (aStart < aEnd && bStart < bEnd && listA.get(aStart).equals(listB.get(bStart))) {
        tickAbort(abort);
        anchors.add(new Anchor(aStart, bStart));
        aStart++;
        bStart++;
      }

      List<Anchor> suffixPairs = new ArrayList<>();
      while (aEnd > aStart && bEnd > bStart && listA.get(aEnd - 1).equals(listB.get(bEnd - 1))) {
        tickAbort(abort);
        aEnd--;
        bEnd--;
        suffixPairs.add(new Anchor(aEnd, bEnd));
      }
      if (!suffixPairs.isEmpty()) {
        Collections.reverse(suffixPairs);
        stack.push(new EmitEntry(suffixPairs));
      }

      int gapA = aEnd - aStart;
      int gapB = bEnd - bStart;
      if (gapA <= 0 || gapB <= 0) {
        continue;
      }

      List<Anchor> uniqueLis =
          depth < MAX_PATIENCE_DEPTH
              ? findUniqueLis(listA, listB, aStart, aEnd, bStart, bEnd, abort)
              : ImmutableList.of();
      if (!uniqueLis.isEmpty()) {
        List<StackEntry> subEntries = new ArrayList<>();
        int curA = aStart;
        int curB = bStart;
        for (Anchor u : uniqueLis) {
          if (curA < u.aIdx() && curB < u.bIdx()) {
            subEntries.add(new GapEntry(curA, u.aIdx(), curB, u.bIdx(), depth + 1));
          }
          subEntries.add(new EmitEntry(List.of(u)));
          curA = u.aIdx() + 1;
          curB = u.bIdx() + 1;
        }
        if (curA < aEnd && curB < bEnd) {
          subEntries.add(new GapEntry(curA, aEnd, curB, bEnd, depth + 1));
        }
        for (int idx = subEntries.size() - 1; idx >= 0; idx--) {
          stack.push(subEntries.get(idx));
        }
        continue;
      }

      if ((long) gapA * gapB <= MAX_ALIGN_ITEMS_CELLS) {
        int cols = gapB + 1;
        int[] dp = new int[(gapA + 1) * cols];
        for (int i = 0; i < gapA; i++) {
          String keyA = listA.get(aStart + i);
          int rowOff = i * cols;
          int nextRowOff = (i + 1) * cols;
          for (int j = 0; j < gapB; j++) {
            tickAbort(abort);
            if (keyA.equals(listB.get(bStart + j))) {
              dp[nextRowOff + j + 1] = dp[rowOff + j] + 1;
            } else {
              int left = dp[nextRowOff + j];
              int up = dp[rowOff + j + 1];
              dp[nextRowOff + j + 1] = Math.max(left, up);
            }
          }
        }
        List<Anchor> gapAnchors = new ArrayList<>();
        int i = gapA;
        int j = gapB;
        while (i > 0 && j > 0) {
          tickAbort(abort);
          if (listA.get(aStart + i - 1).equals(listB.get(bStart + j - 1))) {
            gapAnchors.add(new Anchor(aStart + i - 1, bStart + j - 1));
            i--;
            j--;
          } else if (dp[i * cols + (j - 1)] >= dp[(i - 1) * cols + j]) {
            j--;
          } else {
            i--;
          }
        }
        Collections.reverse(gapAnchors);
        anchors.addAll(gapAnchors);
        continue;
      }

      // Monotonic per-key index cursor fallback for huge residual gaps with zero unique keys
      // (strictly O(gapA + gapB) amortized).
      Map<String, List<Integer>> positionsByKey = new HashMap<>();
      for (int j = bStart; j < bEnd; j++) {
        tickAbort(abort);
        positionsByKey.computeIfAbsent(listB.get(j), k -> new ArrayList<>()).add(j);
      }
      Map<String, Integer> cursorByKey = new HashMap<>();
      int bCursor = bStart;
      for (int i = aStart; i < aEnd; i++) {
        tickAbort(abort);
        String k = listA.get(i);
        List<Integer> list = positionsByKey.get(k);
        if (list == null) {
          continue;
        }
        int posIdx = cursorByKey.getOrDefault(k, 0);
        while (posIdx < list.size() && list.get(posIdx) < bCursor) {
          posIdx++;
        }
        if (posIdx < list.size()) {
          int matchB = list.get(posIdx);
          anchors.add(new Anchor(i, matchB));
          bCursor = matchB + 1;
          cursorByKey.put(k, posIdx + 1);
        } else {
          cursorByKey.put(k, posIdx);
        }
      }
    }

    return anchors.build();
  }

  private static List<Anchor> findUniqueLis(
      List<String> keysA,
      List<String> keysB,
      int aStart,
      int aEnd,
      int bStart,
      int bEnd,
      @Nullable AbortChecker abort) {
    Map<String, Integer> countA = new HashMap<>();
    for (int i = aStart; i < aEnd; i++) {
      tickAbort(abort);
      countA.merge(keysA.get(i), 1, Integer::sum);
    }
    Map<String, Integer> posB = new HashMap<>();
    for (int j = bStart; j < bEnd; j++) {
      tickAbort(abort);
      String k = keysB.get(j);
      if (!Integer.valueOf(1).equals(countA.get(k))) {
        continue;
      }
      if (posB.containsKey(k)) {
        posB.put(k, -1);
      } else {
        posB.put(k, j);
      }
    }

    List<Anchor> pairs = new ArrayList<>();
    for (int i = aStart; i < aEnd; i++) {
      tickAbort(abort);
      Integer bIdx = posB.get(keysA.get(i));
      if (bIdx != null && bIdx >= 0) {
        pairs.add(new Anchor(i, bIdx));
      }
    }
    if (pairs.size() <= 1) {
      return pairs;
    }

    int[] tailPairIdx = new int[pairs.size()];
    int tailLen = 0;
    int[] prevPairIdx = new int[pairs.size()];
    Arrays.fill(prevPairIdx, -1);
    for (int idx = 0; idx < pairs.size(); idx++) {
      int bVal = pairs.get(idx).bIdx();
      int lo = 0;
      int hi = tailLen;
      while (lo < hi) {
        int mid = (lo + hi) >>> 1;
        if (pairs.get(tailPairIdx[mid]).bIdx() < bVal) {
          lo = mid + 1;
        } else {
          hi = mid;
        }
      }
      if (lo > 0) {
        prevPairIdx[idx] = tailPairIdx[lo - 1];
      }
      tailPairIdx[lo] = idx;
      if (lo == tailLen) {
        tailLen++;
      }
    }

    List<Anchor> lis = new ArrayList<>(tailLen);
    int cur = tailPairIdx[tailLen - 1];
    while (cur >= 0) {
      lis.add(pairs.get(cur));
      cur = prevPairIdx[cur];
    }
    Collections.reverse(lis);
    return lis;
  }

  /**
   * Aligns two lists of items using default word tokenization, {@link
   * #DEFAULT_MAX_HOMOLOGOUS_DISTANCE}, and {@link #defaultIsContentToken(String)}.
   */
  public static ImmutableList<AlignedHomologousItem> alignHomologousItems(
      List<String> itemsA, List<String> itemsB) {
    return alignHomologousItems(
        itemsA,
        itemsB,
        SequenceAligner::tokenizeSimpleWords,
        DEFAULT_MAX_HOMOLOGOUS_DISTANCE,
        SequenceAligner::defaultIsContentToken);
  }

  /**
   * Aligns two lists of items using the provided tokenizer, {@link
   * #DEFAULT_MAX_HOMOLOGOUS_DISTANCE}, and {@link #defaultIsContentToken(String)}.
   */
  public static ImmutableList<AlignedHomologousItem> alignHomologousItems(
      List<String> itemsA, List<String> itemsB, Function<String, List<String>> tokenizer) {
    return alignHomologousItems(
        itemsA,
        itemsB,
        tokenizer,
        DEFAULT_MAX_HOMOLOGOUS_DISTANCE,
        SequenceAligner::defaultIsContentToken);
  }

  /**
   * Aligns two lists of items using dynamic programming over normalized edit distance, pairing
   * items only when their distance is {@code <= maxDistance}.
   */
  public static ImmutableList<AlignedHomologousItem> alignHomologousItems(
      List<String> itemsA,
      List<String> itemsB,
      Function<String, List<String>> tokenizer,
      double maxDistance,
      Predicate<String> isContentToken) {
    return alignHomologousItems(
        itemsA,
        itemsB,
        tokenizer,
        maxDistance,
        isContentToken,
        new HomologousBudget(
            MAX_ALIGN_ITEMS_CELLS,
            DEFAULT_HOMOLOGOUS_EDIT_CELL_BUDGET,
            MAX_ALIGN_ITEMS_CELLS,
            DEFAULT_HOMOLOGOUS_OVERLAP_WORK_BUDGET));
  }

  static ImmutableList<AlignedHomologousItem> alignHomologousItems(
      List<String> itemsA,
      List<String> itemsB,
      Function<String, List<String>> tokenizer,
      double maxDistance,
      Predicate<String> isContentToken,
      HomologousBudget budget) {
    checkAbortNow(budget.abort);
    ImmutableList<String> listA = ImmutableList.copyOf(checkNotNull(itemsA, "itemsA"));
    ImmutableList<String> listB = ImmutableList.copyOf(checkNotNull(itemsB, "itemsB"));
    checkNotNull(tokenizer, "tokenizer");
    checkArgument(!Double.isNaN(maxDistance), "maxDistance must not be NaN");
    checkNotNull(isContentToken, "isContentToken");
    checkNotNull(budget, "budget");

    int m = listA.size();
    int n = listB.size();
    if (m == 0 && n == 0) {
      return ImmutableList.of();
    }

    // Trim exact common prefix and suffix first.
    int prefixLen = 0;
    while (prefixLen < m && prefixLen < n && listA.get(prefixLen).equals(listB.get(prefixLen))) {
      tickAbort(budget.abort);
      prefixLen++;
    }
    int suffixLen = 0;
    while (suffixLen < m - prefixLen
        && suffixLen < n - prefixLen
        && listA.get(m - 1 - suffixLen).equals(listB.get(n - 1 - suffixLen))) {
      tickAbort(budget.abort);
      suffixLen++;
    }

    ImmutableList.Builder<AlignedHomologousItem> result = ImmutableList.builder();
    for (int p = 0; p < prefixLen; p++) {
      tickAbort(budget.abort);
      result.add(AlignedHomologousItem.unchanged(listA.get(p), listB.get(p), p, p));
    }

    int mm = m - prefixLen - suffixLen;
    int nn = n - prefixLen - suffixLen;
    long wholeCells = (long) mm * nn;

    if (mm == 0) {
      for (int j = 0; j < nn; j++) {
        tickAbort(budget.abort);
        int bIdx = prefixLen + j;
        result.add(AlignedHomologousItem.added(listB.get(bIdx), bIdx));
      }
    } else if (nn == 0) {
      for (int i = 0; i < mm; i++) {
        tickAbort(budget.abort);
        int aIdx = prefixLen + i;
        result.add(AlignedHomologousItem.deleted(listA.get(aIdx), aIdx));
      }
    } else if (wholeCells > MAX_ALIGN_ITEMS_CELLS
        || (wholeCells > 1 && wholeCells > budget.remainingPairCells)
        || (wholeCells == 1
            && budget.remainingPairCells < 1
            && budget.remainingSingletonPairs <= 0)) {
      // Patience-style exact-anchor decomposition with a bounded fuzzy-DP cell budget across
      // residual gaps so edits near both ends of >100-line blocks keep word-level inline diffs
      // while staying bounded in worst-case cost.
      ImmutableList<String> midA = listA.subList(prefixLen, m - suffixLen);
      ImmutableList<String> midB = listB.subList(prefixLen, n - suffixLen);
      ImmutableList<Anchor> anchors = computeLinearExactAnchors(midA, midB, budget.abort);

      int aCursor = 0;
      int bCursor = 0;
      for (Anchor anchor : anchors) {
        checkAbortNow(budget.abort);
        processAlignGap(
            listA,
            listB,
            prefixLen,
            aCursor,
            anchor.aIdx(),
            bCursor,
            anchor.bIdx(),
            budget,
            tokenizer,
            maxDistance,
            isContentToken,
            result);
        int aIdx = prefixLen + anchor.aIdx();
        int bIdx = prefixLen + anchor.bIdx();
        result.add(AlignedHomologousItem.unchanged(listA.get(aIdx), listB.get(bIdx), aIdx, bIdx));
        aCursor = anchor.aIdx() + 1;
        bCursor = anchor.bIdx() + 1;
      }
      processAlignGap(
          listA,
          listB,
          prefixLen,
          aCursor,
          mm,
          bCursor,
          nn,
          budget,
          tokenizer,
          maxDistance,
          isContentToken,
          result);
    } else {
      if (wholeCells == 1) {
        if (budget.remainingPairCells >= 1) {
          budget.remainingPairCells -= 1;
        } else {
          budget.remainingSingletonPairs--;
        }
      } else {
        budget.remainingPairCells -= wholeCells;
      }
      alignHomologousGapWithFuzzyDp(
          listA,
          listB,
          prefixLen,
          mm,
          prefixLen,
          nn,
          tokenizer,
          maxDistance,
          isContentToken,
          budget,
          result);
    }

    for (int s = 0; s < suffixLen; s++) {
      int aIdx = m - suffixLen + s;
      int bIdx = n - suffixLen + s;
      result.add(AlignedHomologousItem.unchanged(listA.get(aIdx), listB.get(bIdx), aIdx, bIdx));
    }

    return result.build();
  }

  private static void processAlignGap(
      List<String> itemsA,
      List<String> itemsB,
      int prefixLen,
      int gapAStart,
      int gapAEnd,
      int gapBStart,
      int gapBEnd,
      HomologousBudget budget,
      Function<String, List<String>> tokenizer,
      double maxDistance,
      Predicate<String> isContentToken,
      ImmutableList.Builder<AlignedHomologousItem> result) {
    int gapA = gapAEnd - gapAStart;
    int gapB = gapBEnd - gapBStart;
    if (gapA == 0 && gapB == 0) {
      return;
    }
    if (gapA == 0) {
      for (int j = gapBStart; j < gapBEnd; j++) {
        int bIdx = prefixLen + j;
        result.add(AlignedHomologousItem.added(itemsB.get(bIdx), bIdx));
      }
      return;
    }
    if (gapB == 0) {
      for (int i = gapAStart; i < gapAEnd; i++) {
        int aIdx = prefixLen + i;
        result.add(AlignedHomologousItem.deleted(itemsA.get(aIdx), aIdx));
      }
      return;
    }
    long cells = (long) gapA * gapB;
    boolean allowed =
        cells <= MAX_ALIGN_ITEMS_CELLS
            && (cells <= budget.remainingPairCells
                || (cells == 1 && budget.remainingSingletonPairs > 0));
    if (allowed) {
      if (cells <= budget.remainingPairCells) {
        budget.remainingPairCells -= cells;
      } else {
        budget.remainingSingletonPairs--;
      }
      alignHomologousGapWithFuzzyDp(
          itemsA,
          itemsB,
          prefixLen + gapAStart,
          gapA,
          prefixLen + gapBStart,
          gapB,
          tokenizer,
          maxDistance,
          isContentToken,
          budget,
          result);
      return;
    }
    for (int i = gapAStart; i < gapAEnd; i++) {
      int aIdx = prefixLen + i;
      result.add(AlignedHomologousItem.deleted(itemsA.get(aIdx), aIdx));
    }
    for (int j = gapBStart; j < gapBEnd; j++) {
      int bIdx = prefixLen + j;
      result.add(AlignedHomologousItem.added(itemsB.get(bIdx), bIdx));
    }
  }

  /**
   * Aligns a contiguous sub-interval {@code [offsetA, offsetA + lenA) x [offsetB, offsetB + lenB)}
   * via fuzzy normalized edit distance DP, emitting paired {@link DiffBlockStatus#MODIFIED} items
   * when {@code distance <= maxDistance}.
   */
  private static void alignHomologousGapWithFuzzyDp(
      List<String> itemsA,
      List<String> itemsB,
      int offsetA,
      int lenA,
      int offsetB,
      int lenB,
      Function<String, List<String>> tokenizer,
      double maxDistance,
      Predicate<String> isContentToken,
      HomologousBudget budget,
      ImmutableList.Builder<AlignedHomologousItem> result) {
    List<String> sliceA = itemsA.subList(offsetA, offsetA + lenA);
    List<String> sliceB = itemsB.subList(offsetB, offsetB + lenB);
    List<FilteredTokens> filteredA = new ArrayList<>(lenA);
    for (String item : sliceA) {
      checkAbortNow(budget.abort);
      filteredA.add(filterTokens(tokenizer.apply(item), isContentToken));
    }
    List<FilteredTokens> filteredB = new ArrayList<>(lenB);
    for (String item : sliceB) {
      checkAbortNow(budget.abort);
      filteredB.add(filterTokens(tokenizer.apply(item), isContentToken));
    }

    GapEditScorer gapScorer =
        (lenA > 0 && lenB > 0 && (lenA > 1 || lenB > 1)) ? new GapEditScorer(lenA, lenB) : null;

    // Score matrix: pairing (i, j) yields a positive score of (1 - distance)
    // when distance <= maxDistance.
    double[][] pairScore = new double[lenA][lenB];
    for (int i = 0; i < lenA; i++) {
      checkAbortNow(budget.abort);
      FilteredTokens fa = filteredA.get(i);
      for (int j = 0; j < lenB; j++) {
        tickAbort(budget.abort);
        if (sliceA.get(i).equals(sliceB.get(j))) {
          pairScore[i][j] = 2.0; // Strongly prefer exact matches
          continue;
        }
        double dist =
            computeDistanceFromFiltered(fa, filteredB.get(j), maxDistance, budget, gapScorer, i, j);
        pairScore[i][j] = dist <= maxDistance ? 1.0 - dist : -1.0;
      }
    }

    double[][] dp = new double[lenA + 1][lenB + 1];
    for (int i = 0; i < lenA; i++) {
      for (int j = 0; j < lenB; j++) {
        tickAbort(budget.abort);
        double best = Math.max(dp[i][j + 1], dp[i + 1][j]);
        if (pairScore[i][j] >= 0) {
          best = Math.max(best, dp[i][j] + pairScore[i][j]);
        }
        dp[i + 1][j + 1] = best;
      }
    }

    List<AlignedHomologousItem> gapResult = new ArrayList<>();
    int i = lenA;
    int j = lenB;
    while (i > 0 || j > 0) {
      if (i > 0
          && j > 0
          && pairScore[i - 1][j - 1] >= 0
          && Math.abs(dp[i][j] - (dp[i - 1][j - 1] + pairScore[i - 1][j - 1])) < 1e-9) {
        int aIdx = offsetA + i - 1;
        int bIdx = offsetB + j - 1;
        String itemA = itemsA.get(aIdx);
        String itemB = itemsB.get(bIdx);
        gapResult.add(
            itemA.equals(itemB)
                ? AlignedHomologousItem.unchanged(itemA, itemB, aIdx, bIdx)
                : AlignedHomologousItem.modified(itemA, itemB, aIdx, bIdx));
        i--;
        j--;
      } else if (j > 0 && (i == 0 || dp[i][j - 1] >= dp[i - 1][j])) {
        int bIdx = offsetB + j - 1;
        gapResult.add(AlignedHomologousItem.added(itemsB.get(bIdx), bIdx));
        j--;
      } else {
        int aIdx = offsetA + i - 1;
        gapResult.add(AlignedHomologousItem.deleted(itemsA.get(aIdx), aIdx));
        i--;
      }
    }
    for (int k = gapResult.size() - 1; k >= 0; k--) {
      result.add(gapResult.get(k));
    }
  }

  /**
   * Lazily initialized sorted integer-ID multiset overlap scorer for a fuzzy DP interval, bounding
   * asymmetric pairs to {@code O(min(U_a, U_b) log(max / min))} / {@code O(U_a + U_b)} without
   * per-pair {@link HashMap} allocations or linear scans over the longer sequence.
   */
  static final class GapEditScorer {
    private static final int[] EMPTY_INT = new int[0];
    private static final EncodedSeq EMPTY_SEQ = new EncodedSeq(EMPTY_INT, EMPTY_INT);

    private static final class EncodedSeq {
      final int[] ids;
      final int[] counts;

      EncodedSeq(int[] ids, int[] counts) {
        this.ids = ids;
        this.counts = counts;
      }
    }

    private final Map<String, Integer> vocab = new HashMap<>();
    private final EncodedSeq[] encodedA;
    private final EncodedSeq[] encodedB;

    GapEditScorer(int lenA, int lenB) {
      this.encodedA = new EncodedSeq[lenA];
      this.encodedB = new EncodedSeq[lenB];
    }

    int overlap(
        int rowIdx,
        List<String> wordsA,
        int colIdx,
        List<String> wordsB,
        @Nullable HomologousBudget budget) {
      AbortChecker abort = budget != null ? budget.abort : null;
      EncodedSeq a = ensureEncoded(encodedA, rowIdx, wordsA, abort);
      EncodedSeq b = ensureEncoded(encodedB, colIdx, wordsB, abort);
      EncodedSeq shorter = a.ids.length <= b.ids.length ? a : b;
      EncodedSeq longer = a.ids.length <= b.ids.length ? b : a;
      int lenS = shorter.ids.length;
      int lenL = longer.ids.length;
      if (lenS == 0 || lenL == 0) {
        return 0;
      }
      boolean useBinarySearch = lenL > lenS * 4L;
      long work =
          useBinarySearch
              ? (long) lenS * (32 - Integer.numberOfLeadingZeros(lenL))
              : (long) lenS + lenL;
      if (budget != null && !budget.tryConsumeOverlapWork(work)) {
        return -1;
      }
      if (useBinarySearch) {
        return overlapBinarySearch(shorter, longer, abort);
      }
      return overlapTwoPointer(shorter, longer, abort);
    }

    private EncodedSeq ensureEncoded(
        EncodedSeq[] cache, int idx, List<String> words, @Nullable AbortChecker abort) {
      EncodedSeq existing = cache[idx];
      if (existing != null) {
        return existing;
      }
      int n = words.size();
      if (n == 0) {
        cache[idx] = EMPTY_SEQ;
        return EMPTY_SEQ;
      }
      int[] raw = new int[n];
      for (int k = 0; k < n; k++) {
        tickAbort(abort);
        raw[k] = vocab.computeIfAbsent(words.get(k), ignored -> vocab.size());
      }
      Arrays.sort(raw);
      int uniq = 1;
      for (int k = 1; k < n; k++) {
        tickAbort(abort);
        if (raw[k] != raw[k - 1]) {
          uniq++;
        }
      }
      int[] uIds = new int[uniq];
      int[] uCnts = new int[uniq];
      int pos = 0;
      uIds[0] = raw[0];
      uCnts[0] = 1;
      for (int k = 1; k < n; k++) {
        tickAbort(abort);
        if (raw[k] == raw[k - 1]) {
          uCnts[pos]++;
        } else {
          pos++;
          uIds[pos] = raw[k];
          uCnts[pos] = 1;
        }
      }
      EncodedSeq encoded = new EncodedSeq(uIds, uCnts);
      cache[idx] = encoded;
      return encoded;
    }

    private static int overlapBinarySearch(
        EncodedSeq shorter, EncodedSeq longer, @Nullable AbortChecker abort) {
      int lo = 0;
      int lenL = longer.ids.length;
      int minLongId = longer.ids[0];
      int maxLongId = longer.ids[lenL - 1];
      int common = 0;
      for (int k = 0; k < shorter.ids.length; k++) {
        tickAbort(abort);
        int target = shorter.ids[k];
        if (target < minLongId) {
          continue;
        }
        if (target > maxLongId) {
          break;
        }
        int hit = Arrays.binarySearch(longer.ids, lo, lenL, target);
        if (hit >= 0) {
          common += Math.min(shorter.counts[k], longer.counts[hit]);
          lo = hit + 1;
        } else {
          lo = -hit - 1;
        }
        if (lo >= lenL) {
          break;
        }
      }
      return common;
    }

    private static int overlapTwoPointer(
        EncodedSeq shorter, EncodedSeq longer, @Nullable AbortChecker abort) {
      int i = 0;
      int j = 0;
      int lenS = shorter.ids.length;
      int lenL = longer.ids.length;
      int common = 0;
      while (i < lenS && j < lenL) {
        tickAbort(abort);
        int idS = shorter.ids[i];
        int idL = longer.ids[j];
        if (idS == idL) {
          common += Math.min(shorter.counts[i], longer.counts[j]);
          i++;
          j++;
        } else if (idS < idL) {
          i++;
        } else {
          j++;
        }
      }
      return common;
    }
  }

  /** Returns {@code true} if {@code token} contains any letter, number, or underscore. */
  public static boolean defaultIsContentToken(String token) {
    checkNotNull(token, "token");
    for (int i = 0; i < token.length(); ) {
      int cp = token.codePointAt(i);
      if (isWordCodePoint(cp)) {
        return true;
      }
      i += Character.charCount(cp);
    }
    return false;
  }

  static boolean isAllWhitespace(String token) {
    if (token.isEmpty()) {
      return false;
    }
    for (int i = 0; i < token.length(); ) {
      int cp = token.codePointAt(i);
      if (!isWhitespaceCodePoint(cp)) {
        return false;
      }
      i += Character.charCount(cp);
    }
    return true;
  }

  static ImmutableList<String> tokenizeSimpleWords(String text) {
    if (text.isEmpty()) {
      return ImmutableList.of();
    }
    ImmutableList.Builder<String> tokens = ImmutableList.builder();
    int i = 0;
    int len = text.length();
    while (i < len) {
      int cp = text.codePointAt(i);
      int kind = classifyChar(cp);
      int j = i + Character.charCount(cp);
      while (j < len) {
        int nextCp = text.codePointAt(j);
        if (classifyChar(nextCp) != kind) {
          break;
        }
        j += Character.charCount(nextCp);
      }
      tokens.add(text.substring(i, j));
      i = j;
    }
    return tokens.build();
  }

  private static int classifyChar(int cp) {
    if (isWhitespaceCodePoint(cp)) {
      return 0;
    }
    if (isWordCodePoint(cp)) {
      return 1;
    }
    return 2;
  }

  private static boolean isWordCodePoint(int cp) {
    int type = Character.getType(cp);
    return Character.isLetter(cp)
        || type == Character.DECIMAL_DIGIT_NUMBER
        || type == Character.LETTER_NUMBER
        || type == Character.OTHER_NUMBER
        || cp == '_';
  }

  static boolean isWhitespaceCodePoint(int cp) {
    return Character.isWhitespace(cp) || Character.isSpaceChar(cp) || cp == '\uFEFF';
  }
}
