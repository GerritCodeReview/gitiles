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

/** Segment produced by intra-sequence diffing. */
public record InlineDiffSegment(String text, Type type) {
  /** Diff classification for an {@link InlineDiffSegment}. */
  public enum Type {
    COMMON,
    ADDED,
    DELETED
  }

  public InlineDiffSegment {
    checkNotNull(text, "text");
    checkNotNull(type, "type");
  }
}
