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
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link LeanHygiene}. */
@RunWith(JUnit4.class)
public final class LeanHygieneTest {

  @Test
  public void extractLeanIdentifiersRespectsLeanIdentifierBoundaries() {
    String cleanCode = "theorem foo : True := by sorry sorry' sorry? sorry₀ αsorry #check sorryAx";
    assertThat(LeanHygiene.extractLeanIdentifiers(cleanCode))
        .containsExactly(
            "theorem", "foo", "True", "by", "sorry", "sorry'", "sorry?", "sorry₀", "αsorry",
            "check", "sorryAx");

    String stripped =
        LeanLexer.stripLeanCommentsAndStrings(
            "def «ignored» : String := \"sorry\" -- sorry\n#check sorryAx");
    assertThat(LeanHygiene.extractLeanIdentifiers(stripped))
        .containsExactly("def", "String", "check", "sorryAx");
  }

  @Test
  public void analyzeDeclarationHygieneDetectsHazardsInCodeAndModifiers() {
    LeanDeclHygiene sorryHygiene =
        LeanHygiene.analyzeDeclarationHygiene(
            LeanDeclKind.THEOREM,
            ImmutableList.of(),
            ImmutableList.of(),
            ImmutableList.of(new LeanCodeLine(1, "theorem t : False := by sorry")));
    assertThat(sorryHygiene.hasSorry()).isTrue();
    assertThat(sorryHygiene.hasAdmit()).isFalse();
    assertThat(sorryHygiene.isAxiom()).isFalse();

    LeanDeclHygiene modHygiene =
        LeanHygiene.analyzeDeclarationHygiene(
            LeanDeclKind.DEF,
            ImmutableList.of("partial", "unsafe"),
            ImmutableList.of("@[inline]"),
            ImmutableList.of(new LeanCodeLine(1, "def f : Nat := 0")));
    assertThat(modHygiene.isPartial()).isTrue();
    assertThat(modHygiene.isUnsafe()).isTrue();
    assertThat(modHygiene.hasSorry()).isFalse();

    LeanDeclHygiene kernelHygiene =
        LeanHygiene.analyzeDeclarationHygiene(
            LeanDeclKind.THEOREM,
            ImmutableList.of(),
            ImmutableList.of(),
            ImmutableList.of(
                new LeanCodeLine(1, "theorem t : True := Lean.«ofReduceBool» true rfl")));
    assertThat(kernelHygiene.hasNativeDecide()).isTrue();
  }

  @Test
  public void countBlocksAggregatesDeclarationsAndFailClosedDirectives() {
    String source =
        String.join(
            "\n\n",
            "theorem t1 : True := trivial",
            "lemma l1 : False := by sorry",
            "example : False := by admit",
            "def d1 : Nat := 1",
            "abbrev a1 := Nat",
            "opaque o1 : Nat := 2",
            "structure S where\n  x : Nat",
            "class C where\n  y : Nat",
            "inductive I where\n  | mk : I",
            "coinductive Co where\n  | step : Co",
            "instance : C where\n  y := 0",
            "axiom ax : False",
            "partial def p (n : Nat) : Nat := p n",
            "unsafe def u : Nat := 0",
            "theorem nd : 1 = 1 := by native_decide",
            "macro \"cheat\" : tactic => `(tactic| sorry)",
            "#eval sorryAx False false",
            "elab \"cheat2\" : tactic => admit",
            "initialize x := native_decide");

    ImmutableList<LeanBlock> blocks = LeanParser.parse(source);
    LeanFileCounts counts = LeanHygiene.count(blocks);
    assertThat(counts.theorems()).isEqualTo(4); // t1, l1, example, nd
    assertThat(counts.definitions()).isEqualTo(5); // d1, a1, o1, p, u
    assertThat(counts.types()).isEqualTo(4); // S, C, I, Co
    assertThat(counts.instances()).isEqualTo(1);
    assertThat(counts.axioms()).isEqualTo(1);
    assertThat(counts.sorryCount()).isEqualTo(3); // l1 + macro + #eval
    assertThat(counts.admitCount()).isEqualTo(2); // example + elab
    assertThat(counts.partialCount()).isEqualTo(1);
    assertThat(counts.unsafeCount()).isEqualTo(1);
    assertThat(counts.nativeDecideCount()).isEqualTo(2); // nd + initialize
  }

  @Test
  public void countEmptyBlocksIsAllZero() {
    assertThat(LeanHygiene.count(ImmutableList.of()))
        .isEqualTo(new LeanFileCounts(0, 0, 0, 0, 0, 0, 0, 0, 0, 0));
  }
}
