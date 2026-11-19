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

import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import java.util.regex.Pattern;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link LeanLexer}, porting {@code lean-diff-util_test.ts} lexer specs. */
@RunWith(JUnit4.class)
public final class LeanLexerTest {

  private static final Pattern WORD_SORRY = Pattern.compile("\\bsorry\\b");
  private static final Pattern WORD_ADMIT = Pattern.compile("\\badmit\\b");
  private static final Pattern WORD_NATIVE_DECIDE = Pattern.compile("\\bnative_decide\\b");

  // Ported from lean-diff-util_test.ts: stripLeanCommentsAndStrings() suite

  @Test
  public void stripsLineCommentsDeeplyNestedBlockCommentsAndStringLiterals() {
    String code =
        String.join(
            "\n",
            "-- sorry in line comment",
            "/- outer /- middle /- inner sorry -/ still nested -/ still comment -/",
            "def msg : String := \"do not trigger \\\"sorry\\\" or admit\"",
            "def multi : String := \"line 1\n  sorry on line 2\n  admit on line 3\"",
            "def interp (x : Nat) : String := s!\"value {x} sorry\"",
            "theorem real : True := by trivial");

    String clean = LeanLexer.stripLeanCommentsAndStrings(code);
    assertThat(WORD_SORRY.matcher(clean).find()).isFalse();
    assertThat(WORD_ADMIT.matcher(clean).find()).isFalse();
    assertThat(clean).contains("def msg : String :=");
    assertThat(clean).contains("theorem real : True := by trivial");
    assertThat(Splitter.on('\n').splitToList(clean))
        .hasSize(Splitter.on('\n').splitToList(code).size());
  }

  @Test
  public void stripsRawStringLiteralsIncludingMultiLineRawStrings() {
    String code =
        String.join(
            "\n",
            "def r0 : String := r\"backslash \\\\ sorry -- not a comment\"",
            "def r1 : String := r#\"quoted \"sorry\" and native_decide\"#",
            "def r2 : String := r##\"nested #\"admit\"# inside\n  still raw sorry\"##",
            "theorem after_raw : True := by trivial");

    String clean = LeanLexer.stripLeanCommentsAndStrings(code);
    assertThat(WORD_SORRY.matcher(clean).find()).isFalse();
    assertThat(WORD_ADMIT.matcher(clean).find()).isFalse();
    assertThat(WORD_NATIVE_DECIDE.matcher(clean).find()).isFalse();
    assertThat(clean).contains("theorem after_raw : True := by trivial");
  }

  @Test
  public void handlesCharLiteralsIncludingDoubleQuoteCharWithoutSwallowingSubsequentCode() {
    String code =
        String.join(
            "\n",
            "def quoteChar : Char := '\"'",
            "def singleQuote : Char := '\\''",
            "def hexChar : Char := '\\x41'",
            "def unicodeChar : Char := '\\u03bb'",
            "theorem real_sorry : False := by sorry");

    String clean = LeanLexer.stripLeanCommentsAndStrings(code);
    assertThat(WORD_SORRY.matcher(clean).find()).isTrue();
    assertThat(clean).contains("theorem real_sorry : False := by sorry");
  }

  @Test
  public void stripsGuillemetEscapedIdentifiers() {
    String code =
        String.join(
            "\n",
            "def «sorry» : Nat := 1",
            "def «admit» : Nat := 2",
            "def «native_decide» : Nat := 3",
            "theorem «1 + 1 = 2» : 1 + 1 = 2 := rfl");

    String clean = LeanLexer.stripLeanCommentsAndStrings(code);
    assertThat(WORD_SORRY.matcher(clean).find()).isFalse();
    assertThat(WORD_ADMIT.matcher(clean).find()).isFalse();
    assertThat(WORD_NATIVE_DECIDE.matcher(clean).find()).isFalse();
    assertThat(clean).contains(":= rfl");
  }

