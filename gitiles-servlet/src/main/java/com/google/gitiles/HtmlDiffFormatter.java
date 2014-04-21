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

import com.google.common.base.Charsets;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;

import org.apache.commons.lang3.StringEscapeUtils;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.patch.FileHeader;
import org.eclipse.jgit.patch.FileHeader.PatchType;
import org.eclipse.jgit.util.RawParseUtils;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;

/** Formats a unified format patch as UTF-8 encoded HTML. */
final class HtmlDiffFormatter extends DiffFormatter {
  private static final byte[] DIFF_BEGIN = "<pre class=\"diff-unified\">".getBytes(Charsets.UTF_8);
  private static final byte[] DIFF_END = "</pre>".getBytes(Charsets.UTF_8);

  private static final byte[] HUNK_BEGIN = "<span class=\"h\">".getBytes(Charsets.UTF_8);
  private static final byte[] HUNK_END = "</span>".getBytes(Charsets.UTF_8);

  private static final byte[] LINE_INSERT_BEGIN = "<span class=\"i\">".getBytes(Charsets.UTF_8);
  private static final byte[] LINE_DELETE_BEGIN = "<span class=\"d\">".getBytes(Charsets.UTF_8);
  private static final byte[] LINE_CHANGE_BEGIN = "<span class=\"c\">".getBytes(Charsets.UTF_8);
  private static final byte[] LINE_END = "</span>\n".getBytes(Charsets.UTF_8);
  private static final Splitter SPACE = Splitter.on(' ').omitEmptyStrings();

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
  public void format(FileHeader hdr, RawText a, RawText b)
      throws IOException {
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

  private void renderHeader(String header)
      throws IOException {
    int lf = header.indexOf('\n');
    String first;
    String rest;
    if (0 <= lf) {
      first = header.substring(0, lf);
      rest = header.substring(lf + 1);
    } else {
      first = header;
      rest = "";
    }

    List<Map<String, String>> parts = Lists.newArrayListWithCapacity(4);
    for (String part : SPACE.split(first)) {
      if (part.startsWith("a/")) {
        parts.add(ImmutableMap.of(
            "text", part,
            "url", revisionUrl(view.getOldRevision(), part.substring(2))));
      } else if (part.startsWith("b/")) {
        parts.add(ImmutableMap.of(
            "text", part,
            "url", revisionUrl(view.getRevision(), part.substring(2))));
      } else {
        parts.add(ImmutableMap.of("text", part));
      }
    }

    getOutputStream().write(renderer.newRenderer("gitiles.diffHeader")
        .setData(ImmutableMap.of("firstParts", parts, "rest", rest, "fileIndex", fileIndex))
        .render()
        .getBytes(Charsets.UTF_8));
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
  protected void writeHunkHeader(int aStartLine, int aEndLine,
      int bStartLine, int bEndLine) throws IOException {
    getOutputStream().write(HUNK_BEGIN);
    // TODO(sop): If hunk header starts including method names, escape it.
    super.writeHunkHeader(aStartLine, aEndLine, bStartLine, bEndLine);
    getOutputStream().write(HUNK_END);
  }

  @Override
  protected void writeLine(char prefix, RawText text, int cur)
      throws IOException {
    // Manually render each line, rather than invoke a Soy template. This method
    // can be called thousands of times in a single request. Avoid unnecessary
    // overheads by formatting as-is.
    OutputStream out = getOutputStream();
    switch (prefix) {
      case '+':
        out.write(LINE_INSERT_BEGIN);
        break;
      case '-':
        out.write(LINE_DELETE_BEGIN);
        break;
      case ' ':
      default:
        out.write(LINE_CHANGE_BEGIN);
        break;
    }
    out.write(prefix);
    out.write(StringEscapeUtils.escapeHtml4(text.getString(cur)).getBytes(Charsets.UTF_8));
    out.write(LINE_END);
  }
}
