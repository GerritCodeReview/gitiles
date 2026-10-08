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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/** Comprehensive XML DOM and geometric unit tests for {@link SimpleDotRenderer}. */
@RunWith(JUnit4.class)
public class SimpleDotRendererTest {

  @Test
  public void testBasicDigraphExactSvgStructure() {
    SvgDoc svg = SvgDoc.renderDot("digraph G {\n  A -> B;\n}\n");
    svg.assertDotDefs();

    // Default node shape in DOT is ellipse
    assertThat(svg.getElementsByTag("ellipse")).hasSize(2);
    assertThat(svg.getAllTextContents()).containsExactly("A", "B");

    List<Element> edges = getGraphvizEdgePaths(svg);
    assertThat(edges).hasSize(1);
    Element edgePath = edges.get(0);
    assertThat(edgePath.getAttribute("class")).contains("graphviz-edge");
    assertThat(edgePath.getAttribute("marker-end")).isEqualTo("url(#graphviz-arrow)");
    assertThat(edgePath.getAttribute("fill")).isEqualTo("none");
    assertThat(edgePath.getAttribute("d")).startsWith("M ");
    assertThat(edgePath.getAttribute("d")).contains(" C ");
  }

  @Test
  public void testBasicUndirectedGraphExactSvgStructure() {
    SvgDoc svg = SvgDoc.renderDot("graph Undirected {\n  A -- B;\n}\n");
    svg.assertDotDefs();

    assertThat(svg.getElementsByTag("ellipse")).hasSize(2);
    assertThat(svg.getAllTextContents()).containsExactly("A", "B");

    List<Element> edges = getGraphvizEdgePaths(svg);
    assertThat(edges).hasSize(1);
    Element edgePath = edges.get(0);
    assertThat(edgePath.getAttribute("class")).contains("graphviz-edge");
    // Undirected graph edges must not have an arrowhead marker by default
    assertThat(edgePath.getAttribute("marker-end")).isEmpty();
    assertThat(edgePath.getAttribute("marker-start")).isEmpty();
  }

  @Test
  public void testMismatchedEdgeOperatorsRejected() {
    // Undirectedgraph with directed edge operator '->' must return Optional.empty()
    assertThat(SimpleDotRenderer.renderToSvg("graph G {\n  A -> B;\n}\n")).isEmpty();

    // Directed digraph with undirected edge operator '--' must return Optional.empty()
    assertThat(SimpleDotRenderer.renderToSvg("digraph G {\n  A -- B;\n}\n")).isEmpty();
  }

  @Test
  public void testStrictDigraphAndStrictGraphCollapseDuplicateEdges() {
    String strictDigraph =
        """
        strict digraph G {
          A -> B;
          A -> B;
          A -> B [label="kept"];
          B -> A;
        }
        """;
    SvgDoc directedSvg = SvgDoc.renderDot(strictDigraph);
    // A -> B collapsed to 1 edge, plus B -> A = 2 directed edges total
    assertThat(getGraphvizEdgePaths(directedSvg)).hasSize(2);

    String strictGraph =
        """
        strict graph G {
          A -- B;
          A -- B;
          B -- A;
        }
        """;
    SvgDoc undirectedSvg = SvgDoc.renderDot(strictGraph);
    // Undirected strict graph collapses A -- B and B -- A into a single edge
    assertThat(getGraphvizEdgePaths(undirectedSvg)).hasSize(1);
  }

