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
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Adversarial integrity, ordering, Unicode, and DoS tests for {@link LeanParser}. */
@RunWith(JUnit4.class)
public final class LeanParserAdversarialTest {

  private static final Splitter NL = Splitter.on('\n');

  private static void assertStructuralInvariants(String source, ImmutableList<LeanBlock> blocks) {
    String norm = LeanLexer.normalizeLeanSource(source);
    List<String> physLines = NL.splitToList(norm);
    BitSet covered = new BitSet(physLines.size() + 1);
    int prevStart = 0;
    int prevEnd = 0;

    for (LeanBlock b : blocks) {
      assertThat(b.startLine()).isAtLeast(1);
      assertThat(b.endLine()).isAtLeast(b.startLine());
      assertThat(b.endLine()).isAtMost(physLines.size());
      assertThat(b.startLine()).isAtLeast(prevStart);
      assertThat(b.startLine()).isAtLeast(prevEnd);
      prevStart = b.startLine();
      prevEnd = b.endLine();

      for (int ln = b.startLine(); ln <= b.endLine(); ln++) {
        covered.set(ln);
      }

      List<LeanCodeLine> lines = new ArrayList<>();
      lines.addAll(b.importLines());
      lines.addAll(b.directiveLines());
      lines.addAll(b.commentLines());
      if (b.decl() != null) {
        lines.addAll(b.decl().signatureLines());
        lines.addAll(b.decl().bodyLines());
      }
      int prevLn = 0;
      for (LeanCodeLine cl : lines) {
        assertThat(cl.text()).doesNotContain("\n");
        assertThat(cl.text()).doesNotContain("\r");
        assertThat(cl.lineNum()).isAtLeast(b.startLine());
        assertThat(cl.lineNum()).isAtMost(b.endLine());
        assertThat(cl.lineNum()).isAtLeast(prevLn);
        prevLn = cl.lineNum();
      }
    }

    for (int ln = 1; ln <= physLines.size(); ln++) {
      if (!physLines.get(ln - 1).strip().isEmpty()) {
        assertThat(covered.get(ln)).isTrue();
      }
    }
  }

  @Test
  public void importLineTrailingDeclarationCannotHideInsideImportsBlock() {
    // S-B-1: trailing theorem/axiom on an import line must split out rather than hide in IMPORTS.
    String src = "import Init.Prelude theorem bad : False := sorry\nimport   \n";
    ImmutableList<LeanBlock> blocks = LeanParser.parse(src);
    assertStructuralInvariants(src, blocks);

    assertThat(blocks.get(0).type()).isEqualTo(LeanBlockType.IMPORTS);
    assertThat(blocks.get(0).raw()).isEqualTo("import Init.Prelude");
    assertThat(blocks.get(1).type()).isEqualTo(LeanBlockType.DECLARATION);
    assertThat(blocks.get(1).decl().name()).isEqualTo("bad");
    assertThat(blocks.get(1).decl().hygiene().hasSorry()).isTrue();
    assertThat(LeanHygiene.count(blocks).sorryCount()).isEqualTo(1);
    assertThat(LeanHygiene.count(blocks).theorems()).isEqualTo(1);
  }

