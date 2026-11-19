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

/** Adversarial security, Unicode, evasion, and linear-time tests for {@link LeanLexer}. */
@RunWith(JUnit4.class)
public final class LeanLexerAdversarialTest {

  private static void assertPreservesLengthAndNewlines(String input) {
    String norm = LeanLexer.normalizeLeanSource(input);
    String clean = LeanLexer.stripLeanCommentsAndStrings(input);
    assertThat(clean.length()).isEqualTo(norm.length());
    for (int i = 0; i < norm.length(); i++) {
      assertThat(clean.charAt(i) == '\n').isEqualTo(norm.charAt(i) == '\n');
    }
  }

  @Test
  public void rawStringMatchesLean4BasicLeanTokenStartAndNoEscapes() {
    // Lean 4 v4.33.0 Basic.lean (isRawStrLitStart/rawStrLitFnAux): r"..." and r#"..."# start only
    // at a token start, have NO escapes (`\"` closes r"..." at `"`), and count matching `#`s.
    String bareRaw =
        "def r (_ : String) : Nat := 0\ndef h := r\"\\\"\ntheorem bad : False := sorry\n";
    String cleanBare = LeanLexer.stripLeanCommentsAndStrings(bareRaw);
    assertThat(cleanBare).contains("theorem bad : False := sorry");
    assertPreservesLengthAndNewlines(bareRaw);

    // Identifier suffix `xr"` does NOT start a raw string (token-start rule).
    String identBeforeRaw = "def xr := 1\ndef y := xr\"not_raw\\\"\"\ntheorem ok : True := rfl\n";
    assertPreservesLengthAndNewlines(identBeforeRaw);
    assertThat(LeanLexer.stripLeanCommentsAndStrings(identBeforeRaw)).doesNotContain("not_raw");

    // Unterminated r#" leaves hasUnterminatedLiteralOrComment true rather than silently succeeding.
    assertThat(
            LeanLexer.hasUnterminatedLiteralOrComment(
                "def h := r#\"unclosed\ntheorem dead : False := sorry"))
        .isTrue();
    assertThat(LeanLexer.hasUnterminatedLiteralOrComment("def s := s!\"{unclosed")).isTrue();
    assertThat(
            LeanLexer.hasUnterminatedLiteralOrComment(
                "def h := r#\"closed\"#\ntheorem live : True := trivial"))
        .isFalse();
  }

  @Test
  public void hashPrefixCannotShadowRawOrInterpolatedStringHandlers() {
    // S-B-7: `#` is not an identifier start in Lean; `#r"..."` and `#s!"{...}"` resume lexing at
    // `r` and `s!`.
    String hashInterp = "def a := #s!\"val {sorry}\"\n";
    String cleanInterp = LeanLexer.stripLeanCommentsAndStrings(hashInterp);
    assertThat(cleanInterp).contains("{sorry}");
    assertPreservesLengthAndNewlines(hashInterp);

    String hashR = "#r\"\\\"\ntheorem bad : False := sorry\n";
    assertThat(LeanLexer.stripLeanCommentsAndStrings(hashR))
        .contains("theorem bad : False := sorry");

    LeanLexer.LeanLexState state = LeanLexer.createLeanLexState();
    String lineStripped = LeanLexer.advanceLeanLexState(state, "def a := #s!\"{sorry}\"");
    assertThat(lineStripped).contains("{sorry}");
    assertThat(LeanLexer.hasOpenLiteralOrComment(state)).isFalse();
  }

  @Test
  public void hygieneStrippingExposesNormalStringInterpolationsWithoutBraceDesync() {
    // S-B-8 (#92 rule 4): for hygiene counting only, code inside balanced `{...}` of any `"..."`
    // string literal is exposed while plain `"..."` text and unclosed `"{"` never desync.
    String dbg = "dbg_trace \"value={(sorry : Nat)}\"\n";
    assertThat(LeanLexer.stripLeanCommentsAndStrings(dbg)).doesNotContain("sorry");
    assertThat(LeanLexer.stripLeanCommentsAndStringsForHygiene(dbg)).contains("(sorry : Nat)");

    String safeBrace = "def brace := \"{\"\ntheorem ok : String := \"sorry\"\n";
    String cleanHygiene = LeanLexer.stripLeanCommentsAndStringsForHygiene(safeBrace);
    assertThat(cleanHygiene).doesNotContain("sorry");
    assertPreservesLengthAndNewlines(safeBrace);

    String escapedBrace = "def esc := \"\\{sorry}\"\n";
    assertThat(LeanLexer.stripLeanCommentsAndStringsForHygiene(escapedBrace))
        .doesNotContain("sorry");
  }

