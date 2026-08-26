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

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.annotation.Nullable;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/** Comprehensive XML DOM-based unit tests for {@link SimpleMermaidRenderer}. */
@RunWith(JUnit4.class)
public class SimpleMermaidRendererTest {

  @Test
  public void testConstructorInstantiation() {
    SimpleMermaidRenderer renderer = new SimpleMermaidRenderer();
    assertThat(renderer).isNotNull();
  }

  @Test
  public void testBasicGraphTDExactSvgStructure() {
    String code = "graph TD\n  A --> B\n";
    Optional<String> svgOpt = SimpleMermaidRenderer.renderToSvg(code);
    assertThat(svgOpt.isPresent()).isTrue();

    SvgDoc svg = new SvgDoc(svgOpt.get());
    svg.assertRootSvg();
    svg.assertDefs();

    // Verify exactly 2 node rects and 1 edge path
    assertThat(svg.getElementsByTag("rect").size()).isEqualTo(2);
    assertThat(svg.getElementsByTag("path").size()).isEqualTo(2); // 1 arrow in defs + 1 edge

    // Verify exact node texts
    List<String> texts = svg.getAllTextContents();
    assertThat(texts).containsExactly("A", "B").inOrder();

    // Verify edge has marker and cubic bezier curve
    Element edgePath = svg.getEdgePaths().get(0);
    assertThat(edgePath.getAttribute("marker-end")).isEqualTo("url(#mermaid-arrow)");
    assertThat(edgePath.getAttribute("stroke")).isEqualTo("#64748b");
    assertThat(edgePath.getAttribute("fill")).isEqualTo("none");
    assertThat(edgePath.getAttribute("d")).startsWith("M ");
    assertThat(edgePath.getAttribute("d")).contains(" C ");
  }

  @Test
  public void testGraphDirectionsAndFallbacks() {
    for (String dir : new String[] {"TD", "TB", "LR", "RL", "BT"}) {
      String code = "graph " + dir + "\n  A --> B\n";
      Optional<String> svgOpt = SimpleMermaidRenderer.renderToSvg(code);
      assertThat(svgOpt.isPresent()).isTrue();
      SvgDoc svg = new SvgDoc(svgOpt.get());
      svg.assertRootSvg();
      assertThat(svg.getAllTextContents()).containsExactly("A", "B").inOrder();
    }
    // Default direction when omitted
    SvgDoc defSvg = new SvgDoc(SimpleMermaidRenderer.renderToSvg("graph\n  A --> B\n").get());
    defSvg.assertRootSvg();
    assertThat(defSvg.getAllTextContents()).containsExactly("A", "B").inOrder();

    // Invalid direction fallback to TD
    SvgDoc fallbackSvg = new SvgDoc(SimpleMermaidRenderer.renderToSvg("flowchart INVALID\n  A --> B\n").get());
    fallbackSvg.assertRootSvg();
    assertThat(fallbackSvg.getAllTextContents()).containsExactly("A", "B").inOrder();
  }

  @Test
  public void testAllNodeShapesExactSvgElements() {
    String code =
        "graph TD\n"
            + "  A[Rectangle Box]\n"
            + "  B(Rounded Ball)\n"
            + "  C([Stadium Ring])\n"
            + "  D[[Subroutine Cart]]\n"
            + "  E[(Cylinder Drum)]\n"
            + "  F((Circle Star))\n"
            + "  G{{Hexagon Block}}\n"
            + "  H{Diamond Kite}\n"
            + "  I>Asymmetric Flag]\n";
    Optional<String> svgOpt = SimpleMermaidRenderer.renderToSvg(code);
    assertThat(svgOpt.isPresent()).isTrue();

    SvgDoc svg = new SvgDoc(svgOpt.get());
    svg.assertRootSvg();

    // Exact text elements
    List<String> texts = svg.getAllTextContents();
    assertThat(texts).containsExactly(
        "Rectangle Box", "Rounded Ball", "Stadium Ring", "Subroutine Cart", "Cylinder Drum", "Circle Star", "Hexagon Block", "Diamond Kite", "Asymmetric Flag")
        .inOrder();

    // Verify Diamond has a polygon with 4 vertices
    Element diamond = svg.findPolygonWithVertices(4);
    assertThat(diamond).isNotNull();
    assertThat(diamond.getAttribute("fill")).isEqualTo("#ffffff");
    assertThat(diamond.getAttribute("stroke")).isEqualTo("#64748b");

    // Verify Hexagon has a polygon with 6 vertices
    Element hexagon = svg.findPolygonWithVertices(6);
    assertThat(hexagon).isNotNull();

    // Verify Asymmetric flag has a polygon with 5 vertices
    Element flag = svg.findPolygonWithVertices(5);
    assertThat(flag).isNotNull();

    // Verify Subroutine has rect with 2 inner border lines
    List<Element> lines = svg.getElementsByTag("line");
    assertThat(lines.size()).isEqualTo(2);

    // Verify Cylinder has rect + curved path
    List<Element> paths = svg.getEdgePaths();
    assertThat(paths.size()).isEqualTo(1); // 1 cylinder cap path
  }

  @Test
  public void testMultilineNodeLabelsWithTspans() {
    String code =
        "graph TD\n"
            + "  A[\"Sunny Blue Sky<br/>Warm Golden Sun<br/>Soft Green Grass\"]\n"
            + "  B[\"Little Red Apple\"]\n"
            + "  A --> B\n";
    Optional<String> svgOpt = SimpleMermaidRenderer.renderToSvg(code);
    assertThat(svgOpt.isPresent()).isTrue();

    SvgDoc svg = new SvgDoc(svgOpt.get());
    svg.assertRootSvg();

    List<Element> tspans = svg.getElementsByTag("tspan");
    assertThat(tspans.size()).isEqualTo(3);
    assertThat(tspans.get(0).getTextContent()).isEqualTo("Sunny Blue Sky");
    assertThat(tspans.get(0).getAttribute("font-weight")).isEqualTo("600");
    assertThat(tspans.get(0).getAttribute("dy")).isEqualTo("0");

    assertThat(tspans.get(1).getTextContent()).isEqualTo("Warm Golden Sun");
    assertThat(tspans.get(1).getAttribute("font-weight")).isEqualTo("400");
    assertThat(tspans.get(1).getAttribute("dy")).isEqualTo("16");

    assertThat(tspans.get(2).getTextContent()).isEqualTo("Soft Green Grass");
    assertThat(tspans.get(2).getAttribute("font-weight")).isEqualTo("400");
    assertThat(tspans.get(2).getAttribute("dy")).isEqualTo("16");

    Element singleLineText = svg.findText("Little Red Apple");
    assertThat(singleLineText).isNotNull();
    assertThat(singleLineText.getAttribute("dominant-baseline")).isEqualTo("central");
  }