  @Test
  public void preservesIntermediateBlockCommentsDerivingCommentsAndColOffsetWs() {
    // S-B-3 (#92 rule 6): bare `:=` and `by` remain stripped per TS card layout, while
    // intermediate `/- ... -/` between `:=` and `by`, `deriving ... -- note`, and leading ws stay.
    String tacticWithComment = "theorem t : True := /- SECRET_A -/ by\n  trivial\n";
    ImmutableList<LeanBlock> blocks1 = LeanParser.parse(tacticWithComment);
    assertStructuralInvariants(tacticWithComment, blocks1);
    LeanDeclaration decl1 = blocks1.get(0).decl();
    assertThat(decl1.signatureLines().get(0).text()).isEqualTo("theorem t : True");
    assertThat(decl1.bodyLines().get(0).text()).isEqualTo("/- SECRET_A -/");
    assertThat(decl1.isTacticProof()).isTrue();

    String oneLiner = "theorem t2 : True := /- A -/ by /- B -/ trivial\n";
    ImmutableList<LeanBlock> blocks2 = LeanParser.parse(oneLiner);
    assertStructuralInvariants(oneLiner, blocks2);
    assertThat(blocks2.get(0).decl().bodyLines().get(0).text())
        .isEqualTo("/- A -/ /- B -/ trivial");

    String derivingComment = "structure S where\n  x : Nat\nderiving Repr -- HIDDEN_NOTE\n";
    ImmutableList<LeanBlock> blocks3 = LeanParser.parse(derivingComment);
    assertStructuralInvariants(derivingComment, blocks3);
    assertThat(blocks3.get(0).decl().fields()).hasSize(1);
    assertThat(blocks3.get(0).decl().derivingClause())
        .containsExactly("Repr", "-- HIDDEN_NOTE")
        .inOrder();

    String colOffsetWs = "open Nat in   def x : Nat := 1\n";
    ImmutableList<LeanBlock> blocks4 = LeanParser.parse(colOffsetWs);
    assertStructuralInvariants(colOffsetWs, blocks4);
    assertThat(blocks4.get(1).raw()).isEqualTo("   def x : Nat := 1");
  }

  @Test
  public void pendingAttrsAndCommentsPreserveLineOrderingAndSingleLineCodeLines() {
    // S-B-4 & CODE-12/CODE-13: interleaved attrs/comments/modifiers & multi-line standalone attrs.
    String interleaved = "@[a]\n-- middle comment\n@[b]\nnamespace N\n";
    ImmutableList<LeanBlock> blocks1 = LeanParser.parse(interleaved);
    assertStructuralInvariants(interleaved, blocks1);
    assertThat(blocks1).hasSize(4);
    assertThat(blocks1.get(0).startLine()).isEqualTo(1);
    assertThat(blocks1.get(1).startLine()).isEqualTo(2);
    assertThat(blocks1.get(2).startLine()).isEqualTo(3);
    assertThat(blocks1.get(3).startLine()).isEqualTo(4);

    String multiLineAttrStandalone = "@[simp,\n  inline]\n\n#eval 1 + 1\n";
    ImmutableList<LeanBlock> blocks2 = LeanParser.parse(multiLineAttrStandalone);
    assertStructuralInvariants(multiLineAttrStandalone, blocks2);
    assertThat(blocks2.get(0).type()).isEqualTo(LeanBlockType.DIRECTIVE);
    assertThat(blocks2.get(0).directiveLines()).hasSize(2);
    assertThat(blocks2.get(0).directiveLines().get(0)).isEqualTo(new LeanCodeLine(1, "@[simp,"));
    assertThat(blocks2.get(0).directiveLines().get(1)).isEqualTo(new LeanCodeLine(2, "  inline]"));
  }

  @Test
  public void unclosedDelimitersAndDirectivesCannotSwallowSubsequentDeclarations() {
    // CODE-22: an unclosed `(` in a declaration with `sorry` must not swallow the next theorem.
    String openParen = "def d : Nat := (\n  sorry\n\ntheorem t2 : False := by sorry\n";
    ImmutableList<LeanBlock> b1 = LeanParser.parse(openParen);
    assertStructuralInvariants(openParen, b1);
    assertThat(b1).hasSize(2);
    assertThat(LeanHygiene.count(b1).sorryCount()).isEqualTo(2);

    // Indented theorem after unclosed `(` also breaks cleanly.
    String indentedTheorem = "def d := (\n  theorem sneaky : False := by sorry\n";
    ImmutableList<LeanBlock> b2 = LeanParser.parse(indentedTheorem);
    assertStructuralInvariants(indentedTheorem, b2);
    assertThat(b2).hasSize(2);
    assertThat(b2.get(1).decl().name()).isEqualTo("sneaky");

    // Non-body directive / fallback must stop before standalone indented modifiers.
    String directiveMod = "#eval 1\n  unsafe\n  def u : Nat := 0\n";
    ImmutableList<LeanBlock> b3 = LeanParser.parse(directiveMod);
    assertStructuralInvariants(directiveMod, b3);
    assertThat(b3.get(1).decl().hygiene().isUnsafe()).isTrue();
  }

