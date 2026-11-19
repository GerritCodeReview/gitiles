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
import static org.eclipse.jgit.util.QuotedString.GIT_PATH;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.html.types.SafeHtml;
import com.google.gitiles.lean.LeanHtml;
import com.google.gitiles.lean.LeanRendering;
import com.google.gitiles.lean.LeanRequestBudget;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import org.apache.commons.text.StringEscapeUtils;
import org.eclipse.jgit.diff.DiffDriver;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffEntry.ChangeType;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.patch.FileHeader;
import org.eclipse.jgit.patch.FileHeader.PatchType;
import org.eclipse.jgit.util.RawParseUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Formats a unified format patch as UTF-8 encoded HTML. */
final class HtmlDiffFormatter extends DiffFormatter {
  private static final Logger log = LoggerFactory.getLogger(HtmlDiffFormatter.class);

  private static final byte[] DIFF_BEGIN =
      "<pre class=\"u-pre u-monospace Diff-unified\">".getBytes(UTF_8);
  private static final byte[] DIFF_END = "</pre>".getBytes(UTF_8);

  private static final byte[] HUNK_BEGIN = "<span class=\"Diff-hunk\">".getBytes(UTF_8);
  private static final byte[] HUNK_END = "</span>".getBytes(UTF_8);

  private static final byte[] LINE_INSERT_BEGIN = "<span class=\"Diff-insert\">".getBytes(UTF_8);
  private static final byte[] LINE_DELETE_BEGIN = "<span class=\"Diff-delete\">".getBytes(UTF_8);
  private static final byte[] LINE_CHANGE_BEGIN = "<span class=\"Diff-change\">".getBytes(UTF_8);
  private static final byte[] LINE_END = "</span>\n".getBytes(UTF_8);

  private final Renderer renderer;
  private final GitilesView view;
  private final @Nullable Config config;
  private final @Nullable String requestUri;
  private final LeanRequestBudget leanBudget = LeanRequestBudget.forRequest();
  private int fileIndex;
  private DiffEntry entry;

  HtmlDiffFormatter(Renderer renderer, GitilesView view, OutputStream out) {
    this(renderer, view, out, null, null);
  }

  HtmlDiffFormatter(
      Renderer renderer,
      GitilesView view,
      OutputStream out,
      @Nullable Config config,
      @Nullable String requestUri) {
    super(out);
    this.renderer = checkNotNull(renderer, "renderer");
    this.view = checkNotNull(view, "view");
    this.config = config;
    this.requestUri = requestUri;
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
    format(hdr, a, b, null);
  }

  @Override
  public void format(FileHeader hdr, RawText a, RawText b, DiffDriver diffDriver)
      throws IOException {
    int start = hdr.getStartOffset();
    int end = hdr.getEndOffset();
    if (!hdr.getHunks().isEmpty()) {
      end = hdr.getHunks().get(0).getStartOffset();
    }

    String leanPlainTextUrl = null;
    String leanRenderedUrl = null;
    SafeHtml leanRendered = null;
    try {
      String leanPath = eligibleLeanPathPrefix(hdr, a, b);
      if (leanPath != null) {
        if (LeanRendering.isRequested(view)) {
          byte[] aBytes = a.getRawContent();
          byte[] bBytes = b.getRawContent();
          if (leanBudget.canAttempt(aBytes.length, bBytes.length)
              && LeanRendering.withinInputCaps(a)
              && LeanRendering.withinInputCaps(b)) {
            String leftSource =
                a.size() == 0 && aBytes.length == 0 ? "" : RawParseUtils.decode(aBytes);
            String rightSource =
                b.size() == 0 && bBytes.length == 0 ? "" : RawParseUtils.decode(bBytes);
            leanRendered =
                LeanHtml.renderDiff(
                    aBytes.length,
                    leftSource,
                    bBytes.length,
                    rightSource,
                    config,
                    view,
                    requestUri,
                    leanPath,
                    leanBudget);
            if (leanRendered != null) {
              leanPlainTextUrl = LeanRendering.plainTextUrl(view);
            }
          }
        } else if (LeanRendering.withinInputCaps(a) && LeanRendering.withinInputCaps(b)) {
          leanRenderedUrl = LeanRendering.renderedUrl(view);
        }
      }
    } catch (RuntimeException | StackOverflowError e) {
      leanRendered = null;
      leanPlainTextUrl = null;
      leanRenderedUrl = null;
      log.warn(
          "Error rendering Lean diff {} ({})",
          LeanRendering.sanitizeForLog(resolveDiffPath(hdr, entry)),
          e.getClass().getSimpleName());
    }

    renderHeader(
        RawParseUtils.decode(hdr.getBuffer(), start, end), leanPlainTextUrl, leanRenderedUrl);

    if (leanRendered != null) {
      getOutputStream().write(leanRendered.getSafeHtmlString().getBytes(UTF_8));
      return;
    }

    if (hdr.getPatchType() == PatchType.UNIFIED) {
      getOutputStream().write(DIFF_BEGIN);
      format(hdr.toEditList(), a, b, diffDriver);
      getOutputStream().write(DIFF_END);
    }
  }

  private @Nullable String eligibleLeanPathPrefix(
      FileHeader hdr, @Nullable RawText a, @Nullable RawText b) {
    if (!LeanRendering.isEnabled(config)) {
      return null;
    }
    if (hdr.getPatchType() != PatchType.UNIFIED || a == null || b == null) {
      return null;
    }
    if (FileMode.GITLINK.equals(hdr.getOldMode()) || FileMode.GITLINK.equals(hdr.getNewMode())) {
      return null;
    }
    if (entry != null
        && (FileMode.GITLINK.equals(entry.getOldMode())
            || FileMode.GITLINK.equals(entry.getNewMode()))) {
      return null;
    }
    String path = resolveDiffPath(hdr, entry);
    if (!LeanRendering.isLeanPath(path)) {
      return null;
    }
    if (hdr.getHunks().isEmpty() || hdr.toEditList().isEmpty()) {
      return null;
    }
    return path;
  }

  private static @Nullable String resolveDiffPath(FileHeader hdr, @Nullable DiffEntry entry) {
    if (entry != null) {
      return entry.getChangeType() == ChangeType.DELETE ? entry.getOldPath() : entry.getNewPath();
    }
    return hdr.getChangeType() == ChangeType.DELETE ? hdr.getOldPath() : hdr.getNewPath();
  }

  private void renderHeader(
      String header, @Nullable String leanPlainTextUrl, @Nullable String leanRenderedUrl)
      throws IOException {
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

    ImmutableMap.Builder<String, Object> data =
        ImmutableMap.<String, Object>builder()
            .put("firstParts", parts)
            .put("rest", rest)
            .put("fileIndex", fileIndex);
    if (leanPlainTextUrl != null) {
      data.put("leanPlainTextUrl", leanPlainTextUrl);
    } else if (leanRenderedUrl != null) {
      data.put("leanRenderedUrl", leanRenderedUrl);
    }

    getOutputStream()
        .write(
            renderer
                .newRenderer("com.google.gitiles.templates.DiffDetail.diffHeader")
                .setData(data.buildOrThrow())
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
    super.writeHunkHeader(
        aStartLine, aEndLine, bStartLine, bEndLine, StringEscapeUtils.escapeHtml4(funcName));
    getOutputStream().write(HUNK_END);
  }

  @Override
  protected void writeLine(char prefix, RawText text, int cur) throws IOException {
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
    out.write(StringEscapeUtils.escapeHtml4(text.getString(cur)).getBytes(UTF_8));
    out.write(LINE_END);
  }
}
