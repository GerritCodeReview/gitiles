// Copyright 2026 Google Inc. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.gitiles.diff;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.jgit.diff.Edit;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class IntraLineDiffResultTest {
  @Test
  public void mutatingCallerEditAfterConstructionDoesNotAffectResult() {
    Edit e = new Edit(0, 1, 0, 1);
    List<Edit> input = new ArrayList<>();
    input.add(e);
    IntraLineDiffResult result = IntraLineDiffResult.editList(input);

    e.shift(100); // mutate the caller's edit after construction

    assertThat(result.edits().get(0).getBeginA()).isEqualTo(0);
    assertThat(result.edits().get(0).getEndA()).isEqualTo(1);
  }

  @Test
  public void mutatingReturnedEditDoesNotAffectStoredResult() {
    IntraLineDiffResult result =
        IntraLineDiffResult.editList(ImmutableList.of(new Edit(0, 1, 0, 1)));

    result.edits().get(0).shift(100); // mutate a returned edit

    assertThat(result.edits().get(0).getBeginA()).isEqualTo(0);
  }

  @Test
  public void replaceEditInternalEditsAreDeepCopied() {
    Edit internal = new Edit(0, 1, 0, 1);
    List<Edit> internals = new ArrayList<>();
    internals.add(internal);
    IntraLineDiffResult result =
        IntraLineDiffResult.editList(ImmutableList.of(new ReplaceEdit(0, 2, 0, 2, internals)));

    assertThat(result.edits().get(0)).isInstanceOf(ReplaceEdit.class);

    internal.shift(100); // mutate the caller's internal edit
    ((ReplaceEdit) result.edits().get(0)).getInternalEdits().get(0).shift(50); // and a returned one

    Edit storedInternal = ((ReplaceEdit) result.edits().get(0)).getInternalEdits().get(0);
    assertThat(storedInternal.getBeginA()).isEqualTo(0);
    assertThat(storedInternal.getEndA()).isEqualTo(1);
  }
}