  @Test
  public void controlWhitespaceNulAndUnicodeMaintainAlignmentAndPreservation() {
    // NUL byte and control chars `\u000B`, `\u2028` must never desync startCol or drop non-empty
    // NUL lines.
    String src = "\u000B@[simp\n]\n\0\0\ndef f := 1\n";
    ImmutableList<LeanBlock> blocks = LeanParser.parse(src);
    assertStructuralInvariants(src, blocks);
    assertThat(blocks.stream().anyMatch(b -> b.raw().contains("\0\0"))).isTrue();
    assertThat(blocks.stream().anyMatch(b -> b.decl() != null && "f".equals(b.decl().name())))
        .isTrue();

    String bomEveryLine = "\uFEFFtheorem a := 1\n\uFEFFopen A in theorem b := by sorry\n";
    ImmutableList<LeanBlock> bomBlocks = LeanParser.parse(bomEveryLine);
    assertStructuralInvariants(bomEveryLine, bomBlocks);
    assertThat(LeanHygiene.count(bomBlocks).sorryCount()).isEqualTo(1);

    // CODE-15: U+2028, U+2029, and U+2003 are not Lean whitespace and must never be dropped as
    // blank.
    String lineSepSrc = "def a := 1\n\u2028\n\u2029\n\u2003\ndef b := 2\n";
    ImmutableList<LeanBlock> lineSepBlocks = LeanParser.parse(lineSepSrc);
    assertStructuralInvariants(lineSepSrc, lineSepBlocks);
    assertThat(lineSepBlocks.stream().anyMatch(b -> b.raw().contains("\u2028"))).isTrue();
    assertThat(lineSepBlocks.stream().anyMatch(b -> b.raw().contains("\u2029"))).isTrue();
    assertThat(lineSepBlocks.stream().anyMatch(b -> b.raw().contains("\u2003"))).isTrue();
  }