  @Test
  public void testQuotedLabelsWithBracketsAndEntities() {
    String code =
        "graph TD\n"
            + "  A[\"Play with teddy bear\"]\n"
            + "  B[\"Find [Puppy] in cozy room\"]\n"
            + "  C{\"Is kitten <tiny> & 'sweet'?\"}\n"
            + "  A --> B --> C\n";
    Optional<String> svgOpt = SimpleMermaidRenderer.renderToSvg(code);
    assertThat(svgOpt.isPresent()).isTrue();

    SvgDoc svg = new SvgDoc(svgOpt.get());
    svg.assertRootSvg();

    // XML parsing confirms correct unescaping of &lt;, &gt;, &apos;, &amp;
    assertThat(svg.getAllTextContents()).containsExactly(
        "Play with teddy bear",
        "Find [Puppy] in cozy room",
        "Is kitten <tiny> & 'sweet'?")
        .inOrder();
  }

  @Test
  public void testQuotedCleanLabel() {
    String code = "graph TD\n  A[\"\\\"Sleepy Kitten\\\"\"] --> B\n";
    Optional<String> svgOpt = SimpleMermaidRenderer.renderToSvg(code);
    assertThat(svgOpt.isPresent()).isTrue();

    SvgDoc svg = new SvgDoc(svgOpt.get());
    assertThat(svg.getAllTextContents()).containsExactly("Sleepy Kitten", "B").inOrder();
  }

  @Test
  public void testAllEdgeTypesAndInfixLabelsExactAttributes() {
    String code =
        "graph LR\n"
            + "  A -->|Yellow Duck| B\n"
            + "  B ---|Blue Bird| C\n"
            + "  C -.->|Green Frog| D\n"
            + "  D ==>|Red Puppy| E\n"
            + "  E -- Orange Kitten --> F\n"
            + "  F -- Purple Bunny --- G\n"
            + "  G == White Lamb ==> H\n"
            + "  H == Pink Piggy === I\n"
            + "  I -. Brown Bear .-> J\n"
            + "  J -. Gray Mouse .- K\n"
            + "  K -.- L\n"
            + "  L === M\n"
            + "  M <--> N\n";
    Optional<String> svgOpt = SimpleMermaidRenderer.renderToSvg(code);
    assertThat(svgOpt.isPresent()).isTrue();

    SvgDoc svg = new SvgDoc(svgOpt.get());
    svg.assertRootSvg();

    List<Element> edgePaths = svg.getEdgePaths();
    assertThat(edgePaths.size()).isEqualTo(13);

    // Verify dashed stroke
    Element dashedEdge = edgePaths.get(2);
    assertThat(dashedEdge.getAttribute("stroke-dasharray")).isEqualTo("4,4");
    assertThat(dashedEdge.getAttribute("marker-end")).isEqualTo("url(#mermaid-arrow)");

    // Verify thick stroke
    Element thickEdge = edgePaths.get(3);
    assertThat(thickEdge.getAttribute("stroke-width")).isEqualTo("2.5");
    assertThat(thickEdge.getAttribute("marker-end")).isEqualTo("url(#mermaid-arrow)");

    // Verify unarrowed solid line
    Element unarrowedLine = edgePaths.get(1);
    assertThat(unarrowedLine.getAttribute("marker-end")).isEmpty();

    // Verify edge labels
    List<String> texts = svg.getAllTextContents();
    assertThat(texts).contains("Yellow Duck");
    assertThat(texts).contains("Blue Bird");
    assertThat(texts).contains("Green Frog");
    assertThat(texts).contains("Red Puppy");
    assertThat(texts).contains("Orange Kitten");
    assertThat(texts).contains("Purple Bunny");
    assertThat(texts).contains("White Lamb");
    assertThat(texts).contains("Pink Piggy");
    assertThat(texts).contains("Brown Bear");
    assertThat(texts).contains("Gray Mouse");
  }

  @Test
  public void testSubgraphsWithTitlesAliasesAndDirectionOverrides() {
    String code =
        "graph TD\n"
            + "  subgraph Playground Park\n"
            + "    direction LR\n"
            + "    E1(puppy)\n"
            + "    E2(kitten)\n"
            + "  end\n"
            + "  subgraph ToyHouse [\"Magic Toy House\"]\n"
            + "    direction INVALID_DIR\n"
            + "    S1[(Teddy)]\n"
            + "  end\n"
            + "  subgraph \"Music Tree Castle\"\n"
            + "    T1[Wooden Blocks]\n"
            + "  end\n"
            + "  subgraph MeadowHill [Sunny Meadow Hill]\n"
            + "    U1[Little Duck]\n"
            + "  end\n"
            + "  Baby --> E1\n"
            + "  E1 --> ToyHouse\n"
            + "  ToyHouse --> T1\n"
            + "  T1 --> MeadowHill\n";
    Optional<String> svgOpt = SimpleMermaidRenderer.renderToSvg(code);
    assertThat(svgOpt.isPresent()).isTrue();

    SvgDoc svg = new SvgDoc(svgOpt.get());
    svg.assertRootSvg();

    // Verify 4 subgraph boundary rects (stroke-dasharray="4,4")
    List<Element> subgraphs = svg.findSubgraphRects();
    assertThat(subgraphs.size()).isEqualTo(4);
    for (Element sgRect : subgraphs) {
      assertThat(sgRect.getAttribute("stroke-dasharray")).isEqualTo("4,4");
      assertThat(sgRect.getAttribute("rx")).isEqualTo("8");
    }

    // Verify subgraph titles
    assertThat(svg.findText("Playground Park")).isNotNull();
    assertThat(svg.findText("Magic Toy House")).isNotNull();
    assertThat(svg.findText("Music Tree Castle")).isNotNull();
    assertThat(svg.findText("Sunny Meadow Hill")).isNotNull();
  }

