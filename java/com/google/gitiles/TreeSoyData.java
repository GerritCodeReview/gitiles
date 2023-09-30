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

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.eclipse.jgit.lib.Constants.OBJ_COMMIT;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.gitiles.PathServlet.FileType;
import com.google.gitiles.doc.MarkdownConfig;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.treewalk.TreeWalk;

/** Soy data converter for git trees. */
public class TreeSoyData {
  /**
   * Number of characters to display for a symlink target. Targets longer than this are abbreviated
   * for display in a tree listing.
   */
  private static final int MAX_SYMLINK_TARGET_LENGTH = 72;

  private static final Map<String, Integer> TYPE_WEIGHT =
      Map.of(
          "TREE", 0,
          "GITLINK", 1,
          "SYMLINK", 2,
          "REGULAR_FILE", 3,
          "EXECUTABLE_FILE", 3);

  /**
   * Maximum number of bytes to load from a blob that claims to be a symlink. If the blob is larger
   * than this byte limit it will be displayed as a binary file instead of as a symlink.
   */
  static final int MAX_SYMLINK_SIZE = 16 << 10;

  static @Nullable String resolveTargetUrl(GitilesView view, String target) {
    String resolved = PathUtil.simplifyPathUpToRoot(target, view.getPathPart());
    if (resolved == null) {
      return null;
    }
    return GitilesView.path().copyFrom(view).setPathPart(resolved).toUrl();
  }

  @VisibleForTesting
  static String getTargetDisplayName(String target) {
    if (target.length() <= MAX_SYMLINK_TARGET_LENGTH) {
      return target;
    }
    int lastSlash = target.lastIndexOf('/');
    // TODO(dborowitz): Doesn't abbreviate a long last path component.
    return lastSlash >= 0 ? "..." + target.substring(lastSlash) : target;
  }

  static String stripEndingSolidus(String p) {
    return p.endsWith("/") ? p.substring(0, p.length() - 1) : p;
  }

  static int sortByTypeAlpha(Map<String, String> m1, Map<String, String> m2) {
    int weightDiff = TYPE_WEIGHT.get(m1.get("type")).compareTo(TYPE_WEIGHT.get(m2.get("type")));
    if (weightDiff == 0) {
      String s1 = m1.get("name");
      String s2 = m2.get("name");
      if (m1.get("type").equals("TREE")) {
        s1 = stripEndingSolidus(s1);
        s2 = stripEndingSolidus(s2);
      }
      return s1.compareToIgnoreCase(s2);
    }
    return weightDiff;
  }

  private final ObjectReader reader;
  private final GitilesView view;
  private final Config cfg;
  private final RevTree rootTree;
  private final String requestUri;
  private ArchiveFormat archiveFormat;

  public TreeSoyData(
      ObjectReader reader, GitilesView view, Config cfg, RevTree rootTree, String requestUri) {
    this.reader = reader;
    this.view = view;
    this.cfg = cfg;
    this.rootTree = rootTree;
    this.requestUri = requestUri;
  }

  public TreeSoyData setArchiveFormat(ArchiveFormat archiveFormat) {
    this.archiveFormat = archiveFormat;
    return this;
  }

  public Map<String, Object> toSoyData(ObjectId treeId, TreeWalk tw)
      throws MissingObjectException, IOException {
    ReadmeHelper readme =
        new ReadmeHelper(reader, view, MarkdownConfig.get(cfg), rootTree, requestUri);
    List<Map<String, String>> entries = Lists.newArrayList();
    GitilesView.Builder urlBuilder = GitilesView.path().copyFrom(view);
    while (tw.next()) {
      FileType type = FileType.forEntry(tw);
      String name = tw.getNameString();

      GitilesView.Type viewType = view.getType();
      if (viewType == GitilesView.Type.PATH) {
        urlBuilder.setPathPart(view.getPathPart() + "/" + name);
      } else if (viewType == GitilesView.Type.REVISION) {
        // Got here from a tag pointing at a tree.
        urlBuilder.setPathPart(name);
      } else {
        throw new IllegalStateException(
            String.format("Cannot render TreeSoyData from %s view", viewType));
      }

      String url = urlBuilder.toUrl();
      if (type == FileType.TREE) {
        name += "/";
        url += "/";
      }
      Map<String, String> entry = Maps.newHashMapWithExpectedSize(4);
      entry.put("type", type.toString());
      entry.put("name", name);
      entry.put("url", url);
      if (type == FileType.SYMLINK) {
        String target = new String(reader.open(tw.getObjectId(0)).getCachedBytes(), UTF_8);
        entry.put("targetName", getTargetDisplayName(target));
        String targetUrl = resolveTargetUrl(view, target);
        if (targetUrl != null) {
          entry.put("targetUrl", targetUrl);
        }
      } else {
        readme.considerEntry(tw);
      }
      entries.add(entry);
    }

    entries.sort(TreeSoyData::sortByTypeAlpha);

    Map<String, Object> data = Maps.newHashMapWithExpectedSize(3);
    data.put("sha", treeId.name());
    data.put("entries", entries);

    if (view.getType() == GitilesView.Type.PATH
        && view.getRevision().getPeeledType() == OBJ_COMMIT) {
      data.put("logUrl", GitilesView.log().copyFrom(view).toUrl());
      data.put(
          "archiveUrl",
          GitilesView.archive()
              .copyFrom(view)
              .setPathPart(Strings.emptyToNull(view.getPathPart()))
              .setExtension(archiveFormat.getDefaultSuffix())
              .toUrl());
      data.put("archiveType", archiveFormat.getShortName());
    }

    if (readme.isPresent()) {
      data.put("readmePath", readme.getPath());
      data.put("readmeHtml", readme.render());
    }

    return data;
  }

  public Map<String, Object> toSoyData(ObjectId treeId) throws MissingObjectException, IOException {
    TreeWalk tw = new TreeWalk(reader);
    tw.addTree(treeId);
    tw.setRecursive(false);
    return toSoyData(treeId, tw);
  }
}
