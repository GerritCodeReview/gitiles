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
import org.eclipse.jgit.annotations.Nullable;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.treewalk.TreeWalk;

class TreeJsonData {
  static class Tree {
    String id;
    List<Entry> entries;
  }

  static class Entry {
    int mode;
    String type;
    String id;
    String name;

    @Nullable String target;
    @Nullable Long size;
  }

  /**
   * Flat list of blob paths under a tree.
   *
   * <p>Unlike {@link Tree} this omits per-entry mode, type and object ID, which shrinks the
   * response by roughly 3x for large trees. Intended for clients that only need path names, such
   * as a file finder.
   */
  static class PathList {
    String id;
    List<String> paths;
  }

  static Tree toJsonData(ObjectId id, TreeWalk tw, boolean includeSizes, boolean recursive)
      throws IOException {
    Tree tree = new Tree();
    tree.id = id.name();
    tree.entries = Lists.newArrayList();
    while (tw.next()) {
      Entry e = new Entry();
      FileMode mode = tw.getFileMode(0);
      e.mode = mode.getBits();
      e.type = Constants.typeString(mode.getObjectType());
      e.id = tw.getObjectId(0).name();
      e.name = recursive ? tw.getPathString() : tw.getNameString();

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

  /**
   * Collect every blob path from an already-recursive {@link TreeWalk}.
   *
   * <p>The listing is always complete. A partial listing would be indistinguishable to a caller
   * from a path that does not exist, so there is no bound on the number of paths returned. This is
   * strictly cheaper than the full recursive listing produced by {@link #toJsonData}, which is
   * itself unbounded.
   *
   * @param id object ID of the tree being walked.
   * @param tw recursive tree walk, positioned before the first entry.
   */
  static PathList toPathsJsonData(ObjectId id, TreeWalk tw) throws IOException {
    PathList result = new PathList();
    result.id = id.name();
    result.paths = Lists.newArrayList();
    while (tw.next()) {
      result.paths.add(tw.getPathString());
    }
    return result;
  }

  private TreeJsonData() {}
}
