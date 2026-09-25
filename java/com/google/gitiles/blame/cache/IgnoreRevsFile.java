// Copyright 2026 Google LLC
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

package com.google.gitiles.blame.cache;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.base.CharMatcher;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Set;
import org.eclipse.jgit.lib.AnyObjectId;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;

/**
 * Revisions to ignore when computing blame, as listed in a file stored in the repository.
 *
 * <p>The file uses the format of the git {@code blame.ignoreRevsFile} option: one full commit SHA-1
 * per line, where {@code #} starts a comment that runs to the end of the line and blank lines are
 * skipped. By convention the file is named {@code .git-blame-ignore-revs} and is stored at the root
 * of the repository.
 *
 * <p>Unlike git, which rejects the whole file if any line is malformed, lines that do not start
 * with a full SHA-1 are skipped and text after the SHA-1 is ignored, so one bad line does not
 * disable the rest of the file.
 *
 * <p>Reading is bounded, since callers may read the file on every blame request: a file larger than
 * {@link #MAX_SIZE} bytes is ignored, and at most {@link #MAX_REVS} revisions are read.
 *
 * <p>The revisions can be passed to {@link BlameCache#get(Repository, ObjectId, String, Set)}.
 */
public final class IgnoreRevsFile {
  /** Conventional path of the file, relative to the root of the repository. */
  public static final String DEFAULT_PATH = ".git-blame-ignore-revs";

  /** Maximum size of the file in bytes; larger files are ignored. */
  public static final int MAX_SIZE = 1 << 20;

  /** Maximum number of revisions read from the file; any further revisions are dropped. */
  public static final int MAX_REVS = 20_000;

  private static final IgnoreRevsFile EMPTY = new IgnoreRevsFile(ImmutableSet.of(), false, false);

  // ObjectId.isId() is not enough: it accepts some non-ASCII characters that
  // ObjectId.fromString() then rejects with an exception.
  private static final CharMatcher HEX_DIGIT =
      CharMatcher.inRange('0', '9')
          .or(CharMatcher.inRange('a', 'f'))
          .or(CharMatcher.inRange('A', 'F'));

  /**
   * Reads the revisions listed in {@link #DEFAULT_PATH} at a commit.
   *
   * @see #read(Repository, AnyObjectId, String)
   */
  public static IgnoreRevsFile read(Repository repo, AnyObjectId commitId) throws IOException {
    return read(repo, commitId, DEFAULT_PATH);
  }

  /**
   * Reads the revisions listed in a file at a commit.
   *
   * @param repo repository to read from.
   * @param commitId commit whose tree contains the file.
   * @param path path of the file, relative to the root of the repository.
   * @return the revisions listed in the file. Empty if nothing exists at the path, if the path is
   *     not a regular file, or if the file is larger than {@link #MAX_SIZE} bytes.
   * @throws IOException if the commit or the file cannot be read.
   */
  public static IgnoreRevsFile read(Repository repo, AnyObjectId commitId, String path)
      throws IOException {
    String relativePath = CharMatcher.is('/').trimFrom(path);
    if (relativePath.isEmpty()) {
      return EMPTY;
    }
    try (RevWalk rw = new RevWalk(repo);
        TreeWalk tw =
            TreeWalk.forPath(rw.getObjectReader(), relativePath, rw.parseTree(commitId))) {
      // Only regular files; skip directories, symlinks and submodules.
      if (tw == null || (tw.getRawMode(0) & FileMode.TYPE_MASK) != FileMode.TYPE_FILE) {
        return EMPTY;
      }
      ObjectLoader loader = rw.getObjectReader().open(tw.getObjectId(0), Constants.OBJ_BLOB);
      if (loader.getSize() > MAX_SIZE) {
        return new IgnoreRevsFile(ImmutableSet.of(), true, false);
      }
      return parse(new String(loader.getCachedBytes(MAX_SIZE), UTF_8));
    }
  }

  /** Parses the content of a file. */
  static IgnoreRevsFile parse(String content) {
    Set<ObjectId> ids = new LinkedHashSet<>();
    for (String line : Splitter.on('\n').split(content)) {
      int comment = line.indexOf('#');
      String text = (comment >= 0 ? line.substring(0, comment) : line).trim();
      int end = CharMatcher.whitespace().indexIn(text);
      String name = end >= 0 ? text.substring(0, end) : text;
      if (name.length() != Constants.OBJECT_ID_STRING_LENGTH || !HEX_DIGIT.matchesAllOf(name)) {
        continue;
      }
      ObjectId id = ObjectId.fromString(name);
      if (ids.contains(id)) {
        continue;
      }
      if (ids.size() >= MAX_REVS) {
        return new IgnoreRevsFile(ImmutableSet.copyOf(ids), false, true);
      }
      ids.add(id);
    }
    return new IgnoreRevsFile(ImmutableSet.copyOf(ids), false, false);
  }

  private final ImmutableSet<ObjectId> ids;
  private final boolean tooLarge;
  private final boolean truncated;

  private IgnoreRevsFile(ImmutableSet<ObjectId> ids, boolean tooLarge, boolean truncated) {
    this.ids = ids;
    this.tooLarge = tooLarge;
    this.truncated = truncated;
  }

  /** Returns the revisions to ignore, in the order they are listed in the file. */
  public ImmutableSet<ObjectId> getIds() {
    return ids;
  }

  /** Returns whether the file was ignored because it is larger than {@link #MAX_SIZE} bytes. */
  public boolean isTooLarge() {
    return tooLarge;
  }

  /** Returns whether revisions after the first {@link #MAX_REVS} were dropped. */
  public boolean isTruncated() {
    return truncated;
  }
}
