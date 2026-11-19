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
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

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
      prefixLen++;
    }
    int suffixLen = 0;
    while (suffixLen < m - prefixLen
        && suffixLen < n - prefixLen
        && listA.get(m - 1 - suffixLen).equals(listB.get(n - 1 - suffixLen))) {
      suffixLen++;
    }

    ImmutableList.Builder<InlineDiffSegment> segments = ImmutableList.builder();
    for (int p = 0; p < prefixLen; p++) {
      segments.add(new InlineDiffSegment(listA.get(p), InlineDiffSegment.Type.COMMON));
    }

    int midLenA = m - prefixLen - suffixLen;
    int midLenB = n - prefixLen - suffixLen;

    if (midLenA == 0) {
      for (int j = 0; j < midLenB; j++) {
        segments.add(new InlineDiffSegment(listB.get(prefixLen + j), InlineDiffSegment.Type.ADDED));
      }
    } else if (midLenB == 0) {
      for (int i = 0; i < midLenA; i++) {
        segments.add(
            new InlineDiffSegment(listA.get(prefixLen + i), InlineDiffSegment.Type.DELETED));
      }
    } else if ((long) midLenA * midLenB > MAX_SEQUENCE_DIFF_CELLS) {
      // Bounded linear fallback for adversarial/huge unaligned sequences.
      for (int i = 0; i < midLenA; i++) {
        segments.add(
            new InlineDiffSegment(listA.get(prefixLen + i), InlineDiffSegment.Type.DELETED));
      }
      for (int j = 0; j < midLenB; j++) {
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
        segments.add(midSegments.get(k));
      }
    }

    for (int s = 0; s < suffixLen; s++) {
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
    ImmutableList<String> listA = ImmutableList.copyOf(checkNotNull(seqA, "seqA"));
    ImmutableList<String> listB = ImmutableList.copyOf(checkNotNull(seqB, "seqB"));
    checkNotNull(isContentToken, "isContentToken");

    List<String> nonWsA = filterNonWhitespace(listA);
    List<String> nonWsB = filterNonWhitespace(listB);
    if (nonWsA.isEmpty() && nonWsB.isEmpty()) {
      return 0.0;
    }
    if (nonWsA.isEmpty() || nonWsB.isEmpty()) {
      return 1.0;
    }

    List<String> wordsA = filterPredicate(nonWsA, isContentToken);
    List<String> wordsB = filterPredicate(nonWsB, isContentToken);
    List<String> contentA = (!wordsA.isEmpty() || !wordsB.isEmpty()) ? wordsA : nonWsA;
    List<String> contentB = (!wordsA.isEmpty() || !wordsB.isEmpty()) ? wordsB : nonWsB;
    if (contentA.isEmpty() || contentB.isEmpty()) {
      return 1.0;
    }

    int commonCount = 0;
    if ((long) contentA.size() * contentB.size() > MAX_EDIT_DISTANCE_CELLS) {
      // Linear O(m + n) multiset overlap approximation for very large token lists.
      Map<String, Integer> countsA = new HashMap<>();
      for (String tok : contentA) {
        countsA.merge(tok, 1, Integer::sum);
      }
      for (String tok : contentB) {
        Integer rem = countsA.get(tok);
        if (rem != null && rem > 0) {
          commonCount++;
          countsA.put(tok, rem - 1);
        }
      }
    } else {
      ImmutableList<InlineDiffSegment> diff = computeSequenceDiff(contentA, contentB);
      for (InlineDiffSegment seg : diff) {
        if (seg.type() == InlineDiffSegment.Type.COMMON) {
          commonCount++;
        }
      }
    }

    int minLen = Math.min(contentA.size(), contentB.size());
    int maxLen = Math.max(contentA.size(), contentB.size());
    double diceDistance = 1.0 - (2.0 * commonCount) / (contentA.size() + contentB.size());
    double overlapDistance = 1.0 - ((double) commonCount) / minLen;
    // If one sequence is an extension of the other (high overlap with the shorter sequence,
    // bounded length ratio, and at least 3 shared content tokens), treat them as homologous so
    // shared prefixes/clauses remain unhighlighted.
    if (commonCount >= 3 && maxLen <= (long) minLen * 5 && overlapDistance <= 0.4) {
      return Math.min(diceDistance, overlapDistance);
    }
    return diceDistance;
  }

  /**
   * Patience-style exact-match anchor finder for large sequences where {@code O(m * n)} DP exceeds
   * the cell budget.
   */
  public static ImmutableList<Anchor> computeLinearExactAnchors(
      List<String> keysA, List<String> keysB) {
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
        anchors.add(new Anchor(aStart, bStart));
        aStart++;
        bStart++;
      }

      List<Anchor> suffixPairs = new ArrayList<>();
      while (aEnd > aStart && bEnd > bStart && listA.get(aEnd - 1).equals(listB.get(bEnd - 1))) {
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
              ? findUniqueLis(listA, listB, aStart, aEnd, bStart, bEnd)
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
        positionsByKey.computeIfAbsent(listB.get(j), k -> new ArrayList<>()).add(j);
      }
      Map<String, Integer> cursorByKey = new HashMap<>();
      int bCursor = bStart;
      for (int i = aStart; i < aEnd; i++) {
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
      List<String> keysA, List<String> keysB, int aStart, int aEnd, int bStart, int bEnd) {
    Map<String, Integer> countA = new HashMap<>();
    for (int i = aStart; i < aEnd; i++) {
      countA.merge(keysA.get(i), 1, Integer::sum);
    }
    Map<String, Integer> posB = new HashMap<>();
    for (int j = bStart; j < bEnd; j++) {
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
    ImmutableList<String> listA = ImmutableList.copyOf(checkNotNull(itemsA, "itemsA"));
    ImmutableList<String> listB = ImmutableList.copyOf(checkNotNull(itemsB, "itemsB"));
    checkNotNull(tokenizer, "tokenizer");
    checkArgument(!Double.isNaN(maxDistance), "maxDistance must not be NaN");
    checkNotNull(isContentToken, "isContentToken");

    int m = listA.size();
    int n = listB.size();
    if (m == 0 && n == 0) {
      return ImmutableList.of();
    }

    // Trim exact common prefix and suffix first.
    int prefixLen = 0;
    while (prefixLen < m && prefixLen < n && listA.get(prefixLen).equals(listB.get(prefixLen))) {
      prefixLen++;
    }
    int suffixLen = 0;
    while (suffixLen < m - prefixLen
        && suffixLen < n - prefixLen
        && listA.get(m - 1 - suffixLen).equals(listB.get(n - 1 - suffixLen))) {
      suffixLen++;
    }

    ImmutableList.Builder<AlignedHomologousItem> result = ImmutableList.builder();
    for (int p = 0; p < prefixLen; p++) {
      result.add(AlignedHomologousItem.unchanged(listA.get(p), listB.get(p), p, p));
    }

    int mm = m - prefixLen - suffixLen;
    int nn = n - prefixLen - suffixLen;

    if (mm == 0) {
      for (int j = 0; j < nn; j++) {
        int bIdx = prefixLen + j;
        result.add(AlignedHomologousItem.added(listB.get(bIdx), bIdx));
      }
    } else if (nn == 0) {
      for (int i = 0; i < mm; i++) {
        int aIdx = prefixLen + i;
        result.add(AlignedHomologousItem.deleted(listA.get(aIdx), aIdx));
      }
    } else if ((long) mm * nn > MAX_ALIGN_ITEMS_CELLS) {
      // Patience-style exact-anchor decomposition with a shared fuzzy-DP cell budget across
      // residual gaps so edits near both ends of >100-line blocks keep word-level inline diffs
      // while staying bounded in worst-case cost.
      ImmutableList<String> midA = listA.subList(prefixLen, m - suffixLen);
      ImmutableList<String> midB = listB.subList(prefixLen, n - suffixLen);
      ImmutableList<Anchor> anchors = computeLinearExactAnchors(midA, midB);
      int remainingCells = MAX_ALIGN_ITEMS_CELLS;

      int aCursor = 0;
      int bCursor = 0;
      for (Anchor anchor : anchors) {
        remainingCells =
            processAlignGap(
                listA,
                listB,
                prefixLen,
                aCursor,
                anchor.aIdx(),
                bCursor,
                anchor.bIdx(),
                remainingCells,
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
          remainingCells,
          tokenizer,
          maxDistance,
          isContentToken,
          result);
    } else {
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
          result);
    }

    for (int s = 0; s < suffixLen; s++) {
      int aIdx = m - suffixLen + s;
      int bIdx = n - suffixLen + s;
      result.add(AlignedHomologousItem.unchanged(listA.get(aIdx), listB.get(bIdx), aIdx, bIdx));
    }

    return result.build();
  }

  @CanIgnoreReturnValue
  private static int processAlignGap(
      List<String> itemsA,
      List<String> itemsB,
      int prefixLen,
      int gapAStart,
      int gapAEnd,
      int gapBStart,
      int gapBEnd,
      int remainingCells,
      Function<String, List<String>> tokenizer,
      double maxDistance,
      Predicate<String> isContentToken,
      ImmutableList.Builder<AlignedHomologousItem> result) {
    int gapA = gapAEnd - gapAStart;
    int gapB = gapBEnd - gapBStart;
    if (gapA == 0 && gapB == 0) {
      return remainingCells;
    }
    if (gapA == 0) {
      for (int j = gapBStart; j < gapBEnd; j++) {
        int bIdx = prefixLen + j;
        result.add(AlignedHomologousItem.added(itemsB.get(bIdx), bIdx));
      }
      return remainingCells;
    }
    if (gapB == 0) {
      for (int i = gapAStart; i < gapAEnd; i++) {
        int aIdx = prefixLen + i;
        result.add(AlignedHomologousItem.deleted(itemsA.get(aIdx), aIdx));
      }
      return remainingCells;
    }
    long cells = (long) gapA * gapB;
    if (cells <= remainingCells) {
      remainingCells -= (int) cells;
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
          result);
      return remainingCells;
    }
    for (int i = gapAStart; i < gapAEnd; i++) {
      int aIdx = prefixLen + i;
      result.add(AlignedHomologousItem.deleted(itemsA.get(aIdx), aIdx));
    }
    for (int j = gapBStart; j < gapBEnd; j++) {
      int bIdx = prefixLen + j;
      result.add(AlignedHomologousItem.added(itemsB.get(bIdx), bIdx));
    }
    return remainingCells;
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
      ImmutableList.Builder<AlignedHomologousItem> result) {
    List<String> sliceA = itemsA.subList(offsetA, offsetA + lenA);
    List<String> sliceB = itemsB.subList(offsetB, offsetB + lenB);
    List<List<String>> tokensCacheA = new ArrayList<>(lenA);
    for (String item : sliceA) {
      tokensCacheA.add(tokenizer.apply(item));
    }
    List<List<String>> tokensCacheB = new ArrayList<>(lenB);
    for (String item : sliceB) {
      tokensCacheB.add(tokenizer.apply(item));
    }

    // Score matrix: pairing (i, j) yields a positive score of (1 - distance)
    // when distance <= maxDistance.
    double[][] pairScore = new double[lenA][lenB];
    for (int i = 0; i < lenA; i++) {
      for (int j = 0; j < lenB; j++) {
        if (sliceA.get(i).equals(sliceB.get(j))) {
          pairScore[i][j] = 2.0; // Strongly prefer exact matches
        } else {
          double dist =
              computeNormalizedEditDistance(
                  tokensCacheA.get(i), tokensCacheB.get(j), isContentToken);
          pairScore[i][j] = dist <= maxDistance ? 1.0 - dist : -1.0;
        }
      }
    }

    double[][] dp = new double[lenA + 1][lenB + 1];
    for (int i = 0; i < lenA; i++) {
      for (int j = 0; j < lenB; j++) {
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

  private static List<String> filterNonWhitespace(List<String> tokens) {
    List<String> out = new ArrayList<>(tokens.size());
    for (String tok : tokens) {
      if (!isAllWhitespace(tok)) {
        out.add(tok);
      }
    }
    return out;
  }

  private static List<String> filterPredicate(List<String> tokens, Predicate<String> predicate) {
    List<String> out = new ArrayList<>(tokens.size());
    for (String tok : tokens) {
      if (predicate.test(tok)) {
        out.add(tok);
      }
    }
    return out;
  }
}
