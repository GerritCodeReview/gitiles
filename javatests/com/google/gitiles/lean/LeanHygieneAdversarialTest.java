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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Adversarial evasion and false-positive tests for {@link LeanHygiene}. */
@RunWith(JUnit4.class)
public final class LeanHygieneAdversarialTest {

  @Test
  public void detectsAdversarialHygieneEvasionVectors() {
    // Unclosed delimiter prior decl cannot hide an appended `sorry` theorem from total count.
    String openDelim = "def d := sorry (\n\ntheorem appended : False := by sorry\n";
    assertThat(LeanHygiene.count(LeanParser.parse(openDelim)).sorryCount()).isEqualTo(2);

    // Import-line trailing theorem evasion (S-B-1).
    String importEvasion = "import Init.Prelude theorem bad : False := sorry\n";
    assertThat(LeanHygiene.count(LeanParser.parse(importEvasion)).sorryCount()).isEqualTo(1);

    // Lean 4 raw string without escape (`r"\"`) closes at second `"`.
    assertThat(
            LeanHygiene.count(LeanParser.parse("def h := r\"\\\"\ntheorem bad : False := sorry\n"))
                .sorryCount())
        .isEqualTo(1);

    // Leading `#` prefix + raw/interpolated string evasions (S-B-7).
    assertThat(LeanHygiene.count(LeanParser.parse("def a := #s!\"{sorry}\"\n")).sorryCount())
        .isEqualTo(1);
    assertThat(
            LeanHygiene.count(LeanParser.parse("#r\"\\\"\ntheorem bad : False := sorry\n"))
                .sorryCount())
        .isEqualTo(1);

    // Unprefixed string interpolations (`dbg_trace`, `throwError`, custom syntax) count `{...}`
    // hazards for hygiene counting only (S-B-8, #92 rule 4).
    assertThat(
            LeanHygiene.count(
                    LeanParser.parse("def d : IO Unit := dbg_trace \"{(sorry : Nat)}\"\n"))
                .sorryCount())
        .isEqualTo(1);
    assertThat(
            LeanHygiene.count(LeanParser.parse("def e : IO Unit := throwError \"bad: {admit}\"\n"))
                .admitCount())
        .isEqualTo(1);
    assertThat(
            LeanHygiene.count(
                    LeanParser.parse("def d2 : IO Unit := dbg_trace \"{ -- }\n sorry }\n"))
                .sorryCount())
        .isEqualTo(1);
    assertThat(
            LeanHygiene.count(
                    LeanParser.parse("def d3 : IO Unit := dbg_trace \"{ /- } -/ sorry }\n"))
                .sorryCount())
        .isEqualTo(1);
    assertThat(
            LeanHygiene.count(LeanParser.parse("def d4 : IO Unit := dbg_trace \"{ '}' sorry }\n"))
                .sorryCount())
        .isEqualTo(1);

    // Multi-line literal spanning fake `/--` inside directive/fallback/declaration (CODE-22).
    assertThat(
            LeanHygiene.count(
                    LeanParser.parse("#eval \"a\n/--\n\"\ntheorem backdoor : False := by sorry\n"))
                .sorryCount())
        .isEqualTo(1);
    assertThat(
            LeanHygiene.count(
                    LeanParser.parse(
                        "def msg : String := s!\"{\n/-- note -/\n}\"\n"
                            + "def fuzz_dbg : IO Unit := dbg_trace \"{sorry}\"\n"))
                .sorryCount())
        .isEqualTo(1);
    assertThat(
            LeanHygiene.count(
                    LeanParser.parse(
                        "def msg2 : String := s!\"{\n\"/--\"\n}\"\n"
                            + "def fuzz_dbg : IO Unit := dbg_trace \"{sorry}\"\n"))
                .sorryCount())
        .isEqualTo(1);

    // Unterminated string/comment detection flags incomplete lexical state cleanly (#92 rule 2).
    ImmutableList<LeanBlock> unterminatedBlocks =
        LeanParser.parse("def h := r#\"ok\"\ntheorem hidden : False := sorry\n");
    assertThat(LeanHygiene.hasUnterminatedLiteralOrComment(unterminatedBlocks)).isTrue();

    // PendingDoc/PendingComment/DECLARATION raw spans must not duplicate tokens on shared lines,
    // preserving lexical parity between LeanHygiene and LeanLexer.
    for (String paritySrc :
        ImmutableList.of(
            "/-- Doc -/ \"unclosed\n",
            "/-- Doc -/ \"hello\nworld\"\n",
            "/-- doc -/ /- c -/ def a := \"x\ny\"\n",
            "open A in /-- Doc -\n-/ theorem t := \"hello\nworld\"\n")) {
      assertThat(LeanHygiene.hasUnterminatedLiteralOrComment(LeanParser.parse(paritySrc)))
          .isEqualTo(LeanLexer.hasUnterminatedLiteralOrComment(paritySrc));
    }

    // Trojan Source / NUL / invisible controls around executable hazards cannot hide them.
    String invisiblePrefix = "\u200Esorry\n\0admit\n\u202Eunsafe\n";
    LeanFileCounts invCounts = LeanHygiene.count(LeanParser.parse(invisiblePrefix));
    assertThat(invCounts.sorryCount()).isEqualTo(1);
    assertThat(invCounts.admitCount()).isEqualTo(1);
    assertThat(invCounts.unsafeCount()).isEqualTo(1);

    // Kernel soundness constants in guillemets are detected.
    String guillemets =
        "theorem a : False := «sorryAx» False false\n"
            + "theorem b : True := Lean.«ofReduceBool» true rfl\n"
            + "theorem c : True := Lean.«ofReduceNat» 0 rfl\n";
    LeanFileCounts gCounts = LeanHygiene.count(LeanParser.parse(guillemets));
    assertThat(gCounts.sorryCount()).isEqualTo(1);
    assertThat(gCounts.nativeDecideCount()).isEqualTo(2);
  }

