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
import com.google.template.soy.shared.restricted.EscapingConventions;
import com.google.template.soy.shared.restricted.EscapingConventions.FilterImageDataUri;
import com.google.template.soy.shared.restricted.EscapingConventions.FilterNormalizeUri;

import org.pegdown.LinkRenderer;
import org.pegdown.ast.AutoLinkNode;
import org.pegdown.ast.ExpImageNode;
import org.pegdown.ast.ExpLinkNode;
import org.pegdown.ast.RefImageNode;
import org.pegdown.ast.RefLinkNode;
import org.pegdown.ast.WikiLinkNode;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** Resolves {@code [foo](/path/to.md)} as relative to repository root. */
class GitLinkRenderer extends LinkRenderer {
  private static final FilterNormalizeUri URI =
      EscapingConventions.FilterNormalizeUri.INSTANCE;

  private static final FilterImageDataUri IMAGE_DATA =
      EscapingConventions.FilterImageDataUri.INSTANCE;

  private final GitilesView view;

  GitLinkRenderer(GitilesView view) {
    this.view = view;
  }

  @Override
  public Rendering render(AutoLinkNode node) {
    String url = node.getText();
    return renderLink(url, null, url);
  }

  @Override
  public Rendering render(ExpLinkNode node, String text) {
    return renderLink(node.url, node.title, text);
  }

  @Override
  public Rendering render(RefLinkNode node, String url,
      String title, String text) {
    return renderLink(url, title, text);
  }

  @Override
  public Rendering render(WikiLinkNode node) {
    try {
      String path = node.getText().replace(' ', '-') + ".md";
      String url = URLEncoder.encode(path, StandardCharsets.UTF_8.name());
      return renderLink(url, null, node.getText());
    } catch (UnsupportedEncodingException e) {
      throw new IllegalStateException();
    }
  }

  Rendering renderLink(String url, String title, String text) {
    if (isAbsoluteMarkdown(url)) {
      url = getMarkdownUrl(url);
    }

    String href;
    if (URI.getValueFilter().matcher(url).find()) {
      href = URI.escape(url);
    } else {
      href = URI.getInnocuousOutput();
    }

    Rendering r = new Rendering(href, text);
    if (!Strings.isNullOrEmpty(title)) {
      r = r.withAttribute("title", encode(title));
    }
    return r;
  }

  @Override
  public Rendering render(ExpImageNode node, String text) {
    return renderImage(node.url, node.title, text);
  }

  @Override
  public Rendering render(RefImageNode node,
      String url, String title, String alt) {
    return renderImage(url, title, alt);
  }

  private Rendering renderImage(String url, String title, String alt) {
    String src;
    if ((url.startsWith("http:") || url.startsWith("https:"))
        && URI.getValueFilter().matcher(url).find()) {
      src = URI.escape(url);
    } else if (isImageDataUrl(url)) {
      src = url;
    } else {
      src = IMAGE_DATA.getInnocuousOutput();
    }

    Rendering r = new Rendering(src, alt);
    if (!Strings.isNullOrEmpty(title)) {
      r = r.withAttribute("title", encode(title));
    }
    return r;
  }

  static boolean isImageDataUrl(String url) {
    return IMAGE_DATA.getValueFilter().matcher(url).find();
  }

  static boolean isAbsoluteMarkdown(String url) {
    return url.length() > 2
        && url.endsWith(".md")
        && url.charAt(0) == '/' && url.charAt(1) != '/';
  }

  String getMarkdownUrl(String url) {
    return GitilesView.doc().copyFrom(view)
        .setPathPart(url.substring(1))
        .toUrl();
  }
}