  @Test
  public void preservesSorryAdmitAndNativeDecideInExecutableCode() {
    String code =
        String.join(
            "\n",
            "theorem t1 : False := by sorry",
            "theorem t2 : False := by admit",
            "theorem t3 : 1 + 1 = 2 := by native_decide");

    String clean = LeanLexer.stripLeanCommentsAndStrings(code);
    assertThat(WORD_SORRY.matcher(clean).find()).isTrue();
    assertThat(WORD_ADMIT.matcher(clean).find()).isTrue();
    assertThat(WORD_NATIVE_DECIDE.matcher(clean).find()).isTrue();
  }

  // Ported from additional lexical security/edge-case tests in lean-diff-util_test.ts

  @Test
  public void unwrapsSoundnessGuillemetsWhilePreservingOffsets() {
    String code =
        "theorem t1 : False := «sorryAx» False false\n"
            + "theorem t2 : True := Lean.«ofReduceNat» 0 rfl";
    String clean = LeanLexer.stripLeanCommentsAndStrings(code);
    assertThat(clean).hasLength(code.length());
    assertThat(clean).contains(" sorryAx ");
    assertThat(clean).contains("Lean. ofReduceNat ");
  }

  @Test
  public void interpolatedStringBraceExpressionsCannotHideSorry() {
    String code =
        String.join(
            "\n",
            "def a : String := s!\"{'\"'}\"",
            "theorem after_char_in_interp : False := by sorry",
            "def b : String := s!\"{r#\"\"\"#}\"",
            "theorem after_raw_in_interp : False := by sorry",
            "def c : String := s!\"{(sorry : String)}\"",
            "def d : String := panic!\"{\"",
            "theorem after_panic : False := by sorry");

    String clean = LeanLexer.stripLeanCommentsAndStrings(code);
    assertThat(clean).contains("theorem after_char_in_interp : False := by sorry");
    assertThat(clean).contains("theorem after_raw_in_interp : False := by sorry");
    assertThat(clean).contains("{(sorry : String)}");
    assertThat(clean).contains("theorem after_panic : False := by sorry");
  }

  @Test
  public void multiLineGuillemetsAndUnicodeBangBoundariesDoNotDesync() {
    String code =
        String.join(
            "\n",
            "def «multi\n\"» : Nat := 1",
            "theorem after_guillemet : False := by sorry",
            "def x1 := f!'\"'\"",
            "theorem after_bang_char : False := by sorry",
            "def x2 := f!r\"\\\"\"",
            "theorem after_bang_raw : False := by sorry",
            "def x3 := fé'\"'\"",
            "theorem after_unicode_char : False := by sorry");

    String clean = LeanLexer.stripLeanCommentsAndStrings(code);
    assertThat(clean).contains("theorem after_guillemet : False := by sorry");
    assertThat(clean).contains("theorem after_bang_char : False := by sorry");
    assertThat(clean).contains("theorem after_bang_raw : False := by sorry");
    assertThat(clean).contains("theorem after_unicode_char : False := by sorry");
  }

  // Normalization, literal matchers, bracket/attr/comment helpers, and LeanLexState

  @Test
  public void normalizeLeanSourceStripsBomAndNormalizesLineEndings() {
    assertThat(LeanLexer.normalizeLeanSource("")).isEmpty();
    assertThat(LeanLexer.normalizeLeanSource("\uFEFFa\r\nb\rc\n\uFEFFd"))
        .isEqualTo("a\nb\nc\n\uFEFFd");
    assertThat(LeanLexer.normalizeLeanSource("plain\ntext")).isEqualTo("plain\ntext");
  }