  @Test
  public void testSubgraphToSubgraphAndSubgraphToNodeEdges() {
    String codeTD =
        "graph TD\n"
            + "  subgraph SubA [\"Garden A\"]\n"
            + "    A1[Daisy Flower]\n"
            + "  end\n"
            + "  subgraph SubB [\"Garden B\"]\n"
            + "    B1[Tulip Flower]\n"
            + "  end\n"
            + "  SubA -->|Garden Link TD| SubB\n"
            + "  SubA -->|Flower Link| NodeC[Red Rose]\n"
            + "  NodeC -->|Petal Link| SubB\n";
    Optional<String> svgTDOpt = SimpleMermaidRenderer.renderToSvg(codeTD);
    assertThat(svgTDOpt.isPresent()).isTrue();

    SvgDoc svgTD = new SvgDoc(svgTDOpt.get());
    svgTD.assertRootSvg();
    assertThat(svgTD.findText("Garden Link TD")).isNotNull();
    assertThat(svgTD.findText("Flower Link")).isNotNull();
    assertThat(svgTD.findText("Petal Link")).isNotNull();

    // Subgraph-to-subgraph edge is rendered as a straight line with arrow marker
    List<Element> lines = svgTD.getElementsByTag("line");
    assertThat(lines.size()).isEqualTo(1);
    assertThat(lines.get(0).getAttribute("marker-end")).isEqualTo("url(#mermaid-arrow)");

    String codeLR =
        "graph LR\n"
            + "  subgraph SubA [\"Garden A\"]\n"
            + "    A1[Daisy Flower]\n"
            + "  end\n"
            + "  subgraph SubB [\"Garden B\"]\n"
            + "    B1[Tulip Flower]\n"
            + "  end\n"
            + "  SubA -->|Garden Link LR| SubB\n";
    Optional<String> svgLROpt = SimpleMermaidRenderer.renderToSvg(codeLR);
    assertThat(svgLROpt.isPresent()).isTrue();
    SvgDoc svgLR = new SvgDoc(svgLROpt.get());
    assertThat(svgLR.findText("Garden Link LR")).isNotNull();
  }

  @Test
  public void testNestedSubgraphsExactHierarchy() {
    String code =
        "graph TD\n"
            + "  subgraph Sandbox [\"Play Sandbox\"]\n"
            + "    subgraph SandCastle [\"Sand Castle\"]\n"
            + "      SPA[\"Red Bucket\"]\n"
            + "    end\n"
            + "    subgraph ToyPond [\"Toy Pond\"]\n"
            + "      CS[\"Yellow Boat\"]\n"
            + "    end\n"
            + "  end\n"
            + "  SPA -->|Water Splash| CS\n";
    Optional<String> svgOpt = SimpleMermaidRenderer.renderToSvg(code);
    assertThat(svgOpt.isPresent()).isTrue();

    SvgDoc svg = new SvgDoc(svgOpt.get());
    svg.assertRootSvg();

    // Exactly 3 subgraph boxes (1 outer + 2 inner)
    List<Element> subgraphs = svg.findSubgraphRects();
    assertThat(subgraphs.size()).isEqualTo(3);

    assertThat(svg.findText("Play Sandbox")).isNotNull();
    assertThat(svg.findText("Sand Castle")).isNotNull();
    assertThat(svg.findText("Toy Pond")).isNotNull();
    assertThat(svg.findText("Red Bucket")).isNotNull();
    assertThat(svg.findText("Yellow Boat")).isNotNull();
    assertThat(svg.findText("Water Splash")).isNotNull();
  }

  @Test
  public void testNestedSubgraphsHorizontal() {
    String code =
        "graph LR\n"
            + "  subgraph Outer [\"Playhouse\"]\n"
            + "    subgraph InnerA [\"Kitten Corner\"]\n"
            + "      A1[Soft Pillow]\n"
            + "    end\n"
            + "    subgraph InnerB [\"Puppy Corner\"]\n"
            + "      B1[Squeaky Ball]\n"
            + "    end\n"
            + "  end\n"
            + "  A1 -->|Play Time| B1\n";
    Optional<String> svgOpt = SimpleMermaidRenderer.renderToSvg(code);
    assertThat(svgOpt.isPresent()).isTrue();

    SvgDoc svg = new SvgDoc(svgOpt.get());
    svg.assertRootSvg();
    assertThat(svg.findText("Play Time")).isNotNull();
  }

  @Test
  public void testSubgraphWithMixedChildrenAndDirectNodes() {
    String codeTD =
        "graph TD\n"
            + "  subgraph OuterTD [\"Tree House TD\"]\n"
            + "    subgraph InnerTD [\"Bird Nest TD\"]\n"
            + "      A1[Baby Bird TD]\n"
            + "    end\n"
            + "    D1[Little Squirrel TD]\n"
            + "  end\n"
            + "  A1 --> D1\n";
    Optional<String> svgTDOpt = SimpleMermaidRenderer.renderToSvg(codeTD);
    assertThat(svgTDOpt.isPresent()).isTrue();
    SvgDoc svgTD = new SvgDoc(svgTDOpt.get());
    assertThat(svgTD.findText("Tree House TD")).isNotNull();
    assertThat(svgTD.findText("Bird Nest TD")).isNotNull();
    assertThat(svgTD.findText("Little Squirrel TD")).isNotNull();

    String codeLR =
        "graph LR\n"
            + "  subgraph OuterLR [\"Tree House LR\"]\n"
            + "    subgraph InnerLR [\"Bird Nest LR\"]\n"
            + "      A1[Baby Bird LR]\n"
            + "    end\n"
            + "    D1[Little Squirrel LR]\n"
            + "  end\n"
            + "  A1 --> D1\n";
    Optional<String> svgLROpt = SimpleMermaidRenderer.renderToSvg(codeLR);
    assertThat(svgLROpt.isPresent()).isTrue();
    SvgDoc svgLR = new SvgDoc(svgLROpt.get());
    assertThat(svgLR.findText("Tree House LR")).isNotNull();
    assertThat(svgLR.findText("Bird Nest LR")).isNotNull();
    assertThat(svgLR.findText("Little Squirrel LR")).isNotNull();
  }

  @Test
  public void testNestedSubgraphWithLabeledAdjacentEdge() {
    String codeTD =
        "graph TD\n"
            + "  subgraph SubTD [\"Animal Farm TD\"]\n"
            + "    A[Happy Lamb]\n"
            + "    B[Little Pony]\n"
            + "    A -->|Green Grass TD| B\n"
            + "  end\n";
    Optional<String> svgTDOpt = SimpleMermaidRenderer.renderToSvg(codeTD);
    assertThat(svgTDOpt.isPresent()).isTrue();
    SvgDoc svgTD = new SvgDoc(svgTDOpt.get());
    assertThat(svgTD.findText("Green Grass TD")).isNotNull();

    String codeLR =
        "graph LR\n"
            + "  subgraph SubLR [\"Animal Farm LR\"]\n"
            + "    A[Happy Lamb]\n"
            + "    B[Little Pony]\n"
            + "    A -->|Green Grass LR| B\n"
            + "  end\n";
    Optional<String> svgLROpt = SimpleMermaidRenderer.renderToSvg(codeLR);
    assertThat(svgLROpt.isPresent()).isTrue();
    SvgDoc svgLR = new SvgDoc(svgLROpt.get());
    assertThat(svgLR.findText("Green Grass LR")).isNotNull();
  }

