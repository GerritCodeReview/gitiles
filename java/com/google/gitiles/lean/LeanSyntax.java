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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import javax.annotation.Nullable;

/** Lean 4 syntax keyword/operator sets and identifier character predicates. */
public final class LeanSyntax {

  public static final ImmutableSet<String> LEAN_DECL_KEYWORDS =
      ImmutableSet.of(
          "theorem",
          "lemma",
          "example",
          "def",
          "abbrev",
          "structure",
          "class",
          "inductive",
          "coinductive",
          "instance",
          "axiom",
          "opaque");

  public static final ImmutableSet<String> LEAN_MODIFIER_KEYWORDS =
      ImmutableSet.of(
          "public",
          "private",
          "protected",
          "noncomputable",
          "partial",
          "unsafe",
          "scoped",
          "local",
          "nonrec",
          "meta");

  public static final ImmutableSet<String> LEAN_DIRECTIVE_KEYWORDS =
      ImmutableSet.of(
          "module",
          "namespace",
          "section",
          "end",
          "open",
          "variable",
          "variables",
          "universe",
          "universes",
          "set_option",
          "attribute",
          "export",
          "notation",
          "infix",
          "infixl",
          "infixr",
          "prefix",
          "postfix",
          "macro",
          "macro_rules",
          "syntax",
          "elab",
          "elab_rules",
          "declare_syntax_cat",
          "initialize",
          "builtin_initialize",
          "mutual",
          "deriving",
          "#check",
          "#eval",
          "#print",
          "#reduce",
          "#guard",
          "#guard_msgs",
          "#synth",
          "#where",
          "#exit");

  public static final ImmutableSet<String> LEAN_KEYWORDS =
      ImmutableSet.of(
          "module",
          "theorem",
          "lemma",
          "example",
          "def",
          "abbrev",
          "structure",
          "class",
          "inductive",
          "coinductive",
          "instance",
          "axiom",
          "opaque",
          "namespace",
          "section",
          "end",
          "import",
          "open",
          "variable",
          "variables",
          "universe",
          "universes",
          "where",
          "deriving",
          "extends",
          "public",
          "private",
          "protected",
          "noncomputable",
          "partial",
          "unsafe",
          "scoped",
          "local",
          "nonrec",
          "meta",
          "mutual",
          "set_option",
          "attribute",
          "export",
          "notation",
          "infix",
          "infixl",
          "infixr",
          "prefix",
          "postfix",
          "macro",
          "macro_rules",
          "syntax",
          "elab",
          "elab_rules",
          "termination_by",
          "decreasing_by",
          "partial_fixpoint",
          "coinductive_fixpoint",
          "inductive_fixpoint",
          "with",
          "let",
          "rec",
          "in",
          "if",
          "then",
          "else",
          "match",
          "fun",
          "do",
          "for",
          "return",
          "have",
          "show",
          "from",
          "by",
          "calc",
          "conv",
          "suffices",
          "try",
          "mut",
          "unless",
          "while",
          "break",
          "continue",
          "nomatch",
          "nofun",
          "throw",
          "catch",
          "finally",
          "forall",
          "exists",
          "at",
          "using");

  public static final ImmutableSet<String> LEAN_TACTICS =
      ImmutableSet.of(
          "intro",
          "intros",
          "rintro",
          "rw",
          "rewrite",
          "simp",
          "simp_all",
          "simp_wf",
          "dsimp",
          "simpa",
          "exact",
          "apply",
          "refine",
          "constructor",
          "cases",
          "rcases",
          "induction",
          "obtain",
          "choose",
          "unfold",
          "by_cases",
          "by_contra",
          "subst",
          "omega",
          "decide",
          "rfl",
          "contradiction",
          "funext",
          "ext",
          "congr",
          "ring",
          "ring_nf",
          "field_simp",
          "linarith",
          "nlinarith",
          "lia",
          "lra",
          "positivity",
          "aesop",
          "norm_num",
          "norm_cast",
          "push_neg",
          "split_ifs",
          "fin_cases",
          "interval_cases",
          "gcongr",
          "trivial",
          "assumption",
          "clear",
          "rename_i",
          "revert",
          "generalize",
          "specialize",
          "use",
          "left",
          "right",
          "split",
          "exfalso",
          "injection",
          "change",
          "set",
          "grind",
          "bv_decide",
          "decreasing_tactic",
          "decreasing_trivial",
          "enter",
          "lhs",
          "rhs",
          "arg",
          "only",
          "first",
          "repeat",
          "all_goals",
          "any_goals",
          "focus",
          "next",
          "case",
          "done",
          "tauto",
          "itauto",
          "ac_rfl",
          "extract_lets",
          "fun_prop",
          "nontriviality");

  public static final ImmutableSet<String> UNAMBIGUOUS_TACTICS =
      ImmutableSet.of(
          "decide",
          "omega",
          "rfl",
          "simp_wf",
          "all_goals",
          "any_goals",
          "bv_decide",
          "decreasing_tactic",
          "decreasing_trivial");

  public static final ImmutableSet<String> LEAN_SORRY_TOKENS =
      ImmutableSet.of("sorry", "sorryAx", "admit", "native_decide", "ofReduceBool", "ofReduceNat");

  /**
   * Kernel soundness constant names that resolve to the exact same {@code Name} even when wrapped
   * in French guillemets ({@code «sorryAx»}, {@code Lean.«ofReduceBool»}). Syntax keywords ({@code
   * sorry}, {@code admit}, {@code native_decide}) are intentionally excluded because guillemets
   * escape them into ordinary user identifiers.
   */
  public static final ImmutableSet<String> SOUNDNESS_GUILLEMET_NAMES =
      ImmutableSet.of(
          "sorryAx", "ofReduceBool", "ofReduceNat", "Lean.ofReduceBool", "Lean.ofReduceNat");

