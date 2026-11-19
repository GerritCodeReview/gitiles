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
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class LeanDiffEngineTest {

  private static String joinSegments(List<InlineEditSegment> segments) {
    StringBuilder sb = new StringBuilder();
    for (InlineEditSegment seg : segments) {
      sb.append(seg.text());
    }
    return sb.toString();
  }

  private static ImmutableList<String> editedTexts(List<InlineEditSegment> segments) {
    ImmutableList.Builder<String> out = ImmutableList.builder();
    for (InlineEditSegment seg : segments) {
      if (seg.edited()) {
        out.add(seg.text());
      }
    }
    return out.build();
  }

  @Test
  public void tokenizeLeanDiffWords_resilienceAndLossless() {
    ImmutableList<String> guillemets = LeanDiffEngine.tokenizeLeanDiffWords("«".repeat(50_000));
    assertThat(guillemets).hasSize(50_000);

    List<String> samples =
        List.of(
            "def x := 0x7ff + 3.14159 + 0b1010 + 1_000_000",
            "theorem ∀ α β, «spaced ident» → ∃ γ, x ≠ y := by simp",
            "def emoji := \"😀🚀\" -- trailing\tspace  ",
            "unclosed «guillemet and \u0007 control");
    for (String sample : samples) {
      ImmutableList<String> tokens = LeanDiffEngine.tokenizeLeanDiffWords(sample);
      assertThat(String.join("", tokens)).isEqualTo(sample);
    }

    assertThat(LeanDiffEngine.tokenizeLeanDiffWords("")).isEmpty();
    assertThat(LeanDiffEngine.tokenizeLeanDiffWords("«»")).containsExactly("«", "»").inOrder();
    assertThat(LeanDiffEngine.tokenizeLeanDiffWords("0x7ff + 3.14159"))
        .containsAtLeast("0x7ff", "3.14159")
        .inOrder();
  }

  @Test
  public void alignLeanCodeLines_numericLiteralsAndTwoTierFallback() {
    ImmutableList<LeanAlignedCodeLine> numDiff =
        LeanDiffEngine.alignLeanCodeLines(
            List.of(new LeanCodeLine(1, "def limit : Nat := 100")),
            List.of(new LeanCodeLine(1, "def limit : Nat := 0")),
            true);
    assertThat(numDiff).hasSize(1);
    assertThat(numDiff.get(0).status()).isEqualTo(DiffBlockStatus.MODIFIED);
    assertThat(editedTexts(numDiff.get(0).leftSegments())).containsExactly("100");
    assertThat(editedTexts(numDiff.get(0).rightSegments())).containsExactly("0");

    ImmutableList<LeanAlignedCodeLine> maxWeightDiff =
        LeanDiffEngine.alignLeanCodeLines(
            List.of(new LeanCodeLine(1, "  have h : x ≤ 31 := by omega")),
            List.of(new LeanCodeLine(1, "  have h : x ≤ MAX_WEIGHT := by omega")),
            true);
    assertThat(maxWeightDiff).hasSize(1);
    assertThat(maxWeightDiff.get(0).status()).isEqualTo(DiffBlockStatus.MODIFIED);
    assertThat(editedTexts(maxWeightDiff.get(0).leftSegments())).containsExactly("31");
    assertThat(editedTexts(maxWeightDiff.get(0).rightSegments())).containsExactly("MAX_WEIGHT");

    // < 2 common non-whitespace words fallback marks the entire line as edited.
    ImmutableList<LeanAlignedCodeLine> fallback =
        LeanDiffEngine.alignLeanCodeLines(
            List.of(new LeanCodeLine(1, "  exact alpha")),
            List.of(new LeanCodeLine(1, "  exact beta")),
            true);
    assertThat(fallback).hasSize(1);
    assertThat(fallback.get(0).status()).isEqualTo(DiffBlockStatus.MODIFIED);
    assertThat(fallback.get(0).leftSegments())
        .containsExactly(new InlineEditSegment("  exact alpha", true));
    assertThat(fallback.get(0).rightSegments())
        .containsExactly(new InlineEditSegment("  exact beta", true));
  }

  @Test
  public void alignLeanCodeLines_perSideMaskSurrogatesAndUnicodeSimilarity() {
    String left = "def f ( ) : \uD835\uDD38 := oldVal";
    String right = "def f (a b) : \uD835\uDD38 := newVal";
    ImmutableList<LeanAlignedCodeLine> aligned =
        LeanDiffEngine.alignLeanCodeLines(
            List.of(new LeanCodeLine(1, left)), List.of(new LeanCodeLine(1, right)), false);
    assertThat(aligned).hasSize(1);
    assertThat(aligned.get(0).status()).isEqualTo(DiffBlockStatus.MODIFIED);
    assertThat(joinSegments(aligned.get(0).leftSegments())).isEqualTo(left);
    assertThat(joinSegments(aligned.get(0).rightSegments())).isEqualTo(right);
    assertThat(editedTexts(aligned.get(0).leftSegments())).containsExactly("oldVal");
    assertThat(editedTexts(aligned.get(0).rightSegments()))
        .containsExactly("a b", "newVal")
        .inOrder();

    ImmutableList<LeanAlignedCodeLine> paired =
        LeanDiffEngine.alignLeanCodeLines(
            List.of(new LeanCodeLine(1, "  | ⟨α, β, γ⟩ => ⟨α, β, γ⟩")),
            List.of(new LeanCodeLine(1, "  | ⟨α, β, γ⟩ => ⟨α, β, γ + 1⟩")),
            false);
    assertThat(paired).hasSize(1);
    assertThat(paired.get(0).status()).isEqualTo(DiffBlockStatus.MODIFIED);

    ImmutableList<LeanAlignedCodeLine> unrelated =
        LeanDiffEngine.alignLeanCodeLines(
            List.of(new LeanCodeLine(1, "  have : α = β")),
            List.of(new LeanCodeLine(1, "  have : ∀ γ δ, γ ⊆ δ")),
            true);
    assertThat(unrelated).hasSize(2);
    assertThat(unrelated.get(0).status()).isEqualTo(DiffBlockStatus.DELETED);
    assertThat(unrelated.get(1).status()).isEqualTo(DiffBlockStatus.ADDED);
  }

  @Test
  public void alignLeanCodeLines_largeProofTopAndBottomEdits() {
    List<LeanCodeLine> baseProof = new ArrayList<>(120);
    List<LeanCodeLine> revProof = new ArrayList<>(120);
    baseProof.add(new LeanCodeLine(1, "  have h_top : n = oldHyp"));
    revProof.add(new LeanCodeLine(1, "  have h_top : n = newHyp"));
    for (int i = 0; i < 118; i++) {
      String text = "  have h_" + i + " : True := trivial";
      baseProof.add(new LeanCodeLine(i + 2, text));
      revProof.add(new LeanCodeLine(i + 2, text));
    }
    baseProof.add(new LeanCodeLine(120, "  exact Nat.add_comm n oldGoal"));
    revProof.add(new LeanCodeLine(120, "  exact Nat.add_comm n newGoal"));

    ImmutableList<LeanAlignedCodeLine> aligned =
        LeanDiffEngine.alignLeanCodeLines(baseProof, revProof, true);
    assertThat(aligned).hasSize(120);
    assertThat(aligned.get(0).status()).isEqualTo(DiffBlockStatus.MODIFIED);
    assertThat(aligned.get(119).status()).isEqualTo(DiffBlockStatus.MODIFIED);
    assertThat(editedTexts(aligned.get(0).leftSegments())).containsExactly("oldHyp");
    assertThat(editedTexts(aligned.get(0).rightSegments())).containsExactly("newHyp");
    assertThat(editedTexts(aligned.get(119).leftSegments())).containsExactly("oldGoal");
    assertThat(editedTexts(aligned.get(119).rightSegments())).containsExactly("newGoal");
  }

  @Test
  public void alignLeanBlocks_pairsByKindAndNameAndComputesSummary() {
    String base =
        String.join(
            "\n",
            "theorem transfer_monotone (x y : Nat) (h : x ≤ y) : x + 1 ≤ y + 1 := by",
            "  sorry");
    String rev =
        String.join(
            "\n",
            "theorem transfer_monotone (x y : Nat) (h : x ≤ y) : x + 1 ≤ y + 1 := by",
            "  omega");
    ImmutableList<LeanBlock> blocksA = LeanParser.parse(base);
    ImmutableList<LeanBlock> blocksB = LeanParser.parse(rev);
    ImmutableList<AlignedLeanRow> rows = LeanDiffEngine.alignLeanBlocks(blocksA, blocksB);

    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).status()).isEqualTo(DiffBlockStatus.MODIFIED);
    LeanDeclarationDiffData declDiff = rows.get(0).declDiff();
    assertThat(declDiff).isNotNull();
    assertThat(declDiff.signatureChanged()).isFalse();
    assertThat(declDiff.bodyChanged()).isTrue();

    LeanDiffSummary summary = LeanDiffEngine.computeLeanSummary(blocksA, blocksB, rows);
    assertThat(summary.base().sorryCount()).isEqualTo(1);
    assertThat(summary.revision().sorryCount()).isEqualTo(0);
    assertThat(summary.modifiedDeclarations()).isEqualTo(1);
  }

  @Test
  public void alignStructureFields_additionsDeletionsAndModifications() {
    String base =
        String.join(
            "\n",
            "structure Config where",
            "  timeout : Nat -- ms",
            "  legacyFlag : Bool := false -- deprecated",
            "  retries : Nat := 3 -- max retries",
            "deriving Repr");
    String rev =
        String.join(
            "\n",
            "structure Config where",
            "  timeout : UInt64 -- milliseconds",
            "  retries : Nat := 3 -- max retries",
            "  strictMode : Bool := true -- fail fast",
            "deriving Repr, BEq");

    ImmutableList<AlignedLeanRow> rows =
        LeanDiffEngine.alignLeanBlocks(LeanParser.parse(base), LeanParser.parse(rev));
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).status()).isEqualTo(DiffBlockStatus.MODIFIED);
    LeanDeclarationDiffData declDiff = rows.get(0).declDiff();
    assertThat(declDiff).isNotNull();
    assertThat(declDiff.fieldsChanged()).isTrue();
    ImmutableList<LeanAlignedField> fields = declDiff.fields();
    assertThat(fields).isNotNull();
    assertThat(
            fields.stream()
                .anyMatch(f ->
                    f.status() == DiffBlockStatus.MODIFIED
                        && f.rightField().name().equals("timeout")
                        && f.commentChanged()))
        .isTrue();
    assertThat(
            fields.stream()
                .anyMatch(f ->
                    f.status() == DiffBlockStatus.DELETED
                        && f.leftField().name().equals("legacyFlag")))
        .isTrue();
    assertThat(
            fields.stream()
                .anyMatch(f ->
                    f.status() == DiffBlockStatus.UNCHANGED
                        && f.rightField().name().equals("retries")))
        .isTrue();
    assertThat(
            fields.stream()
                .anyMatch(f ->
                    f.status() == DiffBlockStatus.ADDED
                        && f.rightField().name().equals("strictMode")))
        .isTrue();

    // Converting field-based structure to custom constructor (`mk ::`) yields null fields diff.
    String customCtor =
        String.join("\n", "structure Config where", "  mk ::", "  timeout : UInt64");
    ImmutableList<AlignedLeanRow> mixedRows =
        LeanDiffEngine.alignLeanBlocks(LeanParser.parse(base), LeanParser.parse(customCtor));
    assertThat(mixedRows.get(0).declDiff().fields()).isNull();
  }

  @Test
  public void alignLeanBlocks_kindRenameModuleDocBannerCommentsAndDirectives() {
    String base =
        String.join(
            "\n",
            "/-!",
            "# Spec v1",
            "Initial proof.",
            "-/",
            "",
            "-- ====",
            "-- Section 1: Old",
            "-- ====",
            "",
            "import Init",
            "",
            "/-",
            "All proofs below use zero sorry.",
            "-/",
            "",
            "lemma helper (n : Nat) : n + 0 = n := by",
            "  sorry");
    String rev =
        String.join(
            "\n",
            "/-!",
            "# Spec v2",
            "Verified proof with zero `sorry`.",
            "-/",
            "",
            "-- ====",
            "-- Section 1: New",
            "-- ====",
            "",
            "import Init.Core",
            "",
            "/-",
            "All proofs below use zero sorry (verified).",
            "-/",
            "",
            "theorem helper (n : Nat) : n + 0 = n := by",
            "  simp");

    ImmutableList<AlignedLeanRow> rows =
        LeanDiffEngine.alignLeanBlocks(LeanParser.parse(base), LeanParser.parse(rev));
    assertThat(rows).hasSize(5);

    assertThat(rows.get(0).status()).isEqualTo(DiffBlockStatus.MODIFIED);
    assertThat(rows.get(0).moduleDocDiff()).isNotNull();
    assertThat(rows.get(0).moduleDocDiff().changed()).isTrue();
    assertThat(rows.get(0).moduleDocDiff().leftText()).contains("# Spec v1");
    assertThat(rows.get(0).moduleDocDiff().rightText()).contains("# Spec v2");

    assertThat(rows.get(1).status()).isEqualTo(DiffBlockStatus.MODIFIED);
    assertThat(rows.get(1).bannerDiff()).isNotNull();
    assertThat(rows.get(1).bannerDiff().changed()).isTrue();
    assertThat(rows.get(1).bannerDiff().leftText()).isEqualTo("Section 1: Old");
    assertThat(rows.get(1).bannerDiff().rightText()).isEqualTo("Section 1: New");

    assertThat(rows.get(2).status()).isEqualTo(DiffBlockStatus.MODIFIED);
    assertThat(rows.get(2).lineDiff()).isNotNull();

    assertThat(rows.get(3).status()).isEqualTo(DiffBlockStatus.MODIFIED);
    assertThat(rows.get(3).lineDiff()).isNotNull();
    assertThat(rows.get(3).lineDiff().stream().allMatch(LeanAlignedCodeLine::forceComment))
        .isTrue();

    assertThat(rows.get(4).status()).isEqualTo(DiffBlockStatus.MODIFIED);
    assertThat(rows.get(4).leftBlock().decl().kind()).isEqualTo(LeanDeclKind.LEMMA);
    assertThat(rows.get(4).rightBlock().decl().kind()).isEqualTo(LeanDeclKind.THEOREM);
  }

  @Test
  public void alignLeanBlocks_unrelatedSingletonAddedDeletedAndLarge250DeclFiles() {
    String pointSrc = String.join("\n", "structure Point where", "  x : Nat", "  y : Nat");
    String thmSrc =
        String.join("\n", "theorem main_soundness (n : Nat) : n + 0 = n := by", "  simp");
    ImmutableList<LeanBlock> pointBlocks = LeanParser.parse(pointSrc);
    ImmutableList<LeanBlock> thmBlocks = LeanParser.parse(thmSrc);
    ImmutableList<AlignedLeanRow> unrelated =
        LeanDiffEngine.alignLeanBlocks(pointBlocks, thmBlocks);
    assertThat(unrelated).hasSize(2);
    assertThat(unrelated.get(0).status()).isEqualTo(DiffBlockStatus.DELETED);
    assertThat(unrelated.get(0).leftBlock().decl().name()).isEqualTo("Point");
    assertThat(unrelated.get(1).status()).isEqualTo(DiffBlockStatus.ADDED);
    assertThat(unrelated.get(1).rightBlock().decl().name()).isEqualTo("main_soundness");

    LeanDiffSummary unrelatedSummary =
        LeanDiffEngine.computeLeanSummary(pointBlocks, thmBlocks, unrelated);
    assertThat(unrelatedSummary.deletedDeclarations()).isEqualTo(1);
    assertThat(unrelatedSummary.addedDeclarations()).isEqualTo(1);
    assertThat(unrelatedSummary.modifiedDeclarations()).isEqualTo(0);

    // 250-declaration file with single modified theorem in middle.
    StringBuilder sbA = new StringBuilder();
    StringBuilder sbB = new StringBuilder();
    for (int k = 0; k < 250; k++) {
      if (k > 0) {
        sbA.append("\n\n");
        sbB.append("\n\n");
      }
      String sig = "theorem thm_" + k + " (n : Nat) : n + " + k + " = " + k + " + n := by\n  ";
      sbA.append(sig).append(k == 125 ? "sorry" : "omega");
      sbB.append(sig).append("omega");
    }
    ImmutableList<LeanBlock> blocksA = LeanParser.parse(sbA.toString());
    ImmutableList<LeanBlock> blocksB = LeanParser.parse(sbB.toString());
    ImmutableList<AlignedLeanRow> rows = LeanDiffEngine.alignLeanBlocks(blocksA, blocksB);
    assertThat(rows).hasSize(250);
    List<AlignedLeanRow> modified =
        rows.stream().filter(r -> r.status() == DiffBlockStatus.MODIFIED).toList();
    assertThat(modified).hasSize(1);
    assertThat(modified.get(0).rightBlock().decl().name()).isEqualTo("thm_125");

    LeanDiffSummary addedOnly =
        LeanDiffEngine.computeLeanSummary(
            List.of(), blocksB, LeanDiffEngine.alignLeanBlocks(List.of(), blocksB));
    assertThat(addedOnly.hasBase()).isFalse();
    assertThat(addedOnly.hasRevision()).isTrue();
    assertThat(addedOnly.addedDeclarations()).isEqualTo(250);
    assertThat(addedOnly.revision().theorems()).isEqualTo(250);

    LeanDiffSummary deletedOnly =
        LeanDiffEngine.computeLeanSummary(
            blocksA, List.of(), LeanDiffEngine.alignLeanBlocks(blocksA, List.of()));
    assertThat(deletedOnly.hasBase()).isTrue();
    assertThat(deletedOnly.hasRevision()).isFalse();
    assertThat(deletedOnly.deletedDeclarations()).isEqualTo(250);
    assertThat(deletedOnly.base().sorryCount()).isEqualTo(1);
  }

  @Test
  public void alignLeanBlocks_disjointLargeIntervalIdentityFallbackAndSignatureCommentDepth() {
    // 200 disjoint declarations exceed MAX_ALIGN_ITEMS_CELLS (10_000) and hit identity fallback.
    StringBuilder sbA = new StringBuilder();
    StringBuilder sbB = new StringBuilder();
    for (int i = 0; i < 200; i++) {
      if (i > 0) {
        sbA.append("\n\n");
        sbB.append("\n\n");
      }
      sbA.append("def a_").append(i).append(" : Nat := ").append(i);
      sbB.append("def b_").append(i).append(" : Nat := ").append(i);
    }
    ImmutableList<AlignedLeanRow> disjointRows =
        LeanDiffEngine.alignLeanBlocks(
            LeanParser.parse(sbA.toString()), LeanParser.parse(sbB.toString()));
    assertThat(disjointRows).hasSize(400);

    // Prefix unsafe + intervening -- comments in signatureLines.
    String withComments =
        String.join(
            "\n",
            "/-- Computes foo. -/",
            "-- temporary note",
            "unsafe",
            "-- another note",
            "@[simp]",
            "def bar : Nat := 1");
    String editedComments = withComments.replace("-- temporary note", "-- updated note");
    ImmutableList<AlignedLeanRow> commentRows =
        LeanDiffEngine.alignLeanBlocks(
            LeanParser.parse(withComments), LeanParser.parse(editedComments));
    assertThat(commentRows).hasSize(1);
    assertThat(commentRows.get(0).status()).isEqualTo(DiffBlockStatus.MODIFIED);
    ImmutableList<LeanAlignedCodeLine> alignedSig = commentRows.get(0).declDiff().signatureLines();
    assertThat(alignedSig.get(0).leftLine().lineNum()).isEqualTo(2);
    assertThat(alignedSig.get(0).rightLine().lineNum()).isEqualTo(2);
    assertThat(alignedSig.get(0).status()).isEqualTo(DiffBlockStatus.MODIFIED);

    // Object.prototype-style declaration names do not interfere with Java maps.
    String protoA =
        String.join(
            "\n\n",
            "def __proto__ : Nat := 1",
            "def constructor : Nat := 2",
            "theorem toString : True := by sorry",
            "theorem hasOwnProperty : True := by trivial");
    String protoB =
        String.join(
            "\n\n",
            "def __proto__ : Nat := 10",
            "def constructor : Nat := 2",
            "theorem toString : True := by trivial",
            "theorem hasOwnProperty : True := by trivial");
    ImmutableList<LeanBlock> pbA = LeanParser.parse(protoA);
    ImmutableList<LeanBlock> pbB = LeanParser.parse(protoB);
    ImmutableList<AlignedLeanRow> protoRows = LeanDiffEngine.alignLeanBlocks(pbA, pbB);
    LeanDiffSummary protoSummary = LeanDiffEngine.computeLeanSummary(pbA, pbB, protoRows);
    assertThat(protoSummary.base().sorryCount()).isEqualTo(1);
    assertThat(protoSummary.revision().sorryCount()).isEqualTo(0);
    assertThat(protoSummary.modifiedDeclarations()).isEqualTo(2);

    // Multi-line block comment depth propagates inside bodyLines and from signatureLines.
    String bodyCommentA =
        String.join("\n", "theorem c : True := by /-", "  body note", "  -/", "  trivial");
    String bodyCommentB =
        String.join("\n", "theorem c : True := by /-", "  updated body note", "  -/", "  trivial");
    ImmutableList<AlignedLeanRow> depthRows =
        LeanDiffEngine.alignLeanBlocks(
            LeanParser.parse(bodyCommentA), LeanParser.parse(bodyCommentB));
    assertThat(depthRows).hasSize(1);
    assertThat(depthRows.get(0).declDiff().bodyLines().get(1).leftBlockCommentDepth()).isEqualTo(1);
    assertThat(depthRows.get(0).declDiff().bodyLines().get(1).rightBlockCommentDepth())
        .isEqualTo(1);

    LeanDeclaration syntheticA =
        new LeanDeclaration(
            LeanDeclKind.THEOREM,
            "c",
            ImmutableList.of(),
            ImmutableList.of(),
            "old doc",
            1,
            1,
            ImmutableList.of(new LeanCodeLine(2, "theorem c : True")),
            ImmutableList.of(new LeanCodeLine(3, "  sorry")),
            true,
            LeanProofStrategy.TACTIC,
            null,
            null,
            null,
            false,
            new LeanDeclHygiene(true, false, false, false, false, false));
    LeanDeclaration syntheticB =
        new LeanDeclaration(
            LeanDeclKind.THEOREM,
            "c",
            ImmutableList.of(),
            ImmutableList.of(),
            "new doc",
            1,
            1,
            ImmutableList.of(new LeanCodeLine(2, "theorem c /- open")),
            ImmutableList.of(new LeanCodeLine(3, "  -/ trivial")),
            true,
            LeanProofStrategy.TACTIC,
            null,
            null,
            null,
            false,
            new LeanDeclHygiene(false, false, false, false, false, false));
    LeanDeclarationDiffData syntheticDiff =
        LeanDiffEngine.buildDeclarationDiffData(syntheticA, syntheticB);
    assertThat(syntheticDiff.docChanged()).isTrue();
    assertThat(syntheticDiff.leftDoc()).isEqualTo("old doc");
    assertThat(syntheticDiff.rightDoc()).isEqualTo("new doc");
    assertThat(syntheticDiff.bodyLines()).hasSize(2);
    assertThat(syntheticDiff.bodyLines().get(0).status()).isEqualTo(DiffBlockStatus.DELETED);
    assertThat(syntheticDiff.bodyLines().get(0).leftBlockCommentDepth()).isEqualTo(0);
    assertThat(syntheticDiff.bodyLines().get(1).status()).isEqualTo(DiffBlockStatus.ADDED);
    assertThat(syntheticDiff.bodyLines().get(1).rightBlockCommentDepth()).isEqualTo(1);

    // Large >10,000-cell interval with matching identity keys pairs them via linear cursor.
    StringBuilder idA = new StringBuilder();
    StringBuilder idB = new StringBuilder();
    for (int i = 0; i < 110; i++) {
      if (i > 0) {
        idA.append("\n\n");
        idB.append("\n\n");
      }
      if (i == 55) {
        idA.append("def shared_key : Nat := 1");
        idB.append("def shared_key : Nat := 2");
      } else {
        idA.append("def only_a_").append(i).append(" : Nat := ").append(i);
        idB.append("def only_b_").append(i).append(" : Nat := ").append(i);
      }
    }
    ImmutableList<AlignedLeanRow> idFallbackRows =
        LeanDiffEngine.alignLeanBlocks(
            LeanParser.parse(idA.toString()), LeanParser.parse(idB.toString()));
    assertThat(
            idFallbackRows.stream()
                .filter(r ->
                    r.status() == DiffBlockStatus.MODIFIED
                        && "shared_key".equals(r.rightBlock().decl().name()))
                .count())
        .isEqualTo(1);

    // >250,000-cell block diff triggers Patience linear exact anchors in alignLeanBlocks.
    StringBuilder bigBlocksA = new StringBuilder("def head_a : Nat := 0\n\n");
    StringBuilder bigBlocksB = new StringBuilder("def head_b : Nat := 0\n\n");
    for (int i = 0; i < 510; i++) {
      String decl = "def item_" + i + " : Nat := " + i + "\n\n";
      bigBlocksA.append(decl);
      bigBlocksB.append(decl);
    }
    bigBlocksA.append("def tail_a : Nat := 1");
    bigBlocksB.append("def tail_b : Nat := 1");
    ImmutableList<AlignedLeanRow> patienceBlockRows =
        LeanDiffEngine.alignLeanBlocks(
            LeanParser.parse(bigBlocksA.toString()), LeanParser.parse(bigBlocksB.toString()));
    assertThat(
            patienceBlockRows.stream().filter(r -> r.status() == DiffBlockStatus.UNCHANGED).count())
        .isEqualTo(510);

    // Directive paired row and empty/null guards.
    ImmutableList<AlignedLeanRow> dirRows =
        LeanDiffEngine.alignLeanBlocks(
            LeanParser.parse("namespace Foo"), LeanParser.parse("namespace Bar"));
    assertThat(dirRows).hasSize(1);
    assertThat(dirRows.get(0).status()).isEqualTo(DiffBlockStatus.MODIFIED);
    assertThat(dirRows.get(0).lineDiff()).isNotNull();

    // Empty and null guards.
    assertThat(LeanDiffEngine.alignLeanBlocks(List.of(), List.of())).isEmpty();
    assertThat(LeanDiffEngine.alignLeanCodeLines(List.of(), List.of())).isEmpty();
    assertThrows(NullPointerException.class, () -> LeanDiffEngine.tokenizeLeanDiffWords(null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new LeanAlignedCodeLine(
                DiffBlockStatus.DELETED,
                new LeanCodeLine(1, "a"),
                new LeanCodeLine(1, "b"),
                ImmutableList.of(),
                ImmutableList.of(),
                0,
                0,
                false,
                false));
  }
}