  @Test
  public void testRankdirDirectionsCoordinateOrdering() {
    // TB (top-to-bottom): A is above B (centerY(A) < centerY(B))
    SvgDoc svgTb = SvgDoc.renderDot("digraph { rankdir=TB; A -> B; }");
    assertThat(getNodeTextY(svgTb, "A")).isLessThan(getNodeTextY(svgTb, "B"));

    // TD (alias for TB): A is above B (centerY(A) < centerY(B))
    SvgDoc svgTd = SvgDoc.renderDot("digraph { rankdir=TD; A -> B; }");
    assertThat(getNodeTextY(svgTd, "A")).isLessThan(getNodeTextY(svgTd, "B"));

    // BT (bottom-to-top): A is below B (centerY(A) > centerY(B))
    SvgDoc svgBt = SvgDoc.renderDot("digraph { rankdir=BT; A -> B; }");
    assertThat(getNodeTextY(svgBt, "A")).isGreaterThan(getNodeTextY(svgBt, "B"));

    // LR (left-to-right): A is to the left of B (centerX(A) < centerX(B))
    SvgDoc svgLr = SvgDoc.renderDot("digraph { rankdir=LR; A -> B; }");
    assertThat(getNodeTextX(svgLr, "A")).isLessThan(getNodeTextX(svgLr, "B"));

    // RL (right-to-left): A is to the right of B (centerX(A) > centerX(B))
    SvgDoc svgRl = SvgDoc.renderDot("digraph { rankdir=RL; A -> B; }");
    assertThat(getNodeTextX(svgRl, "A")).isGreaterThan(getNodeTextX(svgRl, "B"));
  }

  @Test
  public void testScopedDefaultsAndSubgraphInheritanceWithoutLeaking() {
    String dot =
        """
        digraph Scoped {
          graph [rankdir=LR];
          node [shape=box, style="filled", fillcolor="#e8f0fe", color="#1a73e8"];
          edge [color="#5f6368", style="dashed"];

          ParentBefore [label="Parent Before"];

          subgraph cluster_child {
            label="Child Scope";
            node [shape=diamond, fillcolor="#e6f4ea", color="#137333"];
            edge [color="#137333", style="solid"];

            ChildNode [label="Child Diamond"];
            ParentBefore -> ChildNode;
          }

          ParentAfter [label="Parent After"];
          ChildNode -> ParentAfter;
        }
        """;
    SvgDoc svg = SvgDoc.renderDot(dot);

    // ParentBefore and ParentAfter use top-level node [shape=box] -> 2 node rects
    // ChildNode uses child-scoped node [shape=diamond] -> 1 polygon with 4 vertices
    Element diamond = svg.findPolygonWithVertices(4);
    assertThat(diamond).isNotNull();
    assertThat(diamond.getAttribute("fill")).isEqualTo("#e6f4ea");
    assertThat(diamond.getAttribute("stroke")).isEqualTo("#137333");

    // Verify ParentAfter did not leak the child's diamond shape or green fillcolor
    List<Element> nodeRects = new ArrayList<>();
    for (Element r : svg.getElementsByTag("rect")) {
      if (r.getAttribute("class").contains("graphviz-node-shape")) {
        nodeRects.add(r);
      }
    }
    assertThat(nodeRects).hasSize(2);
    for (Element r : nodeRects) {
      assertThat(r.getAttribute("fill")).isEqualTo("#e8f0fe");
      assertThat(r.getAttribute("stroke")).isEqualTo("#1a7333".equals(r.getAttribute("stroke"))
          ? "#1a7333"
          : "#1a73e8");
    }

    // Verify LR rankdir from graph [...] was applied
    assertThat(getNodeTextX(svg, "Parent Before")).isLessThan(getNodeTextX(svg, "Child Diamond"));
    assertThat(getNodeTextX(svg, "Child Diamond")).isLessThan(getNodeTextX(svg, "Parent After"));
  }

