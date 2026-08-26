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

  @Test
  public void renderMermaidDiagram() {
    String md =
        "```mermaid\n"
            + "graph LR\n"
            + "    subgraph CoreGarden\n"
            + "        A[\"Fluffy Puppy\"]\n"
            + "    end\n"
            + "    subgraph GreenLawn\n"
            + "        B[\"Playful Kitten\"]\n"
            + "    end\n"
            + "    B --> A\n"
            + "```\n";
    Config cfg = new Config();
    cfg.setBoolean("markdown", null, "mermaid", true);
    MarkdownConfig mc = new MarkdownConfig(cfg);
    Node node = GitilesMarkdown.parse(mc, md);
    SafeHtml html =
        MarkdownToHtml.builder()
            .setConfig(mc)
            .setFilePath("index.md")
            .build()
            .toSoyHtml(node);
    assertThat(html).isNotNull();
    String htmlStr = html.getSafeHtmlString();
    assertThat(htmlStr).contains("class=\"mermaid-container\"");
    assertThat(htmlStr).contains("<svg class=\"mermaid-svg\"");
    assertThat(htmlStr).contains("Fluffy Puppy");
    assertThat(htmlStr).contains("Playful Kitten");
  }

  @Test
  public void renderMermaidWithSubgraphAndDAG() {
    String md =
        "```mermaid\n"
            + "graph TD\n"
            + "    ClientApp[Little Puppy Plays] --> Extras(Sweet Kitten)\n"
            + "    ClientApp --> Utils(Happy Bunny)\n"
            + "    Utils --> ServiceDiscovery[Red Apple Berry]\n"
            + "    Utils --> ModelManager[Yellow Banana Snack]\n"
            + "    Extras --> Recognition(Fluffy Duckling)\n"
            + "    Recognition --> SODA(Green Frog Jump)\n"
            + "    Recognition --> S3(Sunny Daisy Flower)\n"
            + "    subgraph Play Park Garden\n"
            + "        Executors(Teddy Bear)\n"
            + "        Errors(Wooden Blocks)\n"
            + "        Protos(Toy Wagon)\n"
            + "    end\n"
            + "    Recognition -.-> PlayParkGarden\n"
            + "```\n";
    Config cfg = new Config();
    cfg.setBoolean("markdown", null, "mermaid", true);
    MarkdownConfig mc = new MarkdownConfig(cfg);
    Node node = GitilesMarkdown.parse(mc, md);
    SafeHtml html =
        MarkdownToHtml.builder()
            .setConfig(mc)
            .setFilePath("index.md")
            .build()
            .toSoyHtml(node);
    assertThat(html).isNotNull();
    String htmlStr = html.getSafeHtmlString();
    assertThat(htmlStr).contains("Play Park Garden");
    assertThat(htmlStr).contains("Little Puppy Plays");
    assertThat(htmlStr).contains("Sweet Kitten");
    assertThat(htmlStr).contains("Happy Bunny");
    assertThat(htmlStr).contains("Teddy Bear");
    assertThat(htmlStr).contains("Wooden Blocks");
    assertThat(htmlStr).contains("Toy Wagon");
  }

  @Test
  public void renderMermaidWithQuotedBracketsAndDiamond() {
    String md =
        "```mermaid\n"
            + "graph TD\n"
            + "    A[\"Happy little bunny jumps\"]\n"
            + "    B[\"Locate [TeddyBear] in garden\"]\n"
            + "    A --> B\n"
            + "    B --> C[\"Sing Sweet Melody\"]\n"
            + "    C --> D[\"Dance Around Blossom Tree\"]\n"
            + "    E{\"Is kitten happy & pure?\"}\n"
            + "    E -- Yes --> F[\"Give Tasty Cookie Treat\"]\n"
            + "    F --> G[\"Play With Soft Yarn Ball\"]\n"
            + "    E -- No --> H[\"Read Gentle Story Book\"]\n"
            + "    H --> I[\"Warm Cozy Blanket Nap\"]\n"
            + "    G --> J[\"Wake Up In Morning Sun\"]\n"
            + "    I --> J\n"
            + "    J --> K[\"Smile At Rainbow Sky\"]\n"
            + "```\n";
    Config cfg = new Config();
    cfg.setBoolean("markdown", null, "mermaid", true);
    MarkdownConfig mc = new MarkdownConfig(cfg);
    Node node = GitilesMarkdown.parse(mc, md);
    SafeHtml html =
        MarkdownToHtml.builder()
            .setConfig(mc)
            .setFilePath("index.md")
            .build()
            .toSoyHtml(node);
    assertThat(html).isNotNull();
    String htmlStr = html.getSafeHtmlString();
    assertThat(htmlStr).contains("Is kitten happy &amp;");
    assertThat(htmlStr).contains("pure?");
  }

  @Test
  public void renderMermaidWithBidirectionalEdges() {
    String md =
        "```mermaid\n"
            + "graph TD\n"
            + "    Puppy[\"Fluffy Puppy\"]\n"
            + "    Kitten[\"Playful Kitten\"]\n"
            + "    Bunny[\"Little Bunny\"]\n"
            + "    Puppy -->|Roll Ball| Kitten\n"
            + "    Kitten -->|Chase Toy| Bunny\n"
            + "    Bunny -->|Share Snack| Kitten\n"
            + "    Kitten -->|Give Hug| Puppy\n"
            + "```\n";
    Config cfg = new Config();
    cfg.setBoolean("markdown", null, "mermaid", true);
    MarkdownConfig mc = new MarkdownConfig(cfg);
    Node node = GitilesMarkdown.parse(mc, md);
    SafeHtml html =
        MarkdownToHtml.builder()
            .setConfig(mc)
            .setFilePath("index.md")
            .build()
            .toSoyHtml(node);
    assertThat(html).isNotNull();
    String htmlStr = html.getSafeHtmlString();
    assertThat(htmlStr).contains("Fluffy Puppy");
    assertThat(htmlStr).contains("Playful Kitten");
    assertThat(htmlStr).contains("Roll Ball");
    assertThat(htmlStr).contains("Give Hug");
  }

  @Test
  public void testMarkdownConfigIFrameAndExtensions() {
    Config cfg = new Config();
    cfg.setBoolean("markdown", null, "safehtml", true);
    cfg.setString("markdown", null, "allowiframe", "https://example.com/embed/");
    MarkdownConfig mc = new MarkdownConfig(cfg);
    assertThat(mc.isIFrameAllowed("https://example.com/embed/123")).isTrue();
    assertThat(mc.isIFrameAllowed("https://other.com/embed/123")).isFalse();

    Config anyCfg = new Config();
    anyCfg.setBoolean("markdown", null, "safehtml", true);
    anyCfg.setBoolean("markdown", null, "allowiframe", true);
    MarkdownConfig anyMc = new MarkdownConfig(anyCfg);
    assertThat(anyMc.isIFrameAllowed("https://anything.com")).isTrue();

    MarkdownConfig copied = mc.copyWithExtensions(
        java.util.Collections.singleton("toc"),
        java.util.Collections.singleton("autolink"));
    assertThat(copied).isNotNull();
  }

  @Test
  public void testMarkdownConfigMermaidDisabledFallsBackToCodeBlock() {
    String md = "```mermaid\ngraph TD\n  A --> B\n```\n";
    Config cfg = new Config();
    cfg.setBoolean("markdown", null, "mermaid", false);
    MarkdownConfig mc = new MarkdownConfig(cfg);
    Node node = GitilesMarkdown.parse(mc, md);
    SafeHtml html =
        MarkdownToHtml.builder()
            .setConfig(mc)
            .setFilePath("index.md")
            .build()
            .toSoyHtml(node);
    assertThat(html).isNotNull();
    String htmlStr = html.getSafeHtmlString();
    // When disabled, it renders as a code pre block, not a mermaid svg container
    assertThat(htmlStr).doesNotContain("class=\"mermaid-container\"");
    assertThat(htmlStr).contains("<pre class=\"code\">");
    assertThat(htmlStr).contains("graph TD");
  }

  @Test
  public void testSecurityMermaidMarkdownEndToEndNoScriptOrIframeInjection() {
    String md =
        "# Diagram Title\n\n"
            + "```mermaid\n"
            + "graph TD\n"
            + "  A[\"<script>alert('xss-1')</script>\"]\n"
            + "  B[\"<iframe src='javascript:alert(2)'></iframe>\"]\n"
            + "  C[\"<img src=x onerror=alert('xss-3')>\"]\n"
            + "  D[\"<foreignObject><iframe src='https://evil.com'></iframe></foreignObject>\"]\n"
            + "  A -->|\"<script>alert('edge')</script>\"| B\n"
            + "  B --> C --> D\n"
            + "  click A href \"javascript:alert('click')\"\n"
            + "```\n";

    Config cfg = new Config();
    cfg.setBoolean("markdown", null, "mermaid", true);
    MarkdownConfig mc = new MarkdownConfig(cfg);
    Node node = GitilesMarkdown.parse(mc, md);
    SafeHtml html =
        MarkdownToHtml.builder()
            .setConfig(mc)
            .setFilePath("index.md")
            .build()
            .toSoyHtml(node);

    assertThat(html).isNotNull();
    String htmlStr = html.getSafeHtmlString();

    // Verify rendered HTML has the mermaid-container and SVG
    assertThat(htmlStr).contains("<div class=\"mermaid-container\"><svg class=\"mermaid-svg\"");
    String mermaidHtml = htmlStr.substring(htmlStr.indexOf("<div class=\"mermaid-container\">"));

    // Verify absolutely no executable script, iframe, or event handlers exist in Mermaid HTML
    assertThat(mermaidHtml).doesNotContain("<script");
    assertThat(mermaidHtml).doesNotContain("<iframe");
    assertThat(mermaidHtml).doesNotContain("<foreignObject");
    assertThat(mermaidHtml).doesNotContain("<img");
    assertThat(mermaidHtml).doesNotContain("<a ");

    // Verify payload is safely escaped as XML text inside SVG tspans
    assertThat(htmlStr).contains("&lt;script&gt;alert(&apos;xss-1&apos;)&lt;/script&gt;");
    assertThat(htmlStr).contains("&lt;iframe src=&apos;javascript:alert(2)&apos;&gt;&lt;/iframe&gt;");
    assertThat(htmlStr).contains("&lt;img src=x onerror=alert(&apos;xss-3&apos;)&gt;");
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
