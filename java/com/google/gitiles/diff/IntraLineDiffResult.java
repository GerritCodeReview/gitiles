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

package com.google.gitiles.diff;

import com.google.common.collect.ImmutableList;
import java.util.List;
import org.eclipse.jgit.diff.Edit;

/**
 * Result of an intraline (within-line) diff computation, carrying an explicit {@link Status}
 * alongside the edits.
 *
 * <p>The status distinguishes a real intraline result ({@link Status#EDIT_LIST}) from a line-level
 * fallback produced when the computation timed out or failed. Only {@code EDIT_LIST} results are
 * safe to cache; a timeout or error must never be persisted as if it were a successful "no marks"
 * result.
 */
public final class IntraLineDiffResult {
  /** Outcome of the computation. */
  public enum Status {
    /**
     * Successful intraline result; {@link #edits()} are line-level edits refined with {@link
     * ReplaceEdit} entries carrying character-level internal edits.
     */
    EDIT_LIST,
    /** Computation exceeded its time budget; {@link #edits()} are the line-level fallback. */
    TIMEOUT,
    /** Computation failed; {@link #edits()} are the line-level fallback. */
    ERROR,
    /** Intraline diff was not attempted; {@link #edits()} are the plain line-level edits. */
    DISABLED
  }

  /** Returns a successful (cacheable) result wrapping {@code edits}. */
  public static IntraLineDiffResult editList(List<Edit> edits) {
    return new IntraLineDiffResult(Status.EDIT_LIST, edits);
  }

  /** Returns a timeout result carrying the line-level {@code fallback} edits. */
  public static IntraLineDiffResult timeout(List<Edit> fallback) {
    return new IntraLineDiffResult(Status.TIMEOUT, fallback);
  }

  /** Returns an error result carrying the line-level {@code fallback} edits. */
  public static IntraLineDiffResult error(List<Edit> fallback) {
    return new IntraLineDiffResult(Status.ERROR, fallback);
  }

  /** Returns a disabled result carrying the plain line-level {@code edits}. */
  public static IntraLineDiffResult disabled(List<Edit> edits) {
    return new IntraLineDiffResult(Status.DISABLED, edits);
  }

  private final Status status;
  private final ImmutableList<Edit> edits;

  private IntraLineDiffResult(Status status, List<Edit> edits) {
    this.status = status;
    // jgit Edit is mutable (extend/shift/swap), so deep-copy: this value is the shared "safe to
    // cache" contract and must be isolated from the caller's edits and from callers mutating what
    // was handed back after it was stored.
    this.edits = deepCopy(edits);
  }

  /** Returns the outcome of the computation. */
  public Status status() {
    return status;
  }

  /**
   * Returns a defensive deep copy of the edits: line-level edits refined with {@link ReplaceEdit}
   * internal character edits when {@link #status()} is {@link Status#EDIT_LIST}, otherwise the
   * plain line-level fallback.
   */
  public ImmutableList<Edit> edits() {
    return deepCopy(edits);
  }

  /** Deep-copies edits, preserving {@link ReplaceEdit} and recursively copying its internals. */
  private static ImmutableList<Edit> deepCopy(List<Edit> edits) {
    ImmutableList.Builder<Edit> copy = ImmutableList.builderWithExpectedSize(edits.size());
    for (Edit e : edits) {
      if (e instanceof ReplaceEdit) {
        ReplaceEdit re = (ReplaceEdit) e;
        copy.add(
            new ReplaceEdit(
                re.getBeginA(),
                re.getEndA(),
                re.getBeginB(),
                re.getEndB(),
                deepCopy(re.getInternalEdits())));
      } else {
        copy.add(new Edit(e.getBeginA(), e.getEndA(), e.getBeginB(), e.getEndB()));
      }
    }
    return copy.build();
  }

  /** Returns whether this is a successful intraline result that is safe to cache. */
  public boolean isCacheable() {
    return status == Status.EDIT_LIST;
  }
}
