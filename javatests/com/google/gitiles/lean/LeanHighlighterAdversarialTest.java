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
import com.google.gitiles.lean.LeanHighlighter.LineResult;
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
public final class LeanHighlighterAdversarialTest {

  private static final int MAX_LINES = 20_000;

  private static String joinTokens(List<LeanToken> tokens) {
    StringBuilder sb = new StringBuilder();
    for (LeanToken tok : tokens) {
      sb.append(tok.text());
    }
    return sb.toString();
  }

  @Test
  public void blockCommentDepth_neverOverflowsNearIntMax() {
    LineResult fromMax =
        LeanHighlighter.tokenizeLineWithState("/- /- nested /- comment", false, Integer.MAX_VALUE);
    assertThat(fromMax.blockCommentDepth()).isEqualTo(Integer.MAX_VALUE);
    assertThat(joinTokens(fromMax.tokens())).isEqualTo("/- /- nested /- comment");

    LineResult openThenNested =
        LeanHighlighter.tokenizeLineWithState("a /- /- nested", false, Integer.MAX_VALUE - 1);
    assertThat(openThenNested.blockCommentDepth()).isAtLeast(0);

    List<LeanCodeLine> lines =
        List.of(
            new LeanCodeLine(1, "/- nested /-"),
            new LeanCodeLine(2, "-/"),
            new LeanCodeLine(3, "sorry"));
    int[] depths = LeanHighlighter.computeBlockCommentDepths(lines, Integer.MAX_VALUE);
    assertThat(depths[0]).isEqualTo(Integer.MAX_VALUE);
    assertThat(depths[1]).isEqualTo(Integer.MAX_VALUE);
    assertThat(depths[2]).isEqualTo(Integer.MAX_VALUE - 1);
    assertThat(LeanHighlighter.computeFinalBlockCommentDepth(lines, Integer.MAX_VALUE))
        .isEqualTo(Integer.MAX_VALUE - 1);
  }

  @Test
  public void hashLeadingTokens_doNotAbsorbStringsOrSoundnessKeywords() {
    ImmutableList<LeanToken> hashSorry = LeanHighlighter.tokenizeLine("#sorry", true, 0);
    assertThat(joinTokens(hashSorry)).isEqualTo("#sorry");
    assertThat(hashSorry).contains(new LeanToken("sorry", LeanTokenKind.SORRY));

    ImmutableList<LeanToken> hashInterp =
        LeanHighlighter.tokenizeLine("#s!\"hi {x}\" sorry", false, 0);
    assertThat(joinTokens(hashInterp)).isEqualTo("#s!\"hi {x}\" sorry");
    assertThat(hashInterp).contains(new LeanToken("s!\"hi {x}\"", LeanTokenKind.STRING));
    assertThat(hashInterp).contains(new LeanToken("sorry", LeanTokenKind.SORRY));

    ImmutableList<LeanToken> hashQuoted = LeanHighlighter.tokenizeLine("#\"\\\"\" sorry", false, 0);
    assertThat(joinTokens(hashQuoted)).isEqualTo("#\"\\\"\" sorry");
    assertThat(hashQuoted).contains(new LeanToken("sorry", LeanTokenKind.SORRY));

    ImmutableList<LeanToken> hashRaw = LeanHighlighter.tokenizeLine("#r\"a\\\" sorry", false, 0);
    assertThat(joinTokens(hashRaw)).isEqualTo("#r\"a\\\" sorry");
    assertThat(hashRaw).contains(new LeanToken("r\"a\\\"", LeanTokenKind.STRING));
    assertThat(hashRaw).contains(new LeanToken("sorry", LeanTokenKind.SORRY));

    ImmutableList<LeanToken> hashRawHashes =
        LeanHighlighter.tokenizeLine("#r#\"a \" b\"# sorry", false, 0);
    assertThat(joinTokens(hashRawHashes)).isEqualTo("#r#\"a \" b\"# sorry");
    assertThat(hashRawHashes).contains(new LeanToken("r#\"a \" b\"#", LeanTokenKind.STRING));
    assertThat(hashRawHashes).contains(new LeanToken("sorry", LeanTokenKind.SORRY));
  }

  @Test
  public void unicodeLoneSurrogatesNulBomBidiAndCombiningMarks_remainLossless() {
    List<String> hostileLines =
        List.of(
            "\uFEFF\u0000\u202Etheorem «\u200BsorryAx» := sorry\uFFFD",
            "def x\u0301 := \"\uD800\" + '\uDC00' + s!\"{\uD83D\uDE00}\"",
            "/- \u2066bidi \uD800 lone \u2069 -/ «unclosed",
            "r\"\\\"",
            "@[simp [a, b] \u0000 unclosed");

    for (String line : hostileLines) {
      ImmutableList<LeanToken> tokens = LeanHighlighter.tokenizeLine(line, true, 0);
      assertThat(joinTokens(tokens)).isEqualTo(line);
      for (LeanToken tok : tokens) {
        assertThat(tok.text()).isNotEmpty();
      }
    }
  }

  @Test
  public void maxCapSingleLineAnd20kLines_completeQuicklyAndLosslessly() {
    String twoMibAlternating = "a+".repeat(1 << 20);
    LineResult altRes = LeanHighlighter.tokenizeLineWithState(twoMibAlternating, false, 0);
    assertThat(altRes.blockCommentDepth()).isEqualTo(0);
    assertThat(joinTokens(altRes.tokens())).isEqualTo(twoMibAlternating);

    String rawFail = "r" + "#".repeat(1 << 20) + "x" + "r" + "#".repeat(1 << 19);
    LineResult rawRes = LeanHighlighter.tokenizeLineWithState(rawFail, false, 0);
    assertThat(joinTokens(rawRes.tokens())).isEqualTo(rawFail);

    String deepComments = "/- ".repeat(650_000);
    LineResult deepRes = LeanHighlighter.tokenizeLineWithState(deepComments, false, 0);
    assertThat(deepRes.blockCommentDepth()).isEqualTo(650_000);

    List<LeanCodeLine> lines = new ArrayList<>(MAX_LINES);
    for (int i = 0; i < MAX_LINES; i++) {
      String txt = (i % 2 == 0) ? "/- open" : "-/ def x_" + i + " := " + i;
      lines.add(new LeanCodeLine(i + 1, txt));
    }
    int[] depths = LeanHighlighter.computeBlockCommentDepths(lines, 0);
    assertThat(depths).hasLength(MAX_LINES);
    assertThat(depths[0]).isEqualTo(0);
    assertThat(depths[1]).isEqualTo(1);
    assertThat(LeanHighlighter.computeFinalBlockCommentDepth(lines, 0)).isEqualTo(0);
  }

  @Test
  public void concurrentHighlighting_hasNoSharedMutableState() throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(4);
    try {
      List<Callable<Boolean>> tasks = new ArrayList<>();
      for (int t = 0; t < 8; t++) {
        final int tid = t;
        tasks.add(() -> {
          String line =
              (tid % 2 == 0)
                  ? "theorem t" + tid + " := by /- c -/ omega"
                  : "def x" + tid + " := s!\"{r#\"a\"#}\" sorry";
          for (int i = 0; i < 200; i++) {
            LineResult res = LeanHighlighter.tokenizeLineWithState(line, tid % 2 == 0, 0);
            if (!joinTokens(res.tokens()).equals(line)) {
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
}
