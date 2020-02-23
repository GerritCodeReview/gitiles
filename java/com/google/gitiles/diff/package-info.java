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

/**
 * Intraline (within-line) diff support, published as the {@code com.google.gitiles:intraline-diff}
 * Maven artifact and shared between Gitiles and Gerrit.
 *
 * <p>{@link com.google.gitiles.diff.IntraLineDiff#compute} refines the line-level edits between two
 * texts into character-level edits, wrapping each replaced line range in a {@link
 * com.google.gitiles.diff.ReplaceEdit}. The offsets in a {@code ReplaceEdit}'s internal edits index
 * into the characters of the replaced region, not the whole text.
 *
 * <p>The character diff is O(N*D) and is <em>not</em> bounded internally; each caller is
 * responsible for bounding its runtime (both Gitiles and Gerrit wrap it in a timeout).
 */
package com.google.gitiles.diff;
