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

import javax.annotation.Nullable;

/** Parsed constructor of a Lean {@code inductive} or {@code coinductive} declaration. */
public record LeanInductiveCtor(
    String name, String signatureText, @Nullable String comment, int lineNum, String rawLine) {
  public LeanInductiveCtor {
    requireNonNull(name, "name");
    requireNonNull(signatureText, "signatureText");
    requireNonNull(rawLine, "rawLine");
  }
}