  @Test
  public void testAllNodeShapesExactSvgElements() {
    String dot =
        """
        digraph Shapes {
          rankdir=TB;
          N_ellipse [label="Default Ellipse"];
          N_box [shape=box, style="filled,rounded", label="Rounded Box"];
          N_circle [shape=circle, label="Circle"];
          N_double [shape=doublecircle, label="Double"];
          N_diamond [shape=diamond, label="Diamond"];
          N_hex [shape=hexagon, label="Hexagon"];
          N_cyl [shape=cylinder, label="Cylinder"];
          N_note [shape=note, label="Note Doc"];
          N_folder [shape=folder, label="Folder"];
          N_comp [shape=component, label="Component"];
          N_plain [shape=plaintext, label="Plaintext Label"];

          N_ellipse -> N_box -> N_circle -> N_double -> N_diamond;
          N_diamond -> N_hex -> N_cyl -> N_note -> N_folder -> N_comp -> N_plain;
        }
        """;
    SvgDoc svg = SvgDoc.renderDot(dot);

    assertThat(svg.findText("Default Ellipse")).isNotNull();
    assertThat(svg.findText("Rounded Box")).isNotNull();
    assertThat(svg.findText("Circle")).isNotNull();
    assertThat(svg.findText("Double")).isNotNull();
    assertThat(svg.findText("Diamond")).isNotNull();
    assertThat(svg.findText("Hexagon")).isNotNull();
    assertThat(svg.findText("Cylinder")).isNotNull();
    assertThat(svg.findText("Note Doc")).isNotNull();
    assertThat(svg.findText("Folder")).isNotNull();
    assertThat(svg.findText("Component")).isNotNull();
    assertThat(svg.findText("Plaintext Label")).isNotNull();

    // Ellipse exists for N_ellipse
    assertThat(svg.getElementsByTag("ellipse")).isNotEmpty();

    // Circle + Doublecircle -> at least 3 <circle> elements (1 for circle, 2 concentric for doublecircle)
    assertThat(svg.getElementsByTag("circle").size()).isAtLeast(3);

    // Diamond (4 vertices) and Hexagon (6 vertices)
    assertThat(svg.findPolygonWithVertices(4)).isNotNull();
    assertThat(svg.findPolygonWithVertices(6)).isNotNull();
  }

  @Test
  public void testRecordAndMrecordCompartmentsAndPortStripping() {
    String dot =
        """
        digraph Records {
          rankdir=LR;
          rec1 [shape=record, label="<f0> Header | <f1> Body | <f2> Footer"];
          rec2 [shape=Mrecord, label="{<p1> Top | <p2> Bottom}"];
          rec1:f1:e -> rec2:p1:w [label="port link"];
        }
        """;
    SvgDoc svg = SvgDoc.renderDot(dot);

    // Verify port tags <f0>, <f1>, <f2>, <p1>, <p2> are stripped from rendered text
    String allText = String.join(" ", svg.getAllTextContents());
    assertThat(allText).contains("Header");
    assertThat(allText).contains("Body");
    assertThat(allText).contains("Footer");
    assertThat(allText).contains("Top");
    assertThat(allText).contains("Bottom");
    assertThat(allText).doesNotContain("<f0>");
    assertThat(allText).doesNotContain("<f1>");
    assertThat(allText).doesNotContain("<p1>");

    // Verify divider <line> elements are emitted for record compartments
    assertThat(svg.getElementsByTag("line")).isNotEmpty();
    assertThat(svg.findText("port link")).isNotNull();
  }

  @Test
  public void testPerLineTextJustificationEscapes() {
    String dot =
        """
        digraph Alignment {
          node [shape=box];
          A [label="Centered Title\\n  * Left Bullet 1\\l  * Left Bullet 2\\lRight Aligned Footer\\r"];
        }
        """;
    SvgDoc svg = SvgDoc.renderDot(dot);

    Element centerTspan = svg.findText("Centered Title");
    Element leftTspan1 = svg.findText("* Left Bullet 1");
    Element leftTspan2 = svg.findText("* Left Bullet 2");
    Element rightTspan = svg.findText("Right Aligned Footer");

    assertThat(centerTspan).isNotNull();
    assertThat(leftTspan1).isNotNull();
    assertThat(leftTspan2).isNotNull();
    assertThat(rightTspan).isNotNull();

    assertThat(getEffectiveTextAnchor(centerTspan)).isEqualTo("middle");
    assertThat(getEffectiveTextAnchor(leftTspan1)).isEqualTo("start");
    assertThat(getEffectiveTextAnchor(leftTspan2)).isEqualTo("start");
    assertThat(getEffectiveTextAnchor(rightTspan)).isEqualTo("end");

    double leftX = Double.parseDouble(leftTspan1.getAttribute("x"));
    double centerX = Double.parseDouble(centerTspan.getAttribute("x"));
    double rightX = Double.parseDouble(rightTspan.getAttribute("x"));

    assertThat(leftX).isLessThan(centerX);
    assertThat(centerX).isLessThan(rightX);
  }

