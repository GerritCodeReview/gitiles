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

/** Aligned code line pair with per-side inline edit segments and syntax-highlighting context. */
public record LeanAlignedCodeLine(
    DiffBlockStatus status,
    @Nullable LeanCodeLine leftLine,
    @Nullable LeanCodeLine rightLine,
    ImmutableList<InlineEditSegment> leftSegments,
    ImmutableList<InlineEditSegment> rightSegments,
    int leftBlockCommentDepth,
    int rightBlockCommentDepth,
    boolean inTacticContext,
    boolean forceComment) {

  public LeanAlignedCodeLine {
    checkNotNull(status, "status");
    leftSegments = ImmutableList.copyOf(checkNotNull(leftSegments, "leftSegments"));
    rightSegments = ImmutableList.copyOf(checkNotNull(rightSegments, "rightSegments"));
    checkArgument(leftBlockCommentDepth >= 0, "leftBlockCommentDepth must be non-negative");
    checkArgument(rightBlockCommentDepth >= 0, "rightBlockCommentDepth must be non-negative");
    switch (status) {
      case UNCHANGED, MODIFIED -> {
        checkNotNull(leftLine, "leftLine");
        checkNotNull(rightLine, "rightLine");
      }
      case DELETED -> {
        checkNotNull(leftLine, "leftLine");
        checkArgument(rightLine == null, "deleted line cannot have rightLine");
      }
      case ADDED -> {
        checkNotNull(rightLine, "rightLine");
        checkArgument(leftLine == null, "added line cannot have leftLine");
      }
    }
  }
}