  @Test
  public void unicodeControlsLoneSurrogatesReplacementCharAndSeparatorsAreSafe() {
    String hostile =
        "\uFEFF"
            + "-- \u202Ebidi\u2066\u2069\u200E\u200F\u061C\r\n"
            + "def \0_bad : Nat := 1\r"
            + "def s1 : Char := '\uD800'\n"
            + "def s2 : String := \"\uD800\uDC00\uD83D\uFFFD\u200B\"\n"
            + "def ls := \u2028\u2029\u000B\u00A0\n"
            + "def norm : String := \"a\u0301\"\n"
            + "theorem bad\u202E : False := sorry\n";
    assertPreservesLengthAndNewlines(hostile);
    String clean = LeanLexer.stripLeanCommentsAndStrings(hostile);
    assertThat(clean).contains("theorem bad\u202E : False := sorry");
    assertThat(clean).doesNotContain("bidi");

    // Standalone lone surrogates and invalid indices never throw.
    assertThat(LeanLexer.matchCharLiteral("\uD800'", 0)).isEqualTo(0);
    assertThat(LeanLexer.matchCharLiteral("'\uD800'", 0)).isEqualTo(3);
    assertThat(LeanLexer.hasIdentPartBefore("\uDC00x", 1)).isFalse();
    assertThat(LeanLexer.skipLeadingWsAndBlockCommentsIndex("/- \uD800 -/ def", 0)).isEqualTo(8);
    assertThat(LeanLexer.skipLeadingWsAndBlockCommentsIndex("abc", 99)).isEqualTo(3);

    // Lean 4 Char.isWhitespace is strictly ' ', '\t', '\r', '\n' (Init/Data/Char/Basic.lean):
    // U+2028, U+2029, U+2003, and \u000B are NOT skipped as whitespace or divider whitespace.
    assertThat(LeanLexer.skipLeadingWsAndBlockComments("\u2028def")).isEqualTo("\u2028def");
    assertThat(LeanLexer.skipLeadingWsAndBlockComments("\u000Bdef")).isEqualTo("\u000Bdef");
    assertThat(LeanLexer.isDividerCommentLine("\u2028-- =====")).isFalse();
    assertThat(LeanLexer.isDividerCommentLine("-- ====\u2028")).isFalse();
    assertThat(LeanLexer.isDividerCommentLine("--\u2003=====")).isFalse();

    // R2-CHAOS-3: unwrapBlockComment trims only Lean whitespace (' ', '\t', '\r', '\n'),
    // preserving control chars <= 0x20 and non-Lean separators for downstream Trojan escaping.
    assertThat(LeanLexer.unwrapBlockComment("/--\n  \u0001\u001b\n-/", 3))
        .isEqualTo("\u0001\u001b");
    assertThat(LeanLexer.unwrapBlockComment("/-!\u0001 hello \u001b-/", 3))
        .isEqualTo("\u0001 hello \u001b");
    assertThat(LeanLexer.unwrapBlockComment("/--\n  \u000B\u000C\n  \u0000\n-/", 3))
        .isEqualTo("  \u000B\u000C\n  \u0000");
    assertThat(LeanLexer.unwrapBlockComment("/--\n\u2028\n-/", 3)).isEqualTo("\u2028");
  }