  @Test
  public void testSugiyamaLateralAdjacentLabeledEdge() {
    String code =
        "graph TD\n"
            + "  A1[Fuzzy Panda] --> B1[Baby Giraffe]\n"
            + "  A2[Little Koala] --> B2[Tiny Hamster]\n"
            + "  A1 -->|Sunny Day| A2\n"
            + "  B1 -->|Happy Play| B2\n";
    Optional<String> svgOpt = SimpleMermaidRenderer.renderToSvg(code);
    assertThat(svgOpt.isPresent()).isTrue();
    SvgDoc svg = new SvgDoc(svgOpt.get());
    assertThat(svg.findText("Sunny Day")).isNotNull();
    assertThat(svg.findText("Happy Play")).isNotNull();
  }

  @Test
  public void testBidirectionalMutualEdgesBothOrientations() {
    String codeTD =
        "graph TD\n"
            + "  A[\"Little Lamb\"]\n"
            + "  B[\"Sweet Bunny\"]\n"
            + "  A -->|Hop Down| B\n"
            + "  B -->|Jump Up| A\n";
    Optional<String> svgTDOpt = SimpleMermaidRenderer.renderToSvg(codeTD);
    assertThat(svgTDOpt.isPresent()).isTrue();
    SvgDoc svgTD = new SvgDoc(svgTDOpt.get());
    assertThat(svgTD.findText("Hop Down")).isNotNull();
    assertThat(svgTD.findText("Jump Up")).isNotNull();
    // Exactly 2 mutual curved edge paths
    assertThat(svgTD.getEdgePaths().size()).isEqualTo(2);

    String codeLR =
        "graph LR\n"
            + "  A[\"Little Lamb\"]\n"
            + "  B[\"Sweet Bunny\"]\n"
            + "  A -->|Run Forward| B\n"
            + "  B -->|Run Backward| A\n";
    Optional<String> svgLROpt = SimpleMermaidRenderer.renderToSvg(codeLR);
    assertThat(svgLROpt.isPresent()).isTrue();
    SvgDoc svgLR = new SvgDoc(svgLROpt.get());
    assertThat(svgLR.findText("Run Forward")).isNotNull();
    assertThat(svgLR.findText("Run Backward")).isNotNull();
    assertThat(svgLR.getEdgePaths().size()).isEqualTo(2);
  }

  @Test
  public void testCycleDetectionAndLoopbackBothOrientations() {
    String codeTD =
        "graph TD\n"
            + "  A --> B\n"
            + "  B --> C\n"
            + "  C -->|Loop TD| A\n";
    Optional<String> svgTDOpt = SimpleMermaidRenderer.renderToSvg(codeTD);
    assertThat(svgTDOpt.isPresent()).isTrue();
    SvgDoc svgTD = new SvgDoc(svgTDOpt.get());
    assertThat(svgTD.findText("Loop TD")).isNotNull();
    assertThat(svgTD.getEdgePaths().size()).isEqualTo(3);

    String codeLR =
        "graph LR\n"
            + "  A --> B\n"
            + "  B --> C\n"
            + "  C -->|Loop LR| A\n";
    Optional<String> svgLROpt = SimpleMermaidRenderer.renderToSvg(codeLR);
    assertThat(svgLROpt.isPresent()).isTrue();
    SvgDoc svgLR = new SvgDoc(svgLROpt.get());
    assertThat(svgLR.findText("Loop LR")).isNotNull();
    assertThat(svgLR.getEdgePaths().size()).isEqualTo(3);
  }

  @Test
  public void testSkipLayerBypassBothOrientations() {
    String codeTD =
        "graph TD\n"
            + "  A --> B\n"
            + "  B --> C\n"
            + "  A -->|Skip TD| C\n";
    Optional<String> svgTDOpt = SimpleMermaidRenderer.renderToSvg(codeTD);
    assertThat(svgTDOpt.isPresent()).isTrue();
    SvgDoc svgTD = new SvgDoc(svgTDOpt.get());
    assertThat(svgTD.findText("Skip TD")).isNotNull();

    String codeLR =
        "graph LR\n"
            + "  A --> B\n"
            + "  B --> C\n"
            + "  A -->|Skip LR| C\n";
    Optional<String> svgLROpt = SimpleMermaidRenderer.renderToSvg(codeLR);
    assertThat(svgLROpt.isPresent()).isTrue();
    SvgDoc svgLR = new SvgDoc(svgLROpt.get());
    assertThat(svgLR.findText("Skip LR")).isNotNull();
  }

  @Test
  public void testDirectivesAndStylingIgnoredGracefully() {
    String code =
        "graph TD\n"
            + "  accTitle: Cheerful Morning Playground\n"
            + "  accDescr: Story of fluffy puppy and kitten\n"
            + "  classDef default fill:#f9f,stroke:#333;\n"
            + "  classDef special fill:#bbf,stroke:#333;\n"
            + "  class A special\n"
            + "  style B fill:#dfd,stroke:#333;\n"
            + "  click A href \"https://example.com\"\n"
            + "  linkStyle 0 stroke:#ff3,stroke-width:4px;\n"
            + "  A[Little Kitten] --> B[Fluffy Bunny]\n";
    Optional<String> svgOpt = SimpleMermaidRenderer.renderToSvg(code);
    assertThat(svgOpt.isPresent()).isTrue();
    SvgDoc svg = new SvgDoc(svgOpt.get());
    assertThat(svg.getAllTextContents()).containsExactly("Little Kitten", "Fluffy Bunny").inOrder();
  }

  @Test
  public void testDisconnectedNodes() {
    String code = "graph TD\n  A[Quiet Mouse]\n  B[Sleeping Turtle]\n  C --> D\n";
    Optional<String> svgOpt = SimpleMermaidRenderer.renderToSvg(code);
    assertThat(svgOpt.isPresent()).isTrue();
    SvgDoc svg = new SvgDoc(svgOpt.get());
    assertThat(svg.getAllTextContents()).containsExactly("Quiet Mouse", "Sleeping Turtle", "C", "D").inOrder();
    assertThat(svg.getElementsByTag("rect").size()).isEqualTo(4);
    assertThat(svg.getEdgePaths().size()).isEqualTo(1);
  }

