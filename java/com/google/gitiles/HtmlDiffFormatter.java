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
import static name.fraser.neil.plaintext.diff_match_patch.Operation;
import static org.eclipse.jgit.diff.Edit.Type.DELETE;
import static org.eclipse.jgit.diff.Edit.Type.INSERT;
import static org.eclipse.jgit.diff.Edit.Type.REPLACE;
import static org.eclipse.jgit.lib.Constants.encodeASCII;
import static org.eclipse.jgit.util.QuotedString.GIT_PATH;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import java.io.IOException;
import java.io.OutputStream;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import name.fraser.neil.plaintext.diff_match_patch;
import org.apache.commons.text.StringEscapeUtils;
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
  private static final byte[] LINE_CHANGE_BEGIN = "<span class=\"Diff-change\">".getBytes(UTF_8);
  private static final byte[] LINE_END = "</span>\n".getBytes(UTF_8);
  private static final byte[] MARK_BEGIN = "<span class=\"Diff-mark\">".getBytes(UTF_8);
  private static final byte[] MARK_END = "</span>".getBytes(UTF_8);

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
  public void format(FileHeader hdr, RawText a, RawText b) throws IOException {
    int start = hdr.getStartOffset();
    int end = hdr.getEndOffset();
    if (!hdr.getHunks().isEmpty()) {
      end = hdr.getHunks().get(0).getStartOffset();
    }
    renderHeader(RawParseUtils.decode(hdr.getBuffer(), start, end));

    if (hdr.getPatchType() == PatchType.UNIFIED) {
      getOutputStream().write(DIFF_BEGIN);
      format(hdr.toEditList(), a, b);
      getOutputStream().write(DIFF_END);
    }
  }

  /* adapted from org.eclipse.jgit.diff.DiffFormatter */
  private int context = 3;
  private static final byte[] noNewLine =
      encodeASCII("\\ No newline at end of file\n"); // $NON-NLS-1$

  private static boolean isEndOfLineMissing(final RawText text, final int line) {
    return line == text.size() && text.isMissingNewlineAtEnd();
  }

  private int findCombinedEnd(final List<Edit> edits, final int i) {
    int end = i + 1;
    while (end < edits.size() && (combineA(edits, end) || combineB(edits, end))) end++;
    return end - 1;
  }

  private boolean combineA(final List<Edit> e, final int i) {
    return e.get(i).getBeginA() - e.get(i - 1).getEndA() <= 2 * context;
  }

  private boolean combineB(final List<Edit> e, final int i) {
    return e.get(i).getBeginB() - e.get(i - 1).getEndB() <= 2 * context;
  }

  @Override
  public void format(final EditList edits, final RawText a, final RawText b) throws IOException {
    for (int curIdx = 0; curIdx < edits.size(); ) {
      Edit curEdit = edits.get(curIdx);
      final int endIdx = findCombinedEnd(edits, curIdx);
      final Edit endEdit = edits.get(endIdx);

      int aCur = Math.max(0, curEdit.getBeginA() - context);
      int bCur = Math.max(0, curEdit.getBeginB() - context);
      final int aEnd = Math.min(a.size(), endEdit.getEndA() + context);
      final int bEnd = Math.min(b.size(), endEdit.getEndB() + context);

      writeHunkHeader(aCur, aEnd, bCur, bEnd);

      while (true) {
        for (; aCur < curEdit.getBeginA(); aCur++, bCur++)
          writeEditLine(LINE_CHANGE_BEGIN, a.getString(aCur));

        if (curEdit.getType() == DELETE) {
          /* sequence A has removed the region */
          for (; aCur < curEdit.getEndA(); aCur++)
            writeEditLine(LINE_DELETE_BEGIN, a.getString(aCur));
          if (aCur == aEnd && isEndOfLineMissing(a, aEnd)) getOutputStream().write(noNewLine);
        } else if (curEdit.getType() == INSERT) {
          /* sequence B has inserted the region */
          for (; bCur < curEdit.getEndB(); bCur++)
            writeEditLine(LINE_INSERT_BEGIN, b.getString(bCur));
          if (bCur == bEnd && isEndOfLineMissing(b, bEnd)) getOutputStream().write(noNewLine);
        } else if (curEdit.getType() == REPLACE) {
          /* sequence B has replaced the region with different content */
          diff_match_patch dmp = new diff_match_patch();
          dmp.Diff_Timeout = .050f;
          LinkedList<diff_match_patch.Diff> diffs =
              dmp.diff_main(
                  a.getString(aCur, curEdit.getEndA(), true),
                  b.getString(bCur, curEdit.getEndB(), true),
                  false);
          dmp.diff_cleanupSemantic(diffs);

          /* write deletions */
          writeChanges(diffs, false);
          aCur = curEdit.getEndA();
          if (aCur == aEnd && isEndOfLineMissing(a, aEnd)) getOutputStream().write(noNewLine);

          /* write insertions */
          writeChanges(diffs, true);
          bCur = curEdit.getEndB();
          if (bCur == bEnd && isEndOfLineMissing(b, bEnd)) getOutputStream().write(noNewLine);
        }

        if (curIdx++ < endIdx) curEdit = edits.get(curIdx);
        else {
          /* was the last edit in this hunk */
          for (aCur = curEdit.getEndA(); aCur < aEnd; aCur++, bCur++)
            writeEditLine(LINE_CHANGE_BEGIN, a.getString(aCur));
          if (curEdit.getEndA() < aEnd && isEndOfLineMissing(a, aEnd))
            getOutputStream().write(noNewLine);
          if (curEdit.getEndB() < bEnd && isEndOfLineMissing(b, bEnd))
            getOutputStream().write(noNewLine);
          break;
        }
      }
    }
  }

  /* Escape a string to HTML and write it to the output stream. */
  private void writeHtml(final String str) throws IOException {
    getOutputStream().write(StringEscapeUtils.escapeHtml4(str).getBytes(UTF_8));
  }

  /* Write an edit line in the given context. */
  private void writeEditLine(final byte[] ctxLine, final String str) throws IOException {
    getOutputStream().write(ctxLine);
    writeHtml(str);
    getOutputStream().write(LINE_END);
  }

  /* Write changes (for type == REPLACE). */
  private void writeChanges(final LinkedList<diff_match_patch.Diff> diffs, final boolean insert)
      throws IOException {
    /* A changed line.  We delay flushing changes to the end of each
     * line as we don't mark changes which cover a full line. */
    LinkedList<Change> changedLine = new LinkedList();

    for (diff_match_patch.Diff diff : diffs) {
      boolean mark;
      if (diff.operation == Operation.EQUAL) mark = false;
      else if ((diff.operation == Operation.INSERT) == insert) mark = true;
      else continue;

      int i = 0;
      do {
        /* find next linefeed character */
        final int j = diff.text.indexOf('\n', i);
        if (j < 0)
          /* the remaining of this diff is on the same line */
          changedLine.add(new Change(diff.text.substring(i), mark));
        else {
          /* there is linefeed at index j: append the relevant chunk and flush */
          if (i < j) changedLine.add(new Change(diff.text.substring(i, j), mark));
          flushChangedLine(changedLine, insert);
        }
        i = j + 1;
      } while (i > 0 && i < diff.text.length());
    }
    /* flush last line */
    flushChangedLine(changedLine, insert);
  }

  /* Flush an insertion/deletion change line. */
  private void flushChangedLine(LinkedList<Change> changes, final boolean insert)
      throws IOException {
    getOutputStream().write(insert ? LINE_INSERT_BEGIN : LINE_DELETE_BEGIN);
    ListIterator<Change> iter = changes.listIterator();
    while (iter.hasNext()) {
      Change c = iter.next();
      /* mark that change unless it covers the whole line (singleton change) */
      final boolean mark = c.mark ? (iter.previousIndex() > 0 || iter.hasNext()) : false;
      if (mark) getOutputStream().write(MARK_BEGIN);
      writeHtml(c.text);
      if (mark) getOutputStream().write(MARK_END);
    }
    getOutputStream().write(LINE_END);
    changes.clear(); /* delete all elements in the list */
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
    getOutputStream().write(HUNK_BEGIN);
    // TODO(sop): If hunk header starts including method names, escape it.
    super.writeHunkHeader(aStartLine, aEndLine, bStartLine, bEndLine);
    getOutputStream().write(HUNK_END);
  }
}

final class Change {
  protected final String text;
  protected final boolean mark;

  protected Change(final String text, final boolean mark) {
    this.text = text;
    this.mark = mark;
  }
}