  @Test
  public void guillemetEdgeCasesAndPathologicalLinearPerformance() {
    // S-D-2: repeated empty block comments must complete in linear time (O(n), not O(n^2)).
    String emptyComments = "/- -/".repeat(40_000) + "def ok := 1";
    assertThat(LeanLexer.skipLeadingWsAndBlockComments(emptyComments)).isEqualTo("def ok := 1");

    // Repeated closed and unclosed `r#"` stays O(n).
    String manyRaw = "r#\"x\"# ".repeat(80_000) + "\ntheorem after : False := sorry";
    assertThat(LeanLexer.stripLeanCommentsAndStrings(manyRaw))
        .contains("theorem after : False := sorry");
    assertThat(LeanLexer.stripLeanCommentsAndStrings("r#\" ".repeat(80_000))).hasLength(80_000 * 4);

    // Unclosed guillemet at EOF does not loop or expand length; `«sorryAx»` unwraps in-place.
    assertThat(LeanLexer.stripLeanCommentsAndStrings("«unclosed")).hasLength(9);
    assertThat(LeanLexer.stripLeanCommentsAndStrings("exact «sorryAx»"))
        .isEqualTo("exact  sorryAx ");
    assertThat(LeanLexer.stripLeanCommentsAndStrings("def «multi\nsorry» := 1"))
        .doesNotContain("sorry");

    // Bracket/attribute scanners must share interpolated-string brace semantics with LeanLexState.
    String attrInterp = "@[foo s!\"{\"]\"} {r#\"\"\"#}\"]";
    assertThat(LeanLexer.findMatchingBracket(attrInterp, 1)).isEqualTo(attrInterp.length() - 1);
    LeanLexer.MultiLineAttrScan attrScan =
        LeanLexer.findMultiLineAttributeEnd(
            ImmutableList.of("@[unclosed", "s!\"hi {x} {r#\"\"\"#}\"", "sr#\"", "]m!\"tail"),
            0,
            0,
            1,
            3);
    assertThat(attrScan.closed()).isFalse();

    // Outer attribute `[` must not close on a stray `]` inside an open `s!"...{...}` brace.
    String strayBracketInInterp = "@[foo s!\"{ ] }\"]";
    assertThat(LeanLexer.findMatchingBracket(strayBracketInInterp, 1))
        .isEqualTo(strayBracketInInterp.length() - 1);
    assertThat(
            LeanLexer.findMultiLineAttributeEnd(
                    ImmutableList.of("@[unclosed", "f!\"{", "[", "]", "]", "}"), 0, 0, 1, 5)
                .closed())
        .isFalse();

    // Lean 4 interpolated strings (`s!`, `m!`, `f!`, `v!`, `dbg_trace`, `println!`, `throwError`,
    // `trace[...]`) lex `{...}` with nested strings/comments/chars, while plain `"..."` stays
    // masked (R2-INT-2).
    assertThat(LeanLexer.stripLeanCommentsAndStringsForHygiene("dbg_trace \"{ -- }\\n sorry }\\n"))
        .doesNotContain("sorry");
    assertThat(LeanLexer.stripLeanCommentsAndStringsForHygiene("dbg_trace \"{ -- }\n sorry }\n"))
        .contains("sorry");
    assertThat(LeanLexer.stripLeanCommentsAndStringsForHygiene("dbg_trace \"{ /- } -/ sorry }\n"))
        .contains("sorry");
    assertThat(LeanLexer.stripLeanCommentsAndStringsForHygiene("dbg_trace \"{ '}' sorry }\n"))
        .contains("sorry");
    assertThat(LeanLexer.stripLeanCommentsAndStringsForHygiene("def s := \"{ -- }\nsorry\"\n"))
        .doesNotContain("sorry");
    assertThat(LeanLexer.stripLeanCommentsAndStrings("def ok : String := \"{ sorry }\"\n"))
        .doesNotContain("sorry");
    assertThat(
            LeanLexer.stripLeanCommentsAndStrings(
                "def t : String := \"{ \\\"sorry\\\", \\\"unsafe\\\" }\"\n"))
        .doesNotContain("sorry");
    assertThat(
            LeanLexer.stripLeanCommentsAndStrings(
                "def h1 := dbg_trace \"{'\"'}\"\n"
                    + "def h2 := v!\"{'\"'}\"\n"
                    + "def h3 := println! \"{'\"'}\"\n"
                    + "def h4 := trace[Meta.debug] \"{'\"'} {sorry}\"\n"
                    + "theorem backdoor : False := sorry\n"))
        .contains("theorem backdoor : False := sorry");
    assertThat(
            LeanLexer.hasUnterminatedLiteralOrComment(
                "def h1 := dbg_trace \"{'\"'}\"\ndef h2 := v!\"{'\"'}\"\n"))
        .isFalse();

    // R2-INT-1: standalone operators (`!`, `?`) and number literals (`0`, `1e3`) do not block
    // following char literals `'"'` or raw strings `r#"""#`.
    assertThat(LeanLexer.hasIdentPartBefore("!\"", 1)).isFalse();
    assertThat(LeanLexer.hasIdentPartBefore("0r#\"\"\"#", 1)).isFalse();
    assertThat(LeanLexer.matchCharLiteral("!\"", 1)).isEqualTo(0);
    assertThat(LeanLexer.matchCharLiteral("!'\"'", 1)).isEqualTo(3);
    assertThat(LeanLexer.matchRawStringOpen("0r#\"\"\"#", 1))
        .isEqualTo(new LeanLexer.RawStringOpen(1, 4));
    assertThat(
            LeanLexer.stripLeanCommentsAndStrings(
                "def ok1 : Char := !'\"'\n"
                    + "def ok2 : String := 0r#\"\"\"#\n"
                    + "def ok3 : String := 1e3r#\"\"\"#\n"
                    + "theorem after_ok : False := sorry\n"))
        .contains("theorem after_ok : False := sorry");

    // R2-INT-10: non-letterlike prefix `ⁿ` cannot merge with `r"`, while letterlike `⅋` merges
    // with `r` into `⅋r` before plain `"a\""`.
    assertThat(LeanLexer.matchRawStringOpen("ⁿr\"a\\\"", 1))
        .isEqualTo(new LeanLexer.RawStringOpen(0, 3));
    assertThat(LeanLexer.matchRawStringOpen("⅋r\"a\\\"\"", 1)).isNull();
    assertThat(
            LeanLexer.stripLeanCommentsAndStrings(
                "def swallow1 := ⁿr\"a\\\"\n"
                    + "theorem swallowed_sorry1 : False := sorry\n"
                    + "def swallow2 := ⅋r\"a\\\"\"\n"
                    + "theorem swallowed_sorry2 : False := sorry\n"))
        .contains("theorem swallowed_sorry2 : False := sorry");

    // R2-INT-11: `throwNamedErrorAt`, `logNamedErrorAt`, `logNamedWarningAt`, `throwErrorAt`
    // with delimited `term:max` refs, and trailing-dot `identWithPartialTrailingDot`.
    assertThat(
            LeanLexer.stripLeanCommentsAndStrings(
                "def f1 := throwNamedErrorAt stx myErr \"{'\"'}\"\n"
                    + "theorem evil1 : False := sorry\n"
                    + "def f2 := logNamedErrorAt (← getRef) lean.err \"{'\"'}\"\n"
                    + "theorem evil2 : False := sorry\n"
                    + "def f3 := logNamedWarningAt stx[0].lhs lean. \"{'\"'}\"\n"
                    + "theorem evil3 : False := sorry\n"
                    + "def f4 := throwErrorAt (findStx \"ok\") \"{'\"'}\"\n"
                    + "theorem evil4 : False := sorry\n"))
        .contains("theorem evil4 : False := sorry");
    assertThat(
            LeanLexer.stripLeanCommentsAndStringsForHygiene(
                "def f5 := throwErrorAt (← getRef) \"{'\"'} {sorry}\"\n"))
        .contains("sorry");
    assertThat(
            LeanLexer.stripLeanCommentsAndStringsForHygiene(
                "def safe1 := throwErrorAt stx (helper \"{sorry}\")\n"
                    + "def safe2 := #eval \"{sorry}\"\n"))
        .doesNotContain("sorry");

    // R2-INT-4: `/--` and `/-!` are 3-character openers at nesting depth 0 (`Basic.lean:584`).
    assertThat(LeanLexer.findBlockCommentEnd(ImmutableList.of("/--/-/"), 0).closed()).isFalse();
    assertThat(LeanLexer.findBlockCommentEnd(ImmutableList.of("/-!/-/"), 0).closed()).isFalse();
    assertThat(LeanLexer.findBlockCommentEnd(ImmutableList.of("/--/"), 0).closed()).isFalse();
    assertThat(LeanLexer.findBlockCommentEnd(ImmutableList.of("/---/"), 0).closed()).isTrue();
    assertThat(LeanLexer.findBlockCommentEnd(ImmutableList.of("/--/-/", "-/ -/"), 0))
        .isEqualTo(new LeanLexer.BlockCommentEnd(1, 5, true));
    assertThat(LeanLexer.hasUnterminatedLiteralOrComment("structure Foo where\n  /--/-/")).isTrue();
    assertThat(LeanLexer.skipLeadingWsAndBlockComments("/--/-/ def hidden := 1"))
        .isEqualTo("/--/-/ def hidden := 1");
  }

