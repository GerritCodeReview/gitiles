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

import static com.google.common.base.Preconditions.checkNotNull;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.eclipse.jgit.diff.Edit.Type.DELETE;
import static org.eclipse.jgit.diff.Edit.Type.INSERT;
import static org.eclipse.jgit.diff.Edit.Type.REPLACE;
import static org.eclipse.jgit.lib.Constants.encodeASCII;
import static org.eclipse.jgit.util.QuotedString.GIT_PATH;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.google.gitiles.diff.IntraLineDiff;
import com.google.gitiles.diff.ReplaceEdit;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import javax.annotation.Nullable;
import org.apache.commons.text.StringEscapeUtils;
import org.eclipse.jgit.diff.DiffDriver;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffEntry.ChangeType;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.Edit;
import org.eclipse.jgit.diff.EditList;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.patch.FileHeader;
import org.eclipse.jgit.patch.FileHeader.PatchType;
import org.eclipse.jgit.util.RawParseUtils;

/** Formats a unified format patch as UTF-8 encoded HTML. */
final class HtmlDiffFormatter extends DiffFormatter {
  private static final byte[] DIFF_BEGIN =
      "<pre class=\"u-pre u-monospace Diff-unified\">".getBytes(UTF_8);
  private static final byte[] DIFF_END = "</pre>".getBytes(UTF_8);

  private static final byte[] HUNK_BEGIN = "<span class=\"Diff-hunk\">".getBytes(UTF_8);
  private static final byte[] HUNK_END = "</span>".getBytes(UTF_8);

  private static final byte[] LINE_INSERT_BEGIN = "<span class=\"Diff-insert\">".getBytes(UTF_8);
  private static final byte[] LINE_DELETE_BEGIN = "<span class=\"Diff-delete\">".getBytes(UTF_8);
  private static final byte[] LINE_INSERT_INTRALINE_BEGIN =
      "<span class=\"Diff-insert Diff-intraline\">".getBytes(UTF_8);
  private static final byte[] LINE_DELETE_INTRALINE_BEGIN =
      "<span class=\"Diff-delete Diff-intraline\">".getBytes(UTF_8);
  private static final byte[] LINE_CHANGE_BEGIN = "<span class=\"Diff-change\">".getBytes(UTF_8);
  private static final byte[] LINE_END = "</span>\n".getBytes(UTF_8);
  private static final byte[] MARK_BEGIN = "<span class=\"Diff-mark\">".getBytes(UTF_8);
  private static final byte[] MARK_END = "</span>".getBytes(UTF_8);

  // Bound the character-level intraline diff the same way Gerrit's
  // IntraLineLoader does: run it on a background thread and abandon it after a
  // timeout, since MyersDiff is O(N*D) and can blow up on large replaced
  // regions. Matches Gerrit's default cache.diff_intraline.timeout of 5s.
  private static final long INTRALINE_TIMEOUT_MILLIS = TimeUnit.SECONDS.toMillis(5);

  // Unbounded cached pool, mirroring Gerrit's @DiffExecutor. A cancelled MyersDiff does not stop
  // promptly (it never checks interruption), so the timeout bounds request latency, not CPU -- the
  // same tradeoff Gerrit accepts. Bounding this would diverge from Gerrit.
  private static final ExecutorService INTRALINE_EXECUTOR =
      Executors.newCachedThreadPool(
          new ThreadFactoryBuilder().setNameFormat("gitiles-intraline-%d").setDaemon(true).build());

  private final Renderer renderer;
  private final GitilesView view;
  private int fileIndex;
  private DiffEntry entry;

  HtmlDiffFormatter(Renderer renderer, GitilesView view, OutputStream out) {
    super(out);
    this.renderer = checkNotNull(renderer, "renderer");
    this.view = checkNotNull(view, "view");
  }

  @Override
  public void format(List<? extends DiffEntry> entries) throws IOException {
    for (fileIndex = 0; fileIndex < entries.size(); fileIndex++) {
      entry = entries.get(fileIndex);
      format(entry);
    }
  }

