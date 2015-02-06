// Copyright 2015 Google Inc. All Rights Reserved.
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

import static org.pegdown.FastEncoder.encode;

import com.google.common.base.Strings;
import com.google.gitiles.GitilesView;

import org.pegdown.LinkRenderer;
import org.pegdown.ast.ExpLinkNode;

class GitLinkRenderer extends LinkRenderer {
  private final GitilesView view;

  GitLinkRenderer(GitilesView view) {
    this.view = view;
  }

  @Override
  public Rendering render(ExpLinkNode node, String text) {
    String url = node.url;
    if (url.length() > 2
        && url.endsWith(".md")
        && url.charAt(0) == '/' && url.charAt(1) != '/') {
      url = GitilesView.doc().copyFrom(view)
          .setPathPart(url.substring(1))
          .toUrl();
    }

    Rendering r = new Rendering(url, text);
    if (!Strings.isNullOrEmpty(node.title)) {
      r = r.withAttribute("title", encode(node.title));
    }
    return r;
  }
}
