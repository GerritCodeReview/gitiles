// Copyright (C) 2026 Google Inc. All Rights Reserved.
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

import com.google.common.base.Splitter;
import com.google.gitiles.PathServlet.FileType;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.treewalk.TreeWalk;

/**
 * Soy data for the file navigation sidebar shown next to file pages.
 *
 * <p>The sidebar lists the root of the tree and every directory on the way to the current file, so
 * the file's location is visible at any depth without client-side rendering. Each directory lists
 * at most {@code gitiles.fileNavigationMaxEntries} entries, always including the entry on the
 * current path, and links to the full tree page for the rest.
 */
class FileNavigationSoyData {
  static final int DEFAULT_MAX_ENTRIES = 500;

  /** Returns whether the sidebar is enabled by {@code gitiles.fileNavigation}. */
  static boolean isEnabled(Config cfg) {
    return cfg.getBoolean("gitiles", null, "fileNavigation", false);
  }

  static int getMaxEntries(Config cfg) {
    int max = cfg.getInt("gitiles", null, "fileNavigationMaxEntries", DEFAULT_MAX_ENTRIES);
    return max > 0 ? max : DEFAULT_MAX_ENTRIES;
  }

  private static class Entry {
    final String name;
    final FileType type;
    final ObjectId id;
    boolean onPath;

    Entry(String name, FileType type, ObjectId id) {
      this.name = name;
      this.type = type;
      this.id = id;
    }
  }

  private final ObjectReader reader;
  private final GitilesView view;
  private final int maxEntries;

  FileNavigationSoyData(ObjectReader reader, GitilesView view, int maxEntries) {
    this.reader = reader;
    this.view = view;
    this.maxEntries = maxEntries;
  }

  /** Returns the root listing, with each directory on the path to the current file expanded. */
  Map<String, Object> toSoyData(ObjectId root) throws IOException {
    String path = TreeSoyData.stripEndingSolidus(view.getPathPart());
    return listTree(root, "", Splitter.on('/').splitToList(path));
  }

  /**
   * Lists the tree {@code treeId} at {@code dir}, where {@code remaining} is the rest of the
   * current path below {@code dir}.
   */
  private Map<String, Object> listTree(ObjectId treeId, String dir, List<String> remaining)
      throws IOException {
    String activeName = remaining.get(0);
    boolean activeIsDir = remaining.size() > 1;
    List<Entry> all = new ArrayList<>();
    try (TreeWalk tw = new TreeWalk(reader)) {
      tw.addTree(treeId);
      while (tw.next()) {
        Entry e = new Entry(tw.getNameString(), FileType.forEntry(tw), tw.getObjectId(0));
        all.add(e);
        e.onPath = e.name.equals(activeName) && (e.type == FileType.TREE) == activeIsDir;
      }
    }
    all.sort(
        (a, b) -> TreeSoyData.compareByTypeAlpha(a.type.name(), a.name, b.type.name(), b.name));

    List<Entry> shown = all;
    if (all.size() > maxEntries) {
      shown = new ArrayList<>(all.subList(0, maxEntries));
      for (int i = maxEntries; i < all.size(); i++) {
        if (all.get(i).onPath) {
          // Keep the current path visible even when it sorts past the limit.
          shown.set(maxEntries - 1, all.get(i));
          break;
        }
      }
    }

    List<Map<String, Object>> entries = new ArrayList<>(shown.size());
    for (Entry e : shown) {
      String entryPath = dir.isEmpty() ? e.name : dir + "/" + e.name;
      String url = GitilesView.path().copyFrom(view).setPathPart(entryPath).toUrl();
      Map<String, Object> entry = new HashMap<>();
      entry.put("type", e.type.toString());
      if (e.type == FileType.TREE) {
        entry.put("name", e.name + "/");
        entry.put("url", url + "/");
      } else {
        entry.put("name", e.name);
        entry.put("url", url);
      }
      if (e.onPath) {
        if (activeIsDir) {
          entry.put("children", listTree(e.id, entryPath, remaining.subList(1, remaining.size())));
        } else {
          entry.put("current", true);
        }
      }
      entries.add(entry);
    }

    Map<String, Object> data = new HashMap<>();
    data.put("entries", entries);
    if (shown.size() < all.size()) {
      data.put("moreCount", all.size() - shown.size());
      data.put("moreUrl", treeUrl(dir));
    }
    return data;
  }

  private String treeUrl(String dir) {
    String url = GitilesView.path().copyFrom(view).setPathPart(dir).toUrl();
    return url.endsWith("/") ? url : url + "/";
  }
}