  @Test
  public void avoidsFalsePositivesOnSafeIdentifiersModulesCommentsAndStrings() {
    String safe =
        String.join(
            "\n",
            "import Mathlib.Tactic.Sorry",
            "import Std.Internal.Unsafe",
            "-- sorry admit native_decide axiom partial unsafe",
            "/- outer /- sorry admit sorryAx -/ still comment -/",
            "/-- Docstring mentioning `sorry` and `ofReduceBool`. -/",
            "def s : String := \"sorry admit native_decide axiom partial unsafe\"",
            "def braceOnly : String := \"{\"",
            "def escapedBrace : String := \"\\{sorry}\"",
            "def r : String := r#\"quoted \"sorry\" and {sorry} and native_decide\"#",
            "def «sorry» : Nat := 1",
            "def «admit» : Nat := 2",
            "def «native_decide» : Nat := 3",
            "def sorry' : Nat := 4",
            "def sorry? : Nat := 5",
            "def sorry₀ : Nat := 6",
            "def αsorry : Nat := 7",
            "def s\u0301orry : Nat := 8",
            "def so\u202Erry : Nat := 9",
            "def safeBraceStr : String := \"{ -- }\nsorry\"");

    LeanFileCounts counts = LeanHygiene.count(LeanParser.parse(safe));
    assertThat(counts.sorryCount()).isEqualTo(0);
    assertThat(counts.admitCount()).isEqualTo(0);
    assertThat(counts.nativeDecideCount()).isEqualTo(0);
    assertThat(counts.axioms()).isEqualTo(0);
    assertThat(counts.partialCount()).isEqualTo(0);
    assertThat(counts.unsafeCount()).isEqualTo(0);
  }

  @Test
  public void hygieneCountIsThreadSafe() throws Exception {
    String src =
        "import Init\ntheorem t : False := by sorry\naxiom ax : False\n#eval native_decide\n";
    ImmutableList<LeanBlock> blocks = LeanParser.parse(src);
    LeanFileCounts expected = LeanHygiene.count(blocks);
    assertThat(LeanHygiene.hasUnterminatedLiteralOrComment(blocks)).isFalse();
    ExecutorService pool = Executors.newFixedThreadPool(4);
    try {
      List<Callable<LeanFileCounts>> tasks = new ArrayList<>();
      for (int i = 0; i < 40; i++) {
        tasks.add(() -> LeanHygiene.count(LeanParser.parse(src)));
      }
      List<Future<LeanFileCounts>> futures = pool.invokeAll(tasks);
      for (Future<LeanFileCounts> f : futures) {
        assertThat(f.get()).isEqualTo(expected);
      }
    } finally {
      pool.shutdownNow();
    }
  }
}
