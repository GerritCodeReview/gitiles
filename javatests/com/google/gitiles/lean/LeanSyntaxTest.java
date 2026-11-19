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

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link LeanSyntax} constant sets and character predicates. */
@RunWith(JUnit4.class)
public final class LeanSyntaxTest {

  @Test
  public void declKeywordsMatchEnum() {
    assertThat(LeanSyntax.LEAN_DECL_KEYWORDS).hasSize(LeanDeclKind.values().length);
    for (LeanDeclKind kind : LeanDeclKind.values()) {
      assertThat(LeanSyntax.LEAN_DECL_KEYWORDS).contains(kind.keyword());
    }
  }

  @Test
  public void syntaxSetsContainExpectedEntries() {
    assertThat(LeanSyntax.LEAN_MODIFIER_KEYWORDS)
        .containsAtLeast("public", "private", "noncomputable", "partial", "unsafe", "meta");
    assertThat(LeanSyntax.LEAN_DIRECTIVE_KEYWORDS)
        .containsAtLeast("module", "namespace", "section", "end", "#eval", "#guard_msgs");
    assertThat(LeanSyntax.LEAN_KEYWORDS)
        .containsAtLeast("theorem", "def", "where", "by", "calc", "conv", "termination_by");
    assertThat(LeanSyntax.LEAN_TACTICS)
        .containsAtLeast("intro", "rw", "simp", "omega", "decide", "grind", "bv_decide");
    assertThat(LeanSyntax.UNAMBIGUOUS_TACTICS)
        .containsExactly(
            "decide",
            "omega",
            "rfl",
            "simp_wf",
            "all_goals",
            "any_goals",
            "bv_decide",
            "decreasing_tactic",
            "decreasing_trivial");
    assertThat(LeanSyntax.LEAN_SORRY_TOKENS)
        .containsExactly(
            "sorry", "sorryAx", "admit", "native_decide", "ofReduceBool", "ofReduceNat");
    assertThat(LeanSyntax.SOUNDNESS_GUILLEMET_NAMES)
        .containsExactly(
            "sorryAx", "ofReduceBool", "ofReduceNat", "Lean.ofReduceBool", "Lean.ofReduceNat");
    assertThat(LeanSyntax.LEAN_BUILTIN_TYPES)
        .containsAtLeast("Prop", "Type", "Nat", "String", "UInt64", "BitVec");
    assertThat(LeanSyntax.LEAN_LITERALS)
        .containsExactly("true", "false", "none", "some", "nil", "cons");
    assertThat(LeanSyntax.UNICODE_OPERATORS).containsAtLeast("∀", "∃", "λ", "→", "≤", "⟨", "⟩");
    assertThat(LeanSyntax.MULTI_CHAR_OPERATORS)
        .containsAtLeast("<;>", "|||", ":=", "=>", "->", "<-", "<->", "..");
    assertThat(LeanSyntax.LEAN_INTERP_STRING_PREFIXES).containsExactly("s!", "m!", "f!", "v!");
  }

  @Test
  public void identPredicatesHandleAsciiUnicodeAndSurrogates() {
    assertThat(LeanSyntax.isIdentStart('a')).isTrue();
    assertThat(LeanSyntax.isIdentStart('_')).isTrue();
    assertThat(LeanSyntax.isIdentStart('#')).isTrue();
    assertThat(LeanSyntax.isIdentStart('α')).isTrue();
    assertThat(LeanSyntax.isIdentStart('é')).isTrue();
    assertThat(LeanSyntax.isIdentStart('⅋')).isTrue();
    assertThat(LeanSyntax.isIdentStart('℘')).isTrue();
    assertThat(LeanSyntax.isIdentStart('℃')).isTrue();
    assertThat(LeanSyntax.isIdentStart("𝔸".codePointAt(0))).isTrue();
    assertThat(LeanSyntax.isIdentStart("𝑥".codePointAt(0))).isFalse();
    assertThat(LeanSyntax.isIdentStart('ⁿ')).isFalse();
    assertThat(LeanSyntax.isIdentStart('а')).isFalse();
    assertThat(LeanSyntax.isIdentStart('×')).isFalse();
    assertThat(LeanSyntax.isIdentStart('÷')).isFalse();
    assertThat(LeanSyntax.isIdentStart('0')).isFalse();
    assertThat(LeanSyntax.isIdentStart('\'')).isFalse();
    assertThat(LeanSyntax.isIdentStart('λ')).isFalse();
    assertThat(LeanSyntax.isIdentStart('Π')).isFalse();
    assertThat(LeanSyntax.isIdentStart('Σ')).isFalse();
    assertThat(LeanSyntax.isIdentStart("😀".codePointAt(0))).isFalse();
    assertThat(LeanSyntax.isIdentStart("")).isFalse();
    assertThat(LeanSyntax.isIdentStart((String) null)).isFalse();

    assertThat(LeanSyntax.isIdentPart('a')).isTrue();
    assertThat(LeanSyntax.isIdentPart('0')).isTrue();
    assertThat(LeanSyntax.isIdentPart('_')).isTrue();
    assertThat(LeanSyntax.isIdentPart('\'')).isTrue();
    assertThat(LeanSyntax.isIdentPart('?')).isTrue();
    assertThat(LeanSyntax.isIdentPart('!')).isTrue();
    assertThat(LeanSyntax.isIdentPart('₀')).isTrue();
    assertThat(LeanSyntax.isIdentPart('ₐ')).isTrue();
    assertThat(LeanSyntax.isIdentPart('ᵢ')).isTrue();
    assertThat(LeanSyntax.isIdentPart('ᵪ')).isTrue();
    assertThat(LeanSyntax.isIdentPart('ⱼ')).isTrue();
    assertThat(LeanSyntax.isIdentPart('⅋')).isTrue();
    assertThat(LeanSyntax.isIdentPart('⁹')).isFalse();
    assertThat(LeanSyntax.isIdentPart('²')).isFalse();
    assertThat(LeanSyntax.isIdentPart('ⁿ')).isFalse();
    assertThat(LeanSyntax.isIdentPart('а')).isFalse();
    assertThat(LeanSyntax.isIdentPart('λ')).isFalse();
    assertThat(LeanSyntax.isIdentPart("𝔸".codePointAt(0))).isTrue();
    assertThat(LeanSyntax.isIdentPart("𝑥".codePointAt(0))).isFalse();
    assertThat(LeanSyntax.isIdentPart("😀".codePointAt(0))).isFalse();
    assertThat(LeanSyntax.isIdentPart(" ")).isFalse();
    assertThat(LeanSyntax.isIdentPart((String) null)).isFalse();
  }

