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

import com.google.common.collect.ImmutableSet;
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
        """
        ---
        title: Kittens
        tags:
          - cats
          - fluffy
        ---

        # Heading

        Body text.
        """;
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
        """
        ```mermaid
        graph LR
            subgraph CoreGarden
                A["Fluffy Puppy"]
            end
            subgraph GreenLawn
                B["Playful Kitten"]
            end
            B --> A
        ```
        """;
    Config cfg = new Config();
    cfg.setBoolean("markdown", null, "mermaid", true);
    MarkdownConfig mc = new MarkdownConfig(cfg);
    Node node = GitilesMarkdown.parse(mc, md);
    SafeHtml html =
        MarkdownToHtml.builder().setConfig(mc).setFilePath("index.md").build().toSoyHtml(node);
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
        """
        ```mermaid
        graph TD
            ClientApp[Little Puppy Plays] --> Extras(Sweet Kitten)
            ClientApp --> Utils(Happy Bunny)
            Utils --> ServiceDiscovery[Red Apple Berry]
            Utils --> ModelManager[Yellow Banana Snack]
            Extras --> Recognition(Fluffy Duckling)
            Recognition --> SODA(Green Frog Jump)
            Recognition --> S3(Sunny Daisy Flower)
            subgraph Play Park Garden
                Executors(Teddy Bear)
                Errors(Wooden Blocks)
                Protos(Toy Wagon)
            end
            Recognition -.-> PlayParkGarden
        ```
        """;
    Config cfg = new Config();
    cfg.setBoolean("markdown", null, "mermaid", true);
    MarkdownConfig mc = new MarkdownConfig(cfg);
    Node node = GitilesMarkdown.parse(mc, md);
    SafeHtml html =
        MarkdownToHtml.builder().setConfig(mc).setFilePath("index.md").build().toSoyHtml(node);
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
        """
        ```mermaid
        graph TD
            A["Happy little bunny jumps"]
            B["Locate [TeddyBear] in garden"]
            A --> B
            B --> C["Sing Sweet Melody"]
            C --> D["Dance Around Blossom Tree"]
            E{"Is kitten happy & pure?"}
            E -- Yes --> F["Give Tasty Cookie Treat"]
            F --> G["Play With Soft Yarn Ball"]
            E -- No --> H["Read Gentle Story Book"]
            H --> I["Warm Cozy Blanket Nap"]
            G --> J["Wake Up In Morning Sun"]
            I --> J
            J --> K["Smile At Rainbow Sky"]
        ```
        """;
    Config cfg = new Config();
    cfg.setBoolean("markdown", null, "mermaid", true);
    MarkdownConfig mc = new MarkdownConfig(cfg);
    Node node = GitilesMarkdown.parse(mc, md);
    SafeHtml html =
        MarkdownToHtml.builder().setConfig(mc).setFilePath("index.md").build().toSoyHtml(node);
    assertThat(html).isNotNull();
    String htmlStr = html.getSafeHtmlString();
    assertThat(htmlStr).contains("Is kitten happy &amp;");
    assertThat(htmlStr).contains("pure?");
  }

  @Test
  public void renderMermaidWithBidirectionalEdges() {
    String md =
        """
        ```mermaid
        graph TD
            Puppy["Fluffy Puppy"]
            Kitten["Playful Kitten"]
            Bunny["Little Bunny"]
            Puppy -->|Roll Ball| Kitten
            Kitten -->|Chase Toy| Bunny
            Bunny -->|Share Snack| Kitten
            Kitten -->|Give Hug| Puppy
        ```
        """;
    Config cfg = new Config();
    cfg.setBoolean("markdown", null, "mermaid", true);
    MarkdownConfig mc = new MarkdownConfig(cfg);
    Node node = GitilesMarkdown.parse(mc, md);
    SafeHtml html =
        MarkdownToHtml.builder().setConfig(mc).setFilePath("index.md").build().toSoyHtml(node);
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

    MarkdownConfig copied =
        mc.copyWithExtensions(ImmutableSet.of("toc"), ImmutableSet.of("autolink"));
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
        MarkdownToHtml.builder().setConfig(mc).setFilePath("index.md").build().toSoyHtml(node);
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
        """
        # Diagram Title

        ```mermaid
        graph TD
          A["<script>alert('xss-1')</script>"]
          B["<iframe src='javascript:alert(2)'></iframe>"]
          C["<img src=x onerror=alert('xss-3')>"]
          D["<foreignObject><iframe src='https://evil.com'></iframe></foreignObject>"]
          A -->|"<script>alert('edge')</script>"| B
          B --> C --> D
          click A href "javascript:alert('click')"
        ```
        """;

    Config cfg = new Config();
    cfg.setBoolean("markdown", null, "mermaid", true);
    MarkdownConfig mc = new MarkdownConfig(cfg);
    Node node = GitilesMarkdown.parse(mc, md);
    SafeHtml html =
        MarkdownToHtml.builder().setConfig(mc).setFilePath("index.md").build().toSoyHtml(node);

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
    assertThat(htmlStr)
        .contains("&lt;iframe src=&apos;javascript:alert(2)&apos;&gt;&lt;/iframe&gt;");
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
