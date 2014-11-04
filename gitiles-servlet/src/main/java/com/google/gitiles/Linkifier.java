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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.base.Joiner;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;

import org.eclipse.jgit.lib.Config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.servlet.http.HttpServletRequest;

/** Linkifier for blocks of text such as commit message descriptions. */
public class Linkifier {
  private static final String COMMENTLINK = "commentlink";
  private static final Pattern HTTP_URL_PATTERN;
  private static final Pattern CHANGE_ID_PATTERN;

  static {
    // HTTP URL regex adapted from com.google.gwtexpui.safehtml.client.SafeHtml.
    String httpPart = "(?:" + "[a-zA-Z0-9$_.+!*',%;:@=?#/~<>-]" + "|&(?!lt;|gt;)" + ")";
    String httpPattern = "https?://" + httpPart + "{2,}" + "(?:[(]"
                         + httpPart + "*" + "[)])*" + httpPart + "*";
    HTTP_URL_PATTERN = Pattern.compile(httpPattern);
    CHANGE_ID_PATTERN = Pattern.compile("\\bI[0-9a-f]{8,40}\\b");
  }

  private final GitilesUrls urls;
  private final List<CommentLinkInfo> commentLinks;
  private final Pattern allPattern;

  public Linkifier(GitilesUrls urls, Config config) {
    this.urls = checkNotNull(urls, "urls");

    List<CommentLinkInfo> list = new ArrayList<>();
    list.add(new CommentLinkInfo("StandardHtmlLinks", HTTP_URL_PATTERN, "", ""));
    Set<String> subsections = config.getSubsections(COMMENTLINK);

    List<String> patterns = new ArrayList<>();

    patterns.add(HTTP_URL_PATTERN.pattern());
    patterns.add(CHANGE_ID_PATTERN.pattern());

    for (String subsection : subsections) {
      String match = config.getString("commentlink", subsection, "match");
      String link = config.getString("commentlink", subsection, "link");
      String html = config.getString("commentlink", subsection, "html");
      checkArgument(!Strings.isNullOrEmpty(match), "invalid commentlink.%s.match", subsection);
      Pattern compiledPattern = Pattern.compile(match);
      list.add(new CommentLinkInfo(subsection, compiledPattern, link, html));
      patterns.add(compiledPattern.pattern());
    }
    this.commentLinks = Collections.unmodifiableList(list);

    allPattern = Pattern.compile(Joiner.on('|').join(patterns));
  }

  public List<Map<String, String>> linkify(HttpServletRequest req, String message) {
    // Because we're relying on 'req' as a dynamic parameter, we need to construct
    // the CommentLinkInfo for ChangeIds on the fly.
    String baseGerritUrl = urls.getBaseGerritUrl(req);
    CommentLinkInfo changeIds =
        new CommentLinkInfo("ChangeIdLinks", CHANGE_ID_PATTERN, baseGerritUrl + "#/q/$2,n,z", "");

    List<CommentLinkInfo> operationalCommentLinks = new ArrayList<>(commentLinks);
    operationalCommentLinks.add(changeIds);

    List<Map<String, String>> parsed = Lists.newArrayList();
    parsed.add(ImmutableMap.of("text", message));
    for (int index = 0; index < parsed.size(); index++) {
      Map<String, String> piece = parsed.get(index);
      if (piece.get("url") == null) {
        String text = piece.get("text");
        Matcher m = allPattern.matcher(text);
        if (m.find()) {
          // Go through the individual regexps now for replacement.
          for (CommentLinkInfo cli : operationalCommentLinks) {
            List<Map<String, String>> resultingReplacement = cli.replace(piece.get("text"));
            if (resultingReplacement != null) {
              parsed.remove(index);
              for (Map<String, String> it : resultingReplacement) {
                parsed.add(index, it);
                index++;
              }
            }
          }
        }
      }
    }
    return parsed;
  }
}
