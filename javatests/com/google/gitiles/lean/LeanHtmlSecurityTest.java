// Copyright (C) 2026 Google Inc. All Rights Reserved.
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

package com.google.gitiles.lean;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.html.types.SafeHtml;
import com.google.gitiles.GitilesView;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.eclipse.jgit.lib.Config;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Security test suite for {@link LeanHtml} covering XSS vectors, event-handler injections,
 * dangerous protocols, CSS/style injections, tag breakouts, and parser robustness, modeled on
 * {@code SimpleMermaidRendererSecurityTest}.
 */
@RunWith(JUnit4.class)
public class LeanHtmlSecurityTest {
  private static final ImmutableSet<String> ALLOWED_LEAN_TAGS =
      ImmutableSet.of(
          "h1",
          "h2",
          "h3",
          "h4",
          "h5",
          "h6",
          "a",
          "div",
          "img",
          "p",
          "blockquote",
          "pre",
          "ol",
          "ul",
          "li",
          "dl",
          "dd",
          "dt",
          "del",
          "em",
          "strong",
          "code",
          "br",
          "hr",
          "table",
          "thead",
          "tbody",
          "caption",
          "tr",
          "th",
          "td",
          "span");

  private static final ImmutableList<String> DANGEROUS_TAGS =
      ImmutableList.of(
          "script",
          "foreignObject",
          "iframe",
          "object",
          "embed",
          "form",
          "input",
          "style",
          "details",
          "summary",
          "ins",
          "button",
          "audio",
          "video");

  private static final Pattern TAG_PATTERN =
      Pattern.compile("<(/?)([a-zA-Z][a-zA-Z0-9-]*)([^>]*)>");
  private static final Pattern SVG_BLOCK =
      Pattern.compile("<svg\\b[^>]*>.*?</svg>", Pattern.DOTALL);
  private static final Pattern EVENT_ATTR =
      Pattern.compile("\\bon[a-z]+\\s*=", Pattern.CASE_INSENSITIVE);
  private static final Pattern DISALLOWED_ATTR =
      Pattern.compile(
          "\\b(?:style|data-[a-z0-9_-]*|aria-[a-z0-9_-]*)\\s*=", Pattern.CASE_INSENSITIVE);
  private static final Pattern DANGEROUS_PROTOCOL_IN_ATTR =
      Pattern.compile(
          "(?:javascript|vbscript)\\s*:|data\\s*:\\s*text/html", Pattern.CASE_INSENSITIVE);

  @Test
  public void scriptTagVariationsInModuleDocsAndDocstringsAreNeutralized() {
    ImmutableList<String> payloads =
        ImmutableList.of(
            "<script>alert(1)</script>",
            "<script src='https://evil.com/xss.js'></script>",
            "<script src='//evil.com/xss.js'/>",
            "<sCrIpT>alert('case')</ScRiPt>",
            "<SCRIPT/SRC=\"data:text/javascript,alert(1)\">",
            "<script xmlns=\"http://www.w3.org/1999/xhtml\">alert(1)</script>",
            "<script><![CDATA[alert(1)]]></script>",
            "<script defer>alert(1)</script>",
            "<script type=\"module\">import 'https://evil.com/x.js';</script>");

    for (String payload : payloads) {
      String lean =
          "/-!\n" + payload + "\n-/\n" + "/-- " + payload + " -/\n" + "def safe : Nat := 1\n";
      assertSafeLeanHtml(lean);
    }
  }