  @Test
  public void testSelfLoop() {
    String code = "graph TD\n  A --> A\n";
    Optional<String> svgOpt = SimpleMermaidRenderer.renderToSvg(code);
    assertThat(svgOpt.isPresent()).isTrue();
    SvgDoc svg = new SvgDoc(svgOpt.get());
    assertThat(svg.getAllTextContents()).containsExactly("A");
  }

  @Test
  public void testWhitespaceAndEmptyBlocks() {
    assertThat(SimpleMermaidRenderer.renderToSvg("   \n\n\t").isPresent()).isFalse();
    assertThat(SimpleMermaidRenderer.renderToSvg("graph TD\n").isPresent()).isFalse();
    assertThat(SimpleMermaidRenderer.renderToSvg("graph TD\n%% only comments\n").isPresent()).isFalse();
    assertThat(SimpleMermaidRenderer.renderToSvg("").isPresent()).isFalse();
    assertThat(SimpleMermaidRenderer.renderToSvg(null).isPresent()).isFalse();
  }

  @Test
  public void testEmptyLinesAndCommentsAtStart() {
    String code = "\n\n%% Leading comment\n  \ngraph TD\n\n%% Inner comment\n  A --> B\n";
    Optional<String> svgOpt = SimpleMermaidRenderer.renderToSvg(code);
    assertThat(svgOpt.isPresent()).isTrue();
    SvgDoc svg = new SvgDoc(svgOpt.get());
    assertThat(svg.getAllTextContents()).containsExactly("A", "B").inOrder();

    assertThat(SimpleMermaidRenderer.renderToSvg("%% only comments\nsome random text\n").isPresent())
        .isFalse();
  }

  @Test
  public void testMalformedDelimitersGracefulHandling() {
    String code = "graph TD\n  A[\"Unclosed String\n  B --> A\n";
    Optional<String> svgOpt = SimpleMermaidRenderer.renderToSvg(code);
    assertThat(svgOpt.isPresent()).isTrue();
    SvgDoc svg = new SvgDoc(svgOpt.get());
    assertThat(svg.getAllTextContents()).contains("B");
  }

  @Test
  public void testAllUnsupportedDiagramTypesReturnEmpty() {
    assertThat(SimpleMermaidRenderer.renderToSvg("sequenceDiagram\nAlice->>Bob: Hello\n").isPresent()).isFalse();
    assertThat(SimpleMermaidRenderer.renderToSvg("classDiagram\nClass01 <|-- Class02\n").isPresent()).isFalse();
    assertThat(SimpleMermaidRenderer.renderToSvg("erDiagram\nCUSTOMER ||--o{ ORDER : places\n").isPresent()).isFalse();
    assertThat(SimpleMermaidRenderer.renderToSvg("gantt\ntitle A Gantt Diagram\n").isPresent()).isFalse();
    assertThat(SimpleMermaidRenderer.renderToSvg("pie title Pets\n\"Dogs\" : 386\n").isPresent()).isFalse();
    assertThat(SimpleMermaidRenderer.renderToSvg("gitGraph\ncommit\n").isPresent()).isFalse();
    assertThat(SimpleMermaidRenderer.renderToSvg("xychart-beta\ntitle \"Score\"\n").isPresent()).isFalse();
    assertThat(SimpleMermaidRenderer.renderToSvg("stateDiagram\n[*] --> Still\n").isPresent()).isFalse();
    assertThat(SimpleMermaidRenderer.renderToSvg("stateDiagram-v2\n[*] --> Still\n").isPresent()).isFalse();
  }

  @Test
  public void testNodeReassignmentToSubgraph() {
    String code =
        "graph TD\n"
            + "  A[Singing Robin]\n"
            + "  subgraph Sub\n"
            + "    A\n"
            + "    B[Flying Bluebird]\n"
            + "  end\n"
            + "  A --> B\n";
    Optional<String> svgOpt = SimpleMermaidRenderer.renderToSvg(code);
    assertThat(svgOpt.isPresent()).isTrue();
    SvgDoc svg = new SvgDoc(svgOpt.get());
    assertThat(svg.getAllTextContents()).containsExactly("Sub", "Singing Robin", "Flying Bluebird").inOrder();
  }

  @Test
  public void testRemainingEdgeCasesForFullCoverage() {
    // Quoted pipe label triggering cleanLabel unwrap
    String code1 = "graph TD\n  A -->|\"Sweet Honey Pie\"| B\n";
    Optional<String> svg1 = SimpleMermaidRenderer.renderToSvg(code1);
    assertThat(svg1.isPresent()).isTrue();
    SvgDoc doc1 = new SvgDoc(svg1.get());
    assertThat(doc1.findText("Sweet Honey Pie")).isNotNull();

    // Reverse edge in vertical nested subgraph
    String code2 = "graph TD\n  subgraph Sub\n    A[Baby Chick]\n    B[Mama Hen]\n    B -->|Chirp Vert| A\n  end\n";
    Optional<String> svg2 = SimpleMermaidRenderer.renderToSvg(code2);
    assertThat(svg2.isPresent()).isTrue();
    SvgDoc doc2 = new SvgDoc(svg2.get());
    assertThat(doc2.findText("Chirp Vert")).isNotNull();

    // Reverse edge in horizontal nested subgraph
    String code3 = "graph LR\n  subgraph Sub\n    A[Baby Chick]\n    B[Mama Hen]\n    B -->|Chirp Horiz| A\n  end\n";
    Optional<String> svg3 = SimpleMermaidRenderer.renderToSvg(code3);
    assertThat(svg3.isPresent()).isTrue();
    SvgDoc doc3 = new SvgDoc(svg3.get());
    assertThat(doc3.findText("Chirp Horiz")).isNotNull();

    // Mutual same-layer edge with label
    String code5 = "graph TD\n  A --> B\n  B --> A\n  A -->|Golden Star| B\n  C --> D\n";
    Optional<String> svg5 = SimpleMermaidRenderer.renderToSvg(code5);
    assertThat(svg5.isPresent()).isTrue();

    // Trailing non-edge characters to hit scanEdgeToken default return null
    String code6 = "graph TD\n  A 12345\n";
    Optional<String> svg6 = SimpleMermaidRenderer.renderToSvg(code6);
    assertThat(svg6.isPresent()).isTrue();
  }

  // =========================================================================
  // Helper for DOM-based XML assertions
  // =========================================================================

  private static class SvgDoc {
    final Document doc;
    final Element root;

