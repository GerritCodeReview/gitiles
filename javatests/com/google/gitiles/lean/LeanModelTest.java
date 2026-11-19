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
import java.util.Locale;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for the Lean 4 model enums and records. */
@RunWith(JUnit4.class)
public final class LeanModelTest {

  @Test
  public void declKindKeywordsRoundTrip() {
    assertThat(LeanDeclKind.values()).hasLength(12);
    for (LeanDeclKind kind : LeanDeclKind.values()) {
      assertThat(kind.keyword()).isEqualTo(kind.name().toLowerCase(Locale.ROOT));
      assertThat(LeanDeclKind.fromKeyword(kind.keyword())).hasValue(kind);
    }
    assertThat(LeanDeclKind.THEOREM.keyword()).isEqualTo("theorem");
    assertThat(LeanDeclKind.COINDUCTIVE.keyword()).isEqualTo("coinductive");
    assertThat(LeanDeclKind.fromKeyword("THEOREM")).isEmpty();
    assertThat(LeanDeclKind.fromKeyword("nonexistent")).isEmpty();
    assertThat(LeanDeclKind.fromKeyword("")).isEmpty();
    assertThat(LeanDeclKind.fromKeyword(null)).isEmpty();
  }

  @Test
  public void enumsContainExpectedValues() {
    assertThat(LeanBlockType.values())
        .asList()
        .containsExactly(
            LeanBlockType.MODULE_DOC,
            LeanBlockType.SECTION_BANNER,
            LeanBlockType.IMPORTS,
            LeanBlockType.DIRECTIVE,
            LeanBlockType.DECLARATION,
            LeanBlockType.COMMENT)
        .inOrder();
    assertThat(LeanProofStrategy.values())
        .asList()
        .containsExactly(LeanProofStrategy.TACTIC, LeanProofStrategy.DECIDE, LeanProofStrategy.TERM)
        .inOrder();
  }

  @Test
  public void recordsConstructAndValidateNulls() {
    LeanCodeLine line = new LeanCodeLine(1, "theorem foo : True := trivial");
    assertThat(line.lineNum()).isEqualTo(1);
    assertThat(line.text()).isEqualTo("theorem foo : True := trivial");
    assertThrows(NullPointerException.class, () -> new LeanCodeLine(1, null));

    LeanStructureField field =
        new LeanStructureField("x", "Nat", "0", "counter", 2, "x : Nat := 0");
    assertThat(field.name()).isEqualTo("x");
    assertThat(field.defaultValue()).isEqualTo("0");
    assertThat(field.comment()).isEqualTo("counter");
    LeanStructureField minimalField =
        new LeanStructureField("y", "Bool", null, null, 3, "y : Bool");
    assertThat(minimalField.defaultValue()).isNull();
    assertThat(minimalField.comment()).isNull();

    LeanInductiveCtor ctor = new LeanInductiveCtor("zero", ": Nat", null, 4, "| zero : Nat");
    assertThat(ctor.name()).isEqualTo("zero");
    assertThat(ctor.comment()).isNull();

    LeanDeclHygiene hygiene = new LeanDeclHygiene(false, false, false, false, false, false);
    LeanDeclaration decl =
        new LeanDeclaration(
            LeanDeclKind.THEOREM,
            "foo",
            ImmutableList.of("@[simp]"),
            ImmutableList.of("private"),
            null,
            null,
            null,
            ImmutableList.of(line),
            ImmutableList.of(),
            false,
            LeanProofStrategy.TERM,
            null,
            null,
            null,
            true,
            hygiene);
    assertThat(decl.kind()).isEqualTo(LeanDeclKind.THEOREM);
    assertThat(decl.fields()).isNull();
    assertThat(decl.constructors()).isNull();
    assertThat(decl.derivingClause()).isNull();
    assertThrows(
        NullPointerException.class,
        () ->
            new LeanDeclaration(
                LeanDeclKind.DEF,
                "bar",
                null,
                ImmutableList.of(),
                null,
                null,
                null,
                ImmutableList.of(),
                ImmutableList.of(),
                false,
                null,
                null,
                null,
                null,
                false,
                hygiene));

    LeanBlock block =
        new LeanBlock(
            "decl-0",
            LeanBlockType.DECLARATION,
            line.text(),
            1,
            1,
            null,
            ImmutableList.of(),
            null,
            ImmutableList.of(),
            ImmutableList.of(),
            false,
            decl);
    assertThat(block.decl()).isEqualTo(decl);
    assertThat(block.importLines()).isEmpty();

    LeanFileCounts counts = new LeanFileCounts(1, 2, 3, 4, 5, 0, 1, 0, 0, 1);
    LeanDiffSummary summary = new LeanDiffSummary(counts, counts, true, true, 1, 2, 3);
    assertThat(summary.base()).isEqualTo(counts);
    assertThat(summary.modifiedDeclarations()).isEqualTo(3);
  }
}