  @Test
  public void embeddedHtmlAndForeignObjectInjectionsAreNeutralized() {
    ImmutableList<String> payloads =
        ImmutableList.of(
            "<foreignObject><body xmlns=\"http://www.w3.org/1999/xhtml\">"
                + "<script>alert(1)</script></body></foreignObject>",
            "<iframe src=\"javascript:alert(1)\"></iframe>",
            "<iframe src=\"https://evil.com\"></iframe>",
            "<form action=\"//evil.com\"><input type=\"password\" name=\"pass\"></form>",
            "<embed src=\"evil.swf\"></embed>",
            "<object data=\"javascript:alert(1)\"></object>",
            "<audio src=\"x\" onerror=\"alert(1)\"></audio>",
            "<video src=\"x\" onerror=\"alert(1)\"></video>",
            "<details open ontoggle=\"alert(1)\"><summary>x</summary></details>");

    for (String payload : payloads) {
      String lean =
          "/-!\n" + payload + "\n-/\n/-- " + payload + " -/\ntheorem t : True := by trivial\n";
      assertSafeLeanHtml(lean);
    }
  }

  @Test
  public void eventHandlerAttributesAcrossIdentifiersStringsAndCommentsAreEscaped() {
    ImmutableList<String> payloads =
        ImmutableList.of(
            "<svg onload=\"alert('svg-onload')\">",
            "<img src=\"invalid-image.jpg\" onerror=\"alert('img-onerror')\">",
            "<body onload=\"alert('body-onload')\">",
            "x\" onfocus=\"alert(1)\" autofocus=\"",
            "x' onclick='alert(1)' x='",
            "x` onmouseover=`alert(1)` `");

    for (String payload : payloads) {
      String sanitizedIdent = payload.replace('\n', ' ').replace('«', '_').replace('»', '_');
      String lean =
          "-- ========================================\n"
              + "-- "
              + payload.replace('\n', ' ')
              + "\n"
              + "-- ========================================\n"
              + "@["
              + payload.replace('\n', ' ').replace('[', '(').replace(']', ')')
              + "]\n"
              + "def «"
              + sanitizedIdent
              + "» : String := \""
              + payload.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ")
              + "\"\n"
              + "structure S where\n"
              + "  field : Nat -- "
              + payload.replace('\n', ' ')
              + "\n"
              + "  deriving «"
              + sanitizedIdent
              + "»\n";
      String html = renderAndAssertSafeLeanHtml(lean);
      assertThat(html).contains("&quot;");
    }
  }

  @Test
  public void dangerousAnchorAndImageProtocolsInMarkdownAreFiltered() {
    ImmutableList<String> dangerousUris =
        ImmutableList.of(
            "javascript:alert('a-href')",
            "JaVaScRiPt:alert(1)",
            "data:text/html;base64,PHNjcmlwdD5hbGVydCgxKTwvc2NyaXB0Pg==",
            "vbscript:msgbox(1)",
            "java\0script:alert(1)",
            "java&#x0A;script:alert(1)",
            "java&#x09;script:alert(1)",
            "javascript&colon;alert(1)",
            "blob:https://evil.com/uuid");

    for (String uri : dangerousUris) {
      String lean =
          "/-!\n[click]("
              + uri
              + ")\n\n![img]("
              + uri
              + ")\n-/\n"
              + "/-- [doc]("
              + uri
              + ") -/\n"
              + "def x : Nat := 1\n";
      String html = renderAndAssertSafeLeanHtml(lean);
      assertThat(html.toLowerCase(Locale.ROOT)).doesNotContain("javascript:");
      assertThat(html.toLowerCase(Locale.ROOT)).doesNotContain("vbscript:");
      assertThat(html.toLowerCase(Locale.ROOT)).doesNotContain("data:text/html");
    }
  }

  @Test
  public void relativeImagesDoNotReadRepoTreeAndFallbackToInnocuousUri() {
    String lean =
        "/-!\n![secret](../secret.png)\n![local](diagram.svg)\n-/\n"
            + "theorem t : True := by trivial\n";
    GitilesView view =
        GitilesView.path()
            .setHostName("host")
            .setServletPath("/b")
            .setRepositoryName("repo")
            .setRevision("HEAD")
            .setPathPart("dir/Main.lean")
            .build();
    SafeHtml safe =
        LeanHtml.renderBlob(
            lean, new Config(), view, "/b/repo/+/HEAD/dir/Main.lean", "dir/Main.lean");
    assertThat(safe).isNotNull();
    String html = safe.getSafeHtmlString();
    assertThat(html).contains("data:image/gif;base64,zSoyz");
    assertThat(html).doesNotContain("secret.png");
    assertThat(html).doesNotContain("diagram.svg");
  }