    SvgDoc(String svg) {
      try {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        this.doc = builder.parse(new InputSource(new StringReader(svg)));
        this.root = doc.getDocumentElement();
      } catch (Exception e) {
        throw new AssertionError("SVG string is not valid XML: " + e.getMessage(), e);
      }
    }

    void assertRootSvg() {
      assertThat(root.getTagName()).isEqualTo("svg");
      assertThat(root.getAttribute("class")).isEqualTo("mermaid-svg");
      assertThat(root.getAttribute("xmlns")).isEqualTo("http://www.w3.org/2000/svg");
      assertThat(root.getAttribute("viewBox")).matches("^0 0 \\d+ \\d+$");
      assertThat(root.getAttribute("style")).contains("max-width:");
    }

    void assertDefs() {
      List<Element> defs = getElementsByTag("defs");
      assertThat(defs.size()).isEqualTo(1);
      Element def = defs.get(0);
      NodeList markers = def.getElementsByTagName("marker");
      assertThat(markers.getLength()).isEqualTo(1);
      Element marker = (Element) markers.item(0);
      assertThat(marker.getAttribute("id")).isEqualTo("mermaid-arrow");

      NodeList filters = def.getElementsByTagName("filter");
      assertThat(filters.getLength()).isEqualTo(1);
      Element filter = (Element) filters.item(0);
      assertThat(filter.getAttribute("id")).isEqualTo("node-shadow");
    }

    List<Element> getElementsByTag(String tagName) {
      List<Element> list = new ArrayList<>();
      NodeList nl = doc.getElementsByTagName(tagName);
      for (int i = 0; i < nl.getLength(); i++) {
        Node n = nl.item(i);
        if (n instanceof Element) {
          list.add((Element) n);
        }
      }
      return list;
    }

    List<Element> getEdgePaths() {
      List<Element> list = new ArrayList<>();
      for (Element p : getElementsByTag("path")) {
        // Exclude defs marker path
        if (!"M 0 1.5 L 10 5 L 0 8.5 z".equals(p.getAttribute("d"))) {
          list.add(p);
        }
      }
      return list;
    }

    List<Element> findSubgraphRects() {
      List<Element> list = new ArrayList<>();
      for (Element r : getElementsByTag("rect")) {
        if ("4,4".equals(r.getAttribute("stroke-dasharray"))) {
          list.add(r);
        }
      }
      return list;
    }

    @Nullable Element findPolygonWithVertices(int count) {
      for (Element p : getElementsByTag("polygon")) {
        String pts = p.getAttribute("points").trim();
        if (!pts.isEmpty() && pts.split("\\s+").length == count) {
          return p;
        }
      }
      return null;
    }

    @Nullable Element findText(String text) {
      for (Element t : getElementsByTag("text")) {
        if (text.equals(t.getTextContent().trim())) {
          return t;
        }
      }
      for (Element t : getElementsByTag("tspan")) {
        if (text.equals(t.getTextContent().trim())) {
          return t;
        }
      }
      return null;
    }

    List<String> getAllTextContents() {
      List<String> list = new ArrayList<>();
      for (Element t : getElementsByTag("text")) {
        String txt = t.getTextContent().trim().replaceAll("\\s+", " ");
        if (!txt.isEmpty()) {
          list.add(txt);
        }
      }
      return list;
    }
  }

  @Test
  public void testHorizontalSelfLoopAndExtraHeaders() {
    // Horizontal self loop
    String code1 = "graph LR\n  A --> A\n";
    Optional<String> svg1 = SimpleMermaidRenderer.renderToSvg(code1);
    assertThat(svg1.isPresent()).isTrue();
    SvgDoc doc1 = new SvgDoc(svg1.get());
    assertThat(doc1.findText("A")).isNotNull();

    // Redundant header line in body
    String code2 = "graph TD\n  graph TD\n  A --> B\n";
    Optional<String> svg2 = SimpleMermaidRenderer.renderToSvg(code2);
    assertThat(svg2.isPresent()).isTrue();

    // Direct AST Node empty label setter
    SimpleMermaidRenderer.Node n = new SimpleMermaidRenderer.Node("testNode");
    n.setLabel("");
    assertThat(n.labelLines).containsExactly("");

    // Double quotes in label and title to exercise escapeXml
    String code3 = "graph TD\n  subgraph Sg [\"Magic Castle with \\\"Stars\\\"\"]\n    A[\"Has \\\"Glitter\\\" in pocket\"]\n  end\n";
    Optional<String> svg3 = SimpleMermaidRenderer.renderToSvg(code3);
    assertThat(svg3.isPresent()).isTrue();
  }

  @Test
  public void testSecurityNoScriptOrIframeExecutionInNodeLabels() {
    String code =
        "graph TD\n"
            + "  A[\"<script>alert('xss-script')</script>\"]\n"
            + "  B[\"<iframe src='javascript:alert(1)'></iframe>\"]\n"
            + "  C[\"<img src=x onerror=alert('img-onerror')>\"]\n"
            + "  D[\"<svg onload=alert('svg-onload')>\"]\n"
            + "  E[\"<foreignObject><iframe src='https://evil.com'></iframe></foreignObject>\"]\n"
            + "  F[\"<a href='javascript:alert(1)'>Click Me</a>\"]\n"
            + "  A --> B --> C --> D --> E --> F\n";

    Optional<String> svgOpt = SimpleMermaidRenderer.renderToSvg(code);
    assertThat(svgOpt.isPresent()).isTrue();

    SvgDoc svg = new SvgDoc(svgOpt.get());
    svg.assertRootSvg();

    // Verify absolutely no executable or iframe elements exist in the DOM
    assertThat(svg.getElementsByTag("script")).isEmpty();
    assertThat(svg.getElementsByTag("iframe")).isEmpty();
    assertThat(svg.getElementsByTag("foreignObject")).isEmpty();
    assertThat(svg.getElementsByTag("img")).isEmpty();
    assertThat(svg.getElementsByTag("a")).isEmpty();

    // Verify raw SVG string contains no unescaped tags
    String raw = svgOpt.get();
    assertThat(raw).doesNotContain("<script");
    assertThat(raw).doesNotContain("</script>");
    assertThat(raw).doesNotContain("<iframe");
    assertThat(raw).doesNotContain("</iframe>");
    assertThat(raw).doesNotContain("<foreignObject");
    assertThat(raw).doesNotContain("<img");
    assertThat(raw).doesNotContain("<a ");

    // Verify content is preserved safely as inert escaped text
    assertThat(svg.findText("<script>alert('xss-script')</script>")).isNotNull();
    assertThat(svg.findText("<iframe src='javascript:alert(1)'></iframe>")).isNotNull();
    assertThat(svg.findText("<img src=x onerror=alert('img-onerror')>")).isNotNull();
    assertThat(svg.findText("<svg onload=alert('svg-onload')>")).isNotNull();
    assertThat(svg.findText("<foreignObject><iframe src='https://evil.com'></iframe></foreignObject>")).isNotNull();
    assertThat(svg.findText("<a href='javascript:alert(1)'>Click Me</a>")).isNotNull();
  }

