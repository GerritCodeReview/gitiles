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
import com.google.gitiles.lean.LeanHighlighter.LineResult;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class LeanHighlighterTest {

  private static String joinTokens(List<LeanToken> tokens) {
    StringBuilder sb = new StringBuilder();
    for (LeanToken tok : tokens) {
      sb.append(tok.text());
    }
    return sb.toString();
  }

  private static ImmutableList<String> formatKinds(List<LeanToken> tokens) {
    ImmutableList.Builder<String> out = ImmutableList.builder();
    for (LeanToken tok : tokens) {
      out.add(tok.kind() + ":" + tok.text());
    }
    return out.build();
  }

  @Test
  public void tokenizeLine_highlightsKeywordsTacticsTypesOperatorsBulletsSorryAndPrimes() {
    String line = "  · have hlt' : i < k := by omega -- comment";
    ImmutableList<LeanToken> tokens = LeanHighlighter.tokenizeLine(line, true, 0);
    assertThat(joinTokens(tokens)).isEqualTo(line);

    ImmutableList<String> kinds = formatKinds(tokens);
    assertThat(kinds).contains("BULLET:·");
    assertThat(kinds).contains("KEYWORD:have");
    assertThat(
            tokens.stream()
                .anyMatch(t -> t.kind() == LeanTokenKind.PLAIN && t.text().contains("hlt'")))
        .isTrue();
    assertThat(tokens.stream().anyMatch(t -> t.kind() == LeanTokenKind.STRING)).isFalse();
    assertThat(kinds).contains("TACTIC:omega");
    assertThat(kinds).contains("COMMENT:-- comment");
  }

  @Test
  public void tokenizeLine_rawInterpCharAttributesRadixAndTactics() {
    String rawLine =
        "@[simp [Nat.add_comm]] public def demo := (r#\"quoted \"sorry\"\"#, s!\"hi {x}\", '\"',"
            + " '\\u03bb', 0xff_00, 0b1010, 0o755)";
    ImmutableList<LeanToken> tokens = LeanHighlighter.tokenizeLine(rawLine, false, 0);
    assertThat(joinTokens(tokens)).isEqualTo(rawLine);

    ImmutableList<String> pairs = formatKinds(tokens);
    assertThat(pairs).contains("ATTRIBUTE:@[simp [Nat.add_comm]]");
    assertThat(pairs).contains("KEYWORD:public");
    assertThat(pairs).contains("KEYWORD:def");
    assertThat(pairs).contains("STRING:r#\"quoted \"sorry\"\"#");
    assertThat(pairs).contains("STRING:s!\"hi {x}\"");
    assertThat(pairs).contains("STRING:'\"'");
    assertThat(pairs).contains("STRING:'\\u03bb'");
    assertThat(pairs).contains("NUMBER:0xff_00");
    assertThat(pairs).contains("NUMBER:0b1010");
    assertThat(pairs).contains("NUMBER:0o755");
    assertThat(tokens.stream().anyMatch(t -> t.kind() == LeanTokenKind.SORRY)).isFalse();

    String tacticLine = "  conv => lhs; arg 2; simp_wf <;> grind <;> bv_decide";
    ImmutableList<LeanToken> tacticTokens = LeanHighlighter.tokenizeLine(tacticLine, true, 0);
    assertThat(joinTokens(tacticTokens)).isEqualTo(tacticLine);

    ImmutableList<String> tacticPairs = formatKinds(tacticTokens);
    assertThat(tacticPairs).contains("KEYWORD:conv");
    assertThat(tacticPairs).contains("TACTIC:lhs");
    assertThat(tacticPairs).contains("TACTIC:arg");
    assertThat(tacticPairs).contains("TACTIC:simp_wf");
    assertThat(tacticPairs).contains("OPERATOR:<;>");
    assertThat(tacticPairs).contains("TACTIC:grind");
    assertThat(tacticPairs).contains("TACTIC:bv_decide");

    // Context-sensitive tactics are PLAIN outside tactic context, while unambiguous ones stay
    // TACTIC.
    ImmutableList<LeanToken> nonTacticTokens =
        LeanHighlighter.tokenizeLine("def lhs := omega", false, 0);
    assertThat(
            nonTacticTokens.stream()
                .anyMatch(t -> t.kind() == LeanTokenKind.PLAIN && t.text().contains("lhs")))
        .isTrue();
    assertThat(nonTacticTokens).contains(new LeanToken("omega", LeanTokenKind.TACTIC));
  }

  @Test
  public void tokenizeLine_unicodeOperatorsVsAsciiOperators() {
    String line = "theorem t : ∀ x : Nat, x ≤ x := by";
    ImmutableList<LeanToken> tokens = LeanHighlighter.tokenizeLine(line, false, 0);
    assertThat(joinTokens(tokens)).isEqualTo(line);

    List<String> unicodeOps = new ArrayList<>();
    List<String> allOps = new ArrayList<>();
    for (LeanToken tok : tokens) {
      if (tok.kind() == LeanTokenKind.OPERATOR) {
        allOps.add(tok.text());
        if (LeanHighlighter.isUnicodeOperator(tok.text())) {
          unicodeOps.add(tok.text());
        }
      }
    }
    assertThat(unicodeOps).containsExactly("∀", "≤").inOrder();
    assertThat(allOps).containsExactly("∀", "≤", ":=").inOrder();
    assertThat(LeanHighlighter.isUnicodeOperator(":=")).isFalse();
    assertThat(LeanHighlighter.isUnicodeOperator(null)).isFalse();
  }

  @Test
  public void tokenizeLine_kernelSoundnessAndGuillemets() {
    ImmutableList<LeanToken> t1 =
        LeanHighlighter.tokenizeLine("  exact sorryAx False false", true, 0);
    assertThat(t1).contains(new LeanToken("sorryAx", LeanTokenKind.SORRY));

    ImmutableList<LeanToken> t2 =
        LeanHighlighter.tokenizeLine("  exact «sorryAx» False false", true, 0);
    assertThat(t2).contains(new LeanToken("«sorryAx»", LeanTokenKind.SORRY));

    ImmutableList<LeanToken> t3 =
        LeanHighlighter.tokenizeLine("  exact Lean.ofReduceBool true rfl", true, 0);
    assertThat(t3).contains(new LeanToken("ofReduceBool", LeanTokenKind.SORRY));

    ImmutableList<LeanToken> t4 = LeanHighlighter.tokenizeLine("  exact «sorry» + sorry", true, 0);
    assertThat(
            t4.stream()
                .anyMatch(t -> t.kind() == LeanTokenKind.PLAIN && t.text().contains("«sorry»")))
        .isTrue();
    assertThat(t4).contains(new LeanToken("sorry", LeanTokenKind.SORRY));
  }

  @Test
  public void tokenizeLine_interpolatedStringBoundaries() {
    String fLine = "def x := f!\"hello {a}\"";
    ImmutableList<LeanToken> fTokens = LeanHighlighter.tokenizeLine(fLine, false, 0);
    assertThat(fTokens).contains(new LeanToken("f!\"hello {a}\"", LeanTokenKind.STRING));

    // Nested quotes, char literals, raw strings, and nested braces inside interpolation.
    String nested = "def y := s!\"{ '\"' } { r#\"}\"# } { {a} }\" + sorry";
    ImmutableList<LeanToken> nestedTokens = LeanHighlighter.tokenizeLine(nested, false, 0);
    assertThat(joinTokens(nestedTokens)).isEqualTo(nested);
    assertThat(nestedTokens)
        .contains(new LeanToken("s!\"{ '\"' } { r#\"}\"# } { {a} }\"", LeanTokenKind.STRING));
    assertThat(nestedTokens).contains(new LeanToken("sorry", LeanTokenKind.SORRY));

    // panic!"{" and fé!"a" must NOT enter interpolated string mode.
    ImmutableList<LeanToken> panicTokens =
        LeanHighlighter.tokenizeLine("def m := panic!\"{\" sorry", false, 0);
    assertThat(panicTokens).contains(new LeanToken("sorry", LeanTokenKind.SORRY));
    ImmutableList<LeanToken> accentedTokens =
        LeanHighlighter.tokenizeLine("def n := fé!\"{\" sorry", false, 0);
    assertThat(accentedTokens).contains(new LeanToken("sorry", LeanTokenKind.SORRY));
  }

  @Test
  public void computeBlockCommentDepths_propagatesNestedBlockComments() {
    List<LeanCodeLine> declLines =
        List.of(
            new LeanCodeLine(1, "theorem t : True := by /- start note"),
            new LeanCodeLine(2, "  do not mention sorry or admit here"),
            new LeanCodeLine(3, "  -/ trivial"));
    int[] depths = LeanHighlighter.computeBlockCommentDepths(declLines, 0);
    assertThat(depths).asList().containsExactly(0, 1, 1).inOrder();
    assertThat(LeanHighlighter.computeFinalBlockCommentDepth(declLines, 0)).isEqualTo(0);

    LineResult line2 =
        LeanHighlighter.tokenizeLineWithState(declLines.get(1).text(), true, depths[1]);
    assertThat(line2.blockCommentDepth()).isEqualTo(1);
    assertThat(line2.tokens())
        .containsExactly(new LeanToken(declLines.get(1).text(), LeanTokenKind.COMMENT));

    LineResult line3 =
        LeanHighlighter.tokenizeLineWithState(declLines.get(2).text(), true, depths[2]);
    assertThat(line3.blockCommentDepth()).isEqualTo(0);
    assertThat(line3.tokens().get(0).kind()).isEqualTo(LeanTokenKind.COMMENT);
    assertThat(line3.tokens()).contains(new LeanToken("trivial", LeanTokenKind.TACTIC));

    // Nested block comments inside a single line and across lines.
    LineResult nested =
        LeanHighlighter.tokenizeLineWithState("/- outer /- inner -/ still -/ def x", false, 0);
    assertThat(nested.blockCommentDepth()).isEqualTo(0);
    assertThat(nested.tokens())
        .containsExactly(
            new LeanToken("/- outer /- inner -/ still -/", LeanTokenKind.COMMENT),
            new LeanToken(" ", LeanTokenKind.PLAIN),
            new LeanToken("def", LeanTokenKind.KEYWORD),
            new LeanToken(" x", LeanTokenKind.PLAIN))
        .inOrder();
  }

  @Test
  public void tokenizeLine_edgeCasesEmptySurrogatesUnclosedAndHugeLine() {
    // Empty and negative initial depth.
    LineResult empty = LeanHighlighter.tokenizeLineWithState("", false, -3);
    assertThat(empty.tokens()).isEmpty();
    assertThat(empty.blockCommentDepth()).isEqualTo(0);
    assertThat(LeanHighlighter.computeBlockCommentDepths(List.of(), 0)).isEmpty();
    assertThat(LeanHighlighter.computeFinalBlockCommentDepth(List.of(), -2)).isEqualTo(0);

    // Surrogate pairs (mathematical double-struck A U+1D538, emoji U+1F600, rocket U+1F680)
    // must remain lossless and never split UTF-16 surrogate halves.
    String surrogateLine =
        "\uFEFFdef \uD835\uDD38' := '\uD83D\uDE00' + s!\"\\\uD83D\uDE80\" \uD83D\uDE80 --"
            + " \uD83D\uDE00\r";
    ImmutableList<LeanToken> surrogateTokens =
        LeanHighlighter.tokenizeLine(surrogateLine, false, 0);
    assertThat(joinTokens(surrogateTokens)).isEqualTo(surrogateLine);
    for (LeanToken tok : surrogateTokens) {
      String text = tok.text();
      for (int idx = 0; idx < text.length(); idx++) {
        char c = text.charAt(idx);
        if (Character.isHighSurrogate(c)) {
          assertThat(idx + 1).isLessThan(text.length());
          assertThat(Character.isLowSurrogate(text.charAt(idx + 1))).isTrue();
          idx++;
        } else {
          assertThat(Character.isLowSurrogate(c)).isFalse();
        }
      }
    }
    assertThat(surrogateTokens).contains(new LeanToken("'\uD83D\uDE00'", LeanTokenKind.STRING));
    assertThat(surrogateTokens)
        .contains(new LeanToken("s!\"\\\uD83D\uDE80\"", LeanTokenKind.STRING));

    // Unclosed constructs terminate cleanly and stay lossless.
    List<String> unclosedSamples =
        List.of(
            "\"unclosed string",
            "s!\"unclosed {x",
            "r#\"unclosed raw",
            "@[unclosed attr",
            "«unclosed guillemet",
            "/- unclosed comment /- nested");
    for (String sample : unclosedSamples) {
      ImmutableList<LeanToken> tokList = LeanHighlighter.tokenizeLine(sample, false, 0);
      assertThat(joinTokens(tokList)).isEqualTo(sample);
    }

    // 100KB Lean line stays linear and lossless.
    String hugeLine = "def big : Nat := " + "1 + ".repeat(25_000) + "0";
    ImmutableList<LeanToken> hugeTokens = LeanHighlighter.tokenizeLine(hugeLine, false, 0);
    assertThat(hugeTokens.size()).isGreaterThan(25_000);
    assertThat(joinTokens(hugeTokens)).isEqualTo(hugeLine);

    // Builtin types and literals.
    ImmutableList<LeanToken> typesAndLiterals =
        LeanHighlighter.tokenizeLine("def ok : Nat := some true", false, 0);
    assertThat(typesAndLiterals).contains(new LeanToken("Nat", LeanTokenKind.TYPE));
    assertThat(typesAndLiterals).contains(new LeanToken("some", LeanTokenKind.LITERAL));
    assertThat(typesAndLiterals).contains(new LeanToken("true", LeanTokenKind.LITERAL));

    // Null guards.
    assertThrows(NullPointerException.class, () -> LeanHighlighter.tokenizeLine(null, false, 0));
    assertThrows(NullPointerException.class, () -> new LeanToken(null, LeanTokenKind.PLAIN));
    assertThrows(IllegalArgumentException.class, () -> new LineResult(ImmutableList.of(), -1));
  }
}
