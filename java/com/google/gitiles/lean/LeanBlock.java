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

import static java.util.Objects.requireNonNull;

import com.google.common.collect.ImmutableList;
import javax.annotation.Nullable;

/** A top-level structural block in a parsed Lean 4 source file. */
public record LeanBlock(
    String id,
    LeanBlockType type,
    String raw,
    int startLine,
    int endLine,
    @Nullable String markdownContent,
    ImmutableList<LeanCodeLine> importLines,
    @Nullable String directiveKind,
    ImmutableList<LeanCodeLine> directiveLines,
    ImmutableList<LeanCodeLine> commentLines,
    boolean isLicenseHeader,
    @Nullable LeanDeclaration decl) {
  public LeanBlock {
    requireNonNull(id, "id");
    requireNonNull(type, "type");
    requireNonNull(raw, "raw");
    importLines = ImmutableList.copyOf(importLines);
    directiveLines = ImmutableList.copyOf(directiveLines);
    commentLines = ImmutableList.copyOf(commentLines);
  }
}