  @Test
  public void testSecurityNoScriptOrIframeInEdgeLabels() {
    String code =
        "graph TD\n"
            + "  A -->|\"<script>alert('edge-pipe')</script>\"| B\n"
            + "  B -- \"<iframe src='http://evil.com'></iframe>\" --> C\n"
            + "  C == \"<img src=x onerror=alert('thick-edge')>\" ==> D\n"
            + "  D -. \"<svg onload=alert('dashed-edge')>\" .-> E\n";

    Optional<String> svgOpt = SimpleMermaidRenderer.renderToSvg(code);
    assertThat(svgOpt.isPresent()).isTrue();

    SvgDoc svg = new SvgDoc(svgOpt.get());
    svg.assertRootSvg();

    assertThat(svg.getElementsByTag("script")).isEmpty();
    assertThat(svg.getElementsByTag("iframe")).isEmpty();
    assertThat(svg.getElementsByTag("foreignObject")).isEmpty();

    String raw = svgOpt.get();
    assertThat(raw).doesNotContain("<script");
    assertThat(raw).doesNotContain("</script>");
    assertThat(raw).doesNotContain("<iframe");
    assertThat(raw).doesNotContain("</iframe>");
    assertThat(raw).doesNotContain("<img");
    assertThat(raw).doesNotContain("<svg onload");

    assertThat(svg.findText("<script>alert('edge-pipe')</script>")).isNotNull();
    assertThat(svg.findText("<iframe src='http://evil.com'></iframe>")).isNotNull();
    assertThat(svg.findText("<img src=x onerror=alert('thick-edge')>")).isNotNull();
    assertThat(svg.findText("<svg onload=alert('dashed-edge')>")).isNotNull();
  }

  @Test
  public void testSecurityNoScriptOrIframeInSubgraphTitles() {
    String code =
        "graph TD\n"
            + "  subgraph Sg1 [\"<script>alert('subgraph-title')</script>\"]\n"
            + "    A[Node A]\n"
            + "  end\n"
            + "  subgraph Sg2 [\"<iframe src='javascript:alert(2)'></iframe>\"]\n"
            + "    B[Node B]\n"
            + "  end\n"
            + "  A --> B\n";

    Optional<String> svgOpt = SimpleMermaidRenderer.renderToSvg(code);
    assertThat(svgOpt.isPresent()).isTrue();

    SvgDoc svg = new SvgDoc(svgOpt.get());
    svg.assertRootSvg();

    assertThat(svg.getElementsByTag("script")).isEmpty();
    assertThat(svg.getElementsByTag("iframe")).isEmpty();

    String raw = svgOpt.get();
    assertThat(raw).doesNotContain("<script");
    assertThat(raw).doesNotContain("</script>");
    assertThat(raw).doesNotContain("<iframe");
    assertThat(raw).doesNotContain("</iframe>");

    assertThat(svg.findText("<script>alert('subgraph-title')</script>")).isNotNull();
    assertThat(svg.findText("<iframe src='javascript:alert(2)'></iframe>")).isNotNull();
  }

  @Test
  public void testSecurityDirectivesCannotInjectJavascriptUrls() {
    String code =
        "graph TD\n"
            + "  A[Node A] --> B[Node B]\n"
            + "  click A href \"javascript:alert('click-href')\"\n"
            + "  click B call alert('click-call')\n"
            + "  click A \"javascript:alert('positional-href')\"\n"
            + "  style A fill:url(javascript:alert(1))\n"
            + "  classDef evil fill:red,color:white;\n"
            + "  linkStyle 0 stroke:red;\n";

    Optional<String> svgOpt = SimpleMermaidRenderer.renderToSvg(code);
    assertThat(svgOpt.isPresent()).isTrue();

    SvgDoc svg = new SvgDoc(svgOpt.get());
    svg.assertRootSvg();

    assertThat(svg.getElementsByTag("a")).isEmpty();
    assertThat(svg.getElementsByTag("script")).isEmpty();

    String raw = svgOpt.get();
    assertThat(raw).doesNotContain("javascript:");
    assertThat(raw).doesNotContain("href=");
    assertThat(raw).doesNotContain("onclick=");
    assertThat(raw).doesNotContain("xlink:href");
  }

  @Test
  public void testSecurityXmlBreakoutPayloads() {
    String code =
        "graph TD\n"
            + "  A[\"</text></svg><script>alert('breakout')</script><svg><text>\"]\n"
            + "  B[\"'\"><script>alert('quote-breakout')</script>\"]\n"
            + "  A --> B\n";

    Optional<String> svgOpt = SimpleMermaidRenderer.renderToSvg(code);
    assertThat(svgOpt.isPresent()).isTrue();

    SvgDoc svg = new SvgDoc(svgOpt.get());
    svg.assertRootSvg();

    // Verify the document root remains the only SVG element and no script elements were injected
    assertThat(svg.getElementsByTag("script")).isEmpty();
    assertThat(svg.getElementsByTag("svg").size()).isEqualTo(1);

    String raw = svgOpt.get();
    assertThat(raw).doesNotContain("<script");
    assertThat(raw).doesNotContain("</svg><script>");
  }

