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

import static com.google.common.base.Preconditions.checkState;

import com.google.common.base.Stopwatch;
import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import com.google.common.io.Files;
import com.google.template.soy.data.SoyMapData;
import com.google.template.soy.data.restricted.StringData;

import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.errors.LargeObjectException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.util.RawParseUtils;

import prettify.PrettifyParser;
import prettify.parser.Prettify;
import syntaxhighlight.ParseResult;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.List;

public class BlobRenderer {
  /**
   * Maximum number of bytes to load from a supposed text file for display.
   * Files larger than this will be displayed as binary files, even if the
   * contents was text. For example really big XML files may be above this limit
   * and will get displayed as binary.
   */
  private static final int MAX_FILE_SIZE = 10 << 20;

  private final Renderer renderer;
  private final RevWalk walk;
  private final ObjectId blobId;
  private final String path;

  public BlobRenderer(Renderer renderer, RevWalk walk, ObjectId blobId, String path) {
    this.renderer = renderer;
    this.walk = walk;
    this.blobId = blobId;
    this.path = path;
  }

  public void render(OutputStream out) throws IOException {
    ObjectLoader loader = walk.getObjectReader().open(blobId, Constants.OBJ_BLOB);
    String content;
    try {
      byte[] raw = loader.getCachedBytes(MAX_FILE_SIZE);
      content = !RawText.isBinary(raw) ? RawParseUtils.decode(raw) : null;
    } catch (LargeObjectException.OutOfMemory e) {
      throw e;
    } catch (LargeObjectException e) {
      content = null; // TODO: handle
    }

    Stopwatch sw = Stopwatch.createStarted();
    List<List<SoyMapData>> lines = Lists.newArrayList();
    List<SoyMapData> line = Lists.newArrayList();
    lines.add(line);

    Writer w = new OutputStreamWriter(out);
    int last = 0;
    for (ParseResult r : new PrettifyParser().parse(extension(), content)) {
      checkState(r.getOffset() >= last,
          "out-of-order ParseResult, expected %s >= %s", r.getOffset(), last);
      line = writeResult(lines, null, content, last, r.getOffset());
      last = r.getOffset() + r.getLength();
      line = writeResult(lines, r.getStyleKeysString(), content, r.getOffset(), last);
    }
    if (last < content.length()) {
      writeResult(lines, null, content, last, content.length());
    }
    renderer.newRenderer("gitiles.blobDetail2").setData(new SoyMapData("lines", lines)).render(w);
    w.flush();
    System.err.println("Rendered in " + sw);
  }

  private List<SoyMapData> writeResult(List<List<SoyMapData>> lines, String classes, String s,
      int start, int end) {
    List<SoyMapData> line = lines.get(lines.size() - 1);
    while (true) {
      int nl = nextLineBreak(s, start, end);
      if (nl < 0) {
        break;
      }
      addSpan(line, classes, s, start, nl);

      start = nl + (isCrNl(s, nl) ? 2 : 1);
      if (start == s.length()) {
        return null;
      }
      line = Lists.newArrayList();
      lines.add(line);
    }
    addSpan(line, classes, s, start, end);
    return line;
  }

  private void addSpan(List<SoyMapData> line, String classes, String s, int start, int end) {
    if (end - start > 0) {
      SoyMapData data = new SoyMapData();
      data.put("classes", StringData.forValue(
          !Strings.isNullOrEmpty(classes) ? classes : Prettify.PR_PLAIN));
      data.put("text", StringData.forValue(s.substring(start, end)));
      line.add(new SoyMapData("classes", classes, "text", s.substring(start, end)));
    }
  }

  private static boolean isCrNl(String s, int n) {
    return s.charAt(n) == '\r' && n != s.length() - 1 && s.charAt(n + 1) == '\n';
  }

  private static int nextLineBreak(String s, int start, int end) {
    for (int i = start; i < end; i++) {
      if (s.charAt(i) == '\n' || s.charAt(i) == '\r') {
        return i;
      }
    }
    return -1;
  }

  private String extension() {
    return path != null ? Files.getFileExtension(path) : null;
  }
}