  @Override
  public void format(FileHeader hdr, RawText a, RawText b, DiffDriver diffDriver)
      throws IOException {
    int start = hdr.getStartOffset();
    int end = hdr.getEndOffset();
    if (!hdr.getHunks().isEmpty()) {
      end = hdr.getHunks().get(0).getStartOffset();
    }
    renderHeader(RawParseUtils.decode(hdr.getBuffer(), start, end));

    if (hdr.getPatchType() == PatchType.UNIFIED) {
      getOutputStream().write(DIFF_BEGIN);
      format(hdr.toEditList(), a, b, diffDriver);
      getOutputStream().write(DIFF_END);
    }
  }

  private int context = 3;
  private static final byte[] noNewLine =
      encodeASCII("\\ No newline at end of file\n"); // $NON-NLS-1$

  private static boolean isEndOfLineMissing(final RawText text, final int line) {
    return line == text.size() && text.isMissingNewlineAtEnd();
  }

  private int findCombinedEnd(final List<Edit> edits, final int i) {
    int end = i + 1;
    while (end < edits.size() && (combineA(edits, end) || combineB(edits, end))) {
      end++;
    }
    return end - 1;
  }

  private boolean combineA(final List<Edit> e, final int i) {
    return e.get(i).getBeginA() - e.get(i - 1).getEndA() <= 2 * context;
  }

  private boolean combineB(final List<Edit> e, final int i) {
    return e.get(i).getBeginB() - e.get(i - 1).getEndB() <= 2 * context;
  }