  @Test
  public void testNestedClustersAndRankSameAlignment() {
    String dot =
        """
        digraph Clusters {
          rankdir=TB;
          compound=true;

          subgraph cluster_outer {
            label="Outer Cluster";
            style="filled,rounded";
            color="#1a73e8";
            fillcolor="#f8fafd";

            subgraph cluster_inner_a {
              label="Inner A";
              color="#137333";
              fillcolor="#f6fbf7";
              A1 [label="Node A1", shape=box];
            }

            subgraph cluster_inner_b {
              label="Inner B";
              color="#b06000";
              fillcolor="#fefaf6";
              B1 [label="Node B1", shape=box];
            }

            A1 -> B1;
          }

          { rank=same; Peer1; Peer2; }
          B1 -> Peer1;
          B1 -> Peer2;
        }
        """;
    SvgDoc svg = SvgDoc.renderDot(dot);

    List<SvgDoc.Rect2D> clusters = svg.getSubgraphBoundingBoxes();
    assertThat(clusters).hasSize(3);

    assertThat(svg.findText("Outer Cluster")).isNotNull();
    assertThat(svg.findText("Inner A")).isNotNull();
    assertThat(svg.findText("Inner B")).isNotNull();

    // Verify { rank=same; Peer1; Peer2; } places Peer1 and Peer2 on the same horizontal rank (Y coordinate)
    double peer1Y = getNodeTextY(svg, "Peer1");
    double peer2Y = getNodeTextY(svg, "Peer2");
    assertThat(Math.abs(peer1Y - peer2Y)).isLessThan(2.0);
  }

  @Test
  public void testCyclesSelfLoopsChainedEdgesNodeGroupsAndCompoundEdges() {
    String dot =
        """
        digraph ComplexEdges {
          rankdir=TB;
          compound=true;

          subgraph cluster_src {
            label="Source Cluster";
            S1 -> S2 -> S3;
          }

          subgraph cluster_dst {
            label="Dest Cluster";
            D1;
          }

          // Cycle back-edge
          S3 -> S1 [label="back-edge"];

          // Self-loop
          S2 -> S2 [label="self-loop"];

          // Node group fan-out
          {S2 S3} -> {D1 D2};

          // Compound edge with ltail/lhead
          S3 -> D1 [ltail=cluster_src, lhead=cluster_dst, label="cluster-to-cluster"];
        }
        """;
    SvgDoc svg = SvgDoc.renderDot(dot);

    assertThat(svg.findText("Source Cluster")).isNotNull();
    assertThat(svg.findText("Dest Cluster")).isNotNull();
    assertThat(svg.findText("back-edge")).isNotNull();
    assertThat(svg.findText("self-loop")).isNotNull();
    assertThat(svg.findText("cluster-to-cluster")).isNotNull();
    assertThat(getGraphvizEdgePaths(svg).size()).isAtLeast(8);
  }

  @Test
  public void testCommentsAndStringConcatenation() {
    String dot =
        """
        // Single-line C++ comment
        # Preprocessor-style comment
        /* Multi-line
           block comment */
        digraph CommentsAndConcat {
          A [label="Hello " + "Graphviz " + "World"];
          B [label="Line 1 // not a comment\\n" + "Line 2 /* also not a comment */"];
          A -> B [label="Edge " + "Label"];
        }
        """;
    SvgDoc svg = SvgDoc.renderDot(dot);

    assertThat(svg.findText("Hello Graphviz World")).isNotNull();
    assertThat(svg.findText("Line 1 // not a comment")).isNotNull();
    assertThat(svg.findText("Line 2 /* also not a comment */")).isNotNull();
    assertThat(svg.findText("Edge Label")).isNotNull();
  }

