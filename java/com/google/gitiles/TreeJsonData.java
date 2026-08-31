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

package com.google.gitiles;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.collect.Lists;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import org.eclipse.jgit.annotations.Nullable;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.treewalk.TreeWalk;

class TreeJsonData {
  static class Tree {
    String id;
    List<Entry> entries;

    /** Set when {@code filter} matched more entries than {@code limit} allowed. */
    @Nullable Boolean truncated;
  }

  static class Entry {
    int mode;
    String type;
    String id;
    String name;

    @Nullable String target;
    @Nullable Long size;
  }

  static Tree toJsonData(ObjectId id, TreeWalk tw, boolean includeSizes, boolean recursive)
      throws IOException {
    return toJsonData(id, tw, includeSizes, recursive, null, Integer.MAX_VALUE);
  }

  /**
   * Serializes a tree walk.
   *
   * @param filter if non-null, only entries whose name contains it (ignoring case) are returned.
   *     Case folding is applied here, so callers need not normalize it.
   * @param limit maximum number of entries to return; only applied when {@code filter} is set, so
   *     that unfiltered listings keep their existing unbounded behavior.
   */
  static Tree toJsonData(
      ObjectId id,
      TreeWalk tw,
      boolean includeSizes,
      boolean recursive,
      @Nullable String filter,
      int limit)
      throws IOException {
    String needle = filter != null ? filter.toLowerCase(Locale.ROOT) : null;
    Tree tree = new Tree();
    tree.id = id.name();
    tree.entries = Lists.newArrayList();
    while (tw.next()) {
      FileMode mode = tw.getFileMode(0);
      String name = recursive ? tw.getPathString() : tw.getNameString();
      if (needle != null) {
        if (!name.toLowerCase(Locale.ROOT).contains(needle)) {
          continue;
        }
        if (tree.entries.size() >= limit) {
          tree.truncated = true;
          break;
        }
      }

      Entry e = new Entry();
      e.mode = mode.getBits();
      e.type = Constants.typeString(mode.getObjectType());
      e.id = tw.getObjectId(0).name();
      e.name = name;

      if (includeSizes) {
        if ((mode.getBits() & FileMode.TYPE_MASK) == FileMode.TYPE_FILE) {
          e.size = tw.getObjectReader().getObjectSize(tw.getObjectId(0), Constants.OBJ_BLOB);
        } else if ((mode.getBits() & FileMode.TYPE_MASK) == FileMode.TYPE_SYMLINK) {
          e.target =
              new String(tw.getObjectReader().open(tw.getObjectId(0)).getCachedBytes(), UTF_8);
        }
      }
      tree.entries.add(e);
    }
    return tree;
  }

  private TreeJsonData() {}
}
