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

import com.google.common.html.types.SafeHtml;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.eclipse.jgit.lib.Config;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Adversarial security and rendering-integrity tests for {@link LeanHtml#renderDiff}. */
@RunWith(JUnit4.class)
public class LeanDiffAdversarialTest {

  @Test
  public void blankLineOnlyDiffBetweenBlocksFallsBackToNullInsteadOfHidingChanges() {
    String left = "def keepA : Nat := 1\n\ndef keepB : Nat := 2\n";
    String right = "\n\ndef keepA : Nat := 1\n\n\n\ndef keepB : Nat := 2\n\n";
    assertThat(LeanHtml.renderDiff(left, right, new Config(), null, null, "BlankLines.lean"))
        .isNull();
  }

  @Test
  public void crlfOnlyLineEndingDiffFallsBackToNullInsteadOfHidingChanges() {
    String lf = "def a := 1\ndef b := 2\n";
    String crlf = "def a := 1\r\ndef b := 2\r\n";
    String cr = "def a := 1\rdef b := 2\r";
    assertThat(LeanHtml.renderDiff(crlf, lf, new Config(), null, null, "Crlf.lean")).isNull();
    assertThat(LeanHtml.renderDiff(cr, lf, new Config(), null, null, "Cr.lean")).isNull();
  }

  @Test
  public void delimiterOnlyBannerAndModuleDocDiffsRenderRawChanges() {
    String left =
        """
        /-! Title -/
        -- ====
        -- Section
        -- ====
        def x : Nat := 1
        """;
    String right =
        """
        /-!
        Title
        -/
        -- ----
        -- Section
        -- ----
        def x : Nat := 1
        """;
    SafeHtml safe = LeanHtml.renderDiff(left, right, new Config(), null, null, "Delims.lean");
    assertThat(safe).isNotNull();
    String html = safe.getSafeHtmlString();
    assertThat(html).contains("LeanCode-line--deleted");
    assertThat(html).contains("LeanCode-line--added");
    assertThat(html).contains("-- ====");
    assertThat(html).contains("-- ----");
  }

  @Test
  public void movedAndRenamedDeclarationsShowHeaderAndBodyChanges() {
    String left =
        """
        theorem helper_old : True := by
          trivial

        theorem anchor_one : 1 = 1 := by
          rfl

        theorem anchor_two : 2 = 2 := by
          rfl
        """;
    String right =
        """
        theorem anchor_one : 1 = 1 := by
          rfl

        theorem anchor_two : 2 = 2 := by
          rfl

        theorem helper_new : True := by
          sorry
        """;
    SafeHtml safe = LeanHtml.renderDiff(left, right, new Config(), null, null, "Moved.lean");
    assertThat(safe).isNotNull();
    String html = safe.getSafeHtmlString();
    assertThat(html).contains("helper_old");
    assertThat(html).contains("helper_new");
    assertThat(html).contains("sorry");
    assertThat(html).contains("LeanDecl-badge--error");
  }

  @Test
  public void diffStrategyChangeWithoutBodyOrSigChangeNeverHidesDiff() {
    String left = "theorem t : True := trivial\n";
    String right = "theorem t : True := by trivial\n";
    SafeHtml safe = LeanHtml.renderDiff(left, right, new Config(), null, null, "Tactic.lean");
    assertThat(safe).isNotNull();
    String html = safe.getSafeHtmlString();
    assertThat(html).contains("LeanCode-line--deleted");
    assertThat(html).contains("LeanCode-line--added");
    assertThat(html).contains("trivial");
  }

  @Test
  public void diffNeutralizesTrojanBidiControlsAndHomoglyphs() {
    String left = "theorem ok : True := by\n  trivial\n";
    String right = "theorem \u043ek : True := by\n  -- \u202Ehide\u200B\n  trivial\n";
    SafeHtml safe = LeanHtml.renderDiff(left, right, new Config(), null, null, "Trojan.lean");
    assertThat(safe).isNotNull();
    String html = safe.getSafeHtmlString();
    assertThat(html).contains("<div class=\"LeanTrojanBanner\" role=\"alert\">");
    assertThat(html).contains("<span class=\"LeanTrojanChar\">U+202E</span>");
    assertThat(html).contains("<span class=\"LeanTrojanChar\">U+200B</span>");
    assertThat(html).contains("homoglyph");
    assertThat(LeanSecurity.containsHiddenOrSurrogate(html)).isFalse();
  }

  @Test
  public void diffOutputCapAbortsEarlyOnExpandingTokens() {
    // 1,000 comment lines with 450 zero-width spaces each (~1.35 MiB raw UTF-8 < MAX_BYTES,
    // 1,001 lines < MAX_LINES) expand via Trojan char spans past MAX_OUTPUT_CHARS (16 MiB).
    String expanding = ("-- " + "\u200B".repeat(450) + "\n").repeat(1_000);
    assertThat(LeanHtml.renderDiff("def x := 1\n", expanding, new Config(), null, null, "Cap.lean"))
        .isNull();
  }

  @Test
  public void perRequestBudgetEnforcesCumulativeOutputCharsAndCpuDeadlineDeterministically() {
    // Cumulative output char budget across calls: first diff succeeds, second exceeds remaining.
    LeanRequestBudget outputBudget =
        LeanRequestBudget.forTesting(
            LeanRequestBudget.MAX_REQUEST_INPUT_BYTES, 4_000, 50, 750_000_000L, () -> 0L);
    String smallLeft = "def a : Nat := 1\n";
    String smallRight = "def a : Nat := 2\n";
    SafeHtml first =
        LeanHtml.renderDiff(
            smallLeft.length(),
            smallLeft,
            smallRight.length(),
            smallRight,
            new Config(),
            null,
            null,
            "One.lean",
            outputBudget);
    assertThat(first).isNotNull();
    assertThat(outputBudget.filesRendered()).isEqualTo(1);
    assertThat(outputBudget.outputCharsUsed()).isEqualTo(first.getSafeHtmlString().length());

    StringBuilder largerRight = new StringBuilder();
    for (int i = 0; i < 40; i++) {
      largerRight.append("def f").append(i).append(" : Nat := ").append(i + 1).append('\n');
    }
    SafeHtml second =
        LeanHtml.renderDiff(
            smallLeft.length(),
            smallLeft,
            largerRight.length(),
            largerRight.toString(),
            new Config(),
            null,
            null,
            "Two.lean",
            outputBudget);
    assertThat(second).isNull();
    assertThat(outputBudget.filesRendered()).isEqualTo(1);

    // Cooperative CPU deadline check via deterministic fake clock.
    AtomicLong ticks = new AtomicLong(0L);
    LeanRequestBudget cpuBudget =
        LeanRequestBudget.forTesting(
            LeanRequestBudget.MAX_REQUEST_INPUT_BYTES,
            LeanRequestBudget.MAX_REQUEST_OUTPUT_CHARS,
            50,
            750_000_000L,
            () -> ticks.getAndAdd(300_000_000L));
    StringBuilder multiLineLeft = new StringBuilder();
    StringBuilder multiLineRight = new StringBuilder();
    for (int i = 0; i < 120; i++) {
      multiLineLeft.append("def d").append(i).append(" : Nat := ").append(i).append('\n');
      multiLineRight.append("def d").append(i).append(" : Nat := ").append(i + 1).append('\n');
    }
    SafeHtml aborted =
        LeanHtml.renderDiff(
            multiLineLeft.length(),
            multiLineLeft.toString(),
            multiLineRight.length(),
            multiLineRight.toString(),
            new Config(),
            null,
            null,
            "Cpu.lean",
            cpuBudget);
    assertThat(aborted).isNull();
    assertThat(cpuBudget.isExhausted()).isTrue();
    assertThat(
            LeanHtml.renderDiff(
                smallLeft.length(),
                smallLeft,
                smallRight.length(),
                smallRight,
                new Config(),
                null,
                null,
                "AfterCpu.lean",
                cpuBudget))
        .isNull();
  }

  @Test
  public void renderDiffAbortsCooperativelyInsideAlignLeanBlocks() {
    StringBuilder left = new StringBuilder();
    StringBuilder right = new StringBuilder();
    for (int i = 0; i < 200; i++) {
      left.append("def f").append(i).append(" (x : Nat) : Nat :=\n  x + ").append(i).append("\n\n");
      right
          .append("def f")
          .append(i)
          .append(" (y : Nat) : Nat :=\n  y * 2 + ")
          .append(i + 1)
          .append("\n\n");
    }

    // Calls 1..4 (ctor, beginOperation, post-parse check, align entry) stay under 750 ms;
    // call 5 (inside alignLeanBlocks loops) crosses the CPU deadline and aborts cleanly.
    AtomicInteger clockCalls = new AtomicInteger();
    LeanRequestBudget midAlignBudget =
        LeanRequestBudget.forTesting(
            LeanRequestBudget.MAX_REQUEST_INPUT_BYTES,
            LeanRequestBudget.MAX_REQUEST_OUTPUT_CHARS,
            50,
            750_000_000L,
            () -> clockCalls.incrementAndGet() >= 5 ? 900_000_000L : 100_000_000L);

    SafeHtml aborted =
        LeanHtml.renderDiff(
            left.length(),
            left.toString(),
            right.length(),
            right.toString(),
            new Config(),
            null,
            null,
            "MidAlignAbort.lean",
            midAlignBudget);
    assertThat(aborted).isNull();
    assertThat(midAlignBudget.isExhausted()).isTrue();
    assertThat(midAlignBudget.filesRendered()).isEqualTo(0);
    assertThat(clockCalls.get()).isAtLeast(5);
    assertThat(clockCalls.get()).isLessThan(15);
  }
}
