// Copyright 2026 Google Inc. All Rights Reserved.
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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.gitiles.GitilesRequestFailureException.FailureReason;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.errors.LargeObjectException;
import org.eclipse.jgit.http.server.ServletUtils;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevObject;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.util.RawParseUtils;

/** Serves file-content search results for a repository tree. */
public class GrepServlet extends BaseServlet {
  private static final long serialVersionUID = 1L;

  private static final String SUBSTRING_PARAM = "s";
  private static final String START_PARAM = "start";
  @VisibleForTesting static final int MAX_MATCHES = 1000;
  private static final int MAX_BLOB_SIZE = 1 << 20; // 1 MB
  @VisibleForTesting static final int MAX_LINE_LENGTH = 500;
  private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);

  protected GrepServlet(GitilesAccess.Factory accessFactory, Renderer renderer) {
    super(renderer, accessFactory);
  }

  @Override
  protected void doGetHtml(HttpServletRequest req, HttpServletResponse res) throws IOException {
    GitilesView view = ViewFilter.getView(req);
    String substring =
        Strings.nullToEmpty(Iterables.getFirst(view.getParameters().get(SUBSTRING_PARAM), null));

    List<Map<String, Object>> files = Lists.newArrayList();
    int matchCount = 0;
    boolean hasMore = false;
    boolean timedOut = false;
    String continueUrl = "";
    String jsonFormatUrl = "";
    if (!substring.isEmpty()) {
      GrepResult result = grep(ServletUtils.getRepository(req), view, substring, getTimeout(req));
      files = toSoyFiles(view, result.matches);
      matchCount = result.matches.size();
      hasMore = result.hasMore;
      timedOut = result.timedOut;
      if (result.next != null) {
        // Continue at the commit that was searched, not the branch, so that every page searches
        // the same snapshot even if the branch moves.
        ObjectId searched = ObjectId.fromString(result.revision);
        continueUrl =
            GitilesView.grep()
                .copyFrom(view)
                .setRevision(Revision.unpeeled(searched.name(), searched))
                .replaceParam(START_PARAM, result.next)
                .toUrl();
      }
      jsonFormatUrl =
          GitilesView.grep()
              .copyFrom(view)
              .replaceParam("format", FormatType.JSON.toString())
              .toUrl();
    }

    Map<String, Object> data = Maps.newHashMapWithExpectedSize(12);
    data.put("title", "Grep - " + view.getRevision().getName());
    data.put("revision", view.getRevision().getName());
    data.put("path", Strings.nullToEmpty(view.getPathPart()));
    data.put("substring", substring);
    data.put("hasSearch", !substring.isEmpty());
    data.put("hasMore", hasMore);
    data.put("timedOut", timedOut);
    data.put("continueUrl", continueUrl);
    data.put("jsonFormatUrl", jsonFormatUrl);
    data.put("files", files);
    data.put("matchCount", matchCount);
    data.put("fileCount", files.size());

    renderHtml(req, res, "com.google.gitiles.templates.GrepDetail.grepDetail", data);
  }

  @Override
  protected void doGetJson(HttpServletRequest req, HttpServletResponse res) throws IOException {
    GitilesView view = ViewFilter.getView(req);
    String substring = Iterables.getFirst(view.getParameters().get(SUBSTRING_PARAM), null);
    if (Strings.isNullOrEmpty(substring)) {
      throw new GitilesRequestFailureException(FailureReason.INCORRECT_PARAMETER)
          .withPublicErrorMessage("missing s parameter");
    }

    GrepResult result = grep(ServletUtils.getRepository(req), view, substring, getTimeout(req));
    renderJson(req, res, result, new TypeToken<GrepResult>() {}.getType());
  }

  private Duration getTimeout(HttpServletRequest req) throws IOException {
    return ConfigUtil.getDuration(
        getAccess(req).getConfig(), "grep", null, "timeout", DEFAULT_TIMEOUT);
  }

  private static GrepResult grep(
      Repository repo, GitilesView view, String substring, Duration timeout) throws IOException {
    String startParam = Iterables.getFirst(view.getParameters().get(START_PARAM), null);
    Position start = startParam != null ? Position.parse(startParam) : null;
    List<Match> matches = Lists.newArrayList();
    long deadline = System.nanoTime() + timeout.toNanos();
    String revision;
    Position stoppedAt;

    try (RevWalk rw = new RevWalk(repo)) {
      RevObject obj = rw.peel(rw.parseAny(view.getRevision().getId()));
      revision = obj.name();
      RevTree root = getRoot(obj);
      String path = Strings.nullToEmpty(view.getPathPart());
      if (path.isEmpty()) {
        stoppedAt = grepTree(rw.getObjectReader(), root, "", substring, matches, start, deadline);
      } else {
        stoppedAt =
            grepPath(rw.getObjectReader(), root, path, substring, matches, start, deadline);
      }
    }

    Position next = stoppedAt;
    if (matches.size() > MAX_MATCHES) {
      // The extra match was found only to know that more exist; continue from its line.
      Match extra = matches.remove(MAX_MATCHES);
      next = new Position(extra.path, extra.lineNumber - 1);
    }
    return new GrepResult(
        matches, next != null, stoppedAt != null, revision, next != null ? next.toString() : null);
  }

  /**
   * Groups matches by file for the HTML view. Matches are found in tree order, so all matches in
   * one file are consecutive.
   */
  private static List<Map<String, Object>> toSoyFiles(GitilesView view, List<Match> matches) {
    List<Map<String, Object>> files = Lists.newArrayList();
    String currentPath = null;
    String fileUrl = null;
    List<Map<String, Object>> lines = null;
    for (Match match : matches) {
      if (!match.path.equals(currentPath)) {
        currentPath = match.path;
        fileUrl = GitilesView.path().copyFrom(view).setPathPart(match.path).toUrl();
        lines = Lists.newArrayList();
        Map<String, Object> file = Maps.newHashMapWithExpectedSize(3);
        file.put("path", match.path);
        file.put("url", fileUrl);
        file.put("lines", lines);
        files.add(file);
      }
      lines.add(match.toSoyData(fileUrl));
    }
    return files;
  }

  /** Returns where to continue if the search stopped because the deadline passed, or null. */
  private static @Nullable Position grepPath(
      ObjectReader reader,
      RevTree root,
      String path,
      String substring,
      List<Match> matches,
      @Nullable Position start,
      long deadline)
      throws IOException {
    try (TreeWalk tw = TreeWalk.forPath(reader, path, root)) {
      if (tw == null) {
        throw new GitilesRequestFailureException(FailureReason.OBJECT_NOT_FOUND);
      }

      FileMode mode = tw.getFileMode(0);
      ObjectId id = tw.getObjectId(0);
      if (isFile(mode)) {
        if (start != null && !start.path.equals(path)) {
          throw Position.notFound();
        }
        grepBlob(reader, id, path, substring, matches, start != null ? start.lastSearchedLine : 0);
        return null;
      }
      if (mode.getObjectType() != Constants.OBJ_TREE) {
        return null;
      }

      try (TreeWalk subtree = new TreeWalk(reader)) {
        subtree.addTree(id);
        subtree.setRecursive(true);
        return grepTree(subtree, path + "/", substring, matches, start, deadline);
      }
    }
  }

  private static @Nullable Position grepTree(
      ObjectReader reader,
      RevTree root,
      String pathPrefix,
      String substring,
      List<Match> matches,
      @Nullable Position start,
      long deadline)
      throws IOException {
    try (TreeWalk tw = new TreeWalk(reader)) {
      tw.addTree(root);
      tw.setRecursive(true);
      return grepTree(tw, pathPrefix, substring, matches, start, deadline);
    }
  }

  /**
   * Searches files in tree order, beginning after {@code start} if set.
   *
   * <p>At least one file is searched before the deadline is checked, so that continuing a search
   * always makes progress.
   *
   * @return where to continue if the search stopped because the deadline passed, or null.
   */
  private static @Nullable Position grepTree(
      TreeWalk tw,
      String pathPrefix,
      String substring,
      List<Match> matches,
      @Nullable Position start,
      long deadline)
      throws IOException {
    boolean skipping = start != null;
    boolean searchedAny = false;
    while (tw.next()) {
      if (matches.size() > MAX_MATCHES) {
        return null;
      }
      if (!isFile(tw.getFileMode(0))) {
        continue;
      }
      String path = pathPrefix + tw.getPathString();
      int afterLine = 0;
      if (skipping) {
        if (!path.equals(start.path)) {
          continue;
        }
        skipping = false;
        afterLine = start.lastSearchedLine;
      }
      if (searchedAny && System.nanoTime() - deadline >= 0) {
        return new Position(path, afterLine);
      }
      grepBlob(tw.getObjectReader(), tw.getObjectId(0), path, substring, matches, afterLine);
      searchedAny = true;
      if (matches.size() > MAX_MATCHES) {
        return null;
      }
    }
    if (skipping) {
      throw Position.notFound();
    }
    return null;
  }

  /**
   * Returns whether {@code mode} is a regular or executable file. Symlinks are also stored as
   * blobs, but their content is the link target rather than file text, so they are not searched.
   */
  private static boolean isFile(FileMode mode) {
    return (mode.getBits() & FileMode.TYPE_MASK) == FileMode.TYPE_FILE;
  }

  /** Searches the lines of a blob after line {@code afterLine}. */
  private static void grepBlob(
      ObjectReader reader,
      ObjectId id,
      String path,
      String substring,
      List<Match> matches,
      int afterLine)
      throws IOException {
    ObjectLoader loader = reader.open(id, Constants.OBJ_BLOB);
    if (loader.getSize() > MAX_BLOB_SIZE) {
      return;
    }

    byte[] raw;
    try {
      raw = loader.getCachedBytes(MAX_BLOB_SIZE);
    } catch (LargeObjectException e) {
      return;
    }
    if (RawText.isBinary(raw)) {
      return;
    }

    String[] lines = RawParseUtils.decode(raw).split("\n", -1);
    // i is 0-based, so the first line searched is line afterLine + 1.
    for (int i = afterLine; i < lines.length; i++) {
      if (matches.size() > MAX_MATCHES) {
        return;
      }
      String line =
          lines[i].endsWith("\r") ? lines[i].substring(0, lines[i].length() - 1) : lines[i];
      int matchStart = line.indexOf(substring);
      if (matchStart >= 0) {
        matches.add(Match.forLine(path, i + 1, line, matchStart, substring.length()));
        if (matches.size() > MAX_MATCHES) {
          return;
        }
      }
    }
  }

  private static RevTree getRoot(RevObject obj) {
    switch (obj.getType()) {
      case Constants.OBJ_COMMIT:
        return ((RevCommit) obj).getTree();
      case Constants.OBJ_TREE:
        return (RevTree) obj;
      default:
        throw new GitilesRequestFailureException(FailureReason.INCORRECT_OBJECT_TYPE)
            .withPublicErrorMessage("The specified object is not a tree-ish.");
    }
  }

  static class GrepResult {
    List<Match> matches;
    boolean hasMore;
    boolean timedOut;
    String revision;
    @Nullable String next;

    GrepResult(
        List<Match> matches,
        boolean hasMore,
        boolean timedOut,
        String revision,
        @Nullable String next) {
      this.matches = matches;
      this.hasMore = hasMore;
      this.timedOut = timedOut;
      this.revision = revision;
      this.next = next;
    }
  }

  /**
   * A point to continue a search from: a file path, and the last line already searched in it.
   *
   * <p>Written as {@code <line>:<path>}, with the line first because paths may contain colons.
   */
  static class Position {
    final String path;
    final int lastSearchedLine;

    Position(String path, int lastSearchedLine) {
      this.path = path;
      this.lastSearchedLine = lastSearchedLine;
    }

    static Position parse(String value) {
      int colon = value.indexOf(':');
      if (colon > 0 && colon < value.length() - 1) {
        try {
          int lastSearchedLine = Integer.parseInt(value.substring(0, colon));
          if (lastSearchedLine >= 0) {
            return new Position(value.substring(colon + 1), lastSearchedLine);
          }
        } catch (NumberFormatException e) {
          // Fall through to the error below.
        }
      }
      throw new GitilesRequestFailureException(FailureReason.INCORRECT_PARAMETER)
          .withPublicErrorMessage("invalid start parameter");
    }

    static GitilesRequestFailureException notFound() {
      return new GitilesRequestFailureException(FailureReason.INCORRECT_PARAMETER)
          .withPublicErrorMessage("start path not found in search");
    }

    @Override
    public String toString() {
      return lastSearchedLine + ":" + path;
    }
  }

  static class Match {
    String path;
    int lineNumber;
    String line;
    boolean truncated;
    transient boolean truncatedStart;
    transient boolean truncatedEnd;

    Match(String path, int lineNumber, String line, boolean truncatedStart, boolean truncatedEnd) {
      this.path = path;
      this.lineNumber = lineNumber;
      this.line = line;
      this.truncated = truncatedStart || truncatedEnd;
      this.truncatedStart = truncatedStart;
      this.truncatedEnd = truncatedEnd;
    }

    /**
     * Creates a match for {@code line}, keeping about {@link #MAX_LINE_LENGTH} characters around
     * the first match so that very long lines, such as minified files, stay small in the response.
     *
     * <p>The kept text grows to the whole match when the search text is longer than {@link
     * #MAX_LINE_LENGTH}, so a match is never cut, and by one character at an edge that would
     * otherwise split a surrogate pair.
     */
    static Match forLine(
        String path, int lineNumber, String line, int matchStart, int matchLength) {
      if (line.length() <= MAX_LINE_LENGTH) {
        return new Match(path, lineNumber, line, false, false);
      }
      int size = Math.max(MAX_LINE_LENGTH, matchLength);
      int start = Math.max(0, matchStart - (size - matchLength) / 2);
      start = Math.min(start, line.length() - size);
      int end = start + size;
      // Do not split a surrogate pair at either edge.
      if (start > 0 && Character.isLowSurrogate(line.charAt(start))) {
        start--;
      }
      if (end < line.length() && Character.isLowSurrogate(line.charAt(end))) {
        end++;
      }
      return new Match(
          path, lineNumber, line.substring(start, end), start > 0, end < line.length());
    }

    Map<String, Object> toSoyData(String fileUrl) {
      Map<String, Object> data = Maps.newHashMapWithExpectedSize(5);
      data.put("lineNumber", lineNumber);
      data.put("line", line);
      data.put("truncatedStart", truncatedStart);
      data.put("truncatedEnd", truncatedEnd);
      data.put("url", fileUrl + "#" + lineNumber);
      return data;
    }
  }
}
