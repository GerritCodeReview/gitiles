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

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.gitiles.lean.SequenceAligner.Anchor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class SequenceAlignerTest {

  private static List<String> splitWhitespaceKeepingEmpties(String s) {
    return Arrays.asList(s.split("\\s+", -1));
  }

  private static ImmutableList<String> splitKeepingWhitespace(String s) {
    return SequenceAligner.tokenizeSimpleWords(s);
  }

  @Test
  public void computeSequenceDiff_basic() {
    List<String> seqA = List.of("a", "b", "c");
    List<String> seqB = List.of("a", "x", "c");
    ImmutableList<InlineDiffSegment> diff = SequenceAligner.computeSequenceDiff(seqA, seqB);
    assertThat(diff)
        .containsExactly(
            new InlineDiffSegment("a", InlineDiffSegment.Type.COMMON),
            new InlineDiffSegment("b", InlineDiffSegment.Type.DELETED),
            new InlineDiffSegment("x", InlineDiffSegment.Type.ADDED),
            new InlineDiffSegment("c", InlineDiffSegment.Type.COMMON))
        .inOrder();
  }

  @Test
  public void computeSequenceDiff_emptyAndOneSided() {
    assertThat(SequenceAligner.computeSequenceDiff(List.of(), List.of())).isEmpty();
    assertThat(SequenceAligner.computeSequenceDiff(List.of(), List.of("a", "b")))
        .containsExactly(
            new InlineDiffSegment("a", InlineDiffSegment.Type.ADDED),
            new InlineDiffSegment("b", InlineDiffSegment.Type.ADDED))
        .inOrder();
    assertThat(SequenceAligner.computeSequenceDiff(List.of("a", "b"), List.of()))
        .containsExactly(
            new InlineDiffSegment("a", InlineDiffSegment.Type.DELETED),
            new InlineDiffSegment("b", InlineDiffSegment.Type.DELETED))
        .inOrder();
  }

  @Test
  public void computeSequenceDiff_prefixSuffixTrimAndCellBudget() {
    // Near-identical huge sequences with a tiny middle edit must use exact DP after trimming.
    List<String> largeA = new ArrayList<>();
    List<String> largeB = new ArrayList<>();
    for (int i = 0; i < 600; i++) {
      largeA.add("prefix_" + i);
      largeB.add("prefix_" + i);
    }
    largeA.add("old_mid");
    largeB.add("new_mid");
    for (int i = 0; i < 600; i++) {
      largeA.add("suffix_" + i);
      largeB.add("suffix_" + i);
    }
    ImmutableList<InlineDiffSegment> trimmedDiff =
        SequenceAligner.computeSequenceDiff(largeA, largeB);
    assertThat(trimmedDiff).hasSize(1202);
    assertThat(trimmedDiff.get(600))
        .isEqualTo(new InlineDiffSegment("old_mid", InlineDiffSegment.Type.DELETED));
    assertThat(trimmedDiff.get(601))
        .isEqualTo(new InlineDiffSegment("new_mid", InlineDiffSegment.Type.ADDED));

    // Completely unaligned 1000x1000 exceeds MAX_SEQUENCE_DIFF_CELLS and falls back linearly.
    List<String> bigA = new ArrayList<>(1000);
    List<String> bigB = new ArrayList<>(1000);
    for (int i = 0; i < 1000; i++) {
      bigA.add("a_" + i);
      bigB.add("b_" + i);
    }
    ImmutableList<InlineDiffSegment> fallback = SequenceAligner.computeSequenceDiff(bigA, bigB);
    assertThat(fallback).hasSize(2000);
    assertThat(fallback.get(0).type()).isEqualTo(InlineDiffSegment.Type.DELETED);
    assertThat(fallback.get(1000).type()).isEqualTo(InlineDiffSegment.Type.ADDED);
  }

  @Test
  public void computeNormalizedEditDistance_deltaStyleEditInference() {
    assertThat(
            SequenceAligner.computeNormalizedEditDistance(
                splitKeepingWhitespace("same sentence"), splitKeepingWhitespace("same sentence")))
        .isEqualTo(0.0);
    assertThat(
            SequenceAligner.computeNormalizedEditDistance(
                splitKeepingWhitespace("alpha beta"), splitKeepingWhitespace("gamma delta")))
        .isEqualTo(1.0);
  }

  @Test
  public void computeNormalizedEditDistance_unicodeAndCustomContentFilter() {
    assertThat(
            SequenceAligner.computeNormalizedEditDistance(
                List.of("α", "=", "β"), List.of("α", "=", "β")))
        .isEqualTo(0.0);
    assertThat(
            SequenceAligner.computeNormalizedEditDistance(
                List.of("have", "α", "β"), List.of("have", "γ", "δ")))
        .isGreaterThan(0.5);

    double withMath =
        SequenceAligner.computeNormalizedEditDistance(
            List.of("∀", "α", "≤", "β"),
            List.of("∃", "α", "≥", "β"),
            t -> SequenceAligner.defaultIsContentToken(t) || "∀∃≤≥".contains(t));
    assertThat(withMath).isEqualTo(0.5);
  }

  @Test
  public void computeNormalizedEditDistance_edgeCasesAndLargeMultisetFallback() {
    assertThat(SequenceAligner.computeNormalizedEditDistance(List.of(), List.of())).isEqualTo(0.0);
    assertThat(
            SequenceAligner.computeNormalizedEditDistance(
                List.of(" ", "\t\r\n", "\uFEFF"), List.of()))
        .isEqualTo(0.0);
    assertThat(SequenceAligner.computeNormalizedEditDistance(List.of(" ", "\t"), List.of("a")))
        .isEqualTo(1.0);
    // Punctuation-only fallback when neither side has content tokens.
    assertThat(SequenceAligner.computeNormalizedEditDistance(List.of("(", ")"), List.of("(", ")")))
        .isEqualTo(0.0);
    assertThat(SequenceAligner.computeNormalizedEditDistance(List.of("(", ")"), List.of("foo")))
        .isEqualTo(1.0);

    // Surrogate pairs: mathematical bold letter U+1D41A is \p{L}; emoji U+1F600 is not content.
    assertThat(SequenceAligner.defaultIsContentToken("\uD835\uDC1A")).isTrue();
    assertThat(SequenceAligner.defaultIsContentToken("x\u2080")).isTrue();
    assertThat(SequenceAligner.defaultIsContentToken("\uD83D\uDE00")).isFalse();

    // Extension heuristic: >= 3 shared tokens, maxLen <= minLen * 5, overlapDistance <= 0.4.
    double extensionDist =
        SequenceAligner.computeNormalizedEditDistance(
            List.of("a", "b", "c", "d"),
            List.of("a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k", "l"));
    assertThat(extensionDist).isEqualTo(0.0);

    // Large >40,000-cell multiset overlap fallback.
    List<String> bigA = new ArrayList<>(400);
    List<String> bigB = new ArrayList<>(400);
    for (int i = 0; i < 400; i++) {
      bigA.add("a_" + i);
      bigB.add("b_" + i);
    }
    assertThat(SequenceAligner.computeNormalizedEditDistance(bigA, bigB)).isEqualTo(1.0);

    List<String> sharedA = new ArrayList<>(250);
    List<String> sharedB = new ArrayList<>(250);
    for (int i = 0; i < 250; i++) {
      sharedA.add(i < 100 ? "shared_" + (i % 10) : "onlyA_" + i);
      sharedB.add(i < 100 ? "shared_" + (i % 10) : "onlyB_" + i);
    }
    assertThat(SequenceAligner.computeNormalizedEditDistance(sharedA, sharedB))
        .isWithin(1e-9)
        .of(1.0 - (2.0 * 100) / 500);
  }

  @Test
  public void alignHomologousItems_pairsModifiedItemsAcrossInsertions() {
    List<String> itemsA = List.of("Configure timeout in default.yaml");
    List<String> itemsB =
        List.of("Brand new prerequisite step", "Configure timeout in production.yaml");
    ImmutableList<AlignedHomologousItem> aligned =
        SequenceAligner.alignHomologousItems(itemsA, itemsB);
    assertThat(aligned)
        .containsExactly(
            AlignedHomologousItem.added("Brand new prerequisite step", 0),
            AlignedHomologousItem.modified(
                "Configure timeout in default.yaml", "Configure timeout in production.yaml", 0, 1))
        .inOrder();
  }

  @Test
  public void alignHomologousItems_boundedOnLargeAdversarialInputs() {
    List<String> itemsA = new ArrayList<>(200);
    List<String> itemsB = new ArrayList<>(200);
    for (int i = 0; i < 200; i++) {
      itemsA.add("item_a_" + i);
      itemsB.add("item_b_" + i);
    }
    ImmutableList<AlignedHomologousItem> aligned =
        SequenceAligner.alignHomologousItems(itemsA, itemsB, List::of);
    assertThat(aligned).hasSize(400);
  }

  @Test
  public void computeLinearExactAnchors_andAlignHomologousItems_patienceOnRepeatedLines() {
    // Deleting a leading repeated line (`  simp`) that also occurs at EOF must not jump the
    // cursor to the bottom and mark all middle lines changed.
    List<String> middle = new ArrayList<>(120);
    for (int i = 0; i < 120; i++) {
      middle.add("  have h" + i + " := rfl");
    }
    List<String> base = new ArrayList<>();
    base.add("  simp");
    base.addAll(middle);
    base.add("  simp");

    List<String> rev = new ArrayList<>(middle);
    rev.add("  simp");

    ImmutableList<AlignedHomologousItem> aligned =
        SequenceAligner.alignHomologousItems(
            base, rev, SequenceAlignerTest::splitWhitespaceKeepingEmpties);
    long unchangedCount =
        aligned.stream().filter(a -> a.type() == DiffBlockStatus.UNCHANGED).count();
    long deletedCount = aligned.stream().filter(a -> a.type() == DiffBlockStatus.DELETED).count();
    long addedCount = aligned.stream().filter(a -> a.type() == DiffBlockStatus.ADDED).count();
    assertThat(unchangedCount).isEqualTo(121);
    assertThat(deletedCount).isEqualTo(1);
    assertThat(addedCount).isEqualTo(0);

    // Large input with 5,000 repeated lines runs in linear time.
    List<String> repA = new ArrayList<>(5000);
    for (int i = 0; i < 5000; i++) {
      repA.add(i % 3 == 0 ? "" : i % 3 == 1 ? "end" : "simp");
    }
    List<String> repB = new ArrayList<>(5000);
    repB.add("extra");
    repB.addAll(repA.subList(1, repA.size()));
    ImmutableList<Anchor> anchors = SequenceAligner.computeLinearExactAnchors(repA, repB);
    assertThat(anchors.size()).isGreaterThan(4900);
  }

  @Test
  public void alignHomologousItems_preservesFuzzyDiffsInLargeBlocksAndRespectsCellBudget() {
    List<String> unchangedMiddle = new ArrayList<>(118);
    for (int i = 0; i < 118; i++) {
      unchangedMiddle.add("  have step_" + i + " : True := trivial");
    }
    List<String> base = new ArrayList<>();
    base.add("  have h_top : a = oldAlpha");
    base.addAll(unchangedMiddle);
    base.add("  exact Nat.add_comm a oldOmega");

    List<String> rev = new ArrayList<>();
    rev.add("  have h_top : a = newAlpha");
    rev.addAll(unchangedMiddle);
    rev.add("  exact Nat.add_comm a newOmega");

    ImmutableList<AlignedHomologousItem> aligned =
        SequenceAligner.alignHomologousItems(
            base, rev, SequenceAlignerTest::splitKeepingWhitespace);
    assertThat(aligned).hasSize(120);
    assertThat(aligned.get(0))
        .isEqualTo(
            AlignedHomologousItem.modified(
                "  have h_top : a = oldAlpha", "  have h_top : a = newAlpha", 0, 0));
    assertThat(aligned.get(119))
        .isEqualTo(
            AlignedHomologousItem.modified(
                "  exact Nat.add_comm a oldOmega", "  exact Nat.add_comm a newOmega", 119, 119));
    long unchanged = aligned.stream().filter(a -> a.type() == DiffBlockStatus.UNCHANGED).count();
    assertThat(unchanged).isEqualTo(118);

    // Shared cell budget: a huge 150x150 rewritten gap exceeds remainingCells and falls back to
    // deleted+added WITHOUT starving a subsequent 1x1 edit.
    List<String> mixedA = new ArrayList<>();
    List<String> mixedB = new ArrayList<>();
    for (int i = 0; i < 150; i++) {
      mixedA.add("old_item_" + i);
      mixedB.add("new_item_" + i);
    }
    for (int i = 0; i < 10; i++) {
      mixedA.add("anchor_" + i);
      mixedB.add("anchor_" + i);
    }
    mixedA.add("  exact tailOld");
    mixedB.add("  exact tailNew");

    ImmutableList<AlignedHomologousItem> mixedAligned =
        SequenceAligner.alignHomologousItems(
            mixedA, mixedB, SequenceAlignerTest::splitKeepingWhitespace);
    assertThat(mixedAligned.get(mixedAligned.size() - 1).type())
        .isEqualTo(DiffBlockStatus.MODIFIED);
    long mixedUnchanged =
        mixedAligned.stream().filter(a -> a.type() == DiffBlockStatus.UNCHANGED).count();
    assertThat(mixedUnchanged).isEqualTo(10);
  }

  @Test
  public void computeLinearExactAnchors_depthCapFallbackAndEmptyInputs() {
    assertThat(SequenceAligner.computeLinearExactAnchors(List.of(), List.of("a"))).isEmpty();
    assertThat(SequenceAligner.computeLinearExactAnchors(List.of("a"), List.of())).isEmpty();

    // Force Patience depth > MAX_PATIENCE_DEPTH (32): at each level k, u_k is unique only after
    // level k+1 splits off the duplicate u_k in its right sibling.
    List<String> deepA = new ArrayList<>();
    List<String> deepB = new ArrayList<>();
    deepA.add("endA");
    deepB.add("endB");
    for (int k = 1; k <= 36; k++) {
      List<String> nextA = new ArrayList<>();
      List<String> nextB = new ArrayList<>();
      nextA.add("pA_" + k);
      nextB.add("pB_" + k);
      nextA.addAll(deepA);
      nextB.addAll(deepB);
      nextA.add("u_" + k);
      nextB.add("u_" + k);
      if (k >= 2) {
        nextA.add("qA_" + k);
        nextB.add("qB_" + k);
        nextA.add("u_" + (k - 1));
        nextB.add("u_" + (k - 1));
        nextA.add("rA_" + k);
        nextB.add("rB_" + k);
      } else {
        nextA.add("rA_1");
        nextB.add("rB_1");
      }
      deepA = nextA;
      deepB = nextB;
    }
    ImmutableList<Anchor> deepAnchors = SequenceAligner.computeLinearExactAnchors(deepA, deepB);
    assertThat(deepAnchors).hasSize(1 + 2 * 35);

    // Zero unique keys with gapA * gapB > MAX_ALIGN_ITEMS_CELLS triggers the O(gapA + gapB)
    // monotonic per-key cursor fallback.
    List<String> dupA = new ArrayList<>(150);
    List<String> dupB = new ArrayList<>(150);
    for (int i = 0; i < 150; i++) {
      dupA.add(i % 2 == 0 ? "a0" : "a1");
      dupB.add(i % 2 == 0 ? "a1" : "a0");
    }
    ImmutableList<Anchor> dupAnchors = SequenceAligner.computeLinearExactAnchors(dupA, dupB);
    assertThat(dupAnchors).hasSize(149);
    assertThat(dupAnchors.get(0)).isEqualTo(new Anchor(0, 1));
    assertThat(dupAnchors.get(148)).isEqualTo(new Anchor(148, 149));
  }

  @Test
  public void modelValidation_rejectsNullsAndInvalidItems() {
    assertThrows(
        NullPointerException.class,
        () -> new InlineDiffSegment(null, InlineDiffSegment.Type.COMMON));
    assertThrows(
        IllegalArgumentException.class,
        () -> new AlignedHomologousItem(DiffBlockStatus.DELETED, "a", "b", 0, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new AlignedHomologousItem(DiffBlockStatus.ADDED, null, "b", null, -1));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            SequenceAligner.alignHomologousItems(
                List.of("a"), List.of("b"), List::of, Double.NaN, s -> true));
  }
}
