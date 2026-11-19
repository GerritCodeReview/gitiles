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
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class LeanDiffEngineAdversarialTest {

  private static boolean rowHasVisibleChange(AlignedLeanRow row) {
    if (row.status() == DiffBlockStatus.ADDED || row.status() == DiffBlockStatus.DELETED) {
      return true;
    }
    if (row.status() == DiffBlockStatus.UNCHANGED) {
      return false;
    }
    if (row.moduleDocDiff() != null) {
      return row.moduleDocDiff().changed()
          && !row.moduleDocDiff().leftText().equals(row.moduleDocDiff().rightText());
    }
    if (row.bannerDiff() != null) {
      return row.bannerDiff().changed()
          && !row.bannerDiff().leftText().equals(row.bannerDiff().rightText());
    }
    if (row.lineDiff() != null) {
      return row.lineDiff().stream().anyMatch(l -> l.status() != DiffBlockStatus.UNCHANGED);
    }
    if (row.declDiff() != null) {
      LeanDeclarationDiffData dd = row.declDiff();
      return dd.docChanged() || dd.signatureChanged() || dd.bodyChanged() || dd.fieldsChanged();
    }
    return false;
  }

  private static void assertAllBlocksPreservedInOrder(
      List<LeanBlock> blocksA, List<LeanBlock> blocksB, List<AlignedLeanRow> rows) {
    List<LeanBlock> gotA = new ArrayList<>();
    List<LeanBlock> gotB = new ArrayList<>();
    for (AlignedLeanRow row : rows) {
      switch (row.status()) {
        case UNCHANGED -> {
          gotA.add(row.leftBlock());
          gotB.add(row.rightBlock());
          assertThat(row.leftBlock().raw()).isEqualTo(row.rightBlock().raw());
        }
        case MODIFIED -> {
          gotA.add(row.leftBlock());
          gotB.add(row.rightBlock());
        }
        case DELETED -> gotA.add(row.leftBlock());
        case ADDED -> gotB.add(row.rightBlock());
      }
    }
    assertThat(gotA).isEqualTo(blocksA);
    assertThat(gotB).isEqualTo(blocksB);
  }

  @Test
  public void hiddenChanges_structureFieldsBannersModuleDocsAndAttributesAreDetected() {
    // 1. Structure field whitespace/comment formatting change must not stay UNCHANGED.
    String structA = String.join("\n", "structure S where", "  x : Nat := 1 -- note");
    String structB = String.join("\n", "structure S where", "    x  :  Nat := 1 --note");
    ImmutableList<AlignedLeanRow> structRows =
        LeanDiffEngine.alignLeanBlocks(LeanParser.parse(structA), LeanParser.parse(structB));
    assertThat(structRows).hasSize(1);
    assertThat(structRows.get(0).status()).isEqualTo(DiffBlockStatus.MODIFIED);
    assertThat(structRows.get(0).declDiff()).isNotNull();
    assertThat(structRows.get(0).declDiff().fieldsChanged()).isTrue();
    assertThat(structRows.get(0).declDiff().fields().get(0).status())
        .isEqualTo(DiffBlockStatus.MODIFIED);

    // 2. Section banner divider edit & module doc whitespace edit with identical markdownContent
    //    must fall back to a visible raw lineDiff rather than emitting identical semantic sides.
    String docBannerA =
        String.join("\n", "/-!", "Module Doc", "-/", "", "-- ====", "-- Title", "-- ====");
    String docBannerB =
        String.join("\n", "/-!   Module Doc   -/", "", "-- ----", "-- Title", "-- ----");
    ImmutableList<AlignedLeanRow> dbRows =
        LeanDiffEngine.alignLeanBlocks(LeanParser.parse(docBannerA), LeanParser.parse(docBannerB));
    assertThat(dbRows).hasSize(2);
    assertThat(dbRows.get(0).status()).isEqualTo(DiffBlockStatus.MODIFIED);
    assertThat(rowHasVisibleChange(dbRows.get(0))).isTrue();
    assertThat(dbRows.get(0).lineDiff()).isNotNull();
    assertThat(dbRows.get(1).status()).isEqualTo(DiffBlockStatus.MODIFIED);
    assertThat(rowHasVisibleChange(dbRows.get(1))).isTrue();
    assertThat(dbRows.get(1).lineDiff()).isNotNull();

    // 3. Standalone @[...] attribute change falls back to lineDiff so @[simp] is never dropped.
    String attrA = String.join("\n", "@[simp]", "theorem t : True := by", "  trivial");
    String attrB = String.join("\n", "@[ext]", "theorem t : True := by", "  trivial");
    ImmutableList<LeanBlock> abA = LeanParser.parse(attrA);
    ImmutableList<LeanBlock> abB = LeanParser.parse(attrB);
    ImmutableList<AlignedLeanRow> attrRows = LeanDiffEngine.alignLeanBlocks(abA, abB);
    assertThat(attrRows).hasSize(1);
    assertThat(attrRows.get(0).status()).isEqualTo(DiffBlockStatus.MODIFIED);
    assertThat(rowHasVisibleChange(attrRows.get(0))).isTrue();
    assertThat(LeanDiffEngine.computeLeanSummary(abA, abB, attrRows).modifiedDeclarations())
        .isEqualTo(1);

    // 4. Compact def formatting-only edit (`:=` spacing) does not vanish as zero-change MODIFIED.
    String wsDefA = "def k := 1";
    String wsDefB = "def k  :=   1";
    ImmutableList<AlignedLeanRow> wsRows =
        LeanDiffEngine.alignLeanBlocks(LeanParser.parse(wsDefA), LeanParser.parse(wsDefB));
    assertThat(wsRows).hasSize(1);
    assertThat(wsRows.get(0).status()).isEqualTo(DiffBlockStatus.MODIFIED);
    assertThat(rowHasVisibleChange(wsRows.get(0))).isTrue();
  }

  @Test
  public void selfDiffMovedAndRenamedDeclarations_preserveIntegrity() {
    String src =
        String.join(
            "\n\n",
            "theorem alpha (n : Nat) : n + 0 = n := by\n  omega",
            "theorem anchor (n : Nat) : n = n := by\n  rfl",
            "theorem beta (n : Nat) : n + 1 = 1 + n := by\n  simp");
    ImmutableList<LeanBlock> base = LeanParser.parse(src);

    // Self-diff has zero changes.
    ImmutableList<AlignedLeanRow> self = LeanDiffEngine.alignLeanBlocks(base, base);
    assertThat(self.stream().allMatch(r -> r.status() == DiffBlockStatus.UNCHANGED)).isTrue();
    assertAllBlocksPreservedInOrder(base, base, self);

    // Moved AND modified + renamed AND modified declarations still show content changes.
    String movedAndRenamed =
        String.join(
            "\n\n",
            "theorem anchor (n : Nat) : n = n := by\n  rfl",
            "theorem alpha (n : Nat) : n + 0 = n := by\n  sorry",
            "theorem beta_renamed (n : Nat) : n + 1 = 1 + n := by\n  omega");
    ImmutableList<LeanBlock> rev = LeanParser.parse(movedAndRenamed);
    ImmutableList<AlignedLeanRow> rows = LeanDiffEngine.alignLeanBlocks(base, rev);
    assertAllBlocksPreservedInOrder(base, rev, rows);

    assertThat(
            rows.stream()
                .anyMatch(r ->
                    r.status() != DiffBlockStatus.UNCHANGED
                        && r.leftBlock() != null
                        && "alpha".equals(r.leftBlock().decl().name())))
        .isTrue();
    assertThat(
            rows.stream()
                .anyMatch(r ->
                    r.status() != DiffBlockStatus.UNCHANGED
                        && r.rightBlock() != null
                        && r.rightBlock().raw().contains("sorry")))
        .isTrue();
    assertThat(
            rows.stream()
                .anyMatch(r ->
                    r.status() != DiffBlockStatus.UNCHANGED
                        && r.rightBlock() != null
                        && "beta_renamed".equals(r.rightBlock().decl().name())))
        .isTrue();
  }

  @Test
  public void singleOneMibLineInlineDiff_staysLinearAndLossless() {
    StringBuilder left = new StringBuilder("def a0");
    StringBuilder right = new StringBuilder("def b0");
    for (int i = 0; i < 2_000; i++) {
      left.append(" w").append(i % 400);
      right.append(" w").append(i % 400);
    }
    left.append(" tailOld");
    right.append(" tailNew");

    ImmutableList<LeanAlignedCodeLine> aligned =
        LeanDiffEngine.alignLeanCodeLines(
            List.of(new LeanCodeLine(1, left.toString())),
            List.of(new LeanCodeLine(1, right.toString())),
            false);
    assertThat(aligned).hasSize(1);
    assertThat(aligned.get(0).status()).isEqualTo(DiffBlockStatus.MODIFIED);
    StringBuilder joinLeft = new StringBuilder();
    for (InlineEditSegment seg : aligned.get(0).leftSegments()) {
      joinLeft.append(seg.text());
    }
    StringBuilder joinRight = new StringBuilder();
    for (InlineEditSegment seg : aligned.get(0).rightSegments()) {
      joinRight.append(seg.text());
    }
    assertThat(joinLeft.toString()).isEqualTo(left.toString());
    assertThat(joinRight.toString()).isEqualTo(right.toString());
  }

  @Test
  public void adversarialMultiIntervalAndPairedDecls_stayWithinPerDiffBudget() {
    // Reproducer for verifier S-D-3: 6 declarations x 60 lines x 60 tokens crosses per-diff DP
    // budgets cleanly in linear time without inflating unit-test latency.
    StringBuilder shared = new StringBuilder();
    for (int t = 0; t < 60; t++) {
      shared.append(" + t").append(t);
    }
    String tail = shared.toString();
    StringBuilder declsA = new StringBuilder();
    StringBuilder declsB = new StringBuilder();
    for (int d = 0; d < 6; d++) {
      declsA.append("theorem thm_").append(d).append(" : True := by\n");
      declsB.append("theorem thm_").append(d).append(" : True := by\n");
      for (int l = 0; l < 60; l++) {
        declsA
            .append("  have s_old_")
            .append(l)
            .append(" : Nat := ")
            .append(l)
            .append(tail)
            .append("\n");
        declsB
            .append("  have s_new_")
            .append(l)
            .append(" : Nat := ")
            .append(l)
            .append(tail)
            .append("\n");
      }
      declsA.append("\n");
      declsB.append("\n");
    }
    ImmutableList<LeanBlock> pbA = LeanParser.parse(declsA.toString());
    ImmutableList<LeanBlock> pbB = LeanParser.parse(declsB.toString());
    ImmutableList<AlignedLeanRow> pairedRows = LeanDiffEngine.alignLeanBlocks(pbA, pbB);
    assertAllBlocksPreservedInOrder(pbA, pbB, pairedRows);

    // 3 interleaved 70x70 anonymous block gaps separated by anchors (3 * 4,900 = 14,700 > 10k).
    StringBuilder gapsA = new StringBuilder();
    StringBuilder gapsB = new StringBuilder();
    for (int g = 0; g < 3; g++) {
      for (int i = 0; i < 70; i++) {
        gapsA.append("example : Nat := 1 + x0 + x1 + x2 + x3\n\n");
        gapsB.append("example : Nat := 2 + x0 + x1 + x2 + x3\n\n");
      }
      gapsA.append("def anc_").append(g).append(" : Nat := ").append(g).append("\n\n");
      gapsB.append("def anc_").append(g).append(" : Nat := ").append(g).append("\n\n");
    }
    ImmutableList<LeanBlock> gbA = LeanParser.parse(gapsA.toString());
    ImmutableList<LeanBlock> gbB = LeanParser.parse(gapsB.toString());
    ImmutableList<AlignedLeanRow> gapRows = LeanDiffEngine.alignLeanBlocks(gbA, gbB);
    assertAllBlocksPreservedInOrder(gbA, gbB, gapRows);
    assertThat(gapRows.stream().filter(r -> r.status() == DiffBlockStatus.UNCHANGED).count())
        .isEqualTo(3);

    // Reversed declarations (everything moved) stay exact & linear.
    List<LeanBlock> small = new ArrayList<>(gbA.subList(0, Math.min(150, gbA.size())));
    List<LeanBlock> reversed = new ArrayList<>(small);
    Collections.reverse(reversed);
    ImmutableList<AlignedLeanRow> movedRows = LeanDiffEngine.alignLeanBlocks(small, reversed);
    assertAllBlocksPreservedInOrder(small, reversed, movedRows);
  }

  @Test
  public void asymmetricManyTinyBlocksVsOneHugeBlock_staysLinear() {
    // Reproducer for g3gate S-D-5: 2,000 small DIRECTIVE blocks vs 1 huge 20k-token block (<10k
    // interval cells, forcing GapEditScorer directly forward, reverse, and on MODULE_DOC).
    String left = "foo_bar \n\n".repeat(2_000);
    StringBuilder rightSb = new StringBuilder("in_weird");
    for (int t = 0; t < 20_000; t++) {
      rightSb.append(" tok").append(t % 97);
    }
    String right = rightSb.toString();
    ImmutableList<LeanBlock> la = LeanParser.parse(left);
    ImmutableList<LeanBlock> lb = LeanParser.parse(right);
    ImmutableList<AlignedLeanRow> rowsFwd = LeanDiffEngine.alignLeanBlocks(la, lb);
    assertAllBlocksPreservedInOrder(la, lb, rowsFwd);

    ImmutableList<AlignedLeanRow> rowsRev = LeanDiffEngine.alignLeanBlocks(lb, la);
    assertAllBlocksPreservedInOrder(lb, la, rowsRev);

    // Asymmetric MODULE_DOC interval (maxDistance = 1.0 path, hits GapEditScorer directly).
    String docMany = "/-! x -/\n\n".repeat(1_500);
    String docHuge = "/-! " + right + " -/";
    ImmutableList<LeanBlock> da = LeanParser.parse(docMany);
    ImmutableList<LeanBlock> db = LeanParser.parse(docHuge);
    ImmutableList<AlignedLeanRow> docRows = LeanDiffEngine.alignLeanBlocks(da, db);
    assertAllBlocksPreservedInOrder(da, db, docRows);
  }

  @Test
  public void alignLeanBlocks_cooperativeAbortStopsPromptlyWithoutPartialRows() {
    StringBuilder a = new StringBuilder();
    StringBuilder b = new StringBuilder();
    for (int d = 0; d < 10; d++) {
      a.append("theorem t_").append(d).append(" : True := by\n");
      b.append("theorem t_").append(d).append(" : True := by\n");
      for (int l = 0; l < 60; l++) {
        a.append("  have left_").append(l).append(" : Nat := ").append(l).append(" + x0 + x1\n");
        b.append("  have right_").append(l).append(" : Nat := ").append(l).append(" + x0 + x1\n");
      }
      a.append("\n");
      b.append("\n");
    }
    ImmutableList<LeanBlock> blocksA = LeanParser.parse(a.toString());
    ImmutableList<LeanBlock> blocksB = LeanParser.parse(b.toString());

    // Immediate abort returns empty immediately.
    assertThat(LeanDiffEngine.alignLeanBlocks(blocksA, blocksB, () -> true)).isEmpty();

    // Never-abort overload matches standard 2-arg alignment identically.
    ImmutableList<AlignedLeanRow> baseline = LeanDiffEngine.alignLeanBlocks(blocksA, blocksB);
    ImmutableList<AlignedLeanRow> withFalse =
        LeanDiffEngine.alignLeanBlocks(blocksA, blocksB, () -> false);
    assertThat(withFalse).isEqualTo(baseline);

    // Delayed trigger inside interval / homologous / inline loops stops cleanly well within 50 ms.
    int[] polls = new int[1];
    long[] abortAt = new long[1];
    ImmutableList<AlignedLeanRow> aborted =
        LeanDiffEngine.alignLeanBlocks(
            blocksA,
            blocksB,
            () -> {
              if (++polls[0] >= 5) {
                if (abortAt[0] == 0L) {
                  abortAt[0] = System.nanoTime();
                }
                return true;
              }
              return false;
            });
    long finishNanos = System.nanoTime();
    assertThat(aborted).isEmpty();
    assertThat(polls[0]).isEqualTo(5);
    assertThat(finishNanos - abortAt[0]).isLessThan(50_000_000L);
  }

  @Test
  public void controlCharsAndUnrepresentedBodyLines_neverVanishInStructureDocOrBannerDiffs() {
    // 1. Adding a control-only line inside class/structure with no field edits surfaces the
    //    control line via lineDiff (when parser keeps fields()) or declDiff.bodyLines() (#194).
    String classBase = String.join("\n", "class C where", "  x : Nat", "  y : Nat");
    String classCtrl =
        String.join("\n", "class C where", "  x : Nat", "  \u0001\u001B", "  y : Nat");
    ImmutableList<AlignedLeanRow> classRows =
        LeanDiffEngine.alignLeanBlocks(LeanParser.parse(classBase), LeanParser.parse(classCtrl));
    assertThat(classRows).hasSize(1);
    assertThat(classRows.get(0).status()).isEqualTo(DiffBlockStatus.MODIFIED);
    assertRowPreservesSubstringOnRight(classRows.get(0), "\u0001\u001B");

    // 2. Adding/moving an unrepresented control line inside structure alongside another field edit
    //    must still surface the control line instead of silently dropping it (diff_struct_ctrl).
    String structBase = String.join("\n", "structure S where", "  x : Nat", "  y : Nat");
    String structCtrl =
        String.join("\n", "structure S where", "  x : Int", "  \u000B\u000C", "  y : Nat");
    ImmutableList<AlignedLeanRow> structCtrlRows =
        LeanDiffEngine.alignLeanBlocks(LeanParser.parse(structBase), LeanParser.parse(structCtrl));
    assertThat(structCtrlRows).hasSize(1);
    assertRowPreservesSubstringOnRight(structCtrlRows.get(0), "\u000B\u000C");

    // Benign unchanged blank line between structure fields keeps the structured field table.
    String structBlankA = String.join("\n", "structure B where", "  x : Nat", "", "  y : Nat");
    String structBlankB = String.join("\n", "structure B where", "  x : Int", "", "  y : Nat");
    ImmutableList<AlignedLeanRow> structBlankRows =
        LeanDiffEngine.alignLeanBlocks(
            LeanParser.parse(structBlankA), LeanParser.parse(structBlankB));
    assertThat(structBlankRows).hasSize(1);
    assertThat(structBlankRows.get(0).declDiff()).isNotNull();
    assertThat(structBlankRows.get(0).declDiff().fieldsChanged()).isTrue();

    // 3. Doc-comment control chars at trimmed edges alongside a body edit remain visible either in
    //    declDiff.rightDoc() or via raw lineDiff fallback (R2-CHAOS-4: diff_doc_body).
    String docDeclBase = String.join("\n", "/--", "  hello", "-/", "def f := 1");
    String docDeclCtrl = String.join("\n", "/--", "  \u0001\u001B", "-/", "def f := 2");
    ImmutableList<AlignedLeanRow> docDeclRows =
        LeanDiffEngine.alignLeanBlocks(
            LeanParser.parse(docDeclBase), LeanParser.parse(docDeclCtrl));
    assertThat(docDeclRows).hasSize(1);
    assertThat(docDeclRows.get(0).status()).isEqualTo(DiffBlockStatus.MODIFIED);
    assertRowPreservesSubstringOnRight(docDeclRows.get(0), "\u0001\u001B");

    String docDeclEdgeCtrl = String.join("\n", "/--", "\u0001 hello \u001B", "-/", "def f := 2");
    ImmutableList<AlignedLeanRow> docDeclEdgeRows =
        LeanDiffEngine.alignLeanBlocks(
            LeanParser.parse(docDeclBase), LeanParser.parse(docDeclEdgeCtrl));
    assertThat(docDeclEdgeRows).hasSize(1);
    assertRowPreservesSubstringOnRight(docDeclEdgeRows.get(0), "\u0001");
    assertRowPreservesSubstringOnRight(docDeclEdgeRows.get(0), "\u001B");

    // 4. Module-doc and section-banner edits with control chars at trimmed edges never drop those
    //    control chars across additions or deletions (R2-CHAOS-4: diff_moddoc_edit).
    String modDocBase = String.join("\n", "/-!", "  hello", "-/");
    String modDocCtrl = String.join("\n", "/-!", "  \u0001\u001B", "  hello world", "-/");
    ImmutableList<AlignedLeanRow> modAddRows =
        LeanDiffEngine.alignLeanBlocks(LeanParser.parse(modDocBase), LeanParser.parse(modDocCtrl));
    assertThat(modAddRows).hasSize(1);
    assertRowPreservesSubstringOnRight(modAddRows.get(0), "\u0001\u001B");

    ImmutableList<AlignedLeanRow> modDelRows =
        LeanDiffEngine.alignLeanBlocks(LeanParser.parse(modDocCtrl), LeanParser.parse(modDocBase));
    assertThat(modDelRows).hasSize(1);
    assertThat(rowHasVisibleChange(modDelRows.get(0))).isTrue();
    assertThat(
            (modDelRows.get(0).moduleDocDiff() != null
                    && modDelRows.get(0).moduleDocDiff().leftText().contains("\u0001\u001B"))
                || (modDelRows.get(0).lineDiff() != null
                    && modDelRows.get(0).lineDiff().stream()
                        .anyMatch(l ->
                            l.leftLine() != null && l.leftLine().text().contains("\u0001\u001B"))))
        .isTrue();

    // 5. Structure field trailing comment / deriving control chars remain visible regardless of
    //    whether the parser kept them in fields() or LeanDiffEngine fell back to lineDiff.
    String fieldCommentBase = String.join("\n", "structure FC where", "  x : Nat -- note");
    String fieldCommentCtrl =
        String.join("\n", "structure FC where", "  x : Int -- note\u0001\u001B");
    ImmutableList<AlignedLeanRow> fieldCommentRows =
        LeanDiffEngine.alignLeanBlocks(
            LeanParser.parse(fieldCommentBase), LeanParser.parse(fieldCommentCtrl));
    assertThat(fieldCommentRows).hasSize(1);
    assertRowPreservesSubstringOnRight(fieldCommentRows.get(0), "\u0001\u001B");

    // 6. Direct LeanDiffEngine defense-in-depth: synthetic structure omitting a control body line
    //    from fields() must force raw lineDiff fallback even when another field also changed.
    LeanDeclaration omittedLeft =
        new LeanDeclaration(
            LeanDeclKind.STRUCTURE,
            "S",
            ImmutableList.of(),
            ImmutableList.of(),
            null,
            null,
            null,
            ImmutableList.of(new LeanCodeLine(1, "structure S where")),
            ImmutableList.of(new LeanCodeLine(2, "  x : Nat")),
            false,
            null,
            ImmutableList.of(new LeanStructureField("x", "Nat", null, null, 2, "  x : Nat")),
            null,
            null,
            false,
            new LeanDeclHygiene(false, false, false, false, false, false));
    LeanDeclaration omittedRight =
        new LeanDeclaration(
            LeanDeclKind.STRUCTURE,
            "S",
            ImmutableList.of(),
            ImmutableList.of(),
            null,
            null,
            null,
            ImmutableList.of(new LeanCodeLine(1, "structure S where")),
            ImmutableList.of(
                new LeanCodeLine(2, "  x : Int"), new LeanCodeLine(3, "  \u0001\u001B")),
            false,
            null,
            ImmutableList.of(new LeanStructureField("x", "Int", null, null, 2, "  x : Int")),
            null,
            null,
            false,
            new LeanDeclHygiene(false, false, false, false, false, false));
    AlignedLeanRow omittedRow =
        LeanDiffEngine.buildPairedRow(
            new LeanBlock(
                "decl-left",
                LeanBlockType.DECLARATION,
                "structure S where\n  x : Nat",
                1,
                2,
                null,
                ImmutableList.of(),
                null,
                ImmutableList.of(),
                ImmutableList.of(),
                false,
                omittedLeft),
            new LeanBlock(
                "decl-right",
                LeanBlockType.DECLARATION,
                "structure S where\n  x : Int\n  \u0001\u001B",
                1,
                3,
                null,
                ImmutableList.of(),
                null,
                ImmutableList.of(),
                ImmutableList.of(),
                false,
                omittedRight));
    assertThat(omittedRow.lineDiff()).isNotNull();
    assertRowPreservesSubstringOnRight(omittedRow, "\u0001\u001B");
  }

  private static void assertRowPreservesSubstringOnRight(AlignedLeanRow row, String needle) {
    assertThat(rowHasVisibleChange(row)).isTrue();
    boolean inModuleDoc =
        row.moduleDocDiff() != null && row.moduleDocDiff().rightText().contains(needle);
    boolean inBanner = row.bannerDiff() != null && row.bannerDiff().rightText().contains(needle);
    boolean inDeclDoc =
        row.declDiff() != null
            && row.declDiff().rightDoc() != null
            && row.declDiff().rightDoc().contains(needle);
    boolean inDeclBody =
        row.declDiff() != null
            && row.declDiff().bodyLines().stream()
                .anyMatch(l ->
                    l.status() != DiffBlockStatus.UNCHANGED
                        && l.rightLine() != null
                        && l.rightLine().text().contains(needle));
    boolean inDeclFields =
        row.declDiff() != null
            && row.declDiff().fields() != null
            && row.declDiff().fields().stream()
                .anyMatch(f ->
                    f.status() != DiffBlockStatus.UNCHANGED
                        && f.rightField() != null
                        && (f.rightField().rawLine().contains(needle)
                            || f.rightField().typeText().contains(needle)
                            || (f.rightField().defaultValue() != null
                                && f.rightField().defaultValue().contains(needle))
                            || (f.rightField().comment() != null
                                && f.rightField().comment().contains(needle))));
    boolean inLineDiff =
        row.lineDiff() != null
            && row.lineDiff().stream()
                .anyMatch(l ->
                    l.status() != DiffBlockStatus.UNCHANGED
                        && l.rightLine() != null
                        && l.rightLine().text().contains(needle));
    assertThat(inModuleDoc || inBanner || inDeclDoc || inDeclBody || inDeclFields || inLineDiff)
        .isTrue();
  }

  @Test
  public void diffWordsSplitSuperscriptsAndDeclAlignmentPropagatesMultilineStringState() {
    // R2-INT-3: superscripts `⁰-⁹` and OTHER_NUMBER/LETTER_NUMBER must not merge onto `sorry`.
    assertThat(LeanDiffEngine.tokenizeLeanDiffWords("theorem t : False := sorry⁰"))
        .containsAtLeast("sorry", "⁰")
        .inOrder();
    assertThat(LeanDiffEngine.tokenizeLeanDiffWords("admit⁰ + sorry² + sorryAx⁰"))
        .containsAtLeast("admit", "⁰", "sorry", "²", "sorryAx", "⁰")
        .inOrder();
    assertThat(LeanDiffEngine.tokenizeLeanDiffWords("sorryλ + sorryΠ + sorryΣ"))
        .containsAtLeast("sorry", "λ", "sorry", "Π", "sorry", "Σ")
        .inOrder();
    assertThat(LeanDiffEngine.tokenizeLeanDiffWords("sorryⁿ + sorry𝑥 + ⅋r"))
        .containsAtLeast("sorry", "ⁿ", "sorry", "𝑥", "⅋r")
        .inOrder();
    assertThat(LeanDiffEngine.tokenizeLeanDiffWords("def x₁' := 1"))
        .containsExactly("def", " ", "x₁'", " ", ":=", " ", "1")
        .inOrder();

    // R2-INT-5: multiline string containing `/-` in signature or body never leaks comment depth.
    String sigLeakLeft =
        String.join(
            "\n", "def sigLeak (s : String := \"", "/-", "\") : False := by", "  exact sorry");
    String sigLeakRight =
        String.join(
            "\n",
            "def sigLeak (s : String := \"",
            "/-",
            "\") : False := by",
            "  have evil : False := sorry",
            "  exact evil");
    ImmutableList<AlignedLeanRow> rows =
        LeanDiffEngine.alignLeanBlocks(
            LeanParser.parse(sigLeakLeft), LeanParser.parse(sigLeakRight));
    assertThat(rows).hasSize(1);
    LeanDeclarationDiffData dd = rows.get(0).declDiff();
    assertThat(dd).isNotNull();
    // Signature line 2 ("/-") is inString and blockCommentDepth remains 0; body lines stay at 0.
    assertThat(dd.signatureLines().get(1).rightHighlightState().inString()).isTrue();
    assertThat(dd.signatureLines().get(1).rightBlockCommentDepth()).isEqualTo(0);
    for (LeanAlignedCodeLine bodyLine : dd.bodyLines()) {
      assertThat(bodyLine.rightBlockCommentDepth()).isEqualTo(0);
      assertThat(bodyLine.rightHighlightState()).isEqualTo(LeanHighlighter.HighlightState.INITIAL);
    }

    // String spanning directly from signatureLines into bodyLines carries `inString` across.
    LeanDeclaration sigToBodyA =
        new LeanDeclaration(
            LeanDeclKind.DEF,
            "s",
            ImmutableList.of(),
            ImmutableList.of(),
            null,
            null,
            null,
            ImmutableList.of(new LeanCodeLine(1, "def s := \"unclosed")),
            ImmutableList.of(new LeanCodeLine(2, "/-"), new LeanCodeLine(3, "\"")),
            false,
            null,
            null,
            null,
            null,
            false,
            new LeanDeclHygiene(false, false, false, false, false, false));
    LeanDeclaration sigToBodyB =
        new LeanDeclaration(
            LeanDeclKind.DEF,
            "s",
            ImmutableList.of(),
            ImmutableList.of(),
            null,
            null,
            null,
            ImmutableList.of(new LeanCodeLine(1, "def s := \"unclosed")),
            ImmutableList.of(new LeanCodeLine(2, "/- edited"), new LeanCodeLine(3, "\"")),
            false,
            null,
            null,
            null,
            null,
            false,
            new LeanDeclHygiene(false, false, false, false, false, false));
    LeanDeclarationDiffData spanDiff =
        LeanDiffEngine.buildDeclarationDiffData(sigToBodyA, sigToBodyB);
    assertThat(spanDiff.bodyLines().size()).isAtLeast(2);
    for (LeanAlignedCodeLine line : spanDiff.bodyLines()) {
      if (line.leftLine() != null) {
        assertThat(line.leftHighlightState().inString()).isTrue();
        assertThat(line.leftBlockCommentDepth()).isEqualTo(0);
      }
      if (line.rightLine() != null) {
        assertThat(line.rightHighlightState().inString()).isTrue();
        assertThat(line.rightBlockCommentDepth()).isEqualTo(0);
      }
    }
  }
}
