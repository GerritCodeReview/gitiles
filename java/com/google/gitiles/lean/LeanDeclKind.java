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

import com.google.common.collect.ImmutableMap;
import java.util.Optional;
import javax.annotation.Nullable;

/** Lean 4 declaration keyword kinds. */
public enum LeanDeclKind {
  THEOREM("theorem"),
  LEMMA("lemma"),
  EXAMPLE("example"),
  DEF("def"),
  ABBREV("abbrev"),
  STRUCTURE("structure"),
  CLASS("class"),
  INDUCTIVE("inductive"),
  COINDUCTIVE("coinductive"),
  INSTANCE("instance"),
  AXIOM("axiom"),
  OPAQUE("opaque");

  private static final ImmutableMap<String, LeanDeclKind> BY_KEYWORD;

  static {
    ImmutableMap.Builder<String, LeanDeclKind> builder = ImmutableMap.builder();
    for (LeanDeclKind kind : values()) {
      builder.put(kind.keyword, kind);
    }
    BY_KEYWORD = builder.buildOrThrow();
  }

  private final String keyword;

  LeanDeclKind(String keyword) {
    this.keyword = keyword;
  }

  /** Returns the lowercase Lean 4 keyword for this declaration kind. */
  public String keyword() {
    return keyword;
  }

  /** Returns the declaration kind matching {@code keyword}, or empty if unrecognized. */
  public static Optional<LeanDeclKind> fromKeyword(@Nullable String keyword) {
    if (keyword == null) {
      return Optional.empty();
    }
    return Optional.ofNullable(BY_KEYWORD.get(keyword));
  }
}