  @Test
  public void identPredicatesMatchLean4DefsAcrossAllUnicodeCodePoints() {
    // Exhaustive oracle matching Lean 4 v4.33.0 `Init/Data/Char/Basic.lean` (`Char.isAlpha`,
    // `Char.isDigit`) and `Init/Meta/Defs.lean` (`isLetterLike`, `isSubScriptAlnum`, `isIdFirst`,
    // `isIdRest`) across all `0..0x10FFFF` code points (`R2-INT-10`).
    for (int cp = 0; cp <= Character.MAX_CODE_POINT; cp++) {
      boolean leanAlpha = (cp >= 'A' && cp <= 'Z') || (cp >= 'a' && cp <= 'z');
      boolean leanDigit = cp >= '0' && cp <= '9';
      boolean leanLetterLike =
          (0x03B1 <= cp && cp <= 0x03C9 && cp != 0x03BB)
              || (0x0391 <= cp && cp <= 0x03A9 && cp != 0x03A0 && cp != 0x03A3)
              || (0x03CA <= cp && cp <= 0x03FB)
              || (0x1F00 <= cp && cp <= 0x1FFE)
              || (0x2100 <= cp && cp <= 0x214F)
              || (0x1D49C <= cp && cp <= 0x1D59F)
              || (0x00C0 <= cp && cp <= 0x00FF && cp != 0x00D7 && cp != 0x00F7)
              || (0x0100 <= cp && cp <= 0x017F);
      boolean leanSubscript =
          (0x2080 <= cp && cp <= 0x2089)
              || (0x2090 <= cp && cp <= 0x209C)
              || (0x1D62 <= cp && cp <= 0x1D6A)
              || cp == 0x2C7C;
      boolean leanIdFirst = leanAlpha || cp == '_' || leanLetterLike;
      boolean leanIdRest =
          leanAlpha
              || leanDigit
              || cp == '_'
              || cp == '\''
              || cp == '!'
              || cp == '?'
              || leanLetterLike
              || leanSubscript;
      if (cp != '#' && LeanSyntax.isIdentStart(cp) != leanIdFirst) {
        throw new AssertionError("isIdentStart mismatch at U+" + Integer.toHexString(cp));
      }
      if (LeanSyntax.isIdentPart(cp) != leanIdRest) {
        throw new AssertionError("isIdentPart mismatch at U+" + Integer.toHexString(cp));
      }
    }
  }

  @Test
  public void contentTokenAndUnicodeOperatorPredicates() {
    assertThat(LeanSyntax.isLeanContentToken("theorem")).isTrue();
    assertThat(LeanSyntax.isLeanContentToken("123")).isTrue();
    assertThat(LeanSyntax.isLeanContentToken("_")).isTrue();
    assertThat(LeanSyntax.isLeanContentToken("∀")).isTrue();
    assertThat(LeanSyntax.isLeanContentToken("≤")).isTrue();
    assertThat(LeanSyntax.isLeanContentToken("𝔸")).isTrue();
    assertThat(LeanSyntax.isLeanContentToken("   ")).isFalse();
    assertThat(LeanSyntax.isLeanContentToken(":=")).isFalse();
    assertThat(LeanSyntax.isLeanContentToken("")).isFalse();
    assertThat(LeanSyntax.isLeanContentToken(null)).isFalse();

    assertThat(LeanSyntax.isUnicodeOperator("∀")).isTrue();
    assertThat(LeanSyntax.isUnicodeOperator("≤")).isTrue();
    assertThat(LeanSyntax.isUnicodeOperator(":=")).isFalse();
    assertThat(LeanSyntax.isUnicodeOperator(null)).isFalse();
  }
}