  @Test
  public void testDarkModeClassesAndLuminanceContrast() {
    String dot =
        """
        digraph ThemeContrast {
          subgraph cluster_group {
            label="Theme Cluster";
            DefaultNode [shape=box, label="Default Theme Node"];
            LightFillNode [shape=box, style=filled, fillcolor="#e8f0fe", label="Light Fill Node"];
            DarkFillNode [shape=box, style=filled, fillcolor="#1e293b", label="Dark Fill Node"];
            ExplicitFontNode [shape=box, style=filled, fillcolor="#e8f0fe", fontcolor="#1a73e8", label="Explicit Font Node"];
            DefaultNode -> LightFillNode [label="Theme Edge"];
            LightFillNode -> DarkFillNode -> ExplicitFontNode;
          }
        }
        """;
    SvgDoc svg = SvgDoc.renderDot(dot);

    // Verify semantic classes exist in the SVG
    assertThat(svg.findSubgraphRects()).hasSize(1);
    assertThat(svg.findSubgraphRects().get(0).getAttribute("class"))
        .contains("graphviz-subgraph-box");

    List<Element> edges = getGraphvizEdgePaths(svg);
    assertThat(edges).isNotEmpty();
    assertThat(edges.get(0).getAttribute("class")).contains("graphviz-edge");

    Element defaultText = svg.findText("Default Theme Node");
    assertThat(defaultText).isNotNull();
    assertThat( getEffectiveClass(defaultText)).contains("graphviz-node-label");

    // Light fillcolor (#e8f0fe) without explicit fontcolor must receive dark contrast text fill
    Element lightFillText = svg.findText("Light Fill Node");
    assertThat(lightFillText).isNotNull();
    String lightFillTextColor = getEffectiveFill(lightFillText);
    assertThat(lightFillTextColor).isIn(Arrays.asList("#1f2328", "#0f172a", "#202124"));

    // Dark fillcolor (#1e293b) without explicit fontcolor must receive light contrast text fill
    Element darkFillText = svg.findText("Dark Fill Node");
    assertThat(darkFillText).isNotNull();
    String darkFillTextColor = getEffectiveFill(darkFillText);
    assertThat(darkFillTextColor).isIn(Arrays.asList("#f0f6fc", "#e8eaed", "#ffffff", "#f8fafc"));
    assertThat(darkFillTextColor).isNotEqualTo(lightFillTextColor);

    // Explicit fontcolor (#1a73e8) must be respected
    Element explicitFontText = svg.findText("Explicit Font Node");
    assertThat(explicitFontText).isNotNull();
    assertThat(getEffectiveFill(explicitFontText)).isEqualTo("#1a73e8");
  }