  @Test
  public void literalMatchersAndSurrogatePairs() {
    assertThat(LeanLexer.matchCharLiteral("'a'", 0)).isEqualTo(3);
    assertThat(LeanLexer.matchCharLiteral("'\"'", 0)).isEqualTo(3);
    assertThat(LeanLexer.matchCharLiteral("'\\''", 0)).isEqualTo(4);
    assertThat(LeanLexer.matchCharLiteral("'\\x41'", 0)).isEqualTo(6);
    assertThat(LeanLexer.matchCharLiteral("'\\u03bb'", 0)).isEqualTo(8);
    assertThat(LeanLexer.matchCharLiteral("'😀'", 0)).isEqualTo(4);
    assertThat(LeanLexer.matchCharLiteral("''", 0)).isEqualTo(0);
    assertThat(LeanLexer.matchCharLiteral("hlt'", 3)).isEqualTo(0);
    assertThat(LeanLexer.matchCharLiteral("𝔸'", 2)).isEqualTo(0);
    assertThat(LeanLexer.matchCharLiteral("'\\\n'", 0)).isEqualTo(0);

    assertThat(LeanLexer.hasIdentPartBefore("𝔸'", 2)).isTrue();
    assertThat(LeanLexer.hasIdentPartBefore("😀'", 2)).isFalse();
    assertThat(LeanLexer.hasIdentPartBefore("a", 0)).isFalse();

    assertThat(LeanLexer.matchRawStringOpen("r\"abc\"", 0))
        .isEqualTo(new LeanLexer.RawStringOpen(0, 2));
    assertThat(LeanLexer.matchRawStringOpen("r##\"abc\"##", 0))
        .isEqualTo(new LeanLexer.RawStringOpen(2, 4));
    assertThat(LeanLexer.matchRawStringOpen("foo_r\"abc\"", 4)).isNull();
    assertThat(LeanLexer.matchRawStringOpen("r#", 0)).isNull();

    assertThat(LeanLexer.matchRawStringCloseAt("\"##", 0, 2)).isTrue();
    assertThat(LeanLexer.matchRawStringCloseAt("\"#", 0, 2)).isFalse();

    assertThat(LeanLexer.matchNumberLiteral("0xff_00", 0)).isEqualTo(7);
    assertThat(LeanLexer.matchNumberLiteral("0b1010", 0)).isEqualTo(6);
    assertThat(LeanLexer.matchNumberLiteral("0o755", 0)).isEqualTo(5);
    assertThat(LeanLexer.matchNumberLiteral("123_456.78", 0)).isEqualTo(10);
    assertThat(LeanLexer.matchNumberLiteral("0xZZ", 0)).isEqualTo(1);
    assertThat(LeanLexer.matchNumberLiteral("1..4", 0)).isEqualTo(1);
    assertThat(LeanLexer.matchNumberLiteral("x0", 1)).isEqualTo(0);
  }

  @Test
  public void findMatchingBracketHandlesNestedCommentsStringsAndUnclosed() {
    assertThat(LeanLexer.findMatchingBracket("@[simp [Nat.add_comm], inline]", 1)).isEqualTo(29);
    assertThat(LeanLexer.findMatchingBracket("@[simp /- ] -/]", 1)).isEqualTo(14);
    assertThat(LeanLexer.findMatchingBracket("@[attr \"]\", r#\"]\"#, ']', «]»]", 1)).isEqualTo(28);
    assertThat(LeanLexer.findMatchingBracket("@[simp -- ]\n  inline]", 1)).isEqualTo(20);
    assertThat(LeanLexer.findMatchingBracket("@[unclosed -- ]", 1)).isEqualTo(-1);
    assertThat(LeanLexer.findMatchingBracket("not_a_bracket", 0)).isEqualTo(-1);
  }

  @Test
  public void findMultiLineAttributeEndScansAcrossLines() {
    ImmutableList<String> lines =
        ImmutableList.of(
            "@[simp [foo],",
            "  /- ] inside comment -/",
            "  r#\"]\"#,",
            "  inline] theorem after := True");
    LeanLexer.MultiLineAttrScan scan = LeanLexer.findMultiLineAttributeEnd(lines, 0, 0, 1, 3);
    assertThat(scan.closed()).isTrue();
    assertThat(scan.endRow()).isEqualTo(3);
    assertThat(scan.closeColExclusive()).isEqualTo(9);
    assertThat(scan.scannedLines()).isEqualTo(4);
    assertThat(scan.text()).endsWith("inline]");

    LeanLexer.MultiLineAttrScan unclosed =
        LeanLexer.findMultiLineAttributeEnd(ImmutableList.of("@[unclosed", "  foo"), 0, 0, 1, 1);
    assertThat(unclosed.closed()).isFalse();
    assertThat(unclosed.endRow()).isEqualTo(-1);
  }