  /**
   * Computes the intraline (word-level) edits for {@code edits}, bounded by a timeout the same way
   * Gerrit's IntraLineLoader bounds it. Falls back to the plain line-level edits (rendered without
   * intraline marks) if the character diff does not finish in time.
   *
   * <p>Package-private and parameterized on {@code executor}/{@code timeoutMillis} so a test can
   * force the timeout fallback deterministically.
   */
  static ImmutableList<Edit> computeIntraLineEdits(
      ExecutorService executor, long timeoutMillis, EditList edits, RawText a, RawText b) {
    Future<ImmutableList<Edit>> future = executor.submit(() -> IntraLineDiff.compute(a, b, edits));
    try {
      return future.get(timeoutMillis, TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      future.cancel(true);
      Thread.currentThread().interrupt();
    } catch (TimeoutException | ExecutionException e) {
      future.cancel(true);
    }
    return ImmutableList.copyOf(edits);
  }

  @Override
  public void format(final EditList edits, final RawText a, final RawText b, DiffDriver diffDriver)
      throws IOException {
    ImmutableList<Edit> lineEdits =
        computeIntraLineEdits(INTRALINE_EXECUTOR, INTRALINE_TIMEOUT_MILLIS, edits, a, b);

    for (int curIdx = 0; curIdx < lineEdits.size(); ) {
      Edit curEdit = lineEdits.get(curIdx);
      final int endIdx = findCombinedEnd(lineEdits, curIdx);
      final Edit endEdit = lineEdits.get(endIdx);

      int aCur = Math.max(0, curEdit.getBeginA() - context);
      int bCur = Math.max(0, curEdit.getBeginB() - context);
      final int aEnd = Math.min(a.size(), endEdit.getEndA() + context);
      final int bEnd = Math.min(b.size(), endEdit.getEndB() + context);

      writeHunkHeader(aCur, aEnd, bCur, bEnd, getFuncName(a, aCur - 1, diffDriver));

      while (true) {
        for (; aCur < curEdit.getBeginA(); aCur++, bCur++) {
          writeEditLine(LINE_CHANGE_BEGIN, a.getString(aCur));
        }

        if (curEdit.getType() == DELETE) {
          for (; aCur < curEdit.getEndA(); aCur++) {
            writeEditLine(LINE_DELETE_BEGIN, a.getString(aCur));
          }
          if (aCur == aEnd && isEndOfLineMissing(a, aEnd)) {
            getOutputStream().write(noNewLine);
          }
        } else if (curEdit.getType() == INSERT) {
          for (; bCur < curEdit.getEndB(); bCur++) {
            writeEditLine(LINE_INSERT_BEGIN, b.getString(bCur));
          }
          if (bCur == bEnd && isEndOfLineMissing(b, bEnd)) {
            getOutputStream().write(noNewLine);
          }
        } else if (curEdit.getType() == REPLACE) {
          ImmutableList<Edit> wordEdits =
              (curEdit instanceof ReplaceEdit re) ? re.getInternalEdits() : ImmutableList.of();

          writeChanges(
              changesForSide(a.getString(aCur, curEdit.getEndA(), false), wordEdits, false),
              false,
              !wordEdits.isEmpty());
          aCur = curEdit.getEndA();
          if (aCur == aEnd && isEndOfLineMissing(a, aEnd)) {
            getOutputStream().write(noNewLine);
          }

          writeChanges(
              changesForSide(b.getString(bCur, curEdit.getEndB(), false), wordEdits, true),
              true,
              !wordEdits.isEmpty());
          bCur = curEdit.getEndB();
          if (bCur == bEnd && isEndOfLineMissing(b, bEnd)) {
            getOutputStream().write(noNewLine);
          }
        }

        if (curIdx++ < endIdx) {
          curEdit = lineEdits.get(curIdx);
        } else {
          for (aCur = curEdit.getEndA(); aCur < aEnd; aCur++, bCur++) {
            writeEditLine(LINE_CHANGE_BEGIN, a.getString(aCur));
          }
          if (curEdit.getEndA() < aEnd && isEndOfLineMissing(a, aEnd)) {
            getOutputStream().write(noNewLine);
          }
          if (curEdit.getEndB() < bEnd && isEndOfLineMissing(b, bEnd)) {
            getOutputStream().write(noNewLine);
          }
          break;
        }
      }
    }
  }

  private void writeHtml(final String str) throws IOException {
    getOutputStream().write(StringEscapeUtils.escapeHtml4(str).getBytes(UTF_8));
  }

  private void writeEditLine(final byte[] ctxLine, final String str) throws IOException {
    getOutputStream().write(ctxLine);
    writeHtml(str);
    getOutputStream().write(LINE_END);
  }

  private static List<Change> changesForSide(String text, List<Edit> edits, boolean insert) {
    List<Change> changes = new ArrayList<>();
    int ptr = 0;
    for (Edit e : edits) {
      int begin = insert ? e.getBeginB() : e.getBeginA();
      int end = insert ? e.getEndB() : e.getEndA();
      if (ptr < begin) {
        changes.add(new Change(text.substring(ptr, begin), false));
      }
      if (begin < end) {
        changes.add(new Change(text.substring(begin, end), true));
      }
      ptr = end;
    }
    if (ptr < text.length()) {
      changes.add(new Change(text.substring(ptr), false));
    }
    return changes;
  }

  private void writeChanges(
      final List<Change> diffs, final boolean insert, boolean hasIntralineInfo) throws IOException {
    List<Mark> changedLine = new ArrayList<>();
    boolean wroteLine = false;

    for (Change diff : diffs) {
      int i = 0;
      while (i < diff.text.length()) {
        final int j = diff.text.indexOf('\n', i);
        final int end = j < 0 ? diff.text.length() : j;
        if (i < end) {
          changedLine.add(new Mark(diff.text.substring(i, end), diff.mark));
        }
        if (j < 0) {
          break;
        }
        flushChangedLine(changedLine, insert, hasIntralineInfo);
        wroteLine = true;
        i = j + 1;
      }
    }
    if (!changedLine.isEmpty() || !wroteLine) {
      flushChangedLine(changedLine, insert, hasIntralineInfo);
    }
  }

  private void flushChangedLine(List<Mark> changes, final boolean insert, boolean hasIntralineInfo)
      throws IOException {
    getOutputStream()
        .write(
            insert
                ? (hasIntralineInfo ? LINE_INSERT_INTRALINE_BEGIN : LINE_INSERT_BEGIN)
                : (hasIntralineInfo ? LINE_DELETE_INTRALINE_BEGIN : LINE_DELETE_BEGIN));
    for (Mark c : changes) {
      if (c.mark) {
        getOutputStream().write(MARK_BEGIN);
      }
      writeHtml(c.text);
      if (c.mark) {
        getOutputStream().write(MARK_END);
      }
    }
    getOutputStream().write(LINE_END);
    changes.clear();
  }

  private static final class Mark {
    final String text;
    final boolean mark;

    Mark(final String text, final boolean mark) {
      this.text = text;
      this.mark = mark;
    }
  }

  private void renderHeader(String header) throws IOException {
    int lf = header.indexOf('\n');
    String rest = 0 <= lf ? header.substring(lf + 1) : "";

    // Based on DiffFormatter.formatGitDiffFirstHeaderLine.
    List<Map<String, String>> parts = Lists.newArrayListWithCapacity(3);
    parts.add(ImmutableMap.of("text", "diff --git"));
    if (entry.getChangeType() != ChangeType.ADD) {
      parts.add(
          ImmutableMap.of(
              "text", GIT_PATH.quote(getOldPrefix() + entry.getOldPath()),
              "url", revisionUrl(view.getOldRevision(), entry.getOldPath())));
    } else {
      parts.add(ImmutableMap.of("text", GIT_PATH.quote(getOldPrefix() + entry.getNewPath())));
    }
    if (entry.getChangeType() != ChangeType.DELETE) {
      parts.add(
          ImmutableMap.of(
              "text", GIT_PATH.quote(getNewPrefix() + entry.getNewPath()),
              "url", revisionUrl(view.getRevision(), entry.getNewPath())));
    } else {
      parts.add(ImmutableMap.of("text", GIT_PATH.quote(getNewPrefix() + entry.getOldPath())));
    }

    getOutputStream()
        .write(
            renderer
                .newRenderer("com.google.gitiles.templates.DiffDetail.diffHeader")
                .setData(ImmutableMap.of("firstParts", parts, "rest", rest, "fileIndex", fileIndex))
                .renderHtml()
                .get()
                .toString()
                .getBytes(UTF_8));
  }

  private String revisionUrl(Revision rev, String path) {
    return GitilesView.path()
        .copyFrom(view)
        .setOldRevision(Revision.NULL)
        .setRevision(Revision.named(rev.getId().name()))
        .setPathPart(path)
        .toUrl();
  }

  @Override
  protected void writeHunkHeader(int aStartLine, int aEndLine, int bStartLine, int bEndLine)
      throws IOException {
    writeHunkHeader(aStartLine, aEndLine, bStartLine, bEndLine, null);
  }

  @Override
  protected void writeHunkHeader(
      int aStartLine, int aEndLine, int bStartLine, int bEndLine, String funcName)
      throws IOException {
    getOutputStream().write(HUNK_BEGIN);
    writeHtml(formatHunkHeader(aStartLine, aEndLine, bStartLine, bEndLine, funcName));
    getOutputStream().write(HUNK_END);
  }

  private static String formatHunkHeader(
      int aStartLine, int aEndLine, int bStartLine, int bEndLine, String funcName) {
    StringBuilder header = new StringBuilder();
    header.append("@@");
    formatRange(header, '-', aStartLine + 1, aEndLine - aStartLine);
    formatRange(header, '+', bStartLine + 1, bEndLine - bStartLine);
    header.append(" @@");
    if (funcName != null) {
      header.append(' ').append(funcName);
    }
    return header.append('\n').toString();
  }

  private static void formatRange(StringBuilder header, char prefix, int begin, int count) {
    header.append(' ').append(prefix);
    if (count == 0) {
      header.append(begin - 1).append(",0");
    } else if (count == 1) {
      header.append(begin);
    } else {
      header.append(begin).append(',').append(count);
    }
  }

  private static @Nullable String getFuncName(RawText text, int startAt, DiffDriver diffDriver) {
    if (diffDriver != null) {
      while (startAt >= 0) {
        String line = text.getString(startAt);
        startAt--;
        if (matchesAny(diffDriver.getNegatePatterns(), line)) {
          continue;
        }
        if (matchesAny(diffDriver.getMatchPatterns(), line)) {
          String funcName = line.replaceAll("^[ \\t]+", "");
          return funcName.substring(0, Math.min(funcName.length(), 80)).trim();
        }
      }
    }
    return null;
  }

  private static boolean matchesAny(List<Pattern> patterns, String text) {
    if (patterns != null) {
      for (Pattern p : patterns) {
        if (p.matcher(text).find()) {
          return true;
        }
      }
    }
    return false;
  }

  private static final class Change {
    final String text;
    final boolean mark;

    Change(final String text, boolean mark) {
      this.text = text;
      this.mark = mark;
    }
  }
}
