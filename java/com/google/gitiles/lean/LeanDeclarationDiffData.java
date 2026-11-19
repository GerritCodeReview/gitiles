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

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.collect.ImmutableList;
import javax.annotation.Nullable;

/** Structured diff breakdown for a modified pair of Lean 4 declarations. */
public record LeanDeclarationDiffData(
    @Nullable String leftDoc,
    @Nullable String rightDoc,
    boolean docChanged,
    ImmutableList<LeanAlignedCodeLine> signatureLines,
    boolean signatureChanged,
    ImmutableList<LeanAlignedCodeLine> bodyLines,
    boolean bodyChanged,
    @Nullable ImmutableList<LeanAlignedField> fields,
    boolean fieldsChanged) {

  public LeanDeclarationDiffData {
    signatureLines = ImmutableList.copyOf(checkNotNull(signatureLines, "signatureLines"));
    bodyLines = ImmutableList.copyOf(checkNotNull(bodyLines, "bodyLines"));
    fields = fields == null ? null : ImmutableList.copyOf(fields);
  }
}
