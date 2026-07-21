// Copyright (C) 2026 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.gitiles.doc;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.html.types.SafeHtml;
import com.google.gitiles.GitilesView;
import org.commonmark.node.Node;
import org.eclipse.jgit.lib.Config;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for YAML front matter handling in {@link GitilesMarkdown}. */
@RunWith(JUnit4.class)
public class GitilesMarkdownTest {
  private static final String MARKDOWN =
      "---\ntitle: Kittens\nlayout: post\n---\n\n# Heading\n\nBody text.\n";

  @Test
  public void frontMatterStrippedWhenEnabled() {
    String html = render(MARKDOWN, /* frontMatter= */ true);
    assertThat(html).doesNotContain("title: Kittens");
    assertThat(html).doesNotContain("layout: post");
    assertThat(html).contains("Heading</h1>");
    assertThat(html).contains("Body text.");
  }

  @Test
  public void frontMatterNodeChildrenAreStrippedWithoutError() {
    // Front matter parses into a YamlFrontMatterBlock whose YamlFrontMatterNode
    // (CustomNode) children hold the key/value data, including list values.
    // visit(CustomBlock) omits the block without recursing, so those children
    // are never visited: rendering neither throws (as it would for an unhandled
    // CustomNode) nor leaks the metadata.
    String markdown =
        "---\n"
            + "title: Kittens\n"
            + "tags:\n"
            + "  - cats\n"
            + "  - fluffy\n"
            + "---\n"
            + "\n"
            + "# Heading\n"
            + "\n"
            + "Body text.\n";
    String html = render(markdown, /* frontMatter= */ true);
    assertThat(html).doesNotContain("Kittens");
    assertThat(html).doesNotContain("cats");
    assertThat(html).doesNotContain("fluffy");
    assertThat(html).contains("Heading</h1>");
    assertThat(html).contains("Body text.");
  }

  @Test
  public void frontMatterRenderedAsContentWhenDisabled() {
    // Without the extension the delimiters and keys are parsed as ordinary
    // Markdown, leaking the raw metadata into the output. This is the behavior
    // markdown.frontmatter is meant to fix.
    String html = render(MARKDOWN, /* frontMatter= */ false);
    assertThat(html).contains("title: Kittens");
  }

  private static String render(String markdown, boolean frontMatter) {
    Config cfg = new Config();
    cfg.setBoolean("markdown", null, "frontmatter", frontMatter);
    MarkdownConfig mc = new MarkdownConfig(cfg);
    Node node = GitilesMarkdown.parse(mc, markdown);
    GitilesView view =
        GitilesView.revision()
            .setHostName("127.0.0.1")
            .setServletPath("/g")
            .setRepositoryName("repo")
            .setRevision("HEAD")
            .build();
    SafeHtml html =
        MarkdownToHtml.builder()
            .setGitilesView(view)
            .setConfig(mc)
            .setFilePath("index.md")
            .build()
            .toSoyHtml(node);
    return html == null ? "" : html.getSafeHtmlString();
  }
}
