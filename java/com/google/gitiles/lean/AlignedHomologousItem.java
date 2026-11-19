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

import javax.annotation.Nullable;

/** Single aligned item produced by {@link SequenceAligner#alignHomologousItems}. */
public record AlignedHomologousItem(
    DiffBlockStatus type,
    @Nullable String textA,
    @Nullable String textB,
    @Nullable Integer aIdx,
    @Nullable Integer bIdx) {

  public AlignedHomologousItem {
    checkNotNull(type, "type");
    switch (type) {
      case UNCHANGED, MODIFIED -> {
        checkNotNull(textA, "textA");
        checkNotNull(textB, "textB");
        checkNotNull(aIdx, "aIdx");
        checkNotNull(bIdx, "bIdx");
        checkArgument(aIdx >= 0, "aIdx must be non-negative: %s", aIdx);
        checkArgument(bIdx >= 0, "bIdx must be non-negative: %s", bIdx);
      }
      case DELETED -> {
        checkNotNull(textA, "textA");
        checkNotNull(aIdx, "aIdx");
        checkArgument(aIdx >= 0, "aIdx must be non-negative: %s", aIdx);
        checkArgument(textB == null && bIdx == null, "deleted item cannot have right-side fields");
      }
      case ADDED -> {
        checkNotNull(textB, "textB");
        checkNotNull(bIdx, "bIdx");
        checkArgument(bIdx >= 0, "bIdx must be non-negative: %s", bIdx);
        checkArgument(textA == null && aIdx == null, "added item cannot have left-side fields");
      }
    }
  }

  public static AlignedHomologousItem unchanged(String textA, String textB, int aIdx, int bIdx) {
    return new AlignedHomologousItem(DiffBlockStatus.UNCHANGED, textA, textB, aIdx, bIdx);
  }

  public static AlignedHomologousItem modified(String textA, String textB, int aIdx, int bIdx) {
    return new AlignedHomologousItem(DiffBlockStatus.MODIFIED, textA, textB, aIdx, bIdx);
  }

  public static AlignedHomologousItem deleted(String textA, int aIdx) {
    return new AlignedHomologousItem(DiffBlockStatus.DELETED, textA, null, aIdx, null);
  }

  public static AlignedHomologousItem added(String textB, int bIdx) {
    return new AlignedHomologousItem(DiffBlockStatus.ADDED, null, textB, null, bIdx);
  }
}