  @Test
  public void interpCmdStateTracksCommandsWithoutKeywordDuplication() {
    LeanLexer.InterpCmdState s = LeanLexer.InterpCmdState.INITIAL;
    assertThat(s.expectsQuote()).isFalse();
    assertThat(s.inTraceBracket()).isFalse();

    // `dbg_trace`, `println!`, `throwError` expect quote across spaces/newlines.
    LeanLexer.InterpCmdState dbg = s.onIdent("dbg_trace").onPunct(' ').onNewline();
    assertThat(dbg.expectsQuote()).isTrue();
    assertThat(dbg.inTraceBracket()).isFalse();
    assertThat(dbg.onPunct('+').expectsQuote()).isFalse();

    // `trace[Meta.debug]` and nested `trace[[Meta].debug]`, resetting on newline if unclosed.
    LeanLexer.InterpCmdState tr =
        s.onIdent("trace").onPunct('[').onIdent("Meta").onPunct('.').onIdent("debug");
    assertThat(tr.inTraceBracket()).isTrue();
    assertThat(tr.expectsQuote()).isFalse();
    assertThat(tr.onNewline()).isEqualTo(LeanLexer.InterpCmdState.INITIAL);
    assertThat(tr.onPunct(']').expectsQuote()).isTrue();
    assertThat(
            s.onIdent("trace_goal")
                .onPunct('[')
                .onPunct('[')
                .onIdent("a")
                .onPunct(']')
                .inTraceBracket())
        .isTrue();
    assertThat(
            s.onIdent("trace_goal")
                .onPunct('[')
                .onPunct('[')
                .onIdent("a")
                .onPunct(']')
                .onPunct(']')
                .expectsQuote())
        .isTrue();

    // `throwErrorAt x.y "..."`, `throwNamedError `a "..."`, `logNamedWarning «a».«b» "..."`,
    // `throwNamedError lean. "..."`, `throwErrorAt (← getRef) "..."`, and `*Named*At ref id "..."`.
    LeanLexer.InterpCmdState named =
        s.onIdent("throwErrorAt").onPunct(' ').onIdent("stx").onPunct('.').onIdent("arg");
    assertThat(named.expectsQuote()).isTrue();
    assertThat(
            s.onIdent("throwErrorAt")
                .onPunct(' ')
                .onPunct('(')
                .onPunct('←')
                .onIdent("getRef")
                .inTermDelimiter())
        .isTrue();
    assertThat(
            s.onIdent("throwErrorAt")
                .onPunct(' ')
                .onPunct('(')
                .onIdent("findStx")
                .onPunct(' ')
                .onQuote()
                .inTermDelimiter())
        .isTrue();
    assertThat(
            s.onIdent("throwErrorAt")
                .onPunct(' ')
                .onPunct('(')
                .onIdent("getRef")
                .onPunct(')')
                .onPunct(' ')
                .expectsQuote())
        .isTrue();
    assertThat(
            s.onIdent("throwErrorAt")
                .onPunct(' ')
                .onIdent("stx")
                .onPunct('[')
                .onNumber()
                .onPunct(']')
                .onPunct('.')
                .onIdent("lhs")
                .onPunct(' ')
                .expectsQuote())
        .isTrue();
    assertThat(
            s.onIdent("throwErrorAt")
                .onPunct(' ')
                .onPunct('#')
                .onIdent("eval")
                .onPunct(' ')
                .expectsQuote())
        .isFalse();
    assertThat(
            s.onIdent("throwErrorAt")
                .onPunct(' ')
                .onPunct('#')
                .onPunct('[')
                .onIdent("a")
                .onPunct(']')
                .onPunct(' ')
                .expectsQuote())
        .isTrue();
    assertThat(s.onIdent("throwNamedError").onPunct(' ').onPunct('`').onIdent("tag").expectsQuote())
        .isTrue();
    assertThat(
            s.onIdent("throwNamedError")
                .onPunct(' ')
                .onIdent("lean")
                .onPunct('.')
                .onPunct(' ')
                .expectsQuote())
        .isTrue();
    assertThat(
            s.onIdent("throwNamedErrorAt")
                .onPunct(' ')
                .onPunct('(')
                .onIdent("getRef")
                .onPunct(')')
                .onPunct(' ')
                .expectsQuote())
        .isFalse();
    assertThat(
            s.onIdent("logNamedWarningAt")
                .onPunct(' ')
                .onPunct('(')
                .onIdent("getRef")
                .onPunct(')')
                .onPunct(' ')
                .onIdent("lean")
                .onPunct('.')
                .expectsQuote())
        .isTrue();
    assertThat(
            s.onIdent("logNamedWarning")
                .onPunct(' ')
                .onGuillemet()
                .onPunct('.')
                .onGuillemet()
                .expectsQuote())
        .isTrue();
  }

