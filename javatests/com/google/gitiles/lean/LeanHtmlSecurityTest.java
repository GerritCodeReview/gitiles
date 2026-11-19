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
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.html.types.SafeHtml;
import com.google.gitiles.GitilesView;
import com.google.gitiles.doc.GitilesMarkdown;
import com.google.gitiles.doc.MarkdownConfig;
import com.google.gitiles.doc.MarkdownToHtml;
import java.io.IOException;
import java.io.StringReader;
import java.util.Enumeration;
import java.util.Locale;
import javax.swing.text.MutableAttributeSet;
import javax.swing.text.html.HTML;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.parser.ParserDelegator;
import org.eclipse.jgit.lib.Config;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Security test suite for {@link LeanHtml} covering XSS vectors, parser-validated element/attribute
 * allowlists, Trojan Source / homoglyph neutralization, Markdown hardening, and robustness.
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

  private static final ImmutableSet<String> GLOBAL_ATTRS = ImmutableSet.of("id", "class", "role");

  private static final ImmutableMap<String, ImmutableSet<String>> TAG_EXTRA_ATTRS =
      ImmutableMap.of(
          "a", ImmutableSet.of("href", "title", "name"),
          "img", ImmutableSet.of("src", "title", "alt"),
          "ol", ImmutableSet.of("start"),
          "td", ImmutableSet.of("colspan", "align"),
          "th", ImmutableSet.of("colspan", "align"));

  private static final ImmutableList<String> DANGEROUS_MARKERS =
      ImmutableList.of(
          "<script",
          "</script",
          "<iframe",
          "<svg",
          "<foreignobject",
          "<style",
          "</style",
          "</textarea",
          "<object",
          "<embed",
          "<form",
          "<input",
          "<details",
          "<summary",
          "<ins",
          "<button",
          "<audio",
          "<video",
          "<!--",
          "]]>");

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
  public void mermaidInsideLeanDocstringCannotInjectScriptOrSvg() {
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
    assertThat(html).doesNotContain("<div class=\"mermaid-container\">");
    assertThat(html).doesNotContain("<svg");
    assertThat(html).contains("<pre class=\"code\">");
    assertThat(html).contains("&lt;script&gt;alert(&#39;mermaid&#39;)&lt;/script&gt;");
    assertThat(html.toLowerCase(Locale.ROOT)).doesNotContain("href=\"javascript:");
  }

  @Test
  public void hostAllowIframeConfigCannotInjectIframeIntoLeanDocstrings() {
    Config hostConfig = new Config();
    hostConfig.setBoolean("markdown", null, "safehtml", true);
    hostConfig.setStringList(
        "markdown", null, "allowiframe", ImmutableList.of("true", "https://evil.example/"));
    String lean =
        "/-!\n<iframe src=\"https://evil.example/frame\" height=\"100px\" width=\"100px\">"
            + "</iframe>\n-/\ndef safe : Nat := 1\n";

    SafeHtml safe = LeanHtml.renderBlob(lean, hostConfig, null, null, "Iframe.lean");
    assertThat(safe).isNotNull();
    String html = safe.getSafeHtmlString();
    assertStrictAllowlist(html);
    assertThat(html.toLowerCase(Locale.ROOT)).doesNotContain("<iframe");
    assertThat(html).contains("&lt;iframe src=&quot;https://evil.example/frame&quot;");
  }

  @Test
  public void deeplyNestedMarkdownInDocstringsDoesNotOverflowStack() {
    String nestedQuotes = "/-!\n" + "> ".repeat(10_000) + "boom\n-/\n";
    String nestedLinks = "/-- " + "[a](".repeat(2_000) + "target)\n -/\ndef x : Nat := 1\n";

    String html = renderAndAssertSafeLeanHtml(nestedQuotes + nestedLinks);
    assertThat(html).contains("<pre><code>");
    assertThat(html).contains("boom");
    assertThat(html).contains("def");
  }

  @Test
  public void trojanSourceBidiInvisibleControlsAndHomoglyphsShowBannerAndCodepointSpans() {
    String trojan =
        "/-! Doc &rlm; &#x202E; smuggle -/\n"
            + "theorem t\u0435st : True := by\n"
            + "  -- \u202A\u202B\u202C\u202D\u202E\u2066\u2067\u2068\u2069\u200E\u200F\u061C\n"
            + "  -- \u200B\u200C\u200D\u2060\uFEFF\u00AD\u034F\u3164\n"
            + "  trivial\n"
            + "def f\u03BFo : Nat := 1\n"
            + "def \uD835\uDC1A : Nat := 2\n";

    String html = renderAndAssertSafeLeanHtml(trojan);
    assertThat(html).contains("<div class=\"LeanTrojanBanner\" role=\"alert\">");
    assertThat(html).contains("<span class=\"LeanTrojanBanner-badge\">Unicode warning</span>");
    assertThat(html).contains("homoglyph");
    assertThat(html).contains("<span class=\"LeanTrojanChar\">U+202E</span>");
    assertThat(html).contains("<span class=\"LeanTrojanChar\">U+2066</span>");
    assertThat(html).contains("<span class=\"LeanTrojanChar\">U+200E</span>");
    assertThat(html).contains("<span class=\"LeanTrojanChar\">U+061C</span>");
    assertThat(html).contains("<span class=\"LeanTrojanChar\">U+200B</span>");
    assertThat(html).contains("<span class=\"LeanTrojanChar\">U+FEFF</span>");
    assertThat(html).contains("<span class=\"LeanTrojanChar\">U+00AD</span>");
    // No raw bidi/Cf character survives into rendered HTML text or attributes.
    assertThat(LeanSecurity.containsHiddenOrSurrogate(html)).isFalse();

    // Legitimate Mathlib Greek names do not trigger false-positive homoglyph warnings.
    String mathlibClean = "theorem mul_α_eq_β (α β : Nat) : α + β = β + α := by omega\n";
    String cleanHtml = renderAndAssertSafeLeanHtml(mathlibClean);
    assertThat(cleanHtml).doesNotContain("LeanTrojanBanner");
    assertThat(cleanHtml).doesNotContain("LeanTrojanChar");
    assertThat(cleanHtml).doesNotContain("homoglyph");
  }

  @Test
  public void unicodeEdgeCasesU2028U2029NulLoneSurrogatesUfffdAndLongLinePassAllowlist() {
    StringBuilder payload = new StringBuilder();
    payload.append("def edge\u2028\u2029\0\uFFFD : String := \"");
    payload.append('\uD800').append('x').append('\uDFFF');
    payload.append("a".repeat(60_000));
    payload.append("\"\n");

    String html = renderAndAssertSafeLeanHtml(payload.toString());
    assertThat(html).contains("<span class=\"LeanTrojanChar\">U+2028</span>");
    assertThat(html).contains("<span class=\"LeanTrojanChar\">U+2029</span>");
    assertThat(html).contains("<span class=\"LeanTrojanChar\">U+0000</span>");
    assertThat(html).contains("<span class=\"LeanTrojanChar\">U+D800</span>");
    assertThat(html).contains("<span class=\"LeanTrojanChar\">U+DFFF</span>");
    assertThat(html).contains("\uFFFD");
    assertThat(LeanSecurity.containsHiddenOrSurrogate(html)).isFalse();

    assertThat(
            LeanHtml.renderBlob(
                "def edge : String := \"" + "a".repeat(LeanRendering.MAX_LINE_CHARS) + "\"\n",
                new Config(),
                null,
                null,
                "OverLine.lean"))
        .isNull();
  }

  @Test
  public void outputCapAbortsEarlyWithoutBuildingOversizeBuffer() {
    // 1,000 comment lines with 450 zero-width spaces each (~1.35 MiB raw UTF-8 < MAX_BYTES,
    // 1,001 lines < MAX_LINES) expand via Trojan char spans past MAX_OUTPUT_CHARS (16 MiB).
    String expanding = ("-- " + "\u200B".repeat(450) + "\n").repeat(1_000);
    assertThat(LeanHtml.renderBlob(expanding, new Config(), null, null, "Expand.lean")).isNull();
  }

  @Test
  public void owaspBreakoutsAndEntityEvasionsAreNeutralized() {
    ImmutableList<String> owaspPayloads =
        ImmutableList.of(
            "</script><script>alert(1)</script>",
            "</style><img src=x onerror=alert(1)>",
            "</textarea><script>alert(1)</script>",
            "<!--><script>alert(1)</script>-->",
            "]]><script>alert(1)</script>",
            "[x](&#106;&#97;&#118;&#97;&#115;&#99;&#114;&#105;&#112;&#116;:alert(1))",
            "[x](jav&#x09;ascript:alert(1))",
            "![x](data:image/svg+xml;base64,PHN2ZyBvbmxvYWQ9YWxlcnQoMSk+)",
            "[x](`javascript:alert(1)`)");
    StringBuilder doc = new StringBuilder("/-!\n");
    for (String p : owaspPayloads) {
      doc.append(p).append("\n");
    }
    doc.append("-/\ntheorem t : True := by trivial\n");
    assertSafeLeanHtml(doc.toString());
  }

  @Test
  public void structureAndConstructorLineCommentsPreserveRawTextAndLexicalScope() {
    String lean =
        """
        structure S where
          x : Nat -- keep <!-- raw --> and foo_bar_baz <T>
        inductive I where
          | mk : Nat -- keep <!-- ctor --> and a_b_c
        theorem ok : True := by
          trivial
        """;
    String html = renderAndAssertSafeLeanHtml(lean);
    assertThat(html).contains("no sorry found (lexical scan)");
    assertThat(html).doesNotContain("LeanPill--verified");
    assertThat(html).contains("&lt;!-- raw --&gt;");
    assertThat(html).contains("foo_bar_baz &lt;T&gt;");
    assertThat(html).contains("&lt;!-- ctor --&gt;");
    assertThat(html).contains("a_b_c");
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

  @Test
  public void renderDiffHardensVariationHomoglyphRtlAndProtoLinks() {
    String variationSource =
        """
        /-!
        Verify check&#xFE00;name and supp&#xE0100;name and khmer&#x17B4;mark
        -/
        def «check» : Nat := 1
        def «check\uFE00» : Nat := 2
        def «check\uDB40\uDD00» : Nat := 3
        def «khmer\u17B4\u180B» : Nat := 4
        def «braille\u2800blank» : Nat := 5
        """;
    SafeHtml variationDiff =
        LeanHtml.renderDiff(
            "def «check» : Nat := 1\n",
            variationSource,
            new Config(),
            null,
            null,
            "Variation.lean");
    assertThat(variationDiff).isNotNull();
    String variationHtml = variationDiff.getSafeHtmlString();
    assertStrictAllowlist(variationHtml);
    assertThat(variationHtml).contains("<div class=\"LeanTrojanBanner\" role=\"alert\">");
    assertThat(variationHtml).contains("<span class=\"LeanTrojanChar\">U+FE00</span>");
    assertThat(variationHtml).contains("<span class=\"LeanTrojanChar\">U+E0100</span>");
    assertThat(LeanSecurity.containsHiddenOrSurrogate(variationHtml)).isFalse();

    String nonDeclHomoglyphs =
        """
        structure User where
          is_v\u0430lid : Bool
        inductive Auth where
          | v\u0430lid
        theorem sound_proof : my_f\u0430lse := by
          exact proof_v\u0430r
        def compact_param (x_\u0430 : Nat) : Nat := x_\u0430
        """;
    SafeHtml nonDeclDiff =
        LeanHtml.renderDiff(
            "structure User where\n  is_valid : Bool\n",
            nonDeclHomoglyphs,
            new Config(),
            null,
            null,
            "NonDeclHomoglyph.lean");
    assertThat(nonDeclDiff).isNotNull();
    String nonDeclHtml = nonDeclDiff.getSafeHtmlString();
    assertStrictAllowlist(nonDeclHtml);
    assertThat(nonDeclHtml).contains("<div class=\"LeanTrojanBanner\" role=\"alert\">");
    assertThat(nonDeclHtml).contains("LeanDecl-badge--warning\">homoglyph");

    SafeHtml fixedDiff =
        LeanHtml.renderDiff(
            "def dup : Nat := a_\u0430\n",
            "def dup : Nat := a\n",
            new Config(),
            null,
            null,
            "FixedHomoglyph.lean");
    assertThat(fixedDiff).isNotNull();
    String fixedHtml = fixedDiff.getSafeHtmlString();
    assertStrictAllowlist(fixedHtml);
    assertThat(fixedHtml).contains("<div class=\"LeanTrojanBanner\" role=\"alert\">");
    assertThat(fixedHtml).doesNotContain("LeanDecl-badge--warning\">homoglyph");

    String rtl =
        """
        /-!
        Inline `"\u05D0" == "\u05D1"` and `-- \u05E9\u05DC\u05D5\u05DD := 1 ; \u05D0\u05DE\u05EA := 0`
        Para (\u05D0 : Nat) -> \u05D0 = \u05D1

        ```lean
        def md_eq := "\u05D0" == "\u05D1"
        -- \u05E9\u05DC\u05D5\u05DD := 1 ; \u05D0\u05DE\u05EA := 0
        ```
        -/
        /-- Doc `"\u05D0" == "\u05D1"` -/
        def check_eq : Bool := "\u05D0" == "\u05D1"
        theorem t (\u05D0 : Nat) (\u05D1 : Nat) : \u05D0 = \u05D1 := by
          -- \u05E9\u05DC\u05D5\u05DD := 1 ; \u05D0\u05DE\u05EA := 0
          rfl
        """;
    SafeHtml rtlDiff =
        LeanHtml.renderDiff(
            "def check_eq : Bool := false\n", rtl, new Config(), null, null, "RtlDiff.lean");
    assertThat(rtlDiff).isNotNull();
    String rtlHtml = rtlDiff.getSafeHtmlString();
    assertStrictAllowlist(rtlHtml);
    assertThat(rtlHtml).contains("<span class=\"LeanBidiIsolate\">\u05D0</span>");
    assertThat(rtlHtml).contains("<span class=\"LeanBidiIsolate\">\u05D1</span>");
    assertThat(rtlHtml)
        .contains("<code class=\"code\">&quot;<span class=\"LeanBidiIsolate\">\u05D0</span>&quot;");
    assertThat(rtlHtml)
        .contains("<span class=\"str\">&quot;<span class=\"LeanBidiIsolate\">\u05D0</span>&quot;");
    assertThat(rtlHtml)
        .contains("Para (<span class=\"LeanBidiIsolate\">\u05D0</span> : Nat) -&gt; ");

    GitilesView diffView =
        GitilesView.path()
            .setHostName("host")
            .setServletPath("/b")
            .setRepositoryName("repo")
            .setRevision("main")
            .setPathPart("Test.lean")
            .build();
    String protoMarkdown =
        """
        /-!
        # F1
        # 1
        [proto-rel](//evil.example/phish)
        ![img-proto](//evil.example/pixel.png)
        -/
        /--
        # F2
        -/
        def x : Nat := 1
        """;
    SafeHtml protoDiff =
        LeanHtml.renderDiff(
            "def x : Nat := 0\n",
            protoMarkdown,
            new Config(),
            diffView,
            "/b/repo/+diff/c^!/",
            "Test.lean");
    assertThat(protoDiff).isNotNull();
    String protoHtml = protoDiff.getSafeHtmlString();
    assertStrictAllowlist(protoHtml);
    assertThat(protoHtml).doesNotContain("href=\"//");
    assertThat(protoHtml).doesNotContain("src=\"//");
    assertThat(protoHtml).doesNotContain("<a class=\"h\"");
    assertThat(protoHtml).doesNotContain("name=\"F1\"");
    assertThat(protoHtml).doesNotContain("name=\"F2\"");
  }

  @Test
  public void blobVisibleTextPreservesLineNumbersAndSourceTextAcrossConstructs() {
    String source =
        """
        /- Copyright 2026 -/
        /-! Module intro -/
        import Mathlib.Init
        -- === Helpers ===
        namespace Demo
        def fast (x : Nat) : Nat := x + 1
        structure Box where
          val : Nat -- field comment
          deriving Repr
        inductive Choice where
          | left : Choice
          | right : Nat -> Choice
        theorem ok (n : Nat) : n = n := by
          rfl
        """;
    String html = renderAndAssertSafeLeanHtml(source);
    String[] lines = source.split("\n", -1);
    int expectedLines = lines[lines.length - 1].isEmpty() ? lines.length - 1 : lines.length;
    for (int line = 1; line <= expectedLines; line++) {
      assertThat(html).contains("id=\"" + line + "\"");
      assertThat(html).contains("href=\"#" + line + "\"");
    }
    String visible = extractVisibleText(html);
    assertThat(visible).contains("Copyright 2026");
    assertThat(visible).contains("Module intro");
    assertThat(visible).contains("import Mathlib.Init");
    assertThat(visible).contains("Helpers");
    assertThat(visible).contains("namespace Demo");
    assertThat(visible).contains("def fast (x : Nat) : Nat := x + 1");
    assertThat(visible).contains("structure Box where");
    assertThat(visible).contains("field comment");
    assertThat(visible).contains("deriving");
    assertThat(visible).contains("Repr");
    assertThat(visible).contains("inductive Choice where");
    assertThat(visible).contains("| left : Choice");
    assertThat(visible).contains("| right : Nat -> Choice");
    assertThat(visible).contains("theorem ok");
    assertThat(visible).contains("rfl");
  }

  @Test
  public void variationSelectorsAndInvisibleMarksTriggerBannerAndSpans() {
    String source =
        """
        /-!
        Verify check&#xFE00;name and supp&#xE0100;name and khmer&#x17B4;mark
        -/
        def «check» : Nat := 1
        def «check\uFE00» : Nat := 2
        def «check\uDB40\uDD00» : Nat := 3
        def «khmer\u17B4\u180B» : Nat := 4
        def «braille\u2800blank» : Nat := 5
        """;
    String html = renderAndAssertSafeLeanHtml(source);
    assertThat(html).contains("<div class=\"LeanTrojanBanner\" role=\"alert\">");
    assertThat(html).contains("<span class=\"LeanTrojanChar\">U+FE00</span>");
    assertThat(html).contains("<span class=\"LeanTrojanChar\">U+E0100</span>");
    assertThat(html).contains("<span class=\"LeanTrojanChar\">U+17B4</span>");
    assertThat(html).contains("<span class=\"LeanTrojanChar\">U+180B</span>");
    assertThat(html).contains("<span class=\"LeanTrojanChar\">U+2800</span>");
    assertThat(html).contains("<pre><code>Verify check&amp;#xFE00;name");
    assertThat(LeanSecurity.containsHiddenOrSurrogate(html)).isFalse();
  }

  @Test
  public void homoglyphDetectionCoversPureScriptsLetterlikeGreekIpaRomanAndNonDeclCode() {
    ImmutableList<String> spoofingFiles =
        ImmutableList.of(
            "def «\uA4F3\uA4D7» : Nat := 1\n", // Pure Lisu ꓳꓗ
            "def «\u2C80\u2C82\u2C88» : Nat := 1\n", // Pure Coptic ⲀⲂⲈ
            "def «\u212A» : Nat := 1\n", // Letterlike Kelvin K
            "def «\u210E» : Nat := 1\n", // Letterlike Planck ℎ
            "def «\u2113» : Nat := 1\n", // Letterlike ell ℓ
            "def «\u03BD» : Nat := 1\n", // Greek nu ν
            "def «\u03F3» : Nat := 1\n", // Greek yot ϳ
            "def «\u03FA» : Nat := 1\n", // Greek San Ϻ
            "def «\u0251» : Nat := 1\n", // IPA alpha ɑ
            "def «\u0261» : Nat := 1\n", // IPA script g ɡ
            "def «\u2160» : Nat := 1\n"); // Roman numeral Ⅰ
    for (String src : spoofingFiles) {
      String html = renderAndAssertSafeLeanHtml(src);
      assertThat(html).contains("<div class=\"LeanTrojanBanner\" role=\"alert\">");
      assertThat(html).contains("<span class=\"LeanDecl-badge LeanDecl-badge--warning\">homoglyph");
    }

    String nonDeclHomoglyphs =
        """
        structure User where
          is_v\u0430lid : Bool
        inductive Auth where
          | v\u0430lid
        theorem sound_proof : my_f\u0430lse := by
          exact proof_v\u0430r
        def compact_param (x_\u0430 : Nat) : Nat := x_\u0430
        """;
    String nonDeclHtml = renderAndAssertSafeLeanHtml(nonDeclHomoglyphs);
    assertThat(nonDeclHtml).contains("<div class=\"LeanTrojanBanner\" role=\"alert\">");
    assertThat(nonDeclHtml).contains("is_v\u0430lid");
    assertThat(nonDeclHtml).contains("v\u0430lid");
    assertThat(nonDeclHtml).contains("my_f\u0430lse");
    assertThat(countOccurrences(nonDeclHtml, "LeanDecl-badge--warning\">homoglyph")).isEqualTo(4);

    // Same-name compact declarations distinguish homoglyph membership by object identity.
    String sameNameBlob =
        renderAndAssertSafeLeanHtml("def dup : Nat := 1\ndef dup : Nat := a_\u0430\n");
    assertThat(sameNameBlob).contains("<div class=\"LeanTrojanBanner\" role=\"alert\">");
    assertThat(countOccurrences(sameNameBlob, "LeanDecl-badge--warning\">homoglyph")).isEqualTo(1);
    assertThat(sameNameBlob).contains("LeanDecl LeanDecl--compact");

    // Legitimate Mathlib subscripts, blackboard bold types, comments, and strings stay unflagged.
    String benign =
        """
        -- Привет мир (Cyrillic inside comment is prose, not a code identifier)
        def label : String := "Привет"
        def ℝ_to_ℕ (xᵢ : Nat) (aⱼ : Nat) : Nat := xᵢ + aⱼ
        theorem α_comm (α β : Nat) : α + β = β + α := by omega
        """;
    String benignHtml = renderAndAssertSafeLeanHtml(benign);
    assertThat(benignHtml).doesNotContain("LeanTrojanBanner");
    assertThat(benignHtml).doesNotContain("homoglyph");
  }

  @Test
  public void implicitRtlCharactersAreIsolatedAcrossStringsCodeAndComments() {
    String rtl =
        """
        /-!
        Inline `"\u05D0" == "\u05D1"` and `-- \u05E9\u05DC\u05D5\u05DD := 1 ; \u05D0\u05DE\u05EA := 0`
        Para (\u05D0 : Nat) -> \u05D0 = \u05D1

        ```lean
        def md_eq := "\u05D0" == "\u05D1"
        -- \u05E9\u05DC\u05D5\u05DD := 1 ; \u05D0\u05DE\u05EA := 0
        ```
        -/
        /-- Doc `"\u05D0" == "\u05D1"` -/
        def check_eq : Bool := "\u05D0" == "\u05D1"
        theorem t (\u05D0 : Nat) (\u05D1 : Nat) : \u05D0 = \u05D1 := by
          -- \u05E9\u05DC\u05D5\u05DD := 1 ; \u05D0\u05DE\u05EA := 0
          rfl
        """;
    String html = renderAndAssertSafeLeanHtml(rtl);
    assertThat(html).contains("<span class=\"LeanBidiIsolate\">\u05D0</span>");
    assertThat(html).contains("<span class=\"LeanBidiIsolate\">\u05D1</span>");
    assertThat(html).contains("<span class=\"LeanBidiIsolate\">\u05E9\u05DC\u05D5\u05DD</span>");
    assertThat(html).contains("<span class=\"LeanBidiIsolate\">\u05D0\u05DE\u05EA</span>");
    assertThat(html)
        .contains("<code class=\"code\">&quot;<span class=\"LeanBidiIsolate\">\u05D0</span>&quot;");
    assertThat(html)
        .contains("<span class=\"str\">&quot;<span class=\"LeanBidiIsolate\">\u05D0</span>&quot;");
    assertThat(html).contains("Para (<span class=\"LeanBidiIsolate\">\u05D0</span> : Nat) -&gt; ");
  }

  @Test
  public void protocolRelativeAndHeadingAnchorsAreBlockedOnlyOnLeanPath() {
    GitilesView blobView =
        GitilesView.path()
            .setHostName("host")
            .setServletPath("/b")
            .setRepositoryName("repo")
            .setRevision("main")
            .setPathPart("Test.lean")
            .build();
    String lean =
        """
        /-!
        # F1
        # 1
        [proto-rel](//evil.example/phish)
        [triple-slash](///evil.example/phish)
        [ws-proto](\t//evil.example/phish)
        [backslash](/\\evil.example/phish)
        [ref-link][r]
        ![img-proto](//evil.example/pixel.png)
        ![img-triple](///evil.example/pixel2.png)

        [r]: //evil.example/ref
        -/
        /--
        # F2
        ![doc-img](//evil.example/doc.png)
        [safe](https://lean-lang.org)
        [local](Other.lean)
        -/
        def x : Nat := 1
        """;

    SafeHtml blob =
        LeanHtml.renderBlob(lean, new Config(), blobView, "/b/repo/+/main/Test.lean", "Test.lean");
    assertThat(blob).isNotNull();
    String html = blob.getSafeHtmlString();
    assertStrictAllowlist(html);
    assertThat(html).doesNotContain("href=\"//");
    assertThat(html).doesNotContain("src=\"//");
    assertThat(html).doesNotContain("href=\"/\\");
    assertThat(html).contains("<p>[r]: //evil.example/ref</p>");
    assertThat(html).contains("href=\"about:invalid#zSoyz\"");
    assertThat(html).contains("src=\"data:image/gif;base64,zSoyz\"");
    assertThat(html).contains("href=\"https://lean-lang.org\"");
    assertThat(html).contains("href=\"Other.lean\"");
    assertThat(html).contains("<h1>F1</h1>");
    assertThat(html).contains("<h1>1</h1>");
    assertThat(html).contains("<h1>F2</h1>");
    assertThat(html).doesNotContain("<a class=\"h\"");
    assertThat(html).doesNotContain("name=\"F1\"");
    assertThat(html).doesNotContain("name=\"1\"");
    assertThat(html).doesNotContain("name=\"F2\"");

    // Upstream .md link allowlist behavior remains untouched.
    MarkdownConfig defaultMd = MarkdownConfig.get(new Config());
    SafeHtml upstreamMd =
        MarkdownToHtml.builder()
            .setConfig(defaultMd)
            .build()
            .toSoyHtml(GitilesMarkdown.parse(defaultMd, "[ok](//example.com/foo.html)"));
    assertThat(upstreamMd).isNotNull();
    assertThat(upstreamMd.getSafeHtmlString()).contains("href=\"//example.com/foo.html\"");
  }

  @Test
  public void html5NamedInvisibleEntitiesTriggerBannerAndSafePreFallback() {
    String source =
        """
        /-!
        Hello&ZeroWidthSpace;World &NoBreak; &InvisibleTimes; &InvisibleComma; &NegativeVeryThinSpace; &ApplyFunction; &varsubsetneq;
        -/
        def ok : Nat := 1
        """;
    String html = renderAndAssertSafeLeanHtml(source);
    assertThat(html).contains("<div class=\"LeanTrojanBanner\" role=\"alert\">");
    assertThat(html).contains("Detected 7 hidden/control characters");
    assertThat(html).contains("<pre><code>Hello&amp;ZeroWidthSpace;World");
    assertThat(LeanSecurity.containsHiddenOrSurrogate(html)).isFalse();
  }

  @Test
  public void dosGuardsPreventQuadraticMarkdownPrettifyTableTocAndMicroBlockBombs() {
    // R2-DOS-1: 120 KiB + 1.8 MiB repeated '&' without ';' stays O(N) inside scanEntitiesInText.
    String ampBlock =
        "/-!\n" + "&".repeat(60_000) + "\n" + "&".repeat(60_000) + "\n-/\ndef x := 1\n";
    String ampLarge = "/-!\n" + ("&".repeat(60_000) + "\n").repeat(30) + "-/\ndef x := 1\n";
    assertSafeLeanHtml(ampBlock);
    SafeHtml ampLargeSafe =
        LeanHtml.renderBlob(ampLarge, new Config(), null, null, "AmpLarge.lean");
    assertThat(ampLargeSafe).isNotNull();
    assertThat(ampLargeSafe.getSafeHtmlString()).contains("<pre><code>&amp;");

    // R2-DOS-2 & R2-DOS-3: [TOC] disabled and heading count/size bombs fall back to <pre><code>.
    String tocAndHeadings =
        "/-!\n" + "# h\n".repeat(150) + "[TOC]\n".repeat(150) + "-/\ndef t := 1\n";
    String tocHtml = renderAndAssertSafeLeanHtml(tocAndHeadings);
    assertThat(tocHtml).doesNotContain("class=\"toc\"");
    assertThat(tocHtml).contains("<pre><code>");

    String longHeading = "/-!\n# " + "a".repeat(600) + "\n-/\ndef h := 1\n";
    assertThat(renderAndAssertSafeLeanHtml(longHeading)).contains("<pre><code>");

    // Bounded Markdown output cap triggers preformatted fallback rather than OOM.
    LeanRequestBudget tinyOut =
        LeanRequestBudget.forTesting(
            LeanRequestBudget.MAX_REQUEST_INPUT_BYTES, 2_200, 1, 750_000_000L, () -> 0L);
    String expandingMd =
        "/-!\n" + "- [link](https://lean-lang.org/docs)\n".repeat(45) + "-/\ndef o := 1\n";
    SafeHtml tinyOutRes =
        LeanHtml.renderBlob(
            expandingMd.length(), expandingMd, new Config(), null, null, "CapMd.lean", tinyOut);
    if (tinyOutRes != null) {
      assertThat(tinyOutRes.getSafeHtmlString().length()).isAtMost(2_200);
      assertThat(tinyOutRes.getSafeHtmlString()).contains("<pre><code>");
    }

    // R2-DOS-4: Interleaved delimiter pattern (with and without blank paragraphs) is blocked,
    // while legitimate Lean snake_case identifiers inside docstrings still render rich Markdown.
    String interleaved = "/-!\n" + "a*b_c~d".repeat(200) + "\n-/\ndef d := 1\n";
    String interleavedMultiPara =
        "/-!\n" + ("a*b~".repeat(100) + "\n\n").repeat(12) + "-/\ndef d2 := 1\n";
    assertThat(renderAndAssertSafeLeanHtml(interleaved)).contains("<pre><code>");
    assertThat(renderAndAssertSafeLeanHtml(interleavedMultiPara)).contains("<pre><code>");

    String snakeIdentifiers =
        "/-!\nSee " + "foo_bar_baz_1 ".repeat(100) + "for details.\n-/\ndef ok := 1\n";
    String snakeHtml = renderAndAssertSafeLeanHtml(snakeIdentifiers);
    assertThat(snakeHtml).contains("<p>See foo_bar_baz_1");

    // R2-DOS-5: Oversized Markdown fenced code block skips ThreadSafePrettifyParser.
    String bigFence = "/-!\n```lean\n" + "1:=".repeat(6_000) + "\n```\n-/\ndef f := 1\n";
    String bigFenceHtml = renderAndAssertSafeLeanHtml(bigFence);
    assertThat(bigFenceHtml).contains("<pre class=\"code\">1:=");
    assertThat(bigFenceHtml).doesNotContain("<pre class=\"code\"><span class=");

    // R2-DOS-6: Wide GFM Markdown tables exceeding pipe limit degrade safely without building
    // <table>.
    String wideTable =
        "/-!\n" + "| a ".repeat(1_200) + "|\n" + "| --- ".repeat(1_200) + "|\n-/\ndef w := 1\n";
    String wideTableHtml = renderAndAssertSafeLeanHtml(wideTable);
    assertThat(wideTableHtml).contains("<pre><code>");
    assertThat(wideTableHtml).doesNotContain("<table>");

    // R2-DOS-7: Extreme micro-block count (>25k) falls back to null; >256 doc blocks degrade
    // cleanly.
    String microBlocks = ("open A in ".repeat(10) + "\n").repeat(2_600);
    assertThat(LeanHtml.renderBlob(microBlocks, new Config(), null, null, "Open26k.lean")).isNull();

    String manyDocs = "/-! doc -/ ".repeat(270) + "\ndef m := 1\n";
    String manyDocsHtml = renderAndAssertSafeLeanHtml(manyDocs);
    assertThat(manyDocsHtml).contains("<p>doc</p>");
    assertThat(manyDocsHtml).contains("<pre><code>doc</code></pre>");
  }

  private static int countOccurrences(String haystack, String needle) {
    int count = 0;
    int idx = 0;
    while ((idx = haystack.indexOf(needle, idx)) >= 0) {
      count++;
      idx += needle.length();
    }
    return count;
  }

  private static String extractVisibleText(String html) {
    StringBuilder out = new StringBuilder();
    HTMLEditorKit.ParserCallback callback =
        new HTMLEditorKit.ParserCallback() {
          @Override
          public void handleText(char[] data, int pos) {
            out.append(data);
          }

          @Override
          public void handleEndTag(HTML.Tag t, int pos) {
            String tag = t.toString().toLowerCase(Locale.ROOT);
            if (tag.equals("td") || tag.equals("th")) {
              out.append(' ');
            } else if (tag.equals("div")
                || tag.equals("tr")
                || tag.equals("p")
                || tag.equals("li")
                || tag.equals("pre")
                || tag.startsWith("h")) {
              out.append('\n');
            }
          }
        };
    try {
      new ParserDelegator().parse(new StringReader(html), callback, true);
    } catch (IOException e) {
      throw new AssertionError(e);
    }
    return out.toString();
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
    String lower = html.toLowerCase(Locale.ROOT);
    for (String marker : DANGEROUS_MARKERS) {
      assertThat(lower).doesNotContain(marker);
    }

    HTMLEditorKit.ParserCallback callback =
        new HTMLEditorKit.ParserCallback() {
          @Override
          public void handleStartTag(HTML.Tag t, MutableAttributeSet a, int pos) {
            validateParsedTag(t.toString(), a);
          }

          @Override
          public void handleSimpleTag(HTML.Tag t, MutableAttributeSet a, int pos) {
            validateParsedTag(t.toString(), a);
          }

          @Override
          public void handleEndTag(HTML.Tag t, int pos) {
            String tag = t.toString().toLowerCase(Locale.ROOT);
            // ParserDelegator wraps fragments in implicit html/body; reject unexpected tags.
            if (!tag.equals("html") && !tag.equals("head") && !tag.equals("body")) {
              assertThat(ALLOWED_LEAN_TAGS).contains(tag);
            }
          }

          @Override
          public void handleComment(char[] data, int pos) {
            throw new AssertionError("Unexpected HTML comment in Lean output");
          }

          @Override
          public void handleError(String errorMsg, int pos) {
            // Note: ParserDelegator reports HTML4 SGML warnings for HTML5 attrs like "role"
            // or entity "&apos;" while still calling handleStartTag/handleSimpleTag with them.
          }
        };

    try {
      new ParserDelegator().parse(new StringReader(html), callback, true);
    } catch (IOException e) {
      throw new AssertionError(e);
    }
  }

  private static void validateParsedTag(String rawTag, MutableAttributeSet attrs) {
    String tag = rawTag.toLowerCase(Locale.ROOT);
    if (tag.equals("html") || tag.equals("head") || tag.equals("body")) {
      return;
    }
    assertThat(ALLOWED_LEAN_TAGS).contains(tag);
    ImmutableSet<String> extra = TAG_EXTRA_ATTRS.getOrDefault(tag, ImmutableSet.of());
    Enumeration<?> names = attrs.getAttributeNames();
    while (names.hasMoreElements()) {
      Object key = names.nextElement();
      if ("_implied_".equals(key.toString())) {
        continue;
      }
      String attrName = key.toString().toLowerCase(Locale.ROOT);
      assertThat(attrName).doesNotMatch("^on.*");
      assertThat(attrName).isNotEqualTo("style");
      assertThat(attrName).doesNotMatch("^(?:data|aria)-.*");
      assertThat(GLOBAL_ATTRS.contains(attrName) || extra.contains(attrName)).isTrue();

      Object rawVal = attrs.getAttribute(key);
      String attrVal = rawVal != null ? rawVal.toString() : "";
      if ("href".equals(attrName)) {
        assertSafeHref(attrVal);
      } else if ("src".equals(attrName)) {
        assertSafeImgSrc(attrVal);
      }
    }
  }

  private static void assertSafeHref(String href) {
    String normalized = stripControlAndWhitespace(href).toLowerCase(Locale.ROOT);
    boolean allowed =
        normalized.startsWith("#")
            || (normalized.startsWith("/")
                && !normalized.startsWith("//")
                && !normalized.startsWith("/\\"))
            || normalized.startsWith("http://")
            || normalized.startsWith("https://")
            || normalized.startsWith("mailto:")
            || normalized.startsWith("git://")
            || normalized.equals("about:invalid#zsoyz")
            || isSafeRelativePath(normalized);
    assertThat(allowed).isTrue();
  }

  private static boolean isSafeRelativePath(String normalized) {
    if (normalized.isEmpty() || normalized.startsWith("\\")) {
      return false;
    }
    for (int i = 0; i < normalized.length(); i++) {
      char c = normalized.charAt(i);
      if (c == '/' || c == '?' || c == '#') {
        return true;
      }
      if (c == ':') {
        return false;
      }
    }
    return true;
  }

  private static void assertSafeImgSrc(String src) {
    String normalized = stripControlAndWhitespace(src).toLowerCase(Locale.ROOT);
    boolean allowed =
        normalized.startsWith("http://")
            || normalized.startsWith("https://")
            || normalized.equals("data:image/gif;base64,zsoyz")
            || (normalized.startsWith("data:image/")
                && !normalized.startsWith("data:image/svg+xml"));
    assertThat(allowed).isTrue();
  }

  private static String stripControlAndWhitespace(String s) {
    StringBuilder out = new StringBuilder(s.length());
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c > 0x20 && c != 0x7F) {
        out.append(c);
      }
    }
    return out.toString();
  }
}
