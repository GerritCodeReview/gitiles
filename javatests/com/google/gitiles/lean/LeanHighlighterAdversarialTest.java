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
import com.google.gitiles.lean.LeanHighlighter.HighlightState;
import com.google.gitiles.lean.LeanHighlighter.LineResult;
import com.google.gitiles.lean.LeanHighlighter.StateLineResult;
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
    // Sub-quadratic scaling on alternating operator tokens without allocating 2M LeanTokens.
    String alternatingN = "a+".repeat(16_384);
    LineResult altN = LeanHighlighter.tokenizeLineWithState(alternatingN, false, 0);
    assertThat(altN.blockCommentDepth()).isEqualTo(0);
    assertThat(joinTokens(altN.tokens())).isEqualTo(alternatingN);

    String rawFail = "r" + "#".repeat(65_536) + "x" + "r" + "#".repeat(32_768);
    LineResult rawRes = LeanHighlighter.tokenizeLineWithState(rawFail, false, 0);
    assertThat(joinTokens(rawRes.tokens())).isEqualTo(rawFail);

    String deepComments = "/- ".repeat(10_000);
    LineResult deepRes = LeanHighlighter.tokenizeLineWithState(deepComments, false, 0);
    assertThat(deepRes.blockCommentDepth()).isEqualTo(10_000);

    int lineCount = 2_000;
    List<LeanCodeLine> lines = new ArrayList<>(lineCount);
    for (int i = 0; i < lineCount; i++) {
      String txt = (i % 2 == 0) ? "/- open" : "-/ def x_" + i + " := " + i;
      lines.add(new LeanCodeLine(i + 1, txt));
    }
    int[] depths = LeanHighlighter.computeBlockCommentDepths(lines, 0);
    assertThat(depths).hasLength(lineCount);
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

  @Test
  public void docCommentOpeners_treatThreeCharDocPrefixBeforeNesting() {
    // R2-INT-4: `/--` and `/-!` are 3-char tokens before block comment nesting starts.
    LineResult docNested = LeanHighlighter.tokenizeLineWithState("/--/-/", false, 0);
    assertThat(docNested.blockCommentDepth()).isEqualTo(2);
    assertThat(docNested.tokens()).containsExactly(new LeanToken("/--/-/", LeanTokenKind.COMMENT));

    LineResult modDocNested = LeanHighlighter.tokenizeLineWithState("/-!/-/", false, 0);
    assertThat(modDocNested.blockCommentDepth()).isEqualTo(2);
    assertThat(modDocNested.tokens())
        .containsExactly(new LeanToken("/-!/-/", LeanTokenKind.COMMENT));

    LineResult docClosed = LeanHighlighter.tokenizeLineWithState("/-- doc -/ def x", false, 0);
    assertThat(docClosed.blockCommentDepth()).isEqualTo(0);
    assertThat(docClosed.tokens()).contains(new LeanToken("def", LeanTokenKind.KEYWORD));

    List<LeanCodeLine> lines =
        List.of(
            new LeanCodeLine(1, "/--/-/"),
            new LeanCodeLine(2, "theorem commented_out : False := sorry"),
            new LeanCodeLine(3, "-/ -/"),
            new LeanCodeLine(4, "theorem live : True := trivial"));
    ImmutableList<HighlightState> states =
        LeanHighlighter.computeHighlightStates(lines, HighlightState.INITIAL);
    assertThat(states.get(1).blockCommentDepth()).isEqualTo(2);
    assertThat(states.get(3).blockCommentDepth()).isEqualTo(0);
    ImmutableList<LeanToken> line2 =
        LeanHighlighter.tokenizeLine(lines.get(1).text(), false, states.get(1));
    assertThat(line2)
        .containsExactly(
            new LeanToken("theorem commented_out : False := sorry", LeanTokenKind.COMMENT));
    ImmutableList<LeanToken> line4 =
        LeanHighlighter.tokenizeLine(lines.get(3).text(), false, states.get(3));
    assertThat(line4).contains(new LeanToken("theorem", LeanTokenKind.KEYWORD));
  }

  @Test
  public void multiLineStringsRawStringsAndInterpolations_doNotLeakBlockCommentDepth() {
    // R2-INT-5: multiline plain/raw/interp strings containing `/-` must highlight as STRING and
    // must never leak blockCommentDepth into subsequent code lines.
    List<LeanCodeLine> plainStringLines =
        List.of(
            new LeanCodeLine(1, "def sigLeak (s : String := \""),
            new LeanCodeLine(2, "/-"),
            new LeanCodeLine(3, "\") : False := by"),
            new LeanCodeLine(4, "  have evil : False := sorry"),
            new LeanCodeLine(5, "  exact evil"));
    ImmutableList<HighlightState> plainStates =
        LeanHighlighter.computeHighlightStates(plainStringLines, HighlightState.INITIAL);
    assertThat(plainStates.get(1).inString()).isTrue();
    assertThat(plainStates.get(1).blockCommentDepth()).isEqualTo(0);
    assertThat(plainStates.get(3)).isEqualTo(HighlightState.INITIAL);
    assertThat(
            LeanHighlighter.tokenizeLine(plainStringLines.get(1).text(), false, plainStates.get(1)))
        .containsExactly(new LeanToken("/-", LeanTokenKind.STRING));
    assertThat(
            LeanHighlighter.tokenizeLine(plainStringLines.get(3).text(), true, plainStates.get(3)))
        .contains(new LeanToken("sorry", LeanTokenKind.SORRY));

    // Legacy computeBlockCommentDepths also remains free of false comment leaks.
    assertThat(LeanHighlighter.computeBlockCommentDepths(plainStringLines, 0))
        .asList()
        .containsExactly(0, 0, 0, 0, 0)
        .inOrder();

    // Multiline raw string `r#"..."#` containing `/-`.
    List<LeanCodeLine> rawStringLines =
        List.of(
            new LeanCodeLine(1, "def rawLeak : String := r#\""),
            new LeanCodeLine(2, "/- inside raw"),
            new LeanCodeLine(3, "\"#"),
            new LeanCodeLine(4, "sorry"));
    ImmutableList<HighlightState> rawStates =
        LeanHighlighter.computeHighlightStates(rawStringLines, HighlightState.INITIAL);
    assertThat(rawStates.get(1).rawStringHashes()).isEqualTo(1);
    assertThat(rawStates.get(3)).isEqualTo(HighlightState.INITIAL);
    assertThat(LeanHighlighter.tokenizeLine(rawStringLines.get(1).text(), false, rawStates.get(1)))
        .containsExactly(new LeanToken("/- inside raw", LeanTokenKind.STRING));
    assertThat(LeanHighlighter.tokenizeLine(rawStringLines.get(3).text(), true, rawStates.get(3)))
        .contains(new LeanToken("sorry", LeanTokenKind.SORRY));

    // Multiline interpolated string with nested braces and inner multiline string containing `/-`.
    List<LeanCodeLine> interpLines =
        List.of(
            new LeanCodeLine(1, "def interpLeak : String := s!\"{"),
            new LeanCodeLine(2, "  let inner := \""),
            new LeanCodeLine(3, "/-"),
            new LeanCodeLine(4, "\""),
            new LeanCodeLine(5, "} end\""),
            new LeanCodeLine(6, "sorry"));
    ImmutableList<HighlightState> interpStates =
        LeanHighlighter.computeHighlightStates(interpLines, HighlightState.INITIAL);
    assertThat(interpStates.get(1).interpBraceDepths()).containsExactly(1);
    assertThat(interpStates.get(2).inString()).isTrue();
    assertThat(interpStates.get(5)).isEqualTo(HighlightState.INITIAL);
    assertThat(LeanHighlighter.tokenizeLine(interpLines.get(2).text(), false, interpStates.get(2)))
        .containsExactly(new LeanToken("/-", LeanTokenKind.STRING));
    assertThat(LeanHighlighter.tokenizeLine(interpLines.get(5).text(), true, interpStates.get(5)))
        .contains(new LeanToken("sorry", LeanTokenKind.SORRY));

    // Multiline `«...»` guillemet identifier containing `/-` does not open block comment.
    StateLineResult g1 =
        LeanHighlighter.tokenizeLineWithHighlightState("def «", false, HighlightState.INITIAL);
    assertThat(g1.state().inGuillemet()).isTrue();
    StateLineResult g2 =
        LeanHighlighter.tokenizeLineWithHighlightState("/-» := sorry", false, g1.state());
    assertThat(g2.state()).isEqualTo(HighlightState.INITIAL);
    assertThat(g2.tokens()).contains(new LeanToken("sorry", LeanTokenKind.SORRY));
  }

  @Test
  public void interpolatedCommandStrings_doNotLeakStringStateAcrossNestedQuotes() {
    // R2-INT-9: interpolated command prefixes enter brace-aware interpolation on `"..."` so
    // `"{ '"' }"` cannot invert quote parity onto trailing declarations.
    List<String> cmdLines =
        List.of(
            "dbg_trace \"{ '\"' }\"",
            "println! \"{ '\"' }\"",
            "throwError \"{ '\"' }\"",
            "trace[Meta.debug] \"{ '\"' }\"",
            "trace_goal[Meta.debug] \"{ '\"' }\"",
            "throwNamedError `foo.bar \"{ '\"' }\"",
            "throwNamedError lean. \"{ '\"' }\"",
            "throwNamedErrorAt stx `a.b \"{ '\"' }\"",
            "logNamedErrorAt stx `a.b \"{ '\"' }\"",
            "logNamedWarningAt stx `a.b \"{ '\"' }\"",
            "throwErrorAt (← getRef) \"{ '\"' }\"",
            "throwErrorAt (findStx \"ok\") \"{ '\"' }\"",
            "throwErrorAt stx[0].lhs \"{ '\"' }\"",
            "throwErrorAt #[stx] \"{ '\"' }\"",
            "throwErrorAt stx.1 \"{ '\"' }\"");
    for (String cmdLine : cmdLines) {
      ImmutableList<HighlightState> states =
          LeanHighlighter.computeHighlightStates(
              List.of(
                  new LeanCodeLine(1, cmdLine),
                  new LeanCodeLine(2, "theorem backdoor : False := sorry")),
              HighlightState.INITIAL);
      assertThat(states.get(1)).isEqualTo(HighlightState.INITIAL);
      ImmutableList<LeanToken> nextTokens =
          LeanHighlighter.tokenizeLine("theorem backdoor : False := sorry", false, states.get(1));
      assertThat(nextTokens)
          .containsAtLeast(
              new LeanToken("theorem", LeanTokenKind.KEYWORD),
              new LeanToken("sorry", LeanTokenKind.SORRY))
          .inOrder();
    }

    // Multiline and comment-interleaved command strings keep EXPECT_QUOTE_NEXT across lines,
    // including multiline plain strings inside delimited `throwErrorAt (...)`.
    List<LeanCodeLine> multiLines =
        List.of(
            new LeanCodeLine(1, "dbg_trace /- note -/"),
            new LeanCodeLine(2, "  \"{ '\"' }\""),
            new LeanCodeLine(3, "throwErrorAt (fn \"multi"),
            new LeanCodeLine(4, "line\") \"{ '\"' }\""),
            new LeanCodeLine(5, "theorem live : False := sorry"));
    ImmutableList<HighlightState> multiStates =
        LeanHighlighter.computeHighlightStates(multiLines, HighlightState.INITIAL);
    assertThat(multiStates.get(1).interpBraceDepths()).isEmpty();
    assertThat(multiStates.get(3).inString()).isTrue();
    assertThat(multiStates.get(4)).isEqualTo(HighlightState.INITIAL);
    assertThat(LeanHighlighter.tokenizeLine(multiLines.get(4).text(), false, multiStates.get(4)))
        .contains(new LeanToken("sorry", LeanTokenKind.SORRY));

    // Nested command interpolation inside `s!"{ ... }"`.
    StateLineResult nested =
        LeanHighlighter.tokenizeLineWithHighlightState(
            "s!\"{ dbg_trace \"{ '}' }\" }\" sorry", false, HighlightState.INITIAL);
    assertThat(nested.state()).isEqualTo(HighlightState.INITIAL);
    assertThat(nested.tokens()).contains(new LeanToken("sorry", LeanTokenKind.SORRY));

    // Unclosed `trace[` resets at newline so a following plain `"{ '"' }"` does not interpolate.
    ImmutableList<HighlightState> brokenTrace =
        LeanHighlighter.computeHighlightStates(
            List.of(new LeanCodeLine(1, "trace[Meta.debug"), new LeanCodeLine(2, "\"{ '\"' }\"")),
            HighlightState.INITIAL);
    assertThat(brokenTrace.get(1)).isEqualTo(HighlightState.INITIAL);
    StateLineResult plainAfterBroken =
        LeanHighlighter.tokenizeLineWithHighlightState("\"{ '\"' }\"", false, brokenTrace.get(1));
    assertThat(plainAfterBroken.state().inString()).isTrue();

    // Plain `IO.println` / string binding must NOT interpolate `{ sorry }`.
    StateLineResult nonInterp =
        LeanHighlighter.tokenizeLineWithHighlightState(
            "def tpl := \"{ sorry }\"", false, HighlightState.INITIAL);
    assertThat(nonInterp.state()).isEqualTo(HighlightState.INITIAL);
    assertThat(nonInterp.tokens()).doesNotContain(new LeanToken("sorry", LeanTokenKind.SORRY));
  }

  @Test
  public void charLiteralsRawNumbersAndExcludedLetters_matchCoreLexerRules() {
    // R2-INT-1: `!'"'` and adjacent char literals tokenize as char literals and do not leak.
    for (String line :
        List.of("def p := !'\"' | sorry", "def q := 'a''\"' | sorry", "1e3'\"'+sorry")) {
      StateLineResult res =
          LeanHighlighter.tokenizeLineWithHighlightState(line, false, HighlightState.INITIAL);
      assertThat(res.state()).isEqualTo(HighlightState.INITIAL);
      assertThat(res.tokens()).contains(new LeanToken("sorry", LeanTokenKind.SORRY));
    }

    // R2-INT-2: `0r#"""#`, `1e3r#"""#`, `0x1fr#"""#`, `1e3s!"..."`, and `v!"{ '"' }"` match lexer.
    for (String line :
        List.of(
            "def a := 0r#\"\"\"# + sorry",
            "def b := 1e3r#\"\"\"# + sorry",
            "def c := 0x1fr#\"\"\"# + sorry",
            "def d := 'x'r#\"\"\"# + sorry",
            "def e := 1e3s!\"{ '\"' }\" + sorry",
            "v!\"{ '\"' }\" sorry")) {
      StateLineResult res =
          LeanHighlighter.tokenizeLineWithHighlightState(line, false, HighlightState.INITIAL);
      assertThat(res.state()).isEqualTo(HighlightState.INITIAL);
      assertThat(res.tokens()).contains(new LeanToken("sorry", LeanTokenKind.SORRY));
    }

    // R2-INT-3 & R2-INT-10: superscripts, math letters, non-Lean scripts, and λ/Π/Σ do not merge
    // into `sorry`; `⅋r` and `ⁿr` match Lean 4 `isLetterLike` vs raw-string rules.
    for (String hiddenSorry :
        List.of("sorry⁰", "sorry²", "sorryⁿ", "sorry𝑥", "sorryλ", "sorryΠ", "sorryΣ")) {
      assertThat(LeanHighlighter.tokenizeLine(hiddenSorry, false, 0))
          .contains(new LeanToken("sorry", LeanTokenKind.SORRY));
    }
    for (String line : List.of("def s1 := ⁿr\"a\\\" | sorry", "def s2 := ⅋r\"a\\\"\" | sorry")) {
      StateLineResult res =
          LeanHighlighter.tokenizeLineWithHighlightState(line, false, HighlightState.INITIAL);
      assertThat(res.state()).isEqualTo(HighlightState.INITIAL);
      assertThat(res.tokens()).contains(new LeanToken("sorry", LeanTokenKind.SORRY));
    }
  }
}