  @Test
  public void cssAndStyleInjectionsAreStrippedOrEscaped() {
    ImmutableList<String> payloads =
        ImmutableList.of(
            "<style>@import 'javascript:alert(1)';</style>",
            "<style>body { background: url(\"javascript:alert(1)\"); }</style>",
            "<div style=\"fill:expression(alert(1))\">Styled Div</div>",
            "<div style=\"background-image:url(javascript:alert(1))\">Background</div>",
            "<span style=\"position:fixed;inset:0\">Overlay</span>");

    for (String payload : payloads) {
      String lean = "/-!\n" + payload + "\n-/\n/-- " + payload + " -/\ndef x : Nat := 1\n";
      assertSafeLeanHtml(lean);
    }
  }

  @Test
  public void htmlBreakoutPayloadsCannotEscapeContainer() {
    ImmutableList<String> payloads =
        ImmutableList.of(
            "</div></div><script>alert('div-breakout')</script><div>",
            "</pre></code><iframe src='javascript:alert(1)'></iframe><pre><code>",
            "</span></a><script>alert('span-breakout')</script>",
            "--> <script>alert('comment-breakout')</script> <!--",
            "\"><script>alert('quote-breakout')</script>",
            "'\"><script>alert('single-quote-breakout')</script>");

    for (String payload : payloads) {
      String lean =
          "/-!\n"
              + payload
              + "\n-/\n"
              + "def «"
              + payload.replace('«', '_').replace('»', '_').replace('\n', ' ')
              + "» : Nat := 1\n";
      assertSafeLeanHtml(lean);
    }
  }

  @Test
  public void mermaidInsideLeanDocstringCannotInjectScript() {
    String lean =
        """
        /-!
        ```mermaid
        graph TD
          A["<script>alert('mermaid')</script>"] --> B["<img src=x onerror=alert(1)>"]
          click A href "javascript:alert(1)"
        ```
        -/
        theorem ok : True := by trivial
        """;

    String html = renderAndAssertSafeLeanHtml(lean);
    assertThat(html).contains("<div class=\"mermaid-container\"><svg");
    assertThat(html).contains("&lt;script&gt;alert(&apos;mermaid&apos;)&lt;/script&gt;");
    assertThat(html.toLowerCase(Locale.ROOT)).doesNotContain("javascript:");
  }

  @Test
  public void markdownDisabledEscapesAllRawHtmlPayloads() {
    Config config = new Config();
    config.setBoolean("markdown", null, "render", false);

    String lean =
        "/-!\n<script>alert(1)</script><img src=x onerror=alert(1)>\n-/\n"
            + "/-- <iframe src='javascript:alert(1)'></iframe> -/\n"
            + "def ok : Nat := 1\n";

    SafeHtml safe = LeanHtml.renderBlob(lean, config, null, null, "NoMd.lean");
    assertThat(safe).isNotNull();
    String html = safe.getSafeHtmlString();
    assertStrictAllowlist(html);
    assertThat(html).contains("&lt;script&gt;alert(1)&lt;/script&gt;");
    assertThat(html).contains("&lt;img src=x onerror=alert(1)&gt;");
  }

  @Test
  public void nullBytesControlCharsAndDeepBlockCommentsDoNotBreakSafety() {
    StringBuilder deepNested = new StringBuilder();
    for (int i = 0; i < 500; i++) {
      deepNested.append("/- ");
    }
    deepNested.append("<script>alert(1)</script>");
    for (int i = 0; i < 500; i++) {
      deepNested.append(" -/");
    }
    deepNested.append("\ndef x\0y : Nat := 1\n");

    assertSafeLeanHtml(deepNested.toString());
  }

