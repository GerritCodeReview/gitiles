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

package com.google.gitiles.doc.html;

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

/** Sanitizes URLs by applying Soy's URI functions to them. */
public class GitLinkRenderer extends LinkRenderer {
  private static final FilterNormalizeUri URI =
      EscapingConventions.FilterNormalizeUri.INSTANCE;

  private static final FilterImageDataUri IMAGE_DATA =
      EscapingConventions.FilterImageDataUri.INSTANCE;

  private final GitilesView view;

  GitLinkRenderer(GitilesView view) {
    this.view = view;
  }

  @Override
  public Rendering render(ExpLinkNode node, String textEscaped) {
    return renderLink(node.url, node.title, textEscaped);
  }

  @Override
  public Rendering render(RefLinkNode node, String url,
      String title, String textEscaped) {
    return renderLink(url, title, textEscaped);
  }

  @Override
  public Rendering render(AutoLinkNode node) {
    String url = node.getText();
    return renderLink(url, null, encode(url));
  }

  @Override
  public Rendering render(WikiLinkNode node) {
    String text = node.getText();
    String url = text.replace(' ', '-') + ".md";
    return renderLink(url, null, encode(text));
  }

  private Rendering renderLink(String url, String title, String textEscaped) {
    if (isAbsoluteMarkdown(url)) {
      url = GitilesView.doc().copyFrom(view).setPathPart(url).toUrl();
    }

    String href;
    if (URI.getValueFilter().matcher(url).find()) {
      href = URI.escape(url);
    } else {
      href = URI.getInnocuousOutput();
    }

    Rendering r = new Rendering(href, textEscaped);
    if (!Strings.isNullOrEmpty(title)) {
      r = r.withAttribute("title", encode(title));
    }
    return r;
  }

  @Override
  public Rendering render(ExpImageNode node, String altEscaped) {
    return renderImage(node.url, node.title, altEscaped);
  }

  @Override
  public Rendering render(RefImageNode node,
      String url, String title, String altEscaped) {
    return renderImage(url, title, altEscaped);
  }

  private Rendering renderImage(String url, String title, String altEscaped) {
    String src;
    if ((url.startsWith("http:") || url.startsWith("https:"))
        && URI.getValueFilter().matcher(url).find()) {
      src = URI.escape(url);
    } else if (isImageDataUrl(url)) {
      src = url;
    } else {
      src = IMAGE_DATA.getInnocuousOutput();
    }

    Rendering r = new Rendering(src, altEscaped);
    if (!Strings.isNullOrEmpty(title)) {
      r = r.withAttribute("title", encode(title));
    }
    return r;
  }

  public static boolean isImageDataUrl(String url) {
    return IMAGE_DATA.getValueFilter().matcher(url).find();
  }

  public static boolean isAbsoluteMarkdown(String url) {
    return url.length() > 2
        && url.endsWith(".md")
        && url.charAt(0) == '/' && url.charAt(1) != '/';
  }
}
