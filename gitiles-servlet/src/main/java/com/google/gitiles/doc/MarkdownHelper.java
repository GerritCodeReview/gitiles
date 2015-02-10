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

import static org.pegdown.Extensions.ALL;
import static org.pegdown.Extensions.HARDWRAPS;
import static org.pegdown.Extensions.SUPPRESS_ALL_HTML;

import com.google.common.base.Strings;
import com.google.gitiles.GitilesView;
import com.google.gitiles.doc.DocServlet.SourceFile;
import com.google.gitiles.doc.html.GitLinkRenderer;
import com.google.template.soy.shared.restricted.Sanitizers;

import org.pegdown.ParsingTimeoutException;
import org.pegdown.PegDownProcessor;
import org.pegdown.ast.HeaderNode;
import org.pegdown.ast.Node;
import org.pegdown.ast.ReferenceNode;
import org.pegdown.ast.RootNode;
import org.pegdown.ast.TextNode;
import org.pegdown.plugins.PegDownPlugins;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class MarkdownHelper {
  private static final Logger log = LoggerFactory.getLogger(MarkdownHelper.class);

  // SUPPRESS_ALL_HTML is enabled to permit hosting arbitrary user content
  // while avoiding XSS style HTML, CSS and JavaScript injection attacks.
  //
  // HARDWRAPS is disabled to permit line wrapping within paragraphs to
  // make the source file easier to read in 80 column terminals without
  // this impacting the rendered formatting.
  static final int MD_OPTIONS = (ALL | SUPPRESS_ALL_HTML) & ~(HARDWRAPS);

  static RootNode parseMarkdown(String markdownSource) {
    PegDownPlugins plugins = new PegDownPlugins.Builder()
        .withPlugin(GitilesMarkdown.class)
        .build();
    return new PegDownProcessor(MD_OPTIONS, plugins)
        .parseMarkdown(markdownSource.toCharArray());
  }

  static RootNode parseFile(GitilesView view, SourceFile md) {
    if (md == null) {
      return null;
    }

    RootNode docTree;
    try {
      docTree = MarkdownHelper.parseMarkdown(md.text);
    } catch (ParsingTimeoutException e) {
      log.error("timeout rendering {}/{} at {}",
          view.getRepositoryName(),
          md.path,
          view.getRevision().getName());
      return null;
    }
    if (docTree == null) {
      log.error("cannot parse {}/{} at {}",
          view.getRepositoryName(),
          md.path,
          view.getRevision().getName());
    }
    return docTree;
  }

  /** Combine child nodes as string; this must be escaped for HTML. */
  public static String getInnerText(Node h) {
    List<Node> ch = h.getChildren();
    if (ch == null || ch.isEmpty()) {
      return null;
    }

    StringBuilder b = new StringBuilder();
    for (Node n : ch) {
      if (n instanceof TextNode) {
        b.append(((TextNode) n).getText());
      }
    }
    return Strings.emptyToNull(b.toString().trim());
  }

  static String getTitle(Node root) {
    if (root instanceof HeaderNode) {
      HeaderNode h = (HeaderNode) root;
      if (h.getLevel() == 1) {
        return getInnerText(h);
      }
    }

    List<Node> ch = root.getChildren();
    if (ch != null) {
      for (Node n : ch) {
        String title = getTitle(n);
        if (title != null) {
          return title;
        }
      }
    }
    return null;
  }

  static Map<String, Object> banner(GitilesView view, RootNode nav) {
    Map<String, Object> data = new HashMap<>();
    data.put("siteTitle", null);
    data.put("logoUrl", null);
    data.put("homeUrl", null);

    if (nav == null) {
      return data;
    }

    for (Iterator<Node> i = nav.getChildren().iterator(); i.hasNext();) {
      Node n = i.next();
      if (n instanceof HeaderNode) {
        HeaderNode h = (HeaderNode) n;
        if (h.getLevel() == 1) {
          data.put("siteTitle", getInnerText(h));
          i.remove();
          break;
        }
      }
    }

    for (ReferenceNode r : nav.getReferences()) {
      String key = getInnerText(r);
      String url = r.getUrl();
      if ("logo".equalsIgnoreCase(key)) {
        Object src;
        if (GitLinkRenderer.isImageDataUrl(url)) {
          src = Sanitizers.filterImageDataUri(url);
        } else {
          src = url;
        }
        data.put("logoUrl", src);
      } else if ("home".equalsIgnoreCase(key)) {
        if (GitLinkRenderer.isAbsoluteMarkdown(url)) {
          url = GitilesView.doc().copyFrom(view).setPathPart(url).toUrl();
        }
        data.put("homeUrl", url);
      }
    }
    return data;
  }

  private MarkdownHelper() {
  }
}
