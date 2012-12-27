// Copyright 2012 Google Inc. All Rights Reserved.
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

import static com.google.gitiles.RevisionParser.PATH_SPLITTER;

import static org.eclipse.jgit.lib.Constants.OBJ_COMMIT;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Charsets;
import com.google.common.base.Objects;
import com.google.common.collect.AbstractIterator;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.io.Files;
import com.google.gitiles.PathServlet.FileType;

import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;

import java.io.IOException;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.StringTokenizer;

/** Soy data converter for git trees. */
public class TreeSoyData {
  /**
   * Number of characters to display for a symlink target. Targets longer than
   * this are abbreviated for display in a tree listing.
   */
  private static final int MAX_SYMLINK_TARGET_LENGTH = 72;

  /**
   * Maximum number of bytes to load from a blob that claims to be a symlink. If
   * the blob is larger than this byte limit it will be displayed as a binary
   * file instead of as a symlink.
   */
  static final int MAX_SYMLINK_SIZE = 16 << 10;

  public static class Entry {
    private final FileType type;
    private final String name;
    private final ObjectId id;

    public Entry(TreeWalk tw) {
      this.type = FileType.forEntry(tw);
      this.name = tw.getNameString();
      this.id = tw.getObjectId(0);
    }

    public FileType getType() {
      return type;
    }

    public String getName() {
      return name;
    }

    public ObjectId getId() {
      return id;
    }

    @Override
    public String toString() {
      return Objects.toStringHelper(this)
          .add("type", type)
          .add("name", name)
          .add("id", id)
          .toString();
    }
  }

  public static Entry newEntry(TreeWalk tw) throws MissingObjectException, IOException {
    return tw.next() ? new Entry(tw) : null;
  }

  static String resolveTargetUrl(GitilesView view, String target) {
    if (target.startsWith("/")) {
      return null;
    }

    // simplifyPath() normalizes "a/../../" to "a", so manually check whether
    // the path leads above the git root.
    String path = Objects.firstNonNull(view.getTreePath(), "");
    int depth = new StringTokenizer(path, "/").countTokens();
    for (String part : PATH_SPLITTER.split(target)) {
      if (part.equals("..")) {
        depth--;
        if (depth < 0) {
          return null;
        }
      } else if (!part.isEmpty() && !part.equals(".")) {
        depth++;
      }
    }

    path = Files.simplifyPath(view.getTreePath() + "/" + target);
    return GitilesView.path().copyFrom(view).setTreePath(!path.equals(".") ? path : "").toUrl();
  }

  @VisibleForTesting
  static String getTargetDisplayName(String target) {
    if (target.length() <= MAX_SYMLINK_TARGET_LENGTH) {
      return target;
    } else {
      int lastSlash = target.lastIndexOf('/');
      // TODO(dborowitz): Doesn't abbreviate a long last path component.
      return lastSlash >= 0 ? "..." + target.substring(lastSlash) : target;
    }
  }

  private final RevWalk rw;
  private final GitilesView view;

  public TreeSoyData(RevWalk rw, GitilesView view) {
    this.rw = rw;
    this.view = view;
  }

  public Map<String, Object> toSoyData(ObjectId treeId) throws MissingObjectException, IOException {
    TreeWalk tw = new TreeWalk(rw.getObjectReader());
    tw.addTree(treeId);
    tw.setRecursive(false);
    return toSoyData(treeId, tw);
  }

  static Iterable<Entry> iterEntries(final TreeWalk tw) {
    return new Iterable<Entry>() {
      @Override
      public Iterator<Entry> iterator() {
        return new AbstractIterator<Entry>() {
          @Override
          protected Entry computeNext() {
            try {
              Entry result = newEntry(tw);
              if (result == null) {
                endOfData();
              }
              return result;
            } catch (MissingObjectException e) {
              throw new RuntimeException(e);
            } catch (IOException e) {
              throw new RuntimeException(e);
            }
          }
        };
      }
    };
  }

  public Map<String, Object> toSoyData(ObjectId treeId, final TreeWalk tw)
      throws MissingObjectException, IOException {
    return toSoyData(treeId, iterEntries(tw));
  }

  public Map<String, Object> toSoyData(ObjectId treeId, Iterable<Entry> entries)
      throws MissingObjectException, IOException {
    List<Object> soyEntries = Lists.newArrayList();
    GitilesView.Builder urlBuilder = GitilesView.path().copyFrom(view);

    for (Entry entry : entries) {
      soyEntries.add(toSoyData(entry, urlBuilder));
    }

    Map<String, Object> data = Maps.newHashMapWithExpectedSize(3);
    data.put("sha", treeId.name());
    data.put("entries", soyEntries);

    if (view.getType() == GitilesView.Type.PATH
        && view.getRevision().getPeeledType() == OBJ_COMMIT) {
      data.put("logUrl", GitilesView.log().copyFrom(view).toUrl());
    }

    return data;
  }

  private Map<String, String> toSoyData(Entry entry, GitilesView.Builder urlBuilder)
      throws MissingObjectException, IOException {
    switch (view.getType()) {
      case PATH:
        urlBuilder.setTreePath(view.getTreePath() + "/" + entry.name);
        break;
      case REVISION:
        // Got here from a tag pointing at a tree.
        urlBuilder.setTreePath(entry.name);
        break;
      default:
        throw new IllegalStateException(String.format(
            "Cannot render TreeSoyData from %s view", view.getType()));
    }

    String name = entry.name;
    String url = urlBuilder.toUrl();
    if (entry.type == FileType.TREE) {
      name += "/";
      url += "/";
    }
    Map<String, String> data = Maps.newHashMapWithExpectedSize(4);
    data.put("type", entry.type.toString());
    data.put("name", name);
    data.put("url", url);
    if (entry.type == FileType.SYMLINK) {
      String target = new String(
          rw.getObjectReader().open(entry.id).getCachedBytes(),
          Charsets.UTF_8);
      data.put("targetName", getTargetDisplayName(target));
      String targetUrl = resolveTargetUrl(view, target);
      if (targetUrl != null) {
        data.put("targetUrl", targetUrl);
      }
    }
    return data;
  }
}