  @Test
  public void multiLineLiteralsAcrossDirectivesFallbacksAndMutualCannotTrapTopLevelDocComments() {
    // CODE-22: `#eval " ... /-- ... "` closes at `"` and leaves `theorem backdoor` top-level.
    String evalMultiLine = "#eval \"hello\n/--\n\"\ntheorem backdoor : False := by sorry\n";
    ImmutableList<LeanBlock> evalBlocks = LeanParser.parse(evalMultiLine);
    assertStructuralInvariants(evalMultiLine, evalBlocks);
    assertThat(evalBlocks.stream().filter(b -> b.type() == LeanBlockType.MODULE_DOC).count())
        .isEqualTo(0);
    assertThat(LeanHygiene.count(evalBlocks).theorems()).isEqualTo(1);
    assertThat(LeanHygiene.count(evalBlocks).sorryCount()).isEqualTo(1);

    // Unprefixed/fallback code block opening `s!"...\n/--\n"` must not split into MODULE_DOC.
    String fallbackMultiLine =
        "sorry? s!\"start\n/-- unclosed doc\n\nde#}\"\n"
            + "def fuzz_dbg : IO Unit := dbg_trace \"{sorry}\"\n";
    ImmutableList<LeanBlock> fallbackBlocks = LeanParser.parse(fallbackMultiLine);
    assertStructuralInvariants(fallbackMultiLine, fallbackBlocks);
    assertThat(LeanHygiene.count(fallbackBlocks).definitions()).isEqualTo(1);
    assertThat(LeanHygiene.count(fallbackBlocks).sorryCount()).isEqualTo(1);

    // `mutual` line with nested block comment or string literal keeps `theorem bad` countable.
    String mutualNestedComment =
        "mutual /-\n/-- nested\n-/\n-/\ntheorem bad : False := by sorry\nend\n";
    ImmutableList<LeanBlock> mutualBlocks = LeanParser.parse(mutualNestedComment);
    assertStructuralInvariants(mutualNestedComment, mutualBlocks);
    assertThat(LeanHygiene.count(mutualBlocks).sorryCount()).isEqualTo(1);

    String mutualMultiLineString = "mutual \"foo\n/--\n\"\ntheorem bad : False := by sorry\nend\n";
    ImmutableList<LeanBlock> mutualStrBlocks = LeanParser.parse(mutualMultiLineString);
    assertStructuralInvariants(mutualMultiLineString, mutualStrBlocks);
    assertThat(LeanHygiene.count(mutualStrBlocks).sorryCount()).isEqualTo(1);

    // Unclosed `(`, `[`, `{` inside declaration cannot swallow subsequent `import ... theorem ...`.
    String unclosedParenImport =
        "def f (x : Nat\n  sorry\n\nimport Init.Prelude theorem fuzz_evade : False := sorry\n";
    ImmutableList<LeanBlock> unclosedBlocks = LeanParser.parse(unclosedParenImport);
    assertStructuralInvariants(unclosedParenImport, unclosedBlocks);
    assertThat(LeanHygiene.count(unclosedBlocks).theorems()).isEqualTo(1);
    assertThat(LeanHygiene.count(unclosedBlocks).sorryCount()).isEqualTo(2);

    // Interp + raw string + `sr#"` inside unclosed @[...] cannot invert string parity at `]`.
    String attrSync =
        "@[unclosed\ns!\"hi {x} {r#\"\"\"#}\"\n  | n | sr#\" | e | w\n"
            + "]m!\"protected\ntheorem fuzz_evade : False := by sorry\n";
    ImmutableList<LeanBlock> attrSyncBlocks = LeanParser.parse(attrSync);
    assertStructuralInvariants(attrSync, attrSyncBlocks);
    assertThat(LeanHygiene.count(attrSyncBlocks).theorems()).isEqualTo(1);
    assertThat(LeanHygiene.count(attrSyncBlocks).sorryCount()).isEqualTo(1);

    // Stray `]` inside `f!"{...` inside unclosed `@[` must not desync interp-string end `}` -> `"`.
    String attrInterpBrace =
        "@[unclosed\nf!\"{\n[\n]\n]\n}\n/-\n\"\n#r\"\\\"\ntheorem fuzz_hash_r : False := sorry\n";
    ImmutableList<LeanBlock> attrInterpBlocks = LeanParser.parse(attrInterpBrace);
    assertStructuralInvariants(attrInterpBrace, attrInterpBlocks);
    assertThat(LeanHygiene.count(attrInterpBlocks).theorems()).isEqualTo(1);
    assertThat(LeanHygiene.count(attrInterpBlocks).sorryCount()).isEqualTo(1);

    // `open s!"{ in ...` must not split mid-interp-brace and leak unclosed brace onto `/--`.
    String openInBrace = "open s!\"{ in x\n\"/--\"\n}\"\ntheorem bad : False := by sorry\n";
    ImmutableList<LeanBlock> openInBlocks = LeanParser.parse(openInBrace);
    assertStructuralInvariants(openInBrace, openInBlocks);
    assertThat(LeanHygiene.count(openInBlocks).theorems()).isEqualTo(1);
    assertThat(LeanHygiene.count(openInBlocks).sorryCount()).isEqualTo(1);

    // `import r#"..."#` / `import s!"..."` must never split the literal opener (`r` vs `#"`)
    // leaving trailing `]m!"` inverted across blocks.
    String importRawLit =
        "import r#\"all Init.Data.List\n@[unclosed\n\"#\n" + "theorem bad : False := by sorry\n";
    ImmutableList<LeanBlock> importRawBlocks = LeanParser.parse(importRawLit);
    assertStructuralInvariants(importRawLit, importRawBlocks);
    assertThat(LeanHygiene.count(importRawBlocks).theorems()).isEqualTo(1);
    assertThat(LeanHygiene.count(importRawBlocks).sorryCount()).isEqualTo(1);

    // Same-line trailing `/-- Doc -/ "unclosed` must not duplicate `"unclosed` inside doc `raw()`.
    String sameLineDocQuote = "/-- Doc -/ \"unclosed\n";
    ImmutableList<LeanBlock> docQuoteBlocks = LeanParser.parse(sameLineDocQuote);
    assertStructuralInvariants(sameLineDocQuote, docQuoteBlocks);
    assertThat(docQuoteBlocks).hasSize(2);
    assertThat(docQuoteBlocks.get(0).raw()).isEqualTo("/-- Doc -/");
    assertThat(docQuoteBlocks.get(1).raw()).isEqualTo(" \"unclosed\n");
    assertThat(LeanHygiene.hasUnterminatedLiteralOrComment(docQuoteBlocks)).isTrue();

    // Same-line `open A in /-- Doc -` attached to a declaration must not duplicate `open A in`.
    String openInDocDecl = "open A in /-- Doc -\n-/ theorem t := \"hello\nworld\"\n";
    ImmutableList<LeanBlock> openInDocBlocks = LeanParser.parse(openInDocDecl);
    assertStructuralInvariants(openInDocDecl, openInDocBlocks);
    assertThat(openInDocBlocks).hasSize(2);
    assertThat(openInDocBlocks.get(0).raw()).isEqualTo("open A in");
    assertThat(openInDocBlocks.get(1).type()).isEqualTo(LeanBlockType.DECLARATION);
    assertThat(openInDocBlocks.get(1).raw()).doesNotContain("open A in");
    assertThat(LeanHygiene.hasUnterminatedLiteralOrComment(openInDocBlocks)).isFalse();
  }

