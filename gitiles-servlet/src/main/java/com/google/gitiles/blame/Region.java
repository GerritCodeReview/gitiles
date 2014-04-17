// Copyright (C) 2014 Google Inc. All Rights Reserved.
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

package com.google.gitiles.blame;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.base.Objects;

import org.eclipse.jgit.blame.BlameResult;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;

import java.io.Serializable;
import java.util.List;

/**
 * Region of the blame of a file.
 * <p>
 * Each region must be contained in a {@link RegionList}.
 */
public class Region implements Serializable {
  private static final long serialVersionUID = 1L;

  transient RegionList list;
  private final int commitIdx;
  private final int authorIdx;
  private final int pathIdx;
  private int count;

  public Region(RegionList list, int ci, int ai, int pi) {
    this.list = checkNotNull(list, "list");
    commitIdx = ci;
    authorIdx = ai;
    pathIdx = pi;
    count = 1;
  }

  public int getCount() {
    return count;
  }

  public ObjectId getSourceCommit() {
    return getOrNull(list.commits, commitIdx);
  }

  public PersonIdent getSourceAuthor() {
    return getOrNull(list.authors, authorIdx);
  }

  public String getSourcePath() {
    return getOrNull(list.paths, pathIdx);
  }

  // Path and commit lists are passed in because this is called from the
  // RegionList constructor before it's fully initialized.
  boolean growFrom(BlameResult blame, int i, List<String> paths, List<ObjectId> commits) {
    // Don't compare line numbers, so we collapse regions from the same source
    // but with deleted lines into one.
    if (Objects.equal(blame.getSourcePath(i), getOrNull(paths, pathIdx))
        && Objects.equal(blame.getSourceCommit(i), getOrNull(commits, commitIdx))) {
      count++;
      return true;
    } else {
      return false;
    }
  }

  @Override
  public String toString() {
    String unknown = "<unknwn>";
    StringBuilder sb = new StringBuilder();
    if (getSourceCommit() != null) {
      sb.append(getSourceCommit().name().substring(0, 8));
    } else {
      sb.append(unknown);
    }
    sb.append(" (");
    if (getSourceAuthor() != null) {
      sb.append(getSourceAuthor().toExternalString());
    } else {
      sb.append(unknown);
    }
    sb.append(") [");
    if (getSourcePath() != null) {
      sb.append(getSourcePath());
    } else {
      sb.append(unknown);
    }
    sb.append("]");
    return sb.toString();
  }

  private static <T> T getOrNull(List<T> list, int i) {
    return i >= 0 ? list.get(i) : null;
  }
}