  public static final ImmutableSet<String> LEAN_BUILTIN_TYPES =
      ImmutableSet.of(
          "Prop",
          "Type",
          "Sort",
          "Nat",
          "Int",
          "Bool",
          "String",
          "Char",
          "Float",
          "Float32",
          "Unit",
          "PUnit",
          "Empty",
          "PEmpty",
          "Fin",
          "List",
          "Array",
          "ByteArray",
          "Vector",
          "Subarray",
          "Option",
          "Except",
          "ExceptT",
          "StateM",
          "ReaderM",
          "Id",
          "IO",
          "Task",
          "Thunk",
          "Name",
          "Expr",
          "Decidable",
          "DecidableEq",
          "Repr",
          "Hashable",
          "Inhabited",
          "Nonempty",
          "ToString",
          "LE",
          "LT",
          "BEq",
          "Ord",
          "Ordering",
          "Eq",
          "And",
          "Or",
          "Not",
          "True",
          "False",
          "UInt8",
          "UInt16",
          "UInt32",
          "UInt64",
          "USize",
          "Int8",
          "Int16",
          "Int32",
          "Int64",
          "ISize",
          "BitVec",
          "Prod",
          "Sum",
          "Subtype",
          "Sigma");

  public static final ImmutableSet<String> LEAN_LITERALS =
      ImmutableSet.of("true", "false", "none", "some", "nil", "cons");

  public static final ImmutableSet<String> UNICODE_OPERATORS =
      ImmutableSet.of(
          "∀", "∃", "λ", "→", "←", "↔", "⇒", "⇐", "⇔", "↦", "≤", "≥", "≠", "∧", "∨", "¬", "⊢", "⊥",
          "⊤", "⊔", "⊓", "∈", "∉", "⊆", "⊇", "⊂", "⊃", "∪", "∩", "∅", "⟨", "⟩", "×", "⊕", "⊗", "∘",
          "▸", "≡", "≈", "≃", "≅", "∣", "∑", "∏", "∫");

  public static final ImmutableList<String> MULTI_CHAR_OPERATORS =
      ImmutableList.of(
          "<;>", "<<<", ">>>", "|||", "&&&", "^^^", ":=", "=>", "->", "<-", "<->", "<=", ">=", "!=",
          "==", "&&", "||", "::", "++", "|>", "<|", "..");

  public static final ImmutableSet<String> LEAN_INTERP_STRING_PREFIXES =
      ImmutableSet.of("s!", "m!", "f!");

  private LeanSyntax() {}

  /** Returns whether {@code codePoint} can start a Lean 4 identifier ({@code [\p{L}_#]}). */
  public static boolean isIdentStart(int codePoint) {
    return Character.isLetter(codePoint) || codePoint == '_' || codePoint == '#';
  }

  /** Returns whether {@code ch} contains a valid Lean 4 identifier start character. */
  public static boolean isIdentStart(@Nullable String ch) {
    if (ch == null || ch.isEmpty()) {
      return false;
    }
    for (int i = 0; i < ch.length(); ) {
      int cp = ch.codePointAt(i);
      if (isIdentStart(cp)) {
        return true;
      }
      i += Character.charCount(cp);
    }
    return false;
  }

  /**
   * Returns whether {@code codePoint} can continue a Lean 4 identifier ({@code
   * [\p{L}\p{N}_'?!₀-₉⁰-⁹ₐ-ₜᵢᵣᵤᵥ]}).
   */
  public static boolean isIdentPart(int codePoint) {
    return Character.isLetter(codePoint)
        || isUnicodeNumber(codePoint)
        || codePoint == '_'
        || codePoint == '\''
        || codePoint == '?'
        || codePoint == '!'
        || (codePoint >= 0x2080 && codePoint <= 0x2089) // ₀-₉
        || (codePoint >= 0x2070 && codePoint <= 0x2079) // ⁰-⁹
        || (codePoint >= 0x2090 && codePoint <= 0x209C) // ₐ-ₜ
        || (codePoint >= 0x1D62 && codePoint <= 0x1D65); // ᵢᵣᵤᵥ
  }

  /** Returns whether {@code ch} contains a valid Lean 4 identifier continuation character. */
  public static boolean isIdentPart(@Nullable String ch) {
    if (ch == null || ch.isEmpty()) {
      return false;
    }
    for (int i = 0; i < ch.length(); ) {
      int cp = ch.codePointAt(i);
      if (isIdentPart(cp)) {
        return true;
      }
      i += Character.charCount(cp);
    }
    return false;
  }

  /**
   * Returns whether {@code tok} is a semantic Lean content token (word/number/underscore or Unicode
   * operator) for line similarity scoring.
   */
  public static boolean isLeanContentToken(@Nullable String tok) {
    if (tok == null || tok.isEmpty()) {
      return false;
    }
    if (UNICODE_OPERATORS.contains(tok)) {
      return true;
    }
    for (int i = 0; i < tok.length(); ) {
      int cp = tok.codePointAt(i);
      if (Character.isLetter(cp) || isUnicodeNumber(cp) || cp == '_') {
        return true;
      }
      i += Character.charCount(cp);
    }
    return false;
  }

  /** Returns whether {@code tokenText} is a recognized Lean Unicode operator. */
  public static boolean isUnicodeOperator(@Nullable String tokenText) {
    return tokenText != null && UNICODE_OPERATORS.contains(tokenText);
  }

  private static boolean isUnicodeNumber(int codePoint) {
    int type = Character.getType(codePoint);
    return type == Character.DECIMAL_DIGIT_NUMBER
        || type == Character.LETTER_NUMBER
        || type == Character.OTHER_NUMBER;
  }
}
