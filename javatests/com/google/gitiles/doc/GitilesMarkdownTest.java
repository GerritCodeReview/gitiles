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

  @Test
  public void testTableCodeBlock() {
    String md =
        "| Name | Description | Type | Mandatory | Default |\n"
            + "| :------------- | :------------- | :------------- | :------------- | :-------------"
            + " |\n"
            + "| <a id=\"dtbo_image-name\"></a>name | A unique name for this target. | <a"
            + " href=\"https://bazel.build/concepts/labels#target-names\">Name</a> | required | |\n"
            + "| <a id=\"dtbo_image-srcs\"></a>srcs | List of `*.dtbo` files used to package the"
            + " `dtbo.img`. This corresponds to `MKDTIMG_DTBOS` in build configs; see example"
            + " below.<br><br>Example: <pre><code>kernel_build(&#10; name = \"tuna_kernel\",&#10;"
            + " outs = [&#10; \"path/to/foo.dtbo\",&#10; \"path/to/bar.dtbo\",&#10;"
            + " ],&#10;)&#10;dtbo(&#10; name = \"tuna_images\",&#10; kernel_build ="
            + " \":tuna_kernel\",&#10; srcs = [&#10; \":tuna_kernel/path/to/foo.dtbo\",&#10;"
            + " \":tuna_kernel/path/to/bar.dtbo\",&#10; ],&#10;)</code></pre> | <a"
            + " href=\"https://bazel.build/concepts/labels\">List of labels</a> | optional | `[]`"
            + " |\n"
            + "| <a id=\"dtbo_image-out\"></a>out | Name of the `dtbo` image.<br><br>Default to"
            + " `<name>/dtbo.img` if not set. | String | optional | `\"\"` |\n"
            + "| <a id=\"dtbo_image-config_file\"></a>config_file | A config file to create dtbo"
            + " image by cfg_create command.<br><br>If set, use mkdtimg cfg_create with the given"
            + " config file, instead of mkdtimg create | <a"
            + " href=\"https://bazel.build/concepts/labels\">Label</a> | optional | `None` |\n"
            + "| <a id=\"dtbo_image-opts\"></a>opts | Flags passed to `mkdtimg` tool. Successor of"
            + " `MKDTIMG_FLAGS` | List of strings | optional | `[]` |\n"
            + "| <a id=\"dtbo_image-tool\"></a>tool | Name of the tool called to generate the dtbo"
            + " image.<br><br>Supported tools are `mkdtimg` and `mkdtboimg`. Default to `mkdtimg`"
            + " if not set. | String | optional | `\"mkdtimg\"` |\n";
    String html = render(md, false);
    assertThat(html).contains("<a name=\"dtbo_image-name\"></a>name");
    assertThat(html).contains("<a name=\"dtbo_image-srcs\"></a>srcs");
    assertThat(html)
        .contains("<pre class=\"code\">kernel_build(\n name = &quot;tuna_kernel&quot;,");
    assertThat(html).contains("dtbo(\n name = &quot;tuna_images&quot;,");
    assertThat(html).contains("</pre>");
    assertThat(html).contains("<code class=\"code\">*.dtbo</code>");
    assertThat(html).contains("<code class=\"code\">[]</code>");
  }

  @Test
  public void testTableCodeBlockSimple() {
    String md = "| col |\n| --- |\n| <pre><code>hello world</code></pre> |\n";
    String html = render(md, false);
    assertThat(html).contains("<pre class=\"code\">hello world</pre>");
  }

  @Test
  public void testTableCodeBlockWithoutCodeTag() {
    String md = "| col |\n| --- |\n| <pre>hello world</pre> |\n";
    String html = render(md, false);
    assertThat(html).contains("<pre class=\"code\">hello world</pre>");
  }

  @Test
  public void testTableCodeBlockWithBr() {
    String md = "| col |\n| --- |\n| <pre><code>line 1<br>line 2</code></pre> |\n";
    String html = render(md, false);
    assertThat(html).contains("<pre class=\"code\">line 1\nline 2</pre>");
  }

  @Test
  public void testTableCodeBlockWithLanguage() {
    String md = "| col |\n| --- |\n| <pre><code class=\"lang-c\">int x = 0;</code></pre> |\n";
    String html = render(md, false);
    assertThat(html).contains("<pre class=\"code\">");
    assertThat(html).contains("int");
    assertThat(html).contains("x");
  }

  @Test
  public void testTableMultipleCodeBlocksInCell() {
    String md =
        "| col |\n| --- |\n| <pre><code>code1</code></pre> and <pre><code>code2</code></pre> |\n";
    String html = render(md, false);
    assertThat(html).contains("<pre class=\"code\">code1</pre>");
    assertThat(html).contains(" and ");
    assertThat(html).contains("<pre class=\"code\">code2</pre>");
  }

  @Test
  public void testAnchorWithId() {
    String md = "<a id=\"foo\"></a>line1<br>line2\n";
    String html = render(md, false);
    assertThat(html).contains("<a name=\"foo\"></a>line1<br />line2");
  }

  @Test
  public void testTableCodeBlockXssPrevention() {
    String md = "| col |\n| --- |\n| <pre><code><script>alert(1)</script></code></pre> |\n";
    String html = render(md, false);
    assertThat(html).doesNotContain("<script>");
    assertThat(html).contains("&lt;script&gt;alert(1)&lt;/script&gt;");
  }
}
