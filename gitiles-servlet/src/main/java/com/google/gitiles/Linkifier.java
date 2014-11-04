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

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;

import org.eclipse.jgit.lib.Config;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Vector;

import javax.servlet.http.HttpServletRequest;

/** Linkifier for blocks of text such as commit message descriptions. */
public class Linkifier {
  private static final String COMMENTLINK = "commentlink";
  private Vector<CommentLinkInfo> mCommentLinks;

  // HTTP URL regex adapted from com.google.gwtexpui.safehtml.client.SafeHtml.
  static final String httpPart = "(?:" + "[a-zA-Z0-9$_.+!*',%;:@=?#/~<>-]"
      + "|&(?!lt;|gt;)" + ")";
  static final String httpUrl = "https?://" + httpPart + "{2,}" + "(?:[(]"
      + httpPart + "*" + "[)])*" + httpPart + "*";

  static final String changeId = "\\bI[0-9a-f]{8,40}\\b";

  private final GitilesUrls urls;

  public Linkifier(GitilesUrls urls, Config config) {
    this.urls = checkNotNull(urls, "urls");
    this.mCommentLinks = new Vector<CommentLinkInfo>();

    CommentLinkInfo http =
        new CommentLinkInfo("StandardHtmlLinks", httpUrl, "", "");
    mCommentLinks.add(http);
    
    Set<String> subSections = config.getSubsections(COMMENTLINK);
    for (String subSection : subSections) {
      String match = config.getString("commentlink", subSection, "match");
      String link = config.getString("commentlink", subSection, "link");
      String html = config.getString("commentlink", subSection, "link");
      mCommentLinks.add(new CommentLinkInfo(subSection, match, link, html));
    }
  }
  
  // We cannot have all user provided commentlinks in one regular expression
  // because in these comment links we may have references to groups by number and name.
  // To prevent any possible interference between the regular expressions, we use a matcher
  // for each of its own.
  public List<Map<String, String>> linkify(HttpServletRequest req, String message) {
    // Because we're relying on 'req' as a dynamic parameter, we need to construct
    // the CommentLinkInfo for ChangeIds on the fly
    String baseGerritUrl = urls.getBaseGerritUrl(req);
    CommentLinkInfo changeIds =
        new CommentLinkInfo("ChangeIdLinks", changeId, baseGerritUrl + "#/q/$2,n,z", "");
    mCommentLinks.add(changeIds);
    
    List<Map<String, String>> parsed = Lists.newArrayList();
    parsed.add(ImmutableMap.of("text", message));
    for (CommentLinkInfo clp : mCommentLinks) {
      for (int index = 0; index < parsed.size(); index++) {
        Map<String, String> piece = parsed.get(index);
        if (piece.get("url") == null) {
          List<Map<String, String>> resultingReplacement =
              clp.replace(piece.get("text"));
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
    // remove the ChangeIds CommentLink
    mCommentLinks.remove(mCommentLinks.size() - 1);
     
    return parsed;
  }
}
