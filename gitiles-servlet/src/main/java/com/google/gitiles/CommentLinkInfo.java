// Copyright 2014 Google Inc. All Rights Reserved.
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

import static com.google.common.base.Preconditions.checkArgument;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CommentLinkInfo {
  private final String link;
  private final String html;
  private final String name;

  public final Pattern pattern;

  public CommentLinkInfo(String name, Pattern compiledMatch, String link, String html) {
    checkArgument(name != null, "invalid commentlink.name");
    link = Strings.emptyToNull(link);
    html = Strings.emptyToNull(html);
    this.name = name;
    this.link = link;
    this.html = html;
    this.pattern = compiledMatch;
  }

  public List<Map<String, String>> replace(String input) {
    List<Map<String, String>> parsed = Lists.newArrayList();
    Matcher m = pattern.matcher(input);
    int last = 0;
    while (m.find()) {
      addText(parsed, input.substring(last, m.start()));
      addLink(parsed, m.group(0), m.group(0));
      last = m.end();
    }
    addText(parsed, input.substring(last));
    return parsed;
  }

  private static void addLink(List<Map<String, String>> parts, String text, String url) {
    parts.add(ImmutableMap.of("text", text, "url", url));
  }

  private static void addText(List<Map<String, String>> parts, String text) {
    if (text.isEmpty()) {
      return;
    }
    if (parts.isEmpty()) {
      parts.add(ImmutableMap.of("text", text));
    } else {
      Map<String, String> old = parts.get(parts.size() - 1);
      if (!old.containsKey("url")) {
        parts.set(parts.size() - 1, ImmutableMap.of("text", old.get("text") + text));
      } else {
        parts.add(ImmutableMap.of("text", text));
      }
    }
  }
}
