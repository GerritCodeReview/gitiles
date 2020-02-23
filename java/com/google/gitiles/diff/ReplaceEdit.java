// Copyright (C) 2010 The Android Open Source Project
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

package com.google.gitiles.diff;

import static com.google.common.base.Preconditions.checkArgument;

import com.google.common.collect.ImmutableList;
import java.util.List;
import org.eclipse.jgit.diff.Edit;

/**
 * An {@link Edit} of type {@link Edit.Type#REPLACE} that also carries the character-level edits
 * within the replaced region, as computed by {@link IntraLineDiff}.
 *
 * <p>The internal edits index into the characters of the replaced region (not the whole text), so
 * callers can highlight exactly which characters changed within the replaced lines.
 */
public final class ReplaceEdit extends Edit {
  private final ImmutableList<Edit> internalEdits;

  /**
   * Creates a replace edit spanning the given pre-image and post-image line ranges.
   *
   * @param as begin line in the pre-image (inclusive)
   * @param ae end line in the pre-image (exclusive)
   * @param bs begin line in the post-image (inclusive)
   * @param be end line in the post-image (exclusive)
   * @param internalEdits character-level edits within the replaced region
   */
  public ReplaceEdit(int as, int ae, int bs, int be, List<Edit> internalEdits) {
    super(as, ae, bs, be);
    checkArgument(getType() == Edit.Type.REPLACE, "not a replace edit: %s", this);
    this.internalEdits = ImmutableList.copyOf(internalEdits);
  }

  /**
   * Creates a replace edit covering the same range as {@code edit}.
   *
   * @param edit the line-level edit whose range this replace edit adopts
   * @param internalEdits character-level edits within the replaced region
   */
  public ReplaceEdit(Edit edit, List<Edit> internalEdits) {
    this(edit.getBeginA(), edit.getEndA(), edit.getBeginB(), edit.getEndB(), internalEdits);
  }

  /** Returns the character-level edits within the replaced region. */
  public ImmutableList<Edit> getInternalEdits() {
    return internalEdits;
  }
}