  @Test
  public void testIsolatedSubgraphAlongsideMainDagTree() {
    String code =
        "graph TD\n"
            + "    ClientApp[Little Puppy Plays] --> Extras(Sweet Kitten)\n"
            + "    ClientApp --> Utils(Happy Bunny)\n"
            + "    \n"
            + "    Utils --> ServiceDiscovery[Red Apple Berry]\n"
            + "    Utils --> ModelManager[Yellow Banana Snack]\n"
            + "    \n"
            + "    Extras --> Recognition(Fluffy Duckling)\n"
            + "    \n"
            + "    Recognition --> SODA(Green Frog Jump)\n"
            + "    Recognition --> S3(Sunny Daisy Flower)\n"
            + "    \n"
            + "    subgraph Play Park Garden\n"
            + "        Executors(Teddy Bear)\n"
            + "        Errors(Wooden Blocks)\n"
            + "        Protos(Toy Wagon)\n"
            + "    end\n"
            + "    \n"
            + "    Recognition -.-> PlayParkGarden\n";

    Optional<String> svgOpt = SimpleMermaidRenderer.renderToSvg(code);
    assertThat(svgOpt.isPresent()).isTrue();

    SvgDoc svg = new SvgDoc(svgOpt.get());
    svg.assertRootSvg();

    // Verify all nodes and subgraph title exist
    assertThat(svg.findText("Play Park Garden")).isNotNull();
    assertThat(svg.findText("Teddy Bear")).isNotNull();
    assertThat(svg.findText("Wooden Blocks")).isNotNull();
    assertThat(svg.findText("Toy Wagon")).isNotNull();
    assertThat(svg.findText("Little Puppy Plays")).isNotNull();
    assertThat(svg.findText("Sweet Kitten")).isNotNull();
    assertThat(svg.findText("Happy Bunny")).isNotNull();
    assertThat(svg.findText("Red Apple Berry")).isNotNull();
    assertThat(svg.findText("Yellow Banana Snack")).isNotNull();
    assertThat(svg.findText("Fluffy Duckling")).isNotNull();
    assertThat(svg.findText("Green Frog Jump")).isNotNull();
    assertThat(svg.findText("Sunny Daisy Flower")).isNotNull();
    assertThat(svg.findText("PlayParkGarden")).isNotNull();

    // Verify vertical stack in Play Park Garden subgraph (Teddy Bear above Wooden Blocks above Toy Wagon)
    Element executorsText = svg.findText("Teddy Bear");
    Element errorsText = svg.findText("Wooden Blocks");
    Element protoText = svg.findText("Toy Wagon");
    double execY = Double.parseDouble(executorsText.getAttribute("y"));
    double errY = Double.parseDouble(errorsText.getAttribute("y"));
    double protoY = Double.parseDouble(protoText.getAttribute("y"));
    assertThat(execY).isLessThan(errY);
    assertThat(errY).isLessThan(protoY);

    // Verify Play Park Garden is placed on the left of ClientApp
    Element clientAppText = svg.findText("Little Puppy Plays");
    double execX = Double.parseDouble(executorsText.getAttribute("x"));
    double clientX = Double.parseDouble(clientAppText.getAttribute("x"));
    assertThat(execX).isLessThan(clientX);
  }

  @Test
  public void testMultiNodeChainingWithAmpersand() {
    String code =
        "graph TD\n"
            + "    A[Little Star] --> CheckJDAA{Is Puppy Sleepy?}\n"
            + "    CheckJDAA -- No --> InstallJDA[Play With Soft Ball]\n"
            + "    InstallJDA --> CheckJDAA\n"
            + "    CheckJDAA -- Yes --> B{Wants Sweet Cookie?}\n"
            + "    B -- Yes --> C[Drink Warm Milk Cup]\n"
            + "    B -- No --> D[Sing Happy Lullaby]\n"
            + "    D --> E[Cuddle Warm Blanket]\n"
            + "    D --> F[Hug Fluffy Panda]\n"
            + "    D --> G[Close Shiny Eyes]\n"
            + "    E & F & G --> H[Sweet Dreams Forest]\n"
            + "    C & H --> I[Gentle Good Night]\n"
            + "    I --> J[Sleep Until Morning]\n"
            + "    J --> K[Wake Up Happy Sun]\n";

    Optional<String> svgOpt = SimpleMermaidRenderer.renderToSvg(code);
    assertThat(svgOpt.isPresent()).isTrue();

    SvgDoc svg = new SvgDoc(svgOpt.get());
    svg.assertRootSvg();

    // Verify all nodes exist
    assertThat(svg.findText("Little Star")).isNotNull();
    assertThat(svg.findText("Is Puppy Sleepy?")).isNotNull();
    assertThat(svg.findText("Play With Soft Ball")).isNotNull();
    assertThat(svg.findText("Wants Sweet Cookie?")).isNotNull();
    assertThat(svg.findText("Drink Warm Milk Cup")).isNotNull();
    assertThat(svg.findText("Sing Happy Lullaby")).isNotNull();
    assertThat(svg.findText("Cuddle Warm Blanket")).isNotNull();
    assertThat(svg.findText("Hug Fluffy Panda")).isNotNull();
    assertThat(svg.findText("Close Shiny Eyes")).isNotNull();
    assertThat(svg.findText("Sweet Dreams Forest")).isNotNull();
    assertThat(svg.findText("Gentle Good Night")).isNotNull();
    assertThat(svg.findText("Sleep Until Morning")).isNotNull();
    assertThat(svg.findText("Wake Up Happy Sun")).isNotNull();
    // Verify diamond shape polygons exist for decision nodes CheckJDAA and B
    List<Element> polygons = svg.getElementsByTag("polygon");
    assertThat(polygons.size()).isAtLeast(2); // CheckJDAA and B are diamonds


    // Verify vertical order down the DAG
    Element queryText = svg.findText("Little Star");
    Element hText = svg.findText("Sweet Dreams Forest");
    Element iText = svg.findText("Gentle Good Night");
    Element jText = svg.findText("Sleep Until Morning");
    Element kText = svg.findText("Wake Up Happy Sun");

    double queryY = Double.parseDouble(queryText.getAttribute("y"));
    double hY = Double.parseDouble(hText.getAttribute("y"));
    double iY = Double.parseDouble(iText.getAttribute("y"));
    double jY = Double.parseDouble(jText.getAttribute("y"));
    double kY = Double.parseDouble(kText.getAttribute("y"));

    assertThat(queryY).isLessThan(hY);
    assertThat(hY).isLessThan(iY);
    assertThat(iY).isLessThan(jY);
    assertThat(jY).isLessThan(kY);

    // Also test multi-source to multi-target chaining (A & B --> C & D)
    String multiCode = "graph TD\n  A & B --> C & D\n";
    Optional<String> multiSvg = SimpleMermaidRenderer.renderToSvg(multiCode);
    assertThat(multiSvg.isPresent()).isTrue();
    SvgDoc multiDoc = new SvgDoc(multiSvg.get());
    assertThat(multiDoc.findText("A")).isNotNull();
    assertThat(multiDoc.findText("B")).isNotNull();
    assertThat(multiDoc.findText("C")).isNotNull();
    assertThat(multiDoc.findText("D")).isNotNull();
    assertThat(multiDoc.getElementsByTag("path").size()).isEqualTo(5); // 1 marker path in <defs> + 4 edge paths
  }
}
