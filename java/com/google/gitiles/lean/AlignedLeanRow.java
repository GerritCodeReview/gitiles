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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.collect.ImmutableList;
import javax.annotation.Nullable;

/** Aligned row representing a matched, modified, deleted, or added top-level Lean 4 block. */
public record AlignedLeanRow(
    String rowKey,
    DiffBlockStatus status,
    @Nullable LeanBlock leftBlock,
    @Nullable LeanBlock rightBlock,
    @Nullable Integer leftStartLine,
    @Nullable Integer leftEndLine,
    @Nullable Integer rightStartLine,
    @Nullable Integer rightEndLine,
    @Nullable TextDiffData moduleDocDiff,
    @Nullable TextDiffData bannerDiff,
    @Nullable ImmutableList<LeanAlignedCodeLine> lineDiff,
    @Nullable LeanDeclarationDiffData declDiff) {

  public AlignedLeanRow {
    checkNotNull(rowKey, "rowKey");
    checkNotNull(status, "status");
    lineDiff = lineDiff == null ? null : ImmutableList.copyOf(lineDiff);
    switch (status) {
      case UNCHANGED, MODIFIED -> {
        checkNotNull(leftBlock, "leftBlock");
        checkNotNull(rightBlock, "rightBlock");
      }
      case DELETED -> {
        checkNotNull(leftBlock, "leftBlock");
        checkArgument(rightBlock == null, "deleted row cannot have rightBlock");
      }
      case ADDED -> {
        checkNotNull(rightBlock, "rightBlock");
        checkArgument(leftBlock == null, "added row cannot have leftBlock");
      }
    }
  }
}
