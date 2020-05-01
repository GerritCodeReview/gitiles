// Copyright 2020 Google Inc. All Rights Reserved.
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

package com.google.gitiles.doc;

import com.google.gitiles.FileTypeMaps;
import com.google.gitiles.GitilesView;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import javax.annotation.Nullable;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Parse a query for a live snippet and read the code from Git */
class LiveSnippetHandler {
  public static final String LANGUAGE_TYPE = "live_snippet";

  private static final String FILE_QUERY = "file:";
  private static final String CONTENT_QUERY = "content:";
  private static final Logger log = LoggerFactory.getLogger(LiveSnippetHandler.class);

  private final ObjectReader reader;
  private final GitilesView view;
  private final RevTree root;

  public String filePath;
  public Pattern contentMatcher;

  LiveSnippetHandler(ObjectReader reader, GitilesView view, RevTree root) {
    this.reader = reader;
    this.view = view;
    this.root = root;
  }

  private void resetStates() {
    filePath = null;
    contentMatcher = null;
  }

  void parseQuery(@Nullable String markdownPath, String query) {
    resetStates();

    int file_match = query.indexOf(FILE_QUERY);
    if (file_match == -1) {
      return;
    }

    int content_match = query.indexOf(CONTENT_QUERY);
    String content_re = null;
    if (content_match == -1) {
      filePath = query.substring(file_match + FILE_QUERY.length()).trim();
    } else {
      if (file_match < content_match) {
        filePath = query.substring(file_match + FILE_QUERY.length(), content_match - 1).trim();
        content_re = query.substring(content_match + CONTENT_QUERY.length()).trim();
      } else {
        file_match = query.lastIndexOf(FILE_QUERY);
        filePath = query.substring(file_match + FILE_QUERY.length()).trim();
        content_re = query.substring(content_match + CONTENT_QUERY.length(), file_match - 1).trim();
      }
    }
    String[] splited = filePath.split("\\s+");
    filePath = splited[0];

    filePath = PathResolver.resolve(markdownPath, filePath);
    if (filePath != null && content_re != null && !content_re.isEmpty()) {
      try {
        contentMatcher = Pattern.compile(content_re);
      } catch (PatternSyntaxException err) {
        contentMatcher = null;
        log.error(String.format("Pattern has syntax error: %s", err.getMessage()));
      }
    }
  }

  String getLanguage() {
    return filePath == null ? "" : FileTypeMaps.getLanguageType(filePath);
  }

  String getSnippet() {
    if (filePath == null) {
      return null;
    }

    try {
      TreeWalk tw = TreeWalk.forPath(reader, filePath, root);
      if (tw == null || tw.getFileMode(0) != FileMode.REGULAR_FILE) {
        return null;
      }

      ObjectId id = tw.getObjectId(0);
      byte[] raw = reader.open(id).getCachedBytes();
      if (contentMatcher != null) {
        Matcher m = contentMatcher.matcher(new String(raw, StandardCharsets.UTF_8));
        if (m.find()) {
          String result = m.group(0);
          return result;
        }
      }
    } catch (Exception err) {
      String repo = view != null ? view.getRepositoryName() : "<unknown>";
      log.error(
          String.format("cannot read repo %s file %s from %s", repo, filePath, root.name()), err);
    }
    return null;
  }
}