  @Test
  public void lexerOperationsAreThreadSafe() throws Exception {
    ImmutableList<String> samples =
        ImmutableList.of(
            "\uFEFF/- a /- b -/ -/ def x := r#\"s\"# + s!\"{sorry}\"",
            "«sorryAx» + r\"\\\"\ntheorem t := sorry",
            "/-- doc -/ @[simp,\n inline] def y := '\uD83D\uDE00'");
    ExecutorService pool = Executors.newFixedThreadPool(4);
    try {
      List<Callable<String>> tasks = new ArrayList<>();
      for (int i = 0; i < 40; i++) {
        String s = samples.get(i % samples.size());
        tasks.add(() -> {
          LeanLexer.LeanLexState st = LeanLexer.createLeanLexState();
          LeanLexer.advanceLeanLexState(st, s);
          return LeanLexer.stripLeanCommentsAndStrings(s);
        });
      }
      List<Future<String>> futures = pool.invokeAll(tasks);
      for (int i = 0; i < futures.size(); i++) {
        String expected = LeanLexer.stripLeanCommentsAndStrings(samples.get(i % samples.size()));
        assertThat(futures.get(i).get()).isEqualTo(expected);
      }
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  public void cleanLineFastPathPreservesStateAndResetClearsAllFields() {
    ImmutableList<String> cleanSamples =
        ImmutableList.of(
            "",
            "theorem t_0_0 : True := trivial",
            "x -",
            "x /",
            "a » b",
            "def f (x : Nat) : α\uD835\uDC00 := x + 1",
            "dbg_trace",
            "throwErrorAt (ref)");
    for (String sample : cleanSamples) {
      assertThat(LeanLexer.stripLeanCommentsAndStrings(sample)).isEqualTo(sample);
      assertThat(LeanLexer.stripLeanCommentsAndStringsForHygiene(sample)).isEqualTo(sample);
    }

    // Command-expecting state set on a clean line (fast-path candidate) must carry to next line.
    LeanLexer.LeanLexState st = LeanLexer.createLeanLexState();
    assertThat(LeanLexer.advanceLeanLexState(st, "logNamedWarningAt (getRef) lean."))
        .isEqualTo("logNamedWarningAt (getRef) lean.");
    assertThat(LeanLexer.advanceLeanLexState(st, "\"x {sorry}\"")).isEqualTo("   {sorry} ");
    assertThat(st.hasOpenLiteralOrComment()).isFalse();
    assertThat(st.hasOpenInterpBrace()).isFalse();

    // Multi-line open interp brace must disable fast path when returning to interp string on `}`.
    assertThat(LeanLexer.advanceLeanLexState(st, "dbg_trace \"a {")).isEqualTo("dbg_trace    {");
    assertThat(st.hasOpenInterpBrace()).isTrue();
    assertThat(LeanLexer.advanceLeanLexState(st, "x} hidden\"")).isEqualTo("x}        ");
    assertThat(st.hasOpenLiteralOrComment()).isFalse();

    // Pollute every state field, reset, and confirm clean state and subsequent fast-path reuse.
    LeanLexer.advanceLeanLexState(st, "([{/-\"r");
    st.reset();
    LeanLexer.LeanLexState rawSt = LeanLexer.createLeanLexState();
    rawSt.reset();
    assertThat(st.blockDepth()).isEqualTo(0);
    assertThat(st.inString()).isFalse();
    assertThat(st.inInterpString()).isFalse();
    assertThat(st.rawStringHashes()).isEqualTo(-1);
    assertThat(st.inGuillemet()).isFalse();
    assertThat(st.paren()).isEqualTo(0);
    assertThat(st.bracket()).isEqualTo(0);
    assertThat(st.brace()).isEqualTo(0);
    assertThat(st.hasOpenLiteralOrComment()).isFalse();
    assertThat(st.hasOpenInterpBrace()).isFalse();
    assertThat(st.hasOpenDelimiters()).isFalse();
    assertThat(LeanLexer.advanceLeanLexState(st, "\"ok\"")).isEqualTo("    ");
  }
}