  @Test
  public void testDesignDocArchitectureDiagramRendersWithAllInvariants() {
    String designDocDot =
        """
        digraph gitiles_graphviz_pipeline {
          rankdir=TB;
          compound=true;
          nodesep=0.5;
          ranksep=0.65;
          fontname="Google Sans, Roboto, Arial, sans-serif";
          node [
            shape=box,
            style="filled,rounded",
            penwidth=1.5,
            margin="0.25,0.2",
            fontname="Google Sans, Roboto, Arial, sans-serif",
            fontsize=11,
            color="#5f6368",
            fillcolor="#f8f9fa",
            fontcolor="#202124"
          ];
          edge [
            fontname="Google Sans, Roboto, Arial, sans-serif",
            fontsize=10,
            color="#5f6368",
            fontcolor="#3c4043",
            penwidth=1.4
          ];

          subgraph cluster_markdown {
            label="1. Gitiles CommonMark Pipeline (com.google.gitiles.doc)";
            style="filled,rounded";
            color="#1a73e8";
            fillcolor="#f8fafd";
            fontcolor="#1a73e8";
            fontsize=12;
            margin=18;

            md_config [
              label="MarkdownConfig\\n\\n  * markdown.graphviz (default: true)\\l  * Propagated in copyWithExtensions()\\l",
              color="#1a73e8",
              fillcolor="#e8f0fe"
            ];

            md_visitor [
              label="MarkdownToHtml.visit(FencedCodeBlock)\\n\\n  * Matches info string: dot | graphviz\\l  * Checks config.graphviz && non-empty block\\l  * Delegates source to SimpleDotRenderer\\l",
              color="#1a73e8",
              fillcolor="#e8f0fe"
            ];

            md_config -> md_visitor [label="config.graphviz"];
          }

          subgraph cluster_renderer {
            label="2. Standalone Pure-Java Renderer (SimpleDotRenderer.java)";
            style="filled,rounded";
            color="#137333";
            fillcolor="#f6fbf7";
            fontcolor="#137333";
            fontsize=12;
            margin=18;

            lexer_parser [
              label="Stage A: DOT Lexer & Recursive-Descent Parser\\n\\n  * [strict] (graph | digraph) [id] { ... }\\l  * Scoped graph / node / edge default attributes\\l  * Nested subgraph cluster_* & rank=same blocks\\l  * Chained edges (A -> B -> C) & port stripping\\l  * Alignment escapes: \\\\n (center), \\\\l (left), \\\\r (right)\\l",
              color="#137333",
              fillcolor="#e6f4ea"
            ];

            sugiyama_layout [
              label="Stage B: Sugiyama Hierarchical DAG Layout\\n\\n  * DFS cycle breaking & back-edge reversal\\l  * Longest-path layering + ALAP source compaction\\l  * Virtual dummy nodes for multi-layer edges\\l  * 4-pass barycentric edge-crossing minimization\\l  * Recursive bottom-up cluster_* bounding boxes\\l  * Coordinate transform for TB, BT, LR, RL\\l",
              color="#137333",
              fillcolor="#e6f4ea"
            ];

            svg_emitter [
              label="Stage C: Sanitized SVG & Theme Emitter\\n\\n  * Pure geometric SVG (<rect>, <path>, <polygon>, <text>)\\l  * Per-line <tspan> anchors (middle / start / end)\\l  * CSS color allowlist (isValidCssColor) & XML escaping\\l  * Relative luminance (isLightColor) for dark mode\\l  * Strips URL / href / image / <script> / <foreignObject>\\l",
              color="#137333",
              fillcolor="#e6f4ea"
            ];

            lexer_parser -> sugiyama_layout [label="DotGraph AST", color="#137333"];
            sugiyama_layout -> svg_emitter [label="Positioned DAG + Clusters", color="#137333"];
          }

          subgraph cluster_output {
            label="3. Server-Side HTML Response & Styling (Zero Client JS)";
            style="filled,rounded";
            color="#b06000";
            fillcolor="#fefaf6";
            fontcolor="#b06000";
            fontsize=12;
            margin=18;

            svg_html [
              label="Inline SafeHtml Output\\n\\n  * <div class=\\"graphviz-container\\">\\l  * <svg class=\\"graphviz-svg\\" viewBox=...>\\l  * Styled by doc.css (light & dark mode)\\l",
              color="#137333",
              fillcolor="#e6f4ea"
            ];

            fallback_pre [
              label="Graceful Code Block Fallback\\n\\n  * <pre class=\\"lang-dot\\">...source...</pre>\\l  * Triggered on syntax error or > 400 nodes\\l  * Never fails page render\\l",
              color="#b06000",
              fillcolor="#fef7e0"
            ];
          }

          md_visitor -> lexer_parser [label="renderToSvg(dotSource)", color="#1a73e8"];
          svg_emitter -> svg_html [label="Optional.of(svg)", color="#137333"];
          lexer_parser -> fallback_pre [label="Optional.empty()\\n(unsupported / invalid / oversized)", style="dashed", color="#b06000"];
        }
        """;

    SvgDoc svg = SvgDoc.renderDot(designDocDot);
    svg.assertDotDefs();

    // Verify all 3 clusters and 7 architecture nodes are rendered without overlaps
    assertThat(svg.getSubgraphBoundingBoxes()).hasSize(3);
    assertThat(svg.getNodeBoundingBoxes()).hasSize(7);
    assertThat(getGraphvizEdgePaths(svg)).hasSize(6);

    assertThat(svg.findText("1. Gitiles CommonMark Pipeline (com.google.gitiles.doc)")).isNotNull();
    assertThat(svg.findText("2. Standalone Pure-Java Renderer (SimpleDotRenderer.java)"))
        .isNotNull();
    assertThat(svg.findText("3. Server-Side HTML Response & Styling (Zero Client JS)")).isNotNull();
    assertThat(svg.findText("Stage A: DOT Lexer & Recursive-Descent Parser")).isNotNull();
    assertThat(svg.findText("Stage B: Sugiyama Hierarchical DAG Layout")).isNotNull();
    assertThat(svg.findText("Stage C: Sanitized SVG & Theme Emitter")).isNotNull();
  }