  @Test
  public void blockCommentHelpersAndDividerChecks() {
    ImmutableList<String> nested =
        ImmutableList.of("/- outer /- inner -/", "  still comment -/ after");
    assertThat(LeanLexer.findBlockCommentEnd(nested, 0))
        .isEqualTo(new LeanLexer.BlockCommentEnd(1, 18, true));

    ImmutableList<String> unclosedTrailingBlank = ImmutableList.of("/- unclosed", "line 2", "");
    assertThat(LeanLexer.findBlockCommentEnd(unclosedTrailingBlank, 0))
        .isEqualTo(new LeanLexer.BlockCommentEnd(1, 6, false));

    assertThat(LeanLexer.unwrapBlockComment("/-- Bitwise order. -/", 3))
        .isEqualTo("Bitwise order.");
    assertThat(LeanLexer.unwrapBlockComment("/-!\n# Title\n\nBody\n-/", 3))
        .isEqualTo("# Title\n\nBody");
    assertThat(LeanLexer.unwrapBlockComment("/-! -/", 3)).isEmpty();

    assertThat(LeanLexer.isDividerCommentLine("-- ================")).isTrue();
    assertThat(LeanLexer.isDividerCommentLine("-- ───────")).isTrue();
    assertThat(LeanLexer.isDividerCommentLine("-- ---")).isFalse();
    assertThat(LeanLexer.isDividerCommentLine("-- === title")).isFalse();

    assertThat(LeanLexer.skipLeadingWsAndBlockComments("  /- a /- b -/ -/  /- c -/ def x"))
        .isEqualTo("def x");
    assertThat(LeanLexer.skipLeadingWsAndBlockComments("/- unclosed")).isEqualTo("/- unclosed");
  }

  @Test
  public void leanLexStateTracksMultiLineStringsCommentsAndDelimiters() {
    LeanLexer.LeanLexState state = LeanLexer.createLeanLexState();
    assertThat(LeanLexer.hasOpenLiteralOrComment(state)).isFalse();
    assertThat(LeanLexer.hasOpenDelimiters(state)).isFalse();

    String s1 = LeanLexer.advanceLeanLexState(state, "def f (x : Nat := /- open");
    assertThat(s1).startsWith("def f (x : Nat :=");
    assertThat(LeanLexer.hasOpenLiteralOrComment(state)).isTrue();
    assertThat(LeanLexer.hasOpenDelimiters(state)).isTrue();

    String s2 = LeanLexer.advanceLeanLexState(state, "  comment -/) : String := s!\"hi {");
    assertThat(s2).contains(") : String :=");
    assertThat(LeanLexer.hasOpenLiteralOrComment(state)).isFalse();
    assertThat(LeanLexer.hasOpenDelimiters(state)).isFalse();

    String s3 = LeanLexer.advanceLeanLexState(state, "  x + 1}\" -- trailing");
    assertThat(s3).isEqualTo("  x + 1}  ");
    assertThat(LeanLexer.hasOpenLiteralOrComment(state)).isFalse();
  }

  @Test
  public void handlesEmptyAndPathologicalInputsLinearly() {
    assertThat(LeanLexer.stripLeanCommentsAndStrings("")).isEmpty();
    assertThat(LeanLexer.stripLeanCommentsAndStrings("«".repeat(50_000))).hasLength(50_000);
    assertThat(LeanLexer.stripLeanCommentsAndStrings("s!\"" + "{".repeat(1000))).hasLength(1003);
    assertThat(LeanLexer.stripLeanCommentsAndStrings("/- ".repeat(5000))).hasLength(15_000);
    assertThat(LeanLexer.isDividerCommentLine("-- " + "a".repeat(100_000))).isFalse();
  }
}
