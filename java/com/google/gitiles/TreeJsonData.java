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
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
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
   *
   * <p>This declares the response shape. {@link Source} produces it.
   */
  static class PathList {
    String id;
    List<String> paths;

    /**
     * Serialization source for a {@link PathList}, backed by a live walk.
     *
     * <p>The listing is always complete. A partial listing would be indistinguishable to a caller
     * from a path that does not exist, so there is no bound on the number of paths returned. This
     * is strictly cheaper than the full recursive listing produced by {@link
     * TreeJsonData#toJsonData}, which is itself unbounded.
     *
     * <p>Because it is unbounded, paths are written as the walk yields them rather than collected
     * first; a 500k-path tree would otherwise hold tens of megabytes of strings per concurrent
     * request. The trade is that the response is already partly written if the walk fails, so an
     * object store error arrives as truncated JSON rather than an error status. Callers parse the
     * body, so truncation fails loudly.
     *
     * <p>Single use: serializing consumes the walk.
     */
    static class Source {
      private final ObjectId id;
      private final TreeWalk tw;

      /**
       * @param id object ID of the tree being walked.
       * @param tw recursive tree walk, positioned before the first entry.
       */
      Source(ObjectId id, TreeWalk tw) {
        this.id = id;
        this.tw = tw;
      }
    }

    /** Writes a {@link Source} in exactly the {@link PathList} shape. */
    static final TypeAdapter<Source> SOURCE_ADAPTER =
        new TypeAdapter<Source>() {
          @Override
          public void write(JsonWriter out, Source src) throws IOException {
            out.beginObject();
            out.name("id").value(src.id.name());
            out.name("paths").beginArray();
            while (src.tw.next()) {
              if (src.tw.getFileMode(0).getObjectType() == Constants.OBJ_BLOB) {
                out.value(src.tw.getPathString());
              }
            }
            out.endArray();
            out.endObject();
          }

          @Override
          public Source read(JsonReader in) {
            throw new UnsupportedOperationException();
          }
        };
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

  private TreeJsonData() {}
}
