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

import org.eclipse.jgit.annotations.Nullable;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.treewalk.TreeWalk;

import java.io.IOException;
import java.util.List;

class TreeJsonData {
  static class Tree {
    String id;
    List<Entry> entries;
  }

  // For testing.
  static class LongTree {
    String id;
    List<LongEntry> entries;
  }

  static class Entry {
    int mode;
    String type;
    String id;
    String name;
  }

  static class SizedEntry extends Entry {
    long size;
  }
  static class TargetEntry extends Entry {
    String target;
  }

  // For testing.
  static class LongEntry extends Entry {
    long size;
    String target;
  }

  static Tree toJsonData(ObjectId id, TreeWalk tw,
                         @Nullable Repository repoForSizes) throws IOException {
    ObjectReader reader = repoForSizes != null ? repoForSizes.newObjectReader() : null;
    Tree tree = new Tree();
    tree.id = id.name();
    tree.entries = Lists.newArrayList();
    while (tw.next()) {
      Entry e = new Entry();
      FileMode mode = tw.getFileMode(0);
      e.mode = mode.getBits();
      e.type = Constants.typeString(mode.getObjectType());
      e.id = tw.getObjectId(0).name();
      e.name = tw.getNameString();

      if (reader != null) {
        FileMode fmode = FileMode.fromBits(mode.getBits());
        if (fmode == FileMode.REGULAR_FILE || fmode == FileMode.EXECUTABLE_FILE) {
          SizedEntry se = new SizedEntry();
          se.id = e.id;
          se.mode = e.mode;
          se.name = e.name;
          se.type = e.type;
          se.size = reader.getObjectSize(tw.getObjectId(0), Constants.OBJ_BLOB);
          e = se;
        } else if (fmode == FileMode.SYMLINK) {
          TargetEntry se = new TargetEntry();
          se.id = e.id;
          se.mode = e.mode;
          se.name = e.name;
          se.type = e.type;
          se.target = new String(
                  repoForSizes.open(tw.getObjectId(0), Constants.OBJ_BLOB).getBytes(), UTF_8);
          e = se;
        }
      }
      tree.entries.add(e);
    }
    return tree;
  }

  private TreeJsonData() {
  }
}
