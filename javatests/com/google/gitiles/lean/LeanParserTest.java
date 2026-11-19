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
import java.util.Locale;
import java.util.Optional;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link LeanParser}, porting {@code lean-diff-util_test.ts} parser specs. */
@RunWith(JUnit4.class)
public final class LeanParserTest {

  private static Optional<LeanBlock> maybeFindDecl(ImmutableList<LeanBlock> blocks, String name) {
    for (LeanBlock block : blocks) {
      if (block.decl() != null && name.equals(block.decl().name())) {
        return Optional.of(block);
      }
    }
    return Optional.empty();
  }

  private static LeanBlock findDecl(ImmutableList<LeanBlock> blocks, String name) {
    return maybeFindDecl(blocks, name)
        .orElseThrow(() -> new AssertionError("Missing declaration: " + name));
  }

  private static String joinCodeLines(ImmutableList<LeanCodeLine> lines) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < lines.size(); i++) {
      if (i > 0) {
        sb.append('\n');
      }
      sb.append(lines.get(i).text());
    }
    return sb.toString();
  }

  @Test
  public void returnsEmptyListForEmptySource() {
    assertThat(LeanParser.parse("")).isEmpty();
  }

  @Test
  public void parsesModuleDocLicenseHeaderImportsDirectiveBannerStructureDefTheorem() {
    String source =
        String.join(
            "\n",
            "import Init",
            "",
            "/-",
            "Copyright (C) 2026 Example Contributors",
            "Licensed under the Apache License, Version 2.0",
            "-/",
            "",
            "/-!",
            "# Soundness Proof",
            "Zero `sorry` or `axiom` declarations.",
            "-/",
            "",
            "namespace BoundedLattice",
            "",
            "-- ============================================",
            "-- Section 0: Helpers on `Nat`",
            "-- ============================================",
            "",
            "/-- Bitwise subset order. -/",
            "@[simp]",
            "def maskSub (a b : Nat) : Prop :=",
            "  (a &&& b) = a",
            "",
            "def MAX_LEVEL : Nat := 7",
            "",
            "/-- Single-cell state. -/",
            "structure CellState where",
            "  level : Nat -- `[2:0]` bottom = 7",
            "  isPinned : Bool := false -- `[11]`",
            "  deriving DecidableEq, Repr",
            "",
            "theorem maskSub_refl (a : Nat) : maskSub a a := by",
            "  unfold maskSub",
            "  simp",
            "",
            "end BoundedLattice");

    ImmutableList<LeanBlock> blocks = LeanParser.parse(source);
    assertThat(blocks.stream().map(LeanBlock::type).toList())
        .containsExactly(
            LeanBlockType.IMPORTS,
            LeanBlockType.COMMENT,
            LeanBlockType.MODULE_DOC,
            LeanBlockType.DIRECTIVE,
            LeanBlockType.SECTION_BANNER,
            LeanBlockType.DECLARATION,
            LeanBlockType.DECLARATION,
            LeanBlockType.DECLARATION,
            LeanBlockType.DECLARATION,
            LeanBlockType.DIRECTIVE)
        .inOrder();

    assertThat(blocks.get(0).startLine()).isEqualTo(1);
    assertThat(blocks.get(0).endLine()).isEqualTo(1);
    assertThat(blocks.get(0).importLines()).hasSize(1);

    assertThat(blocks.get(1).isLicenseHeader()).isTrue();
    assertThat(blocks.get(1).startLine()).isEqualTo(3);
    assertThat(blocks.get(1).endLine()).isEqualTo(6);

    assertThat(blocks.get(2).markdownContent()).contains("# Soundness Proof");
    assertThat(blocks.get(2).startLine()).isEqualTo(8);
    assertThat(blocks.get(2).endLine()).isEqualTo(11);

    assertThat(blocks.get(3).directiveKind()).isEqualTo("namespace");
    assertThat(blocks.get(4).markdownContent()).isEqualTo("Section 0: Helpers on `Nat`");

    LeanDeclaration maskSub = blocks.get(5).decl();
    assertThat(maskSub.kind()).isEqualTo(LeanDeclKind.DEF);
    assertThat(maskSub.name()).isEqualTo("maskSub");
    assertThat(maskSub.docComment()).isEqualTo("Bitwise subset order.");
    assertThat(maskSub.attributes()).containsExactly("@[simp]");
    assertThat(maskSub.isCompactDef()).isFalse();
    assertThat(maskSub.hygiene().hasSorry()).isFalse();

    LeanDeclaration maxLevel = blocks.get(6).decl();
    assertThat(maxLevel.kind()).isEqualTo(LeanDeclKind.DEF);
    assertThat(maxLevel.name()).isEqualTo("MAX_LEVEL");
    assertThat(maxLevel.isCompactDef()).isTrue();

    LeanDeclaration cellState = blocks.get(7).decl();
    assertThat(cellState.kind()).isEqualTo(LeanDeclKind.STRUCTURE);
    assertThat(cellState.name()).isEqualTo("CellState");
    assertThat(cellState.fields()).hasSize(2);
    assertThat(cellState.fields().get(0).name()).isEqualTo("level");
    assertThat(cellState.fields().get(0).typeText()).isEqualTo("Nat");
    assertThat(cellState.fields().get(0).comment()).isEqualTo("`[2:0]` bottom = 7");
    assertThat(cellState.fields().get(1).name()).isEqualTo("isPinned");
    assertThat(cellState.fields().get(1).typeText()).isEqualTo("Bool");
    assertThat(cellState.fields().get(1).defaultValue()).isEqualTo("false");
    assertThat(cellState.derivingClause()).containsExactly("DecidableEq", "Repr").inOrder();

    LeanDeclaration maskSubRefl = blocks.get(8).decl();
    assertThat(maskSubRefl.kind()).isEqualTo(LeanDeclKind.THEOREM);
    assertThat(maskSubRefl.name()).isEqualTo("maskSub_refl");
    assertThat(maskSubRefl.isTacticProof()).isTrue();
    assertThat(maskSubRefl.proofStrategy()).isEqualTo(LeanProofStrategy.TACTIC);
    assertThat(maskSubRefl.signatureLines()).hasSize(1);
    assertThat(maskSubRefl.bodyLines()).hasSize(2);
    assertThat(maskSubRefl.hygiene().hasSorry()).isFalse();
  }

  @Test
  public void parsesEquationCompilerDefsAndInductiveConstructors() {
    String source =
        String.join(
            "\n",
            "def bitCount : Nat → Nat → Nat",
            "  | 0, _        => 0",
            "  | k + 1, mask => bitCount k mask + (if mask.testBit k then 1 else 0)",
            "",
            "inductive Verdict where",
            "  | ok : Nat → Verdict -- success",
            "  | err : String → Verdict -- failure",
            "  deriving Repr");

    ImmutableList<LeanBlock> blocks = LeanParser.parse(source);
    assertThat(blocks).hasSize(2);

    LeanDeclaration bitCount = blocks.get(0).decl();
    assertThat(bitCount.name()).isEqualTo("bitCount");
    assertThat(bitCount.signatureLines()).hasSize(1);
    assertThat(bitCount.bodyLines()).hasSize(2);

    LeanDeclaration verdict = blocks.get(1).decl();
    assertThat(verdict.kind()).isEqualTo(LeanDeclKind.INDUCTIVE);
    assertThat(verdict.constructors()).hasSize(2);
    assertThat(verdict.constructors().get(0).name()).isEqualTo("ok");
    assertThat(verdict.constructors().get(0).comment()).isEqualTo("success");
    assertThat(verdict.derivingClause()).containsExactly("Repr");
  }

  @Test
  public void detectsHygieneAlertsWithoutFalsePositivesInComments() {
    String source =
        String.join(
            "\n",
            "/-- Proves with zero `sorry`, `admit`, or `native_decide`. -/",
            "theorem clean_thm : 1 + 1 = 2 := by decide",
            "",
            "theorem has_sorry : False := by",
            "  sorry",
            "",
            "axiom external_oracle : Nat → Bool",
            "",
            "partial def loop (n : Nat) : Nat := loop (n + 1)",
            "",
            "theorem uses_native : 2 + 2 = 4 := by native_decide");

    ImmutableList<LeanBlock> blocks = LeanParser.parse(source);
    assertThat(blocks).hasSize(5);

    assertThat(blocks.get(0).decl().hygiene().hasSorry()).isFalse();
    assertThat(blocks.get(0).decl().hygiene().hasAdmit()).isFalse();
    assertThat(blocks.get(0).decl().hygiene().hasNativeDecide()).isFalse();
    assertThat(blocks.get(0).decl().proofStrategy()).isEqualTo(LeanProofStrategy.DECIDE);

    assertThat(blocks.get(1).decl().hygiene().hasSorry()).isTrue();
    assertThat(blocks.get(2).decl().hygiene().isAxiom()).isTrue();
    assertThat(blocks.get(3).decl().hygiene().isPartial()).isTrue();
    assertThat(blocks.get(4).decl().hygiene().hasNativeDecide()).isTrue();
  }

  @Test
  public void parsesModuleSystemHeadersAndModifierDirectives() {
    String source =
        String.join(
            "\n",
            "module",
            "",
            "public import Init.Core",
            "meta import Lean.Meta",
            "private import Init.Data.Nat.Basic",
            "import all Init.Data.List",
            "",
            "noncomputable section",
            "public section",
            "scoped notation \"⟪\" a \", \" b \"⟫\" => Prod.mk a b",
            "universe u v",
            "variable {α : Type u}",
            "#guard 1 + 1 == 2",
            "end");

    ImmutableList<LeanBlock> blocks = LeanParser.parse(source);
    assertThat(blocks.get(0).type()).isEqualTo(LeanBlockType.DIRECTIVE);
    assertThat(blocks.get(0).directiveKind()).isEqualTo("module");

    assertThat(blocks.get(1).type()).isEqualTo(LeanBlockType.IMPORTS);
    assertThat(blocks.get(1).importLines()).hasSize(4);
    assertThat(blocks.get(1).startLine()).isEqualTo(3);
    assertThat(blocks.get(1).endLine()).isEqualTo(6);

    assertThat(blocks.subList(2, blocks.size()).stream().map(LeanBlock::directiveKind).toList())
        .containsExactly("section", "section", "notation", "universe", "variable", "#guard", "end")
        .inOrder();
  }

  @Test
  public void parsesUniversePolymorphicGuillemetStackedModifiersAndNestedAttrs() {
    String source =
        String.join(
            "\n",
            "@[simp [Nat.add_comm], inline]",
            "public noncomputable def id.{u} {α : Sort u} (a : α) : α := a",
            "",
            "@[deprecated old_fn (since := \"2026-01-01\")] protected theorem Nat.my_id (n : Nat) :"
                + " n = n := rfl",
            "",
            "theorem «1 + 1 = 2» : 1 + 1 = 2 := by decide",
            "",
            "unsafe def peekPtr (x : Nat) : Nat := x",
            "",
            "opaque secretKey : Nat := 42",
            "",
            "example (p q : Prop) (hp : p) : p ∨ q := Or.inl hp",
            "",
            "def nativeOneLiner : Bool := by native_decide");

    ImmutableList<LeanBlock> blocks = LeanParser.parse(source);
    assertThat(blocks).hasSize(7);

    LeanDeclaration idDecl = blocks.get(0).decl();
    assertThat(idDecl.kind()).isEqualTo(LeanDeclKind.DEF);
    assertThat(idDecl.name()).isEqualTo("id");
    assertThat(idDecl.modifiers()).containsExactly("public", "noncomputable").inOrder();
    assertThat(idDecl.attributes()).containsExactly("@[simp [Nat.add_comm], inline]");

    LeanDeclaration natDecl = blocks.get(1).decl();
    assertThat(natDecl.kind()).isEqualTo(LeanDeclKind.THEOREM);
    assertThat(natDecl.name()).isEqualTo("Nat.my_id");
    assertThat(natDecl.modifiers()).containsExactly("protected");
    assertThat(natDecl.attributes())
        .containsExactly("@[deprecated old_fn (since := \"2026-01-01\")]");
    assertThat(natDecl.proofStrategy()).isEqualTo(LeanProofStrategy.TERM);

    LeanDeclaration guillemetDecl = blocks.get(2).decl();
    assertThat(guillemetDecl.name()).isEqualTo("«1 + 1 = 2»");
    assertThat(guillemetDecl.proofStrategy()).isEqualTo(LeanProofStrategy.DECIDE);

    LeanDeclaration unsafeDecl = blocks.get(3).decl();
    assertThat(unsafeDecl.hygiene().isUnsafe()).isTrue();
    assertThat(unsafeDecl.isCompactDef()).isFalse();

    LeanDeclaration opaqueDecl = blocks.get(4).decl();
    assertThat(opaqueDecl.kind()).isEqualTo(LeanDeclKind.OPAQUE);
    assertThat(opaqueDecl.name()).isEqualTo("secretKey");

    LeanDeclaration exDecl = blocks.get(5).decl();
    assertThat(exDecl.kind()).isEqualTo(LeanDeclKind.EXAMPLE);
    assertThat(exDecl.name()).isEmpty();

    LeanDeclaration nativeDecl = blocks.get(6).decl();
    assertThat(nativeDecl.hygiene().hasNativeDecide()).isTrue();
    assertThat(nativeDecl.isCompactDef()).isFalse();
  }

  @Test
  public void parsesStructureExtendsCustomCtorFallbackClassInductiveCoinductiveInstance() {
    String source =
        String.join(
            "\n",
            "structure Child extends Parent1, Parent2 where",
            "  label : String := \"-- not a comment\" -- field note",
            "  quote : Char := '\"' -- double-quote literal",
            "deriving DecidableEq, Repr, Hashable",
            "",
            "structure Point (α : Type) where",
            "  mk ::",
            "  x : α",
            "  y : α",
            "deriving Repr",
            "",
            "class Addable (α : Type) where",
            "  add : α → α → α",
            "",
            "inductive Direction where",
            "  | north | south | east | west -- cardinal directions",
            "deriving Repr, BEq",
            "",
            "inductive BoolMask : Type",
            "  | bits : (a ||| b = c) → BoolMask",
            "  | empty : BoolMask",
            "",
            "coinductive Stream (α : Type) where",
            "  | cons (head : α) (tail : Stream α) : Stream α",
            "",
            "instance (priority := high) [Addable α] : Addable (Option α) where",
            "  add",
            "    | some x, some y => some (Addable.add x y)",
            "    | _, _ => none");

    ImmutableList<LeanBlock> blocks = LeanParser.parse(source);
    assertThat(blocks).hasSize(7);

    LeanDeclaration child = blocks.get(0).decl();
    assertThat(child.kind()).isEqualTo(LeanDeclKind.STRUCTURE);
    assertThat(child.name()).isEqualTo("Child");
    assertThat(child.fields()).hasSize(2);
    assertThat(child.fields().get(0).defaultValue()).isEqualTo("\"-- not a comment\"");
    assertThat(child.fields().get(0).comment()).isEqualTo("field note");
    assertThat(child.fields().get(1).defaultValue()).isEqualTo("'\"'");
    assertThat(child.fields().get(1).comment()).isEqualTo("double-quote literal");
    assertThat(child.derivingClause()).containsExactly("DecidableEq", "Repr", "Hashable").inOrder();

    LeanDeclaration point = blocks.get(1).decl();
    assertThat(point.kind()).isEqualTo(LeanDeclKind.STRUCTURE);
    assertThat(point.fields()).isNull();
    assertThat(point.bodyLines()).hasSize(3);
    assertThat(point.derivingClause()).containsExactly("Repr");

    LeanDeclaration addable = blocks.get(2).decl();
    assertThat(addable.kind()).isEqualTo(LeanDeclKind.CLASS);
    assertThat(addable.fields()).hasSize(1);
    assertThat(addable.fields().get(0).name()).isEqualTo("add");

    LeanDeclaration dir = blocks.get(3).decl();
    assertThat(dir.kind()).isEqualTo(LeanDeclKind.INDUCTIVE);
    assertThat(dir.constructors().stream().map(LeanInductiveCtor::name).toList())
        .containsExactly("north", "south", "east", "west")
        .inOrder();
    assertThat(dir.constructors().get(3).comment()).isEqualTo("cardinal directions");
    assertThat(dir.derivingClause()).containsExactly("Repr", "BEq").inOrder();

    LeanDeclaration mask = blocks.get(4).decl();
    assertThat(mask.constructors()).hasSize(2);
    assertThat(mask.constructors().get(0).name()).isEqualTo("bits");
    assertThat(mask.constructors().get(0).signatureText()).isEqualTo(": (a ||| b = c) → BoolMask");

    LeanDeclaration stream = blocks.get(5).decl();
    assertThat(stream.kind()).isEqualTo(LeanDeclKind.COINDUCTIVE);
    assertThat(stream.constructors()).hasSize(1);
    assertThat(stream.constructors().get(0).name()).isEqualTo("cons");

    LeanDeclaration instAnon = blocks.get(6).decl();
    assertThat(instAnon.kind()).isEqualTo(LeanDeclKind.INSTANCE);
    assertThat(instAnon.name()).isEmpty();
    assertThat(instAnon.signatureLines()).hasSize(1);
    assertThat(instAnon.bodyLines()).hasSize(3);
  }

  @Test
  public void parsesNamedInstanceWhereAndTermInstance() {
    String source =
        String.join(
            "\n",
            "instance instAddNat : Add Nat where",
            "  add x y := x + y",
            "",
            "instance : Inhabited Nat := ⟨0⟩");

    ImmutableList<LeanBlock> blocks = LeanParser.parse(source);
    assertThat(blocks).hasSize(2);

    LeanDeclaration namedInst = blocks.get(0).decl();
    assertThat(namedInst.kind()).isEqualTo(LeanDeclKind.INSTANCE);
    assertThat(namedInst.name()).isEqualTo("instAddNat");
    assertThat(namedInst.signatureLines()).hasSize(1);
    assertThat(namedInst.bodyLines()).hasSize(1);
    assertThat(namedInst.bodyLines().get(0).text()).contains("add x y := x + y");

    LeanDeclaration termInst = blocks.get(1).decl();
    assertThat(termInst.kind()).isEqualTo(LeanDeclKind.INSTANCE);
    assertThat(termInst.name()).isEmpty();
    assertThat(termInst.signatureLines().get(0).text()).isEqualTo("instance : Inhabited Nat");
    assertThat(termInst.bodyLines().get(0).text()).isEqualTo("⟨0⟩");
  }

  @Test
  public void keepsTerminationWhereHelpersAndPartialFixpointAttached() {
    String source =
        String.join(
            "\n",
            "def ackermann (m n : Nat) : Nat :=",
            "  match m, n with",
            "  | 0, y => y + 1",
            "  | x + 1, 0 => ackermann x 1",
            "  | x + 1, y + 1 => ackermann x (ackermann (x + 1) y)",
            "termination_by (m, n)",
            "decreasing_by",
            "  all_goals simp_wf",
            "  sorry",
            "",
            "def sumTo (n : Nat) : Nat :=",
            "  go n 0",
            "where",
            "  go : Nat → Nat → Nat",
            "    | 0, acc => acc",
            "    | k + 1, acc => go k (acc + k + 1)",
            "",
            "def findZero (x : Nat) : Option Nat :=",
            "  if x == 0 then some 0 else findZero (x - 1)",
            "partial_fixpoint");

    ImmutableList<LeanBlock> blocks = LeanParser.parse(source);
    assertThat(blocks).hasSize(3);

    assertThat(blocks.get(0).decl().name()).isEqualTo("ackermann");
    assertThat(blocks.get(0).startLine()).isEqualTo(1);
    assertThat(blocks.get(0).endLine()).isEqualTo(9);
    assertThat(blocks.get(0).decl().hygiene().hasSorry()).isTrue();

    assertThat(blocks.get(1).decl().name()).isEqualTo("sumTo");
    assertThat(blocks.get(1).startLine()).isEqualTo(11);
    assertThat(blocks.get(1).endLine()).isEqualTo(16);
    assertThat(blocks.get(1).decl().hygiene().hasSorry()).isFalse();

    assertThat(blocks.get(2).decl().name()).isEqualTo("findZero");
    assertThat(blocks.get(2).startLine()).isEqualTo(18);
    assertThat(blocks.get(2).endLine()).isEqualTo(20);
  }

  @Test
  public void parsesMutualBlocksIntoInnerDeclarationsWithHygiene() {
    String source =
        String.join(
            "\n",
            "mutual",
            "  inductive Tree where",
            "    | node : TreeList → Tree",
            "",
            "  inductive TreeList where",
            "    | nil : TreeList",
            "    | cons : Tree → TreeList → TreeList",
            "end",
            "",
            "mutual",
            "  /-- Even check. -/",
            "  def isEven : Nat → Bool",
            "    | 0 => true",
            "    | n + 1 => isOdd n",
            "",
            "  theorem even_or_odd (n : Nat) : isEven n = true ∨ isOdd n = true := by",
            "    sorry",
            "end");

    ImmutableList<LeanBlock> blocks = LeanParser.parse(source);
    assertThat(
            blocks.stream()
                .map(b ->
                    b.type() == LeanBlockType.DECLARATION
                        ? b.decl().kind().keyword() + ":" + b.decl().name()
                        : b.type().name().toLowerCase(Locale.ROOT) + ":" + b.directiveKind())
                .toList())
        .containsExactly(
            "directive:mutual",
            "inductive:Tree",
            "inductive:TreeList",
            "directive:end",
            "directive:mutual",
            "def:isEven",
            "theorem:even_or_odd",
            "directive:end")
        .inOrder();

    assertThat(blocks.get(1).decl().constructors()).hasSize(1);
    assertThat(blocks.get(2).decl().constructors()).hasSize(2);
    assertThat(blocks.get(5).decl().docComment()).isEqualTo("Even check.");
    assertThat(blocks.get(6).decl().hygiene().hasSorry()).isTrue();
  }

  @Test
  public void parsesCalcAndConvProofs() {
    String source =
        String.join(
            "\n",
            "theorem trans_calc (a b c : Nat) (h1 : a ≤ b) (h2 : b ≤ c) : a ≤ c := by",
            "  calc",
            "    a ≤ b := h1",
            "    _ ≤ c := h2",
            "",
            "theorem conv_rhs (a b c : Nat) : a + (b + c) = a + (c + b) := by",
            "  conv =>",
            "    lhs",
            "    arg 2",
            "    rw [Nat.add_comm]",
            "",
            "theorem term_calc (a b : Nat) (h : a = b) : a = b :=",
            "  calc",
            "    a = b := h");

    ImmutableList<LeanBlock> blocks = LeanParser.parse(source);
    assertThat(blocks).hasSize(3);
    assertThat(blocks.get(0).decl().proofStrategy()).isEqualTo(LeanProofStrategy.TACTIC);
    assertThat(blocks.get(0).decl().bodyLines()).hasSize(3);
    assertThat(blocks.get(1).decl().proofStrategy()).isEqualTo(LeanProofStrategy.TACTIC);
    assertThat(blocks.get(1).decl().bodyLines()).hasSize(4);
    assertThat(blocks.get(2).decl().proofStrategy()).isEqualTo(LeanProofStrategy.TERM);
    assertThat(blocks.get(2).decl().bodyLines()).hasSize(2);
  }

  @Test
  public void avoidsHygieneFalsePositivesFromLiteralsInsideDeclarations() {
    String source =
        String.join(
            "\n",
            "def trickyLiterals : String × Char × Nat :=",
            "  (r#\"not a \"sorry\" or admit or native_decide\"#, '\"', «sorry»)",
            "where",
            "  «sorry» : Nat := 7");

    ImmutableList<LeanBlock> blocks = LeanParser.parse(source);
    assertThat(blocks).hasSize(1);
    LeanDeclHygiene hygiene = blocks.get(0).decl().hygiene();
    assertThat(hygiene.hasSorry()).isFalse();
    assertThat(hygiene.hasAdmit()).isFalse();
    assertThat(hygiene.hasNativeDecide()).isFalse();
  }

  @Test
  public void adversarialSoundnessSmugglingAndParserEdgeCases() {
    ImmutableList<LeanBlock> dividerBlocks =
        LeanParser.parse(
            String.join(
                "\n",
                "-- ============================================================",
                "-- ============================================================",
                "-- ------------------------------------------------------------",
                "theorem after_dividers : True := by sorry"));
    LeanBlock afterDivider = findDecl(dividerBlocks, "after_dividers");
    assertThat(afterDivider).isNotNull();
    assertThat(afterDivider.decl().hygiene().hasSorry()).isTrue();

    String inSrc =
        String.join(
            "\n",
            "open Nat in theorem sneaky_open : False := by sorry",
            "",
            "set_option maxHeartbeats 0 in",
            "  axiom sneaky_axiom : False",
            "",
            "variable (x : Nat) in",
            "theorem sneaky_var : x = x := by native_decide");
    ImmutableList<LeanBlock> inBlocks = LeanParser.parse(inSrc);
    LeanFileCounts inCounts = LeanHygiene.count(inBlocks);
    assertThat(inCounts.sorryCount()).isEqualTo(1);
    assertThat(inCounts.axioms()).isEqualTo(1);
    assertThat(inCounts.nativeDecideCount()).isEqualTo(1);
    assertThat(findDecl(inBlocks, "sneaky_open")).isNotNull();
    assertThat(findDecl(inBlocks, "sneaky_axiom")).isNotNull();
    assertThat(findDecl(inBlocks, "sneaky_var")).isNotNull();

    String nsSrc =
        String.join(
            "\n",
            "namespace Outer",
            "  section Inner",
            "    theorem indented_bad : False := by",
            "      sorry",
            "    axiom indented_ax : False",
            "  end Inner",
            "end Outer");
    LeanFileCounts nsCounts = LeanHygiene.count(LeanParser.parse(nsSrc));
    assertThat(nsCounts.theorems()).isEqualTo(1);
    assertThat(nsCounts.sorryCount()).isEqualTo(1);
    assertThat(nsCounts.axioms()).isEqualTo(1);
  }

  @Test
  public void column0ContinuationsCommentLookaheadBlankLinesAndNextLineExtends() {
    String src =
        String.join(
            "\n",
            "theorem col0_sorry : False :=",
            "sorry",
            "",
            "theorem comment_before_sorry : False := by",
            "-- harmless comment",
            "  sorry",
            "",
            "def pattern_match : Nat → Nat",
            "",
            "| 0 => 0",
            "| n + 1 => sorry",
            "",
            "structure Child",
            "extends Parent where",
            "  x : Nat");

    ImmutableList<LeanBlock> blocks = LeanParser.parse(src);
    assertThat(findDecl(blocks, "col0_sorry").decl().hygiene().hasSorry()).isTrue();
    assertThat(findDecl(blocks, "comment_before_sorry").decl().hygiene().hasSorry()).isTrue();
    assertThat(findDecl(blocks, "pattern_match").decl().hygiene().hasSorry()).isTrue();
    LeanBlock child = findDecl(blocks, "Child");
    assertThat(child.decl().kind()).isEqualTo(LeanDeclKind.STRUCTURE);
    assertThat(child.decl().fields()).hasSize(1);
  }

  @Test
  public void kernelSoundnessAxiomsAndUnicodeBoundaryIdentifiers() {
    String kernelSrc =
        String.join(
            "\n\n",
            "theorem t1 : False := sorryAx False false",
            "theorem t2 : False := «sorryAx» False false",
            "theorem t3 : True := Lean.ofReduceBool true rfl",
            "theorem t4 : True := Lean.«ofReduceNat» 0 rfl");
    LeanFileCounts kernelCounts = LeanHygiene.count(LeanParser.parse(kernelSrc));
    assertThat(kernelCounts.sorryCount()).isEqualTo(2);
    assertThat(kernelCounts.nativeDecideCount()).isEqualTo(2);

    String boundarySrc =
        String.join(
            "\n",
            "def «multi\n\"» : Nat := 1",
            "theorem after_guillemet : False := by sorry",
            "",
            "def x1 := f!'\"'\"",
            "theorem after_bang_char : False := by sorry",
            "",
            "def x2 := f!r\"\\\"\"",
            "theorem after_bang_raw : False := by sorry",
            "",
            "def x3 := fé'\"'\"",
            "theorem after_unicode_char : False := by sorry",
            "",
            "def sorry' : Nat := 1",
            "def sorry? : Nat := 2",
            "def sorry₀ : Nat := 3",
            "def αsorry : Nat := 4",
            "def «no sorry allowed» : Nat := 5");
    ImmutableList<LeanBlock> bBlocks = LeanParser.parse(boundarySrc);
    assertThat(LeanHygiene.count(bBlocks).sorryCount()).isEqualTo(4);
    assertThat(findDecl(bBlocks, "sorry'").decl().hygiene().hasSorry()).isFalse();
    assertThat(findDecl(bBlocks, "sorry?").decl().hygiene().hasSorry()).isFalse();
    assertThat(findDecl(bBlocks, "sorry₀").decl().hygiene().hasSorry()).isFalse();
    assertThat(findDecl(bBlocks, "αsorry").decl().hygiene().hasSorry()).isFalse();
    assertThat(findDecl(bBlocks, "«no sorry allowed»").decl().hygiene().hasSorry()).isFalse();
  }

  @Test
  public void blockCommentTrailingDeclsDualBlockCommentsAndUnclosedEof() {
    String src =
        String.join(
            "\n",
            "/- Copyright 2026 -/ axiom hidden_after_block : False",
            "/-- Doc on same line -/ theorem hidden_after_doc : False := by sorry",
            "/- first -/ /-",
            "theorem fake_inside_second : False := by sorry",
            "-/",
            "/- unclosed eof comment",
            "");

    ImmutableList<LeanBlock> blocks = LeanParser.parse(src);
    LeanFileCounts counts = LeanHygiene.count(blocks);
    assertThat(counts.axioms()).isEqualTo(1);
    assertThat(counts.sorryCount()).isEqualTo(1);
    assertThat(maybeFindDecl(blocks, "fake_inside_second")).isEmpty();
  }

  @Test
  public void multiLineAttrsModifiersClassInductiveAbbrevBomAndCrlf() {
    String src =
        "\uFEFF"
            + String.join(
                "\r\n",
                "@[simp,",
                "  inline]",
                "theorem attr_multiline : True := by trivial",
                "",
                "@[simp /- ] -/] theorem attr_with_comment : False := by sorry",
                "",
                "@[simp] /- c -/ theorem attr_then_comment : True := by trivial",
                "",
                "unsafe",
                "def sneaky_unsafe : Nat := 0",
                "",
                "partial",
                "def sneaky_partial (n : Nat) : Nat := sneaky_partial n",
                "",
                "private /- c -/ axiom sneaky_private_ax : False",
                "",
                "class inductive MyClassInd : Type where",
                "  | mk : MyClassInd",
                "",
                "class abbrev MyClassAbbrev := MyClassInd");

    ImmutableList<LeanBlock> blocks = LeanParser.parse(src);
    LeanFileCounts counts = LeanHygiene.count(blocks);
    assertThat(counts.sorryCount()).isEqualTo(1);
    assertThat(counts.unsafeCount()).isEqualTo(1);
    assertThat(counts.partialCount()).isEqualTo(1);
    assertThat(counts.axioms()).isEqualTo(1);
    assertThat(findDecl(blocks, "MyClassInd").decl().kind()).isEqualTo(LeanDeclKind.INDUCTIVE);
    assertThat(findDecl(blocks, "MyClassAbbrev").decl().kind()).isEqualTo(LeanDeclKind.ABBREV);
  }

  @Test
  public void detectProofStrategyAndCommentSigSplitting() {
    String src =
        String.join(
            "\n",
            "theorem dirty_decide : False := by decide <;> sorry",
            "",
            "theorem prime_decide : True := by decide'",
            "",
            "theorem tab_decide : 1 + 1 = 2 := by\tdecide",
            "",
            "theorem comment_in_sig (x : Nat /- := fake -/) : x = x := by/- c -/ rfl");

    ImmutableList<LeanBlock> blocks = LeanParser.parse(src);
    LeanDeclaration dirty = findDecl(blocks, "dirty_decide").decl();
    LeanDeclaration prime = findDecl(blocks, "prime_decide").decl();
    LeanDeclaration tab = findDecl(blocks, "tab_decide").decl();
    LeanDeclaration commentSig = findDecl(blocks, "comment_in_sig").decl();

    assertThat(dirty.proofStrategy()).isEqualTo(LeanProofStrategy.TACTIC);
    assertThat(dirty.hygiene().hasSorry()).isTrue();
    assertThat(prime.proofStrategy()).isEqualTo(LeanProofStrategy.TACTIC);
    assertThat(tab.proofStrategy()).isEqualTo(LeanProofStrategy.DECIDE);
    assertThat(tab.isTacticProof()).isTrue();
    assertThat(commentSig.isTacticProof()).isTrue();
    assertThat(joinCodeLines(commentSig.signatureLines())).contains("x = x");
  }

  @Test
  public void unclosedMultiLineAttrsStayLinearAndValidMultiLineAttrParses() {
    ArrayList<String> lines = new ArrayList<>(2001);
    for (int i = 0; i < 2000; i++) {
      lines.add("@[foo");
    }
    assertThat(LeanParser.parse(String.join("\n", lines))).isNotEmpty();

    lines.add("]");
    assertThat(LeanParser.parse(String.join("\n", lines))).isNotEmpty();

    String validAttr =
        String.join(
            "\n",
            "@[simp [foo],",
            "  /- ] inside comment -/",
            "  inline]",
            "def attributed : Nat := 1");
    ImmutableList<LeanBlock> parsed = LeanParser.parse(validAttr);
    assertThat(parsed).hasSize(1);
    assertThat(parsed.get(0).type()).isEqualTo(LeanBlockType.DECLARATION);
    assertThat(parsed.get(0).startLine()).isEqualTo(1);
    assertThat(parsed.get(0).endLine()).isEqualTo(4);
    assertThat(parsed.get(0).decl().name()).isEqualTo("attributed");
    assertThat(parsed.get(0).decl().attributes()).hasSize(1);
  }

  @Test
  public void indentedAndSameLineAxiomCannotHideInsideCleanDecl() {
    ImmutableList<LeanBlock> blocks1 =
        LeanParser.parse("theorem clean : 1 + 1 = 2 := rfl\n\n  axiom bad : False");
    assertThat(blocks1).hasSize(2);
    assertThat(blocks1.get(0).decl().name()).isEqualTo("clean");
    assertThat(blocks1.get(1).decl().kind()).isEqualTo(LeanDeclKind.AXIOM);
    assertThat(blocks1.get(1).decl().name()).isEqualTo("bad");
    assertThat(blocks1.get(1).decl().hygiene().isAxiom()).isTrue();

    ImmutableList<LeanBlock> blocks2 =
        LeanParser.parse("theorem clean2 : 1 + 1 = 2 := rfl\n  axiom bad2 : False");
    assertThat(blocks2).hasSize(2);
    assertThat(blocks2.get(1).decl().kind()).isEqualTo(LeanDeclKind.AXIOM);

    ImmutableList<LeanBlock> blocks3 =
        LeanParser.parse("theorem clean3 : True := (trivial) axiom bad3 : False");
    assertThat(blocks3.get(0).decl().hygiene().isAxiom()).isTrue();
    assertThat(LeanHygiene.count(blocks3).axioms()).isEqualTo(1);

    String benign =
        String.join(
            "\n",
            "structure Config where",
            "  module : String",
            "  «axiom» : Nat",
            "  deriving Repr",
            "",
            "theorem scoped : True := by",
            "  open Nat in",
            "  exact trivial");
    ImmutableList<LeanBlock> benignBlocks = LeanParser.parse(benign);
    assertThat(benignBlocks).hasSize(2);
    assertThat(benignBlocks.get(0).decl().fields()).hasSize(2);
    assertThat(benignBlocks.get(0).decl().derivingClause()).containsExactly("Repr");
    assertThat(benignBlocks.get(0).decl().hygiene().isAxiom()).isFalse();
    assertThat(benignBlocks.get(1).decl().name()).isEqualTo("scoped");
    assertThat(benignBlocks.get(1).decl().hygiene().isAxiom()).isFalse();
  }

  @Test
  public void preservesPrefixesAcrossCommentsAndHandlesCommandBoundariesAndWhereMembers() {
    ImmutableList<LeanBlock> revBlocks =
        LeanParser.parse(String.join("\n", "unsafe", "@[simp]", "def foo : Nat := 1"));
    assertThat(revBlocks).hasSize(1);
    assertThat(revBlocks.get(0).startLine()).isEqualTo(1);
    assertThat(revBlocks.get(0).endLine()).isEqualTo(3);
    assertThat(revBlocks.get(0).raw()).contains("unsafe");
    assertThat(revBlocks.get(0).decl().hygiene().isUnsafe()).isTrue();

    String withComments =
        String.join(
            "\n",
            "/-- Computes foo. -/",
            "-- temporary note",
            "unsafe",
            "-- another note",
            "@[simp]",
            "def bar : Nat := 1");
    ImmutableList<LeanBlock> barBlocks = LeanParser.parse(withComments);
    assertThat(barBlocks).hasSize(1);
    assertThat(barBlocks.get(0).startLine()).isEqualTo(1);
    assertThat(barBlocks.get(0).endLine()).isEqualTo(6);
    assertThat(barBlocks.get(0).decl().docComment()).isEqualTo("Computes foo.");
    assertThat(barBlocks.get(0).decl().attributes()).hasSize(1);
    assertThat(barBlocks.get(0).decl().hygiene().isUnsafe()).isTrue();
    assertThat(barBlocks.get(0).decl().signatureLines())
        .containsExactly(
            new LeanCodeLine(2, "-- temporary note"),
            new LeanCodeLine(3, "unsafe"),
            new LeanCodeLine(4, "-- another note"),
            new LeanCodeLine(6, "def bar : Nat"))
        .inOrder();

    String prefixAfterClean =
        String.join(
            "\n",
            "theorem clean : 1 + 1 = 2 := rfl",
            "",
            "  unsafe",
            "  def bad : Nat := 0",
            "",
            "theorem clean2 : 1 + 1 = 2 := rfl",
            "",
            "  /-- Second theorem doc -/",
            "  theorem with_doc : False := by sorry",
            "",
            "theorem clean_before_attr : 1 + 1 = 2 := rfl",
            "  @[simp]",
            "  theorem with_attr : False := by sorry",
            "",
            "theorem clean3 : True := trivial",
            "  /-! Indented module note -/");
    ImmutableList<LeanBlock> prefixBlocks = LeanParser.parse(prefixAfterClean);
    assertThat(findDecl(prefixBlocks, "clean").decl().hygiene().isUnsafe()).isFalse();
    assertThat(findDecl(prefixBlocks, "clean").endLine()).isEqualTo(1);
    assertThat(findDecl(prefixBlocks, "bad").decl().hygiene().isUnsafe()).isTrue();
    assertThat(findDecl(prefixBlocks, "bad").startLine()).isEqualTo(3);
    assertThat(findDecl(prefixBlocks, "with_doc").decl().docComment())
        .isEqualTo("Second theorem doc");
    assertThat(findDecl(prefixBlocks, "with_attr").decl().attributes())
        .containsExactly("  @[simp]");
    assertThat(prefixBlocks.stream().anyMatch(b -> b.type() == LeanBlockType.MODULE_DOC)).isTrue();

    String derivingSrc =
        String.join(
            "\n",
            "def Color : Type := Nat",
            "  deriving BEq, Repr",
            "",
            "abbrev Alias := Nat",
            "deriving DecidableEq",
            "",
            "def Target := Nat",
            "  deriving instance Hashable for Target");
    ImmutableList<LeanBlock> derivingBlocks = LeanParser.parse(derivingSrc);
    assertThat(derivingBlocks).hasSize(4);
    assertThat(derivingBlocks.get(0).raw()).contains("deriving BEq, Repr");
    assertThat(derivingBlocks.get(1).raw()).contains("deriving DecidableEq");
    assertThat(derivingBlocks.get(2).raw()).doesNotContain("Hashable");
    assertThat(derivingBlocks.get(3).type()).isEqualTo(LeanBlockType.DIRECTIVE);
    assertThat(derivingBlocks.get(3).directiveKind()).isEqualTo("deriving");

    String whereMembersSrc =
        String.join(
            "\n",
            "structure LangConfig where",
            "  /-- Parameterized field named syntax. -/",
            "  syntax (n : Nat) : String",
            "  prefix x := x + 1",
            "",
            "def outerFn (a : Nat) : Nat := helper a where",
            "  prefix n := n + 1");
    ImmutableList<LeanBlock> whereBlocks = LeanParser.parse(whereMembersSrc);
    assertThat(whereBlocks).hasSize(2);
    assertThat(whereBlocks.get(0).raw()).contains("syntax (n : Nat) : String");
    assertThat(whereBlocks.get(0).raw()).contains("prefix x := x + 1");
    assertThat(whereBlocks.get(1).raw()).contains("prefix n := n + 1");
  }

  @Test
  public void parsesSurrogateIdentifiersAndUnclosedStringAtEof() {
    String surrogateSrc =
        "def 𝔸_fn (x : 𝔸) : 𝔸 := x\n\ntheorem unclosed_str : String := \"unclosed";
    ImmutableList<LeanBlock> blocks = LeanParser.parse(surrogateSrc);
    assertThat(blocks).hasSize(2);
    assertThat(blocks.get(0).decl().name()).isEqualTo("𝔸_fn");
    assertThat(blocks.get(1).decl().name()).isEqualTo("unclosed_str");
  }

  @Test
  public void parsesPrototypeNamedDeclsAndLarge250DeclFile() {
    String protoSrc =
        String.join(
            "\n\n",
            "def __proto__ : Nat := 1",
            "def constructor : Nat := 2",
            "theorem toString : True := by sorry",
            "theorem hasOwnProperty : True := by trivial");
    ImmutableList<LeanBlock> protoBlocks = LeanParser.parse(protoSrc);
    assertThat(protoBlocks).hasSize(4);
    assertThat(LeanHygiene.count(protoBlocks).sorryCount()).isEqualTo(1);

    ArrayList<String> decls = new ArrayList<>(250);
    for (int k = 0; k < 250; k++) {
      String proof = (k == 125) ? "sorry" : "omega";
      decls.add(
          "theorem thm_" + k + " (n : Nat) : n + " + k + " = " + k + " + n := by\n  " + proof);
    }
    ImmutableList<LeanBlock> largeBlocks = LeanParser.parse(String.join("\n\n", decls));
    assertThat(largeBlocks).hasSize(250);
    LeanFileCounts counts = LeanHygiene.count(largeBlocks);
    assertThat(counts.theorems()).isEqualTo(250);
    assertThat(counts.sorryCount()).isEqualTo(1);
  }
}