  @Test
  public void renderDiffSanitizesXssVectorsAcrossLeftAndRightSides() {
    ImmutableList<String> payloads =
        ImmutableList.of(
            "<script>alert(1)</script>",
            "<img src=x onerror=alert(1)>",
            "<svg onload=alert(1)>",
            "<iframe src=\"javascript:alert(1)\"></iframe>",
            "<details open ontoggle=alert(1)><summary>x</summary></details>",
            "[click](javascript:alert(1))",
            "![img](javascript:alert(2))",
            "<div style=\"position:fixed\" data-x=\"1\" aria-label=\"a\">x</div>");
    StringBuilder left = new StringBuilder("/-!\n");
    StringBuilder right = new StringBuilder("/-!\n");
    for (String payload : payloads) {
      left.append(payload).append("\n");
      right.append("updated ").append(payload).append("\n");
    }
    left.append("-/\n-- ==== <script>alert(1)</script> ====\n");
    right.append("-/\n-- ==== <img src=x onerror=alert(2)> ====\n");
    left.append(
        """
        /-- [click](javascript:alert(1)) <script>alert('leftDoc')</script> -/
        structure S where
          x : Nat -- <img src=x onerror=alert(3)>
        def «<script>alert(4)</script>» : Nat := 1
        """);
    right.append(
        """
        /-- ![bad](javascript:alert(2)) <iframe src=x></iframe> -/
        structure S where
          x : Int -- <svg onload=alert(5)>
        def «<img src=x onerror=alert(6)>» : Nat := 2
        """);

    SafeHtml safe =
        LeanHtml.renderDiff(
            left.toString(), right.toString(), new Config(), null, null, "Xss.lean");
    assertThat(safe).isNotNull();
    String html = safe.getSafeHtmlString();
    assertStrictAllowlist(html);
    assertThat(html.toLowerCase(Locale.ROOT)).contains("about:invalid#zsoyz");
    assertThat(html.toLowerCase(Locale.ROOT)).contains("data:image/gif;base64,zsoyz");

    Config mdDisabled = new Config();
    mdDisabled.setBoolean("markdown", null, "render", false);
    SafeHtml noMd =
        LeanHtml.renderDiff(left.toString(), right.toString(), mdDisabled, null, null, "Xss.lean");
    assertThat(noMd).isNotNull();
    assertStrictAllowlist(noMd.getSafeHtmlString());
    assertThat(noMd.getSafeHtmlString()).contains("&lt;script&gt;");
  }

  private static void assertSafeLeanHtml(String leanSource) {
    assertThat(renderAndAssertSafeLeanHtml(leanSource)).isNotEmpty();
  }

  private static String renderAndAssertSafeLeanHtml(String leanSource) {
    SafeHtml safe = LeanHtml.renderBlob(leanSource, new Config(), null, null, "Security.lean");
    assertThat(safe).isNotNull();
    String html = safe.getSafeHtmlString();
    assertStrictAllowlist(html);
    return html;
  }

  private static void assertStrictAllowlist(String html) {
    String withoutMermaidSvg = SVG_BLOCK.matcher(html).replaceAll("");
    String lower = withoutMermaidSvg.toLowerCase(Locale.ROOT);
    for (String tag : DANGEROUS_TAGS) {
      assertThat(lower).doesNotContain("<" + tag.toLowerCase(Locale.ROOT));
      assertThat(lower).doesNotContain("</" + tag.toLowerCase(Locale.ROOT));
    }
    assertThat(lower).doesNotContain("<svg");

    Matcher tags = TAG_PATTERN.matcher(withoutMermaidSvg);
    while (tags.find()) {
      String tagName = tags.group(2).toLowerCase(Locale.ROOT);
      String attrs = tags.group(3);
      assertThat(ALLOWED_LEAN_TAGS).contains(tagName);
      assertThat(EVENT_ATTR.matcher(attrs).find()).isFalse();
      assertThat(DISALLOWED_ATTR.matcher(attrs).find()).isFalse();
      assertThat(DANGEROUS_PROTOCOL_IN_ATTR.matcher(attrs).find()).isFalse();
    }
  }
}
