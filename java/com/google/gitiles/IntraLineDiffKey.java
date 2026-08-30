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

import org.eclipse.jgit.lib.ObjectId;

/**
 * Cache key for an intraline diff: the two blob object ids being diffed.
 *
 * <p>Gitiles-specific — a blob pair only, because Gitiles has no whitespace diff preference, so the
 * pair fully determines the intraline result. This is <em>not</em> Gerrit's key, which additionally
 * carries a whitespace dimension.
 */
public final class IntraLineDiffKey {
  private final ObjectId blobA;
  private final ObjectId blobB;

  /**
   * Creates a key for the intraline diff between two blobs.
   *
   * @param blobA the old-side (pre-image) blob id
   * @param blobB the new-side (post-image) blob id
   */
  public IntraLineDiffKey(ObjectId blobA, ObjectId blobB) {
    this.blobA = blobA.copy();
    this.blobB = blobB.copy();
  }

  /** Returns the old-side (pre-image) blob id — {@code DiffEntry.getOldId()}. */
  public ObjectId blobA() {
    return blobA;
  }

  /** Returns the new-side (post-image) blob id — {@code DiffEntry.getNewId()}. */
  public ObjectId blobB() {
    return blobB;
  }

  @Override
  public boolean equals(Object o) {
    if (o instanceof IntraLineDiffKey) {
      IntraLineDiffKey k = (IntraLineDiffKey) o;
      return blobA.equals(k.blobA) && blobB.equals(k.blobB);
    }
    return false;
  }

  @Override
  public int hashCode() {
    return blobA.hashCode() * 31 + blobB.hashCode();
  }

  @Override
  public String toString() {
    return "IntraLineDiffKey{" + blobA.name() + " -> " + blobB.name() + "}";
  }
}
