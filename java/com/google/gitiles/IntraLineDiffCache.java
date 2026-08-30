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

package com.google.gitiles;

import com.google.gitiles.diff.IntraLineDiffResult;
import java.util.concurrent.Callable;

/**
 * Get-or-compute cache for intraline diff results, keyed by blob pair.
 *
 * <p>Standalone Gitiles uses {@link #DIRECT} (no cache) by default. A downstream module — the
 * Gitiles Gerrit plugin — can override the binding with a real implementation (for example one
 * backed by Gerrit's persistent cache layer). Implementations MUST store only results where {@link
 * IntraLineDiffResult#isCacheable()} is true, so a timeout/error fallback is never cached as if it
 * were a successful result, and SHOULD load atomically per key.
 */
public interface IntraLineDiffCache {
  /**
   * Returns the cached result for {@code key}, computing it via {@code loader} on a miss and (only
   * if {@link IntraLineDiffResult#isCacheable()}) storing it.
   */
  IntraLineDiffResult get(IntraLineDiffKey key, Callable<IntraLineDiffResult> loader)
      throws Exception;

  /** No-op cache: always computes via the loader, never stores. */
  IntraLineDiffCache DIRECT = (key, loader) -> loader.call();
}
