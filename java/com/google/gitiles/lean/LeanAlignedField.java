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

/** Aligned structure or class field pair with per-side type diff segments. */
public record LeanAlignedField(
    DiffBlockStatus status,
    @Nullable LeanStructureField leftField,
    @Nullable LeanStructureField rightField,
    ImmutableList<InlineEditSegment> leftTypeSegments,
    ImmutableList<InlineEditSegment> rightTypeSegments,
    boolean commentChanged) {

  public LeanAlignedField {
    checkNotNull(status, "status");
    leftTypeSegments = ImmutableList.copyOf(checkNotNull(leftTypeSegments, "leftTypeSegments"));
    rightTypeSegments = ImmutableList.copyOf(checkNotNull(rightTypeSegments, "rightTypeSegments"));
    switch (status) {
      case UNCHANGED, MODIFIED -> {
        checkNotNull(leftField, "leftField");
        checkNotNull(rightField, "rightField");
      }
      case DELETED -> {
        checkNotNull(leftField, "leftField");
        checkArgument(rightField == null, "deleted field cannot have rightField");
      }
      case ADDED -> {
        checkNotNull(rightField, "rightField");
        checkArgument(leftField == null, "added field cannot have leftField");
      }
    }
  }
}
