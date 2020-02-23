// Copyright (C) 2016 The Android Open Source Project
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

package com.google.gitiles.diff;

import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.util.List;
import org.eclipse.jgit.diff.Edit;
import org.eclipse.jgit.diff.EditList;
import org.eclipse.jgit.diff.RawText;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class IntraLineDiffTest {
  @Test
  public void replaceEditRejectsNonReplaceRanges() {
    assertThrows(
        IllegalArgumentException.class, () -> new ReplaceEdit(0, 0, 0, 1, ImmutableList.of()));
    assertThrows(
        IllegalArgumentException.class, () -> new ReplaceEdit(0, 1, 0, 0, ImmutableList.of()));
  }

  @Test
  public void rewriteAtStartOfLineIsRecognized() {
    assertThat(intraline("abc1\n", "def1\n"))
        .isEqualTo(ref().replace("abc", "def").common("1\n").edits);
  }

  @Test
  public void rewriteAtEndOfLineIsRecognized() {
    assertThat(intraline("abc1\n", "abc2\n"))
        .isEqualTo(ref().common("abc").replace("1", "2").common("\n").edits);
  }

  @Test
  public void completeRewriteIncludesNewline() {
    assertThat(intraline("abc1\n", "def2\n")).isEqualTo(ref().replace("abc1\n", "def2\n").edits);
  }

  @Test
  public void closeEditsAreCombined() {
    assertThat(intraline("ab1cdef2gh\n", "ab2cdef3gh\n"))
        .isEqualTo(ref().common("ab").replace("1cdef2", "2cdef3").common("gh\n").edits);
  }

  @Test
  public void preferInsertAfterCommonPart1() {
    assertThat(intraline("start middle end\n", "start middlemiddle end\n"))
        .isEqualTo(ref().common("start middle").insert("middle").common(" end\n").edits);
  }

  @Test
  public void preferInsertAfterCommonPart2() {
    assertThat(intraline("abc def\n", "abc  def\n"))
        .isEqualTo(ref().common("abc ").insert(" ").common("def\n").edits);
  }

  @Test
  public void preferInsertAtLineBreak1() {
    assertThat(intraline("multi\nline\n", "multi\nlinemulti\nline\n"))
        .isEqualTo(wordEdit(10, 10, 6, 16));
  }

  @Test
  public void preferInsertAtLineBreak2() {
    assertThat(intraline("  abc\n    def\n", "    abc\n      def\n"))
        .isEqualTo(
            ref().common("  ").insert("  ").common("abc\n    ").insert("  ").common("def\n").edits);
  }

  @Test
  public void preferDeleteAtLineBreak() {
    assertThat(intraline("    abc\n      def\n", "  abc\n    def\n"))
        .isEqualTo(
            ref().common("  ").remove("  ").common("abc\n    ").remove("  ").common("def\n").edits);
  }

  @Test
  public void insertedWhitespaceIsRecognized() {
    assertThat(intraline(" int *foobar\n", " int * foobar\n"))
        .isEqualTo(ref().common(" int *").insert(" ").common("foobar\n").edits);
  }

  @Test
  public void insertedWhitespaceIsRecognizedInMultipleLines() {
    assertThat(intraline(" int *foobar\n int *foobar\n", " int * foobar\n int * foobar\n"))
        .isEqualTo(
            ref()
                .common(" int *")
                .insert(" ")
                .common("foobar\n")
                .common(" int *")
                .insert(" ")
                .common("foobar\n")
                .edits);
  }

  @Test
  public void editsMarkedToStaySeparateAreNotCombined() {
    Edit first = new Edit(0, 1, 0, 1);
    Edit second = new Edit(2, 3, 2, 3);

    assertThat(
            IntraLineDiff.compute(
                raw("a\n{\nb\n"),
                raw("x\n{\ny\n"),
                ImmutableList.of(first, second),
                ImmutableSet.of(first)))
        .containsExactly(first, second)
        .inOrder();
  }

  private static ImmutableList<Edit> intraline(String a, String b) {
    return intraline(a, b, new Edit(0, countLines(a), 0, countLines(b)));
  }

  private static ImmutableList<Edit> intraline(String a, String b, Edit lines) {
    ImmutableList<Edit> actual = IntraLineDiff.compute(raw(a), raw(b), ImmutableList.of(lines));

    assertThat(actual).hasSize(1);
    Edit actualEdit = actual.get(0);
    assertThat(actualEdit.getBeginA()).isEqualTo(lines.getBeginA());
    assertThat(actualEdit.getEndA()).isEqualTo(lines.getEndA());
    assertThat(actualEdit.getBeginB()).isEqualTo(lines.getBeginB());
    assertThat(actualEdit.getEndB()).isEqualTo(lines.getEndB());
    assertThat(actualEdit).isInstanceOf(ReplaceEdit.class);

    return ((ReplaceEdit) actualEdit).getInternalEdits();
  }

  private static RawText raw(String text) {
    return new RawText(text.getBytes(UTF_8));
  }

  private static int countLines(String s) {
    int count = 0;
    for (int i = 0; i < s.length(); i++) {
      if (s.charAt(i) == '\n') {
        count++;
      }
    }
    return count;
  }

  private static List<Edit> wordEdit(int as, int ae, int bs, int be) {
    return EditList.singleton(new Edit(as, ae, bs, be));
  }

  private static Reference ref() {
    return new Reference();
  }

  private static class Reference {
    List<Edit> edits = new EditList();
    private int posA;
    private int posB;

    Reference common(String s) {
      int len = s.length();
      posA += len;
      posB += len;
      return this;
    }

    Reference insert(String s) {
      return replace("", s);
    }

    Reference remove(String s) {
      return replace(s, "");
    }

    Reference replace(String a, String b) {
      int lenA = a.length();
      int lenB = b.length();
      edits.add(new Edit(posA, posA + lenA, posB, posB + lenB));
      posA += lenA;
      posB += lenB;
      return this;
    }
  }
}