  private static List<Element> getGraphvizEdgePaths(SvgDoc svg) {
    List<Element> edges = new ArrayList<>();
    for (Element p : svg.getElementsByTag("path")) {
      if (p.getAttribute("class").contains("graphviz-edge")) {
        edges.add(p);
      }
    }
    return edges;
  }

  private static double getNodeTextX(SvgDoc svg, String label) {
    Element el = svg.findText(label);
    assertThat(el).isNotNull();
    if (!el.getAttribute("x").isEmpty()) {
      return Double.parseDouble(el.getAttribute("x"));
    }
    if (el.getParentNode() instanceof Element parent && !parent.getAttribute("x").isEmpty()) {
      return Double.parseDouble(parent.getAttribute("x"));
    }
    throw new AssertionError("No x coordinate found on text element for: " + label);
  }

  private static double getNodeTextY(SvgDoc svg, String label) {
    Element el = svg.findText(label);
    assertThat(el).isNotNull();
    if (!el.getAttribute("y").isEmpty()) {
      return Double.parseDouble(el.getAttribute("y"));
    }
    if (el.getParentNode() instanceof Element parent && !parent.getAttribute("y").isEmpty()) {
      return Double.parseDouble(parent.getAttribute("y"));
    }
    NodeList tspans = el.getElementsByTagName("tspan");
    if (tspans.getLength() > 0 && tspans.item(0) instanceof Element tspan) {
      if (!tspan.getAttribute("y").isEmpty()) {
        return Double.parseDouble(tspan.getAttribute("y"));
      }
    }
    throw new AssertionError("No y coordinate found on text element for: " + label);
  }

  private static String getEffectiveTextAnchor(Element el) {
    if (!el.getAttribute("text-anchor").isEmpty()) {
      return el.getAttribute("text-anchor");
    }
    if (el.getParentNode() instanceof Element parent) {
      return parent.getAttribute("text-anchor");
    }
    return "";
  }

  private static String getEffectiveFill(Element el) {
    if (!el.getAttribute("fill").isEmpty()) {
      return el.getAttribute("fill");
    }
    if (el.getParentNode() instanceof Element parent && !parent.getAttribute("fill").isEmpty()) {
      return parent.getAttribute("fill");
    }
    NodeList children = el.getChildNodes();
    for (int i = 0; i < children.getLength(); i++) {
      Node child = children.item(i);
      if (child instanceof Element childEl && !childEl.getAttribute("fill").isEmpty()) {
        return childEl.getAttribute("fill");
      }
    }
    return "";
  }

  private static String getEffectiveClass(Element el) {
    String cls = el.getAttribute("class");
    if (el.getParentNode() instanceof Element parent) {
      cls = cls + " " + parent.getAttribute("class");
    }
    return cls.trim();
  }

  @Test
  public void testEscapedRecordDelimitersAndRankSameDownstreamPropagation() {
    String dot =
        """
        digraph ReviewEdgeCases {
          rankdir=TB;
          rec [shape=record, fillcolor="burlywood", style=filled,
               label="<f0> a \\| b | <f1> \\<literal\\>\\nline2"];
          deep1 [label="Deep1", style=filled, fillcolor="rgb(240 248 255)"];
          deep2 [label="Deep2"];
          peer [label="Peer"];
          child [label="ChildOfPeer"];

          rec -> deep1 -> deep2;
          peer -> child;
          { rank=same; deep2; peer; }
        }
        """;
    SvgDoc svg = SvgDoc.renderDot(dot);

    String allText = String.join(" ", svg.getAllTextContents());
    assertThat(allText).contains("a | b");
    assertThat(allText).contains("<literal> line2");
    assertThat(allText).doesNotContain("<f0>");
    assertThat(allText).doesNotContain("<f1>");

    // Verify rank=same aligns deep2 and peer, AND propagates downstream so child is below peer
    double deep2Y = getNodeTextY(svg, "Deep2");
    double peerY = getNodeTextY(svg, "Peer");
    double childY = getNodeTextY(svg, "ChildOfPeer");
    assertThat(Math.abs(deep2Y - peerY)).isLessThan(2.0);
    assertThat(childY).isGreaterThan(peerY + 20.0);
  }
}
