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

import com.google.common.collect.ImmutableList;
import com.google.gitiles.lean.SequenceAligner.Anchor;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class SequenceAlignerAdversarialTest {

  private static void assertSequenceDiffIntegrity(
      List<String> seqA, List<String> seqB, List<InlineDiffSegment> diff) {
    List<String> recA = new ArrayList<>();
    List<String> recB = new ArrayList<>();
    for (InlineDiffSegment seg : diff) {
      switch (seg.type()) {
        case COMMON -> {
          recA.add(seg.text());
          recB.add(seg.text());
        }
        case DELETED -> recA.add(seg.text());
        case ADDED -> recB.add(seg.text());
      }
    }
    assertThat(recA).isEqualTo(seqA);
    assertThat(recB).isEqualTo(seqB);
  }

  @Test
  public void computeSequenceDiff_capBoundaryEndsEditedLongLineAndSelfDiff() {
    // 500x500 exact DP boundary vs 501x501 linear fallback both reconstruct inputs losslessly.
    List<String> dpA = new ArrayList<>(500);
    List<String> dpB = new ArrayList<>(500);
    for (int i = 0; i < 500; i++) {
      dpA.add("a_" + i);
      dpB.add("b_" + i);
    }
    assertSequenceDiffIntegrity(dpA, dpB, SequenceAligner.computeSequenceDiff(dpA, dpB));

    // >250,000 cells after trimming: 500k tokens with start & end changed.
    List<String> longA = new ArrayList<>(500_002);
    List<String> longB = new ArrayList<>(500_002);
    longA.add("HEAD_A");
    longB.add("HEAD_B");
    for (int i = 0; i < 500_000; i++) {
      String tok = "t" + (i % 64);
      longA.add(tok);
      longB.add(tok);
    }
    longA.add("TAIL_A");
    longB.add("TAIL_B");
    assertSequenceDiffIntegrity(longA, longB, SequenceAligner.computeSequenceDiff(longA, longB));

    // Self-diff always produces zero ADDED/DELETED segments.
    ImmutableList<InlineDiffSegment> self = SequenceAligner.computeSequenceDiff(longA, longA);
    assertThat(self.stream().allMatch(s -> s.type() == InlineDiffSegment.Type.COMMON)).isTrue();
  }

  @Test
  public void computeNormalizedEditDistance_1dLcsSymmetryAndLargeBounds() {
    List<String> a = List.of("have", "h1", ":", "x", "+", "1", "=", "y");
    List<String> b = List.of("have", "h2", ":", "x", "+", "2", "=", "y");
    double dAb = SequenceAligner.computeNormalizedEditDistance(a, b);
    double dBa = SequenceAligner.computeNormalizedEditDistance(b, a);
    assertThat(dAb).isWithin(1e-12).of(dBa);
    assertThat(dAb).isGreaterThan(0.0);
    assertThat(dAb).isLessThan(1.0);
    assertThat(SequenceAligner.computeNormalizedEditDistance(a, a)).isEqualTo(0.0);

    // >40,000 cells triggers multiset fallback cleanly and stays symmetric in [0.0, 1.0].
    List<String> hugeA = new ArrayList<>(300);
    List<String> hugeB = new ArrayList<>(300);
    for (int i = 0; i < 300; i++) {
      hugeA.add(i < 150 ? "common_" + (i % 20) : "onlyA_" + i);
      hugeB.add(i < 150 ? "common_" + (i % 20) : "onlyB_" + i);
    }
    double hdAb = SequenceAligner.computeNormalizedEditDistance(hugeA, hugeB);
    double hdBa = SequenceAligner.computeNormalizedEditDistance(hugeB, hugeA);
    assertThat(hdAb).isWithin(1e-12).of(hdBa);
    assertThat(hdAb).isAtLeast(0.0);
    assertThat(hdAb).isAtMost(1.0);
  }

  @Test
  public void computeLinearExactAnchors_deepPatienceAndMaxLinesNoUniqueKeys() {
    // Alternating identical tokens at 20,000 lines with zero unique anchors stays strictly linear.
    List<String> noUniqA = new ArrayList<>(LeanRendering.MAX_LINES);
    List<String> noUniqB = new ArrayList<>(LeanRendering.MAX_LINES);
    for (int i = 0; i < LeanRendering.MAX_LINES; i++) {
      noUniqA.add(i % 2 == 0 ? "k0" : "k1");
      noUniqB.add(i % 2 == 0 ? "k1" : "k0");
    }
    ImmutableList<Anchor> anchors = SequenceAligner.computeLinearExactAnchors(noUniqA, noUniqB);
    assertThat(anchors).hasSize(LeanRendering.MAX_LINES - 1);
    int prevA = -1;
    int prevB = -1;
    for (Anchor anc : anchors) {
      assertThat(anc.aIdx()).isGreaterThan(prevA);
      assertThat(anc.bIdx()).isGreaterThan(prevB);
      assertThat(noUniqA.get(anc.aIdx())).isEqualTo(noUniqB.get(anc.bIdx()));
      prevA = anc.aIdx();
      prevB = anc.bIdx();
    }
  }

  @Test
  public void alignHomologousItems_nearDuplicatesStayBoundedAndOrderPreserving() {
    StringBuilder shared = new StringBuilder();
    for (int t = 0; t < 200; t++) {
      shared.append(" tok").append(t);
    }
    String tail = shared.toString();

    List<String> itemsA = new ArrayList<>(100);
    List<String> itemsB = new ArrayList<>(100);
    for (int i = 0; i < 100; i++) {
      itemsA.add("have old_" + i + " :=" + tail + " a" + i);
      itemsB.add("have new_" + i + " :=" + tail + " b" + i);
    }

    ImmutableList<AlignedHomologousItem> aligned =
        SequenceAligner.alignHomologousItems(itemsA, itemsB);
    int nextA = 0;
    int nextB = 0;
    for (AlignedHomologousItem item : aligned) {
      switch (item.type()) {
        case UNCHANGED -> {
          assertThat(item.aIdx()).isEqualTo(nextA);
          assertThat(item.bIdx()).isEqualTo(nextB);
          assertThat(itemsA.get(nextA)).isEqualTo(itemsB.get(nextB));
          nextA++;
          nextB++;
        }
        case MODIFIED -> {
          assertThat(item.aIdx()).isEqualTo(nextA);
          assertThat(item.bIdx()).isEqualTo(nextB);
          assertThat(item.textA()).isEqualTo(itemsA.get(nextA));
          assertThat(item.textB()).isEqualTo(itemsB.get(nextB));
          nextA++;
          nextB++;
        }
        case DELETED -> {
          assertThat(item.aIdx()).isEqualTo(nextA);
          assertThat(item.textA()).isEqualTo(itemsA.get(nextA));
          nextA++;
        }
        case ADDED -> {
          assertThat(item.bIdx()).isEqualTo(nextB);
          assertThat(item.textB()).isEqualTo(itemsB.get(nextB));
          nextB++;
        }
      }
    }
    assertThat(nextA).isEqualTo(100);
    assertThat(nextB).isEqualTo(100);
  }

  @Test
  public void concurrentAlignment_hasNoSharedMutableState() throws Exception {
    List<String> base = List.of("def a := 1", "def b := 2", "def c := 3");
    List<String> rev = List.of("def a := 1", "def b := 20", "def d := 4");
    ExecutorService pool = Executors.newFixedThreadPool(4);
    try {
      List<Callable<Boolean>> tasks = new ArrayList<>();
      for (int t = 0; t < 8; t++) {
        tasks.add(() -> {
          for (int i = 0; i < 100; i++) {
            ImmutableList<AlignedHomologousItem> aligned =
                SequenceAligner.alignHomologousItems(base, rev);
            if (aligned.size() != 4 || aligned.get(0).type() != DiffBlockStatus.UNCHANGED) {
              return false;
            }
          }
          return true;
        });
      }
      for (Future<Boolean> f : pool.invokeAll(tasks)) {
        assertThat(f.get()).isTrue();
      }
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  public void alignHomologousItems_asymmetricManyTinyVsOneHuge_staysLinear() {
    List<String> tinySide = new ArrayList<>(8_000);
    for (int i = 0; i < 8_000; i++) {
      tinySide.add("foo_" + (i % 7) + " bar");
    }
    StringBuilder hugeSb = new StringBuilder();
    for (int t = 0; t < 160_000; t++) {
      hugeSb.append(t == 0 ? "tok" : " tok").append(t % 97);
    }
    List<String> hugeSide = List.of(hugeSb.toString());

    ImmutableList<AlignedHomologousItem> fwd =
        SequenceAligner.alignHomologousItems(tinySide, hugeSide);
    assertThat(fwd).hasSize(8_001);

    ImmutableList<AlignedHomologousItem> rev =
        SequenceAligner.alignHomologousItems(hugeSide, tinySide);
    assertThat(rev).hasSize(8_001);

    // Same asymmetric shape where both sides have at least 3 shared words (forces GapEditScorer).
    List<String> threeWordsTiny = new ArrayList<>(8_000);
    for (int i = 0; i < 8_000; i++) {
      threeWordsTiny.add("tok0 tok1 tok2 x" + i);
    }
    ImmutableList<AlignedHomologousItem> scorerFwd =
        SequenceAligner.alignHomologousItems(threeWordsTiny, hugeSide);
    assertThat(scorerFwd).isNotEmpty();
  }
}