  @Test
  public void pathologicalLinearTimeAtCapSizesDoesNotBlowUp() {
    // S-D-1: 20k `--` comments in decl body with `trivial` or open `(`.
    StringBuilder sb = new StringBuilder();
    sb.append("theorem t : True := by\n");
    for (int i = 0; i < 19_998; i++) {
      sb.append("-- c\n");
    }
    sb.append("  trivial\n");
    ImmutableList<LeanBlock> b1 = LeanParser.parse(sb.toString());
    assertThat(b1).hasSize(1);

    // S-D-2: repeated `@[a]`, `local `, `open A in `, and `/- a -/` on single lines.
    assertThat(LeanParser.parse("@[a]".repeat(40_000) + " def x := 1")).hasSize(1);
    assertThat(LeanParser.parse("theorem x : True := by\n  " + "local ".repeat(20_000))).hasSize(2);
    assertThat(LeanParser.parse("open A in ".repeat(10_000))).hasSize(10_000);
    assertThat(LeanParser.parse("/- a -/".repeat(40_000))).hasSize(40_000);
  }

  @Test
  public void parserIsThreadSafe() throws Exception {
    ImmutableList<String> inputs =
        ImmutableList.of(
            "import Init\ntheorem t : True := /- c -/ by\n  trivial\n",
            "structure S where\n  x : Nat\nderiving Repr -- note\n",
            "@[a]\n-- mid\n@[b]\nopen Nat in def f := 1\n");
    ExecutorService pool = Executors.newFixedThreadPool(4);
    try {
      List<Callable<ImmutableList<LeanBlock>>> tasks = new ArrayList<>();
      for (int i = 0; i < 40; i++) {
        String s = inputs.get(i % inputs.size());
        tasks.add(() -> LeanParser.parse(s));
      }
      List<Future<ImmutableList<LeanBlock>>> futures = pool.invokeAll(tasks);
      for (int i = 0; i < futures.size(); i++) {
        assertThat(futures.get(i).get()).isEqualTo(LeanParser.parse(inputs.get(i % inputs.size())));
      }
    } finally {
      pool.shutdownNow();
    }
  }
}
