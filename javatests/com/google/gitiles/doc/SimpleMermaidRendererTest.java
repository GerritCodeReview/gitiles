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

import com.google.common.base.Splitter;
import java.util.List;
import java.util.Optional;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.w3c.dom.Element;

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
    SvgDoc svg = render("graph TD\n  A --> B\n");
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
      SvgDoc svg = render("graph " + dir + "\n  A --> B\n");
      assertThat(svg.getAllTextContents()).containsExactly("A", "B").inOrder();
    }
    // Default direction when omitted
    SvgDoc defSvg = render("graph\n  A --> B\n");
    assertThat(defSvg.getAllTextContents()).containsExactly("A", "B").inOrder();

    // Invalid direction fallback to TD
    SvgDoc fallbackSvg = render("flowchart INVALID\n  A --> B\n");
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
    SvgDoc svg = render(code);

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

    // Verify Cylinder has 2 paths (body + top rim arc)
    List<Element> paths = svg.getEdgePaths();
    assertThat(paths.size()).isEqualTo(2);
  }

  @Test
  public void testMultilineNodeLabelsWithTspans() {
    String code =
        "graph TD\n"
            + "  A[\"Sunny Blue Sky<br/>Warm Golden Sun<br/>Soft Green Grass\"]\n"
            + "  B[\"Little Red Apple\"]\n"
            + "  A --> B\n";
    SvgDoc svg = render(code);

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
    SvgDoc svg = render(code);

    // XML parsing confirms correct unescaping of &lt;, &gt;, &apos;, &amp;
    assertThat(svg.getAllTextContents()).containsExactly(
        "Play with teddy bear",
        "Find [Puppy] in cozy room",
        "Is kitten <tiny> & 'sweet'?")
        .inOrder();
  }

  @Test
  public void testQuotedCleanLabel() {
    SvgDoc svg = render("graph TD\n  A[\"\\\"Sleepy Kitten\\\"\"] --> B\n");
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
    SvgDoc svg = render(code);

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
    SvgDoc svg = render(code);
    svg.assertNoLabelNodeOverlaps();
    svg.assertSubgraphsDoNotOverlap();

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
    SvgDoc svgTD = render(codeTD);
    assertThat(svgTD.findText("Garden Link TD")).isNotNull();
    assertThat(svgTD.findText("Flower Link")).isNotNull();
    assertThat(svgTD.findText("Petal Link")).isNotNull();
    assertThat(svgTD.getElementsByTag("path")).isNotEmpty();

    String codeLR =
        "graph LR\n"
            + "  subgraph SubA [\"Garden A\"]\n"
            + "    A1[Daisy Flower]\n"
            + "  end\n"
            + "  subgraph SubB [\"Garden B\"]\n"
            + "    B1[Tulip Flower]\n"
            + "  end\n"
            + "  SubA -->|Garden Link LR| SubB\n";
    SvgDoc svgLR = render(codeLR);
    assertThat(svgLR.findText("Garden Link LR")).isNotNull();

    String codeLRWithBlockedNode =
        "graph LR\n"
            + "  subgraph SubA [\"Garden A\"]\n"
            + "    A1[Daisy Flower]\n"
            + "  end\n"
            + "  subgraph SubB [\"Garden B\"]\n"
            + "    B1[Tulip Flower]\n"
            + "  end\n"
            + "  SubA -->|Garden Link LR Blocked| SubB\n"
            + "  SubA -->|Flower Link LR| NodeC[Red Rose]\n"
            + "  NodeC -->|Petal Link LR| SubB\n";
    SvgDoc svgLRBlocked = render(codeLRWithBlockedNode);
    assertThat(svgLRBlocked.findText("Garden Link LR Blocked")).isNotNull();
    assertThat(svgLRBlocked.findText("Flower Link LR")).isNotNull();
    assertThat(svgLRBlocked.findText("Petal Link LR")).isNotNull();
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
    SvgDoc svg = render(code);

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
    SvgDoc svg = render(code);
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
    SvgDoc svgTD = render(codeTD);
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
    SvgDoc svgLR = render(codeLR);
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
    SvgDoc svgTD = render(codeTD);
    assertThat(svgTD.findText("Green Grass TD")).isNotNull();

    String codeLR =
        "graph LR\n"
            + "  subgraph SubLR [\"Animal Farm LR\"]\n"
            + "    A[Happy Lamb]\n"
            + "    B[Little Pony]\n"
            + "    A -->|Green Grass LR| B\n"
            + "  end\n";
    SvgDoc svgLR = render(codeLR);
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
    SvgDoc svg = render(code);
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
    SvgDoc svgTD = render(codeTD);
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
    SvgDoc svgLR = render(codeLR);
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
    SvgDoc svgTD = render(codeTD);
    assertThat(svgTD.findText("Loop TD")).isNotNull();
    assertThat(svgTD.getEdgePaths().size()).isEqualTo(3);

    String codeLR =
        "graph LR\n"
            + "  A --> B\n"
            + "  B --> C\n"
            + "  C -->|Loop LR| A\n";
    SvgDoc svgLR = render(codeLR);
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
    SvgDoc svgTD = render(codeTD);
    assertThat(svgTD.findText("Skip TD")).isNotNull();

    String codeLR =
        "graph LR\n"
            + "  A --> B\n"
            + "  B --> C\n"
            + "  A -->|Skip LR| C\n";
    SvgDoc svgLR = render(codeLR);
    assertThat(svgLR.findText("Skip LR")).isNotNull();
  }

  @Test
  public void testHorizontalGraphWithCycleAndLongSpanEdge() {
    String code =
        "graph LR\n"
            + "  A[Happy Kitten] --> B[Playful Puppy]\n"
            + "  B --> C[Cozy Hamster]\n"
            + "  C -->|Run Back| A\n"
            + "  A -->|Long Leap| C\n";
    SvgDoc doc = render(code);
    assertThat(doc.findText("Happy Kitten")).isNotNull();
    assertThat(doc.findText("Playful Puppy")).isNotNull();
    assertThat(doc.findText("Cozy Hamster")).isNotNull();
    assertThat(doc.findText("Run Back")).isNotNull();
    assertThat(doc.findText("Long Leap")).isNotNull();
  }

  @Test
  public void testNestedSubgraphWithLooseSourceCompaction() {
    String code =
        "graph TD\n"
            + "  subgraph Meadow [\"Sunny Green Meadow\"]\n"
            + "    A[Bright Buttercup] --> B[Busy Ant]\n"
            + "    B --> C[Tall Oak Tree]\n"
            + "    D[Quiet Snail] --> C\n"
            + "  end\n";
    SvgDoc doc = render(code);
    assertThat(doc.findText("Sunny Green Meadow")).isNotNull();
    assertThat(doc.findText("Bright Buttercup")).isNotNull();
    assertThat(doc.findText("Busy Ant")).isNotNull();
    assertThat(doc.findText("Tall Oak Tree")).isNotNull();
    assertThat(doc.findText("Quiet Snail")).isNotNull();
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
    SvgDoc svg = render(code);
    assertThat(svg.getAllTextContents()).containsExactly("Little Kitten", "Fluffy Bunny").inOrder();
  }

  @Test
  public void testDisconnectedNodes() {
    SvgDoc svg = render("graph TD\n  A[Quiet Mouse]\n  B[Sleeping Turtle]\n  C --> D\n");
    assertThat(svg.getAllTextContents()).containsExactly("Quiet Mouse", "Sleeping Turtle", "C", "D").inOrder();
    assertThat(svg.getElementsByTag("rect").size()).isEqualTo(4);
    assertThat(svg.getEdgePaths().size()).isEqualTo(1);
  }

  @Test
  public void testSelfLoop() {
    SvgDoc svg = render("graph TD\n  A --> A\n");
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
    SvgDoc svg = render(code);
    assertThat(svg.getAllTextContents()).containsExactly("A", "B").inOrder();

    assertThat(SimpleMermaidRenderer.renderToSvg("%% only comments\nsome random text\n").isPresent())
        .isFalse();
  }

  @Test
  public void testMalformedDelimitersGracefulHandling() {
    String code = "graph TD\n  A[\"Unclosed String\n  B --> A\n";
    SvgDoc svg = render(code);
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
    SvgDoc svg = render(code);
    assertThat(svg.getAllTextContents()).containsExactly("Sub", "Singing Robin", "Flying Bluebird").inOrder();
  }

  @Test
  public void testRemainingEdgeCasesForFullCoverage() {
    // Quoted pipe label triggering cleanLabel unwrap
    String code1 = "graph TD\n  A -->|\"Sweet Honey Pie\"| B\n";
    SvgDoc doc1 = render(code1);
    assertThat(doc1.findText("Sweet Honey Pie")).isNotNull();

    // Reverse edge in vertical nested subgraph
    String code2 = "graph TD\n  subgraph Sub\n    A[Baby Chick]\n    B[Mama Hen]\n    B -->|Chirp Vert| A\n  end\n";
    SvgDoc doc2 = render(code2);
    assertThat(doc2.findText("Chirp Vert")).isNotNull();

    // Reverse edge in horizontal nested subgraph
    String code3 = "graph LR\n  subgraph Sub\n    A[Baby Chick]\n    B[Mama Hen]\n    B -->|Chirp Horiz| A\n  end\n";
    SvgDoc doc3 = render(code3);
    assertThat(doc3.findText("Chirp Horiz")).isNotNull();

    // Mutual same-layer edge with label
    String code5 = "graph TD\n  A --> B\n  B --> A\n  A -->|Golden Star| B\n  C --> D\n";
    assertThat(render(code5)).isNotNull();

    // Trailing non-edge characters to hit scanEdgeToken default return null
    String code6 = "graph TD\n  A 12345\n";
    assertThat(SimpleMermaidRenderer.renderToSvg(code6).isPresent()).isTrue();
  }

  private static SvgDoc render(String code) {
    return SvgDoc.render(code);
  }

  @Test
  public void testHorizontalSelfLoopAndExtraHeaders() {
    // Horizontal self loop
    String code1 = "graph LR\n  A --> A\n";
    SvgDoc doc1 = render(code1);
    assertThat(doc1.findText("A")).isNotNull();

    // Redundant header line in body
    String code2 = "graph TD\n  graph TD\n  A --> B\n";
    assertThat(SimpleMermaidRenderer.renderToSvg(code2).isPresent()).isTrue();

    // Direct AST Node empty label setter
    SimpleMermaidRenderer.Node n = new SimpleMermaidRenderer.Node("testNode");
    n.setLabel("");
    assertThat(n.labelLines).containsExactly("");

    // Double quotes in label and title to exercise escapeXml
    String code3 = "graph TD\n  subgraph Sg [\"Magic Castle with \\\"Stars\\\"\"]\n    A[\"Has \\\"Glitter\\\" in pocket\"]\n  end\n";
    assertThat(SimpleMermaidRenderer.renderToSvg(code3).isPresent()).isTrue();
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
    SvgDoc svg = render(code);

    assertThat(svg.getElementsByTag("script")).isEmpty();
    assertThat(svg.getElementsByTag("iframe")).isEmpty();
    assertThat(svg.getElementsByTag("foreignObject")).isEmpty();

    String raw = SimpleMermaidRenderer.renderToSvg(code).get();
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
    SvgDoc svg = render(code);

    assertThat(svg.getElementsByTag("script")).isEmpty();
    assertThat(svg.getElementsByTag("iframe")).isEmpty();

    String raw = SimpleMermaidRenderer.renderToSvg(code).get();
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
    SvgDoc svg = render(code);

    assertThat(svg.getElementsByTag("a")).isEmpty();
    assertThat(svg.getElementsByTag("script")).isEmpty();

    String raw = SimpleMermaidRenderer.renderToSvg(code).get();
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
    SvgDoc svg = render(code);

    // Verify the document root remains the only SVG element and no script elements were injected
    assertThat(svg.getElementsByTag("script")).isEmpty();
    assertThat(svg.getElementsByTag("svg").size()).isEqualTo(1);

    String raw = SimpleMermaidRenderer.renderToSvg(code).get();
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
    SvgDoc svg = render(code);

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
    SvgDoc svg = render(code);

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
    SvgDoc multiDoc = render(multiCode);
    assertThat(multiDoc.findText("A")).isNotNull();
    assertThat(multiDoc.findText("B")).isNotNull();
    assertThat(multiDoc.findText("C")).isNotNull();
    assertThat(multiDoc.findText("D")).isNotNull();
    assertThat(multiDoc.getElementsByTag("path").size()).isEqualTo(5); // 1 marker path in <defs> + 4 edge paths
  }

  @Test
  public void testSubgraphDirectionOverrideWithCrossEdges() {
    String code =
        "graph TD\n"
            + "  subgraph Castle [\"Toy Castle\"]\n"
            + "    direction LR\n"
            + "    A[Happy Bear] --> B[Silly Goose]\n"
            + "  end\n"
            + "  subgraph Garden [\"Flower Garden\"]\n"
            + "    C[Sunny Daisy]\n"
            + "  end\n"
            + "  B --> C\n";
    SvgDoc doc = render(code);
    assertThat(doc.findText("Toy Castle")).isNotNull();
    assertThat(doc.findText("Flower Garden")).isNotNull();
    assertThat(doc.findText("Happy Bear")).isNotNull();
    assertThat(doc.findText("Silly Goose")).isNotNull();
    assertThat(doc.findText("Sunny Daisy")).isNotNull();
  }

  @Test
  public void testNestedSubgraphsWithLabeledInterChildEdge() {
    String code =
        "graph LR\n"
            + "  subgraph ToyBox [\"Big Toy Box\"]\n"
            + "    subgraph PuzzleA [\"Puppy Puzzle\"]\n"
            + "      A[Little Dog]\n"
            + "    end\n"
            + "    subgraph PuzzleB [\"Kitten Puzzle\"]\n"
            + "      B[Little Cat]\n"
            + "    end\n"
            + "    A -->|Friendly Meow| B\n"
            + "  end\n"
            + "  subgraph BedTime [\"Sleepy Pillow\"]\n"
            + "    C[Cozy Blanket]\n"
            + "  end\n"
            + "  B -->|Soft Hug| C\n";
    SvgDoc doc = render(code);
    assertThat(doc.findText("Big Toy Box")).isNotNull();
    assertThat(doc.findText("Puppy Puzzle")).isNotNull();
    assertThat(doc.findText("Kitten Puzzle")).isNotNull();
    assertThat(doc.findText("Little Dog")).isNotNull();
    assertThat(doc.findText("Little Cat")).isNotNull();
    assertThat(doc.findText("Friendly Meow")).isNotNull();
    assertThat(doc.findText("Sleepy Pillow")).isNotNull();
    assertThat(doc.findText("Cozy Blanket")).isNotNull();
    assertThat(doc.findText("Soft Hug")).isNotNull();
  }

  @Test
  public void testNestedSubgraphsVerticalWithCrossEdgesAndEmptySubgraphs() {
    String code =
        "graph TD\n"
            + "  subgraph WonderLand [\"Magic Wonderland\"]\n"
            + "    subgraph EmptyBox [\"Empty Treasure Chest\"]\n"
            + "    end\n"
            + "    subgraph ZoneA [\"Butterfly Valley\"]\n"
            + "      A[Shiny Butterfly]\n"
            + "    end\n"
            + "    subgraph ZoneB [\"Rainbow Hill\"]\n"
            + "      B[Glowing Rainbow]\n"
            + "    end\n"
            + "    B -->|Sweet Melody| A\n"
            + "  end\n";
    SvgDoc doc = render(code);
    assertThat(doc.findText("Magic Wonderland")).isNotNull();
    assertThat(doc.findText("Empty Treasure Chest")).isNotNull();
    assertThat(doc.findText("Butterfly Valley")).isNotNull();
    assertThat(doc.findText("Rainbow Hill")).isNotNull();
    assertThat(doc.findText("Shiny Butterfly")).isNotNull();
    assertThat(doc.findText("Glowing Rainbow")).isNotNull();
    assertThat(doc.findText("Sweet Melody")).isNotNull();
  }

  @Test
  public void testSkipLayerBypassWithDummyNodesTD() {
    String code =
        "graph TD\n"
            + "  A[Teddy Bear] -->|Blue Balloon| B[Silly Monkey]\n"
            + "  A -->|Red Apple| C[Happy Puppy]\n"
            + "  B --> C\n"
            + "  C --> D[Little Kitten]\n";
    SvgDoc doc = render(code);
    assertThat(doc.findText("Teddy Bear")).isNotNull();
    assertThat(doc.findText("Silly Monkey")).isNotNull();
    assertThat(doc.findText("Happy Puppy")).isNotNull();
    assertThat(doc.findText("Little Kitten")).isNotNull();
    assertThat(doc.findText("Blue Balloon")).isNotNull();
    assertThat(doc.findText("Red Apple")).isNotNull();
  }

  @Test
  public void testSkipLayerBypassWithDummyNodesLR() {
    String code =
        "graph LR\n"
            + "  A[Teddy Bear] -->|Blue Balloon| B[Silly Monkey]\n"
            + "  A -->|Red Apple| C[Happy Puppy]\n"
            + "  B --> C\n"
            + "  C --> D[Little Kitten]\n";
    SvgDoc doc = render(code);
    assertThat(doc.findText("Teddy Bear")).isNotNull();
    assertThat(doc.findText("Silly Monkey")).isNotNull();
    assertThat(doc.findText("Happy Puppy")).isNotNull();
    assertThat(doc.findText("Little Kitten")).isNotNull();
    assertThat(doc.findText("Blue Balloon")).isNotNull();
    assertThat(doc.findText("Red Apple")).isNotNull();
  }

  @Test
  public void testCompoundSubgraphAndStandaloneNodesLayoutWithStyles() {
    String code =
        "graph LR\n"
            + "  subgraph CastleBox [\"Play Castle\"]\n"
            + "    direction TB\n"
            + "    ToyA[Magic Wand] <--> ToyB[Cozy Teddy]\n"
            + "  end\n"
            + "  subgraph GardenBox [\"Flower Garden\"]\n"
            + "    ToyC[Pink Blossom] --> ToyD[Sweet Daisy]\n"
            + "  end\n"
            + "  ToyB --> ToyC\n"
            + "  ToyE[Happy Butterfly] --> ToyB\n"
            + "  style CastleBox fill:#e3f2fd,stroke:#1e88e5\n"
            + "  style GardenBox fill:rgb(240,250,240),stroke:#43a047\n"
            + "  style ToyE fill:hsl(120,50%,90%),stroke:blue\n"
            + "  style ToyA fill:rgba(255,255,255,0.8),stroke:purple\n"
            + "  style ToyC fill:#ffb300,stroke:#333333\n"
            + "  style \"\" fill:#fff\n"
            + "  style NonExistent fill:#fff\n";
    SvgDoc doc = render(code);
    assertThat(doc.findText("Play Castle")).isNotNull();
    assertThat(doc.findText("Flower Garden")).isNotNull();
    assertThat(doc.findText("Magic Wand")).isNotNull();
    assertThat(doc.findText("Cozy Teddy")).isNotNull();
    assertThat(doc.findText("Pink Blossom")).isNotNull();
    assertThat(doc.findText("Sweet Daisy")).isNotNull();
    assertThat(doc.findText("Happy Butterfly")).isNotNull();
  }

  @Test
  public void testStyledCustomShapes() {
    String code =
        "graph TD\n"
            + "  N1((Sun Ball)) --> N2{Magic Gem}\n"
            + "  N2 --> N3{{Toy Boat}}\n"
            + "  N3 --> N4[(Toy Castle)]\n"
            + "  N4 --> N5>Sweet Candy]\n"
            + "  N5 --> N6[[Puppy House]]\n"
            + "  style N1 fill:#ffecb3,stroke:#ffa000\n"
            + "  style N2 fill:#e1bee7,stroke:#8e24aa\n"
            + "  style N3 fill:#c8e6c9,stroke:#388e3c\n"
            + "  style N4 fill:#b2ebf2,stroke:#00838f\n"
            + "  style N5 fill:#ffcdd2,stroke:#c62828\n"
            + "  style N6 fill:#d1c4e9,stroke:#512da8\n";
    SvgDoc doc = render(code);
    assertThat(doc.findText("Sun Ball")).isNotNull();
    assertThat(doc.findText("Magic Gem")).isNotNull();
    assertThat(doc.findText("Toy Boat")).isNotNull();
    assertThat(doc.findText("Toy Castle")).isNotNull();
    assertThat(doc.findText("Sweet Candy")).isNotNull();
    assertThat(doc.findText("Puppy House")).isNotNull();
  }

  @Test
  public void testSingleUnitCompoundComponent() {
    String code =
        "graph TD\n"
            + "  subgraph SoloBox [\"Secret Clubhouse\"]\n"
            + "    KidA[Little Star] --> KidB[Bright Moon]\n"
            + "  end\n";
    SvgDoc doc = render(code);
    assertThat(doc.findText("Secret Clubhouse")).isNotNull();
    assertThat(doc.findText("Little Star")).isNotNull();
    assertThat(doc.findText("Bright Moon")).isNotNull();
  }

  @Test
  public void testSequentialSubgraphsWithoutInternalDag() {
    String code =
        "graph TD\n"
            + "  subgraph VertBox [\"Stacking Blocks\"]\n"
            + "    BoxA[Red Block]\n"
            + "    BoxB[Blue Block]\n"
            + "    BoxC[Green Block]\n"
            + "  end\n"
            + "  subgraph HorizBox [\"Toy Train\"]\n"
            + "    direction LR\n"
            + "    CarA[Train Engine]\n"
            + "    CarB[Train Caboose]\n"
            + "  end\n";
    SvgDoc doc = render(code);
    assertThat(doc.findText("Stacking Blocks")).isNotNull();
    assertThat(doc.findText("Toy Train")).isNotNull();
  }

  @Test
  public void testSequentialSubgraphsWithLabels() {
    String code =
        "graph TD\n"
            + "  subgraph VertBox [\"Stacking Blocks\"]\n"
            + "    BoxA[Red Block]\n"
            + "    BoxB[Blue Block]\n"
            + "  end\n"
            + "  subgraph HorizBox [\"Toy Train\"]\n"
            + "    direction LR\n"
            + "    CarA[Train Engine]\n"
            + "    CarB[Train Caboose]\n"
            + "  end\n"
            + "  BoxA -->|Stack On| BoxB\n"
            + "  CarA -->|Pull Car| CarB\n"
            + "  style BoxA fill:#112233;stroke:#445566\n"
            + "  style BoxB fill:#112233,stroke:#445566\n";
    SvgDoc doc = render(code);
    assertThat(doc.findText("Stacking Blocks")).isNotNull();
    assertThat(doc.findText("Toy Train")).isNotNull();
  }

  @Test
  public void testMultiLayerCompoundComponent() {
    String code =
        "graph LR\n"
            + "  subgraph Box1 [\"First Box\"]\n"
            + "    A[Puppy Dog]\n"
            + "  end\n"
            + "  subgraph Box2 [\"Second Box\"]\n"
            + "    B[Kitty Cat]\n"
            + "  end\n"
            + "  subgraph Box3 [\"Third Box\"]\n"
            + "    C[Bunny Rabbit]\n"
            + "  end\n"
            + "  A --> B\n"
            + "  B --> C\n";
    SvgDoc doc = render(code);
    assertThat(doc.findText("First Box")).isNotNull();
    assertThat(doc.findText("Second Box")).isNotNull();
    assertThat(doc.findText("Third Box")).isNotNull();
  }

  @Test
  public void testTripleNestedSubgraphsWithCrossChildEdges() {
    String code =
        "graph TD\n"
            + "  subgraph OuterCastle [\"Giant Castle\"]\n"
            + "    subgraph MidTower [\"High Tower\"]\n"
            + "      subgraph InnerRoom [\"Secret Room\"]\n"
            + "        Gem[Magic Ruby]\n"
            + "      end\n"
            + "    end\n"
            + "    subgraph SecondTower [\"Low Tower\"]\n"
            + "      OtherGem[Shiny Emerald]\n"
            + "    end\n"
            + "    Gem -->|Sparkle Magic| OtherGem\n"
            + "  end\n"
            + "  Dragon[Friendly Dragon] --> Gem\n";
    SvgDoc doc = render(code);
    assertThat(doc.findText("Giant Castle")).isNotNull();
    assertThat(doc.findText("High Tower")).isNotNull();
    assertThat(doc.findText("Secret Room")).isNotNull();
    assertThat(doc.findText("Low Tower")).isNotNull();
    assertThat(doc.findText("Magic Ruby")).isNotNull();
    assertThat(doc.findText("Shiny Emerald")).isNotNull();
    assertThat(doc.findText("Friendly Dragon")).isNotNull();
    assertThat(doc.findText("Sparkle Magic")).isNotNull();
  }

  @Test
  public void testDecisionTreeBranchingWithFeedbackLoopAndChildrenWords() {
    String code =
        "graph TD\n"
            + "  A[Little Bunny Play] --> B{Choose Sweet Snack}\n"
            + "  B -->|Crisp Red Apple| C[Happy Bunny Chew]\n"
            + "  B -->|Sweet Yellow Banana| D[Joyful Bunny Hop]\n"
            + "  B -->|Crunchy Orange Carrot| E[Cheerful Bunny Munch]\n"
            + "  E -->|Ask For More Treats| B\n"
            + "  E -->|Tired Little Nap| F[Sleepy Cozy Blanket]\n";
    SvgDoc doc = render(code);
    assertThat(doc.findText("Little Bunny Play")).isNotNull();
    assertThat(doc.findText("Choose Sweet Snack")).isNotNull();
    assertThat(doc.findText("Happy Bunny Chew")).isNotNull();
    assertThat(doc.findText("Joyful Bunny Hop")).isNotNull();
    assertThat(doc.findText("Cheerful Bunny Munch")).isNotNull();
    assertThat(doc.findText("Sleepy Cozy Blanket")).isNotNull();
    assertThat(doc.findText("Crisp Red Apple")).isNotNull();
    assertThat(doc.findText("Sweet Yellow Banana")).isNotNull();
    assertThat(doc.findText("Crunchy Orange Carrot")).isNotNull();
    assertThat(doc.findText("Ask For More Treats")).isNotNull();
    assertThat(doc.findText("Tired Little Nap")).isNotNull();

    Element eText = doc.findText("Cheerful Bunny Munch");
    Element fText = doc.findText("Sleepy Cozy Blanket");
    assertThat(eText).isNotNull();
    assertThat(fText).isNotNull();
    double eX = Double.parseDouble(eText.getAttribute("x"));
    double fX = Double.parseDouble(fText.getAttribute("x"));
    assertThat(Math.abs(eX - fX)).isLessThan(2.0);
  }

  @Test
  public void testLongNodeLabelDoesNotOverflowOrGetCapped() {
    String longLabel = "Happy Little Friendly Puppy Plays With Big Red Ball In The Green Garden";
    String code = "graph TD\n  A[\"" + longLabel + "\"]\n";
    SvgDoc doc = render(code);
    assertThat(doc.findText(longLabel)).isNotNull();

    List<Element> rects = doc.getElementsByTag("rect");
    assertThat(rects.size()).isEqualTo(1);
    double rectWidth = Double.parseDouble(rects.get(0).getAttribute("width"));
    double expectedMin = longLabel.length() * 7.5;
    assertThat(rectWidth).isGreaterThan(expectedMin);
    assertThat(rectWidth).isGreaterThan(350.0);
  }

  @Test
  public void testNestedSubgraphWithSiblingNodesAndCrossLayerEdges() {
    String code =
        "graph TD\n"
            + "  subgraph ToyBox [\"Big Toy Box\"]\n"
            + "    A[Magic Wand] --> B[Golden Crown]\n"
            + "    B --> C[Shiny Sparkles]\n"
            + "    D[Toy Train] --> E{Has Train Track?}\n"
            + "    E -->|Yes| F[Start Train Engine]\n"
            + "    subgraph TrainCars [\"Little Train Cars\"]\n"
            + "      F --> G[Red Caboose]\n"
            + "      G --> H[Blue Engine]\n"
            + "    end\n"
            + "  end\n"
            + "  subgraph Playroom [\"Sunny Playroom\"]\n"
            + "    Target[Happy Child Playing]\n"
            + "  end\n"
            + "  H --> Target\n";
    SvgDoc doc = render(code);

    assertThat(doc.findText("Big Toy Box")).isNotNull();
    assertThat(doc.findText("Magic Wand")).isNotNull();
    assertThat(doc.findText("Golden Crown")).isNotNull();
    assertThat(doc.findText("Shiny Sparkles")).isNotNull();
    assertThat(doc.findText("Toy Train")).isNotNull();
    assertThat(doc.findText("Has Train Track?")).isNotNull();
    assertThat(doc.findText("Start Train Engine")).isNotNull();
    assertThat(doc.findText("Little Train Cars")).isNotNull();
    assertThat(doc.findText("Red Caboose")).isNotNull();
    assertThat(doc.findText("Blue Engine")).isNotNull();
    assertThat(doc.findText("Sunny Playroom")).isNotNull();
    assertThat(doc.findText("Happy Child Playing")).isNotNull();

    Element fEl = doc.findText("Start Train Engine");
    Element gEl = doc.findText("Red Caboose");
    assertThat(fEl).isNotNull();
    assertThat(gEl).isNotNull();
    double fY = Double.parseDouble(fEl.getAttribute("y"));
    double gY = Double.parseDouble(gEl.getAttribute("y"));
    assertThat(fY).isLessThan(gY);
  }

  @Test
  public void testCylinderShapeWithAlapCompactionAndBackEdgeClearance() {
    String code =
        "graph TD\n"
            + "  A[(\"Honey Pot <br> Sweet & Yummy\")] -->|Morning Buzz| B[Busy Little Bumblebee]\n"
            + "  B -->|Happy Flight| C[Flower Garden Patch]\n"
            + "  D[Playful Garden Snail] -->|Slow Crawl| C\n"
            + "  C -->|Gather Nectar| A\n"
            + "  C -->|Pollinate Plants| E[Bright Sunflower]\n";
    SvgDoc doc = render(code);

    assertThat(doc.findText("Honey Pot")).isNotNull();
    assertThat(doc.findText("Sweet & Yummy")).isNotNull();
    assertThat(doc.findText("Busy Little Bumblebee")).isNotNull();
    assertThat(doc.findText("Flower Garden Patch")).isNotNull();
    assertThat(doc.findText("Playful Garden Snail")).isNotNull();
    assertThat(doc.findText("Bright Sunflower")).isNotNull();

    Element bEl = doc.findText("Busy Little Bumblebee");
    Element dEl = doc.findText("Playful Garden Snail");
    assertThat(bEl).isNotNull();
    assertThat(dEl).isNotNull();
    double bY = Double.parseDouble(bEl.getAttribute("y"));
    double dY = Double.parseDouble(dEl.getAttribute("y"));
    assertThat(Math.abs(bY - dY)).isLessThan(2.0);

    List<Element> paths = doc.getEdgePaths();
    assertThat(paths.size()).isAtLeast(5);

    // Verify back-edge from C to A loops with rightward clearance beyond D
    Element dText = doc.findText("Playful Garden Snail");
    double dRight = Double.parseDouble(dText.getAttribute("x")) + 80.0;
    boolean foundClearanceLoop = false;
    for (Element p : paths) {
      String d = p.getAttribute("d");
      if (d.contains(" C ")) {
        for (String part : Splitter.onPattern("[,\\s]+").omitEmptyStrings().split(d)) {
          try {
            double val = Double.parseDouble(part);
            if (val > dRight) {
              foundClearanceLoop = true;
              break;
            }
          } catch (NumberFormatException e) {
            // Ignore non-numeric path commands (e.g. "C", "M")
          }
        }
      }
    }
    assertThat(foundClearanceLoop).isTrue();
  }

  @Test
  public void testSubgraphEdgeWithDirectionOverrideAndDynamicLabelSpacing() {
    String code =
        "flowchart TD\n"
            + "  subgraph StoryOne [\"Teddy Bear Adventure\"]\n"
            + "    direction LR\n"
            + "    P1[Cozy Blanket] ---|Soft Fluffy Hug| P2[Sweet Dream]\n"
            + "    P2 ---|Gentle Night Song| P3[Morning Sun]\n"
            + "  end\n"
            + "  subgraph StoryTwo [\"Puppy Playground\"]\n"
            + "    direction LR\n"
            + "    Q1[Rubber Ball] ---|Happy Bouncy Leap| Q2[Flying Frisbee]\n"
            + "    Q2 ---|Wagging Tail Jump| Q3[Green Lawn]\n"
            + "  end\n"
            + "  StoryOne ==>|Wake Up Early| StoryTwo\n";
    SvgDoc doc = render(code);

    // 1. Subgraph container layout and node containment checks
    List<SvgDoc.Rect2D> sgs = doc.getSubgraphBoundingBoxes();
    assertThat(sgs.size()).isEqualTo(2);
    SvgDoc.Rect2D sg1 = sgs.get(0);
    SvgDoc.Rect2D sg2 = sgs.get(1);

    // StoryOne must be completely vertically above StoryTwo
    assertThat(sg1.bottom()).isLessThan(sg2.y);

    List<SvgDoc.Rect2D> nodes = doc.getNodeBoundingBoxes();
    assertThat(nodes.size()).isEqualTo(6);
    for (int i = 0; i < 3; i++) {
      assertThat(sg1.contains(nodes.get(i), 10.0)).isTrue();
    }
    for (int i = 3; i < 6; i++) {
      assertThat(sg2.contains(nodes.get(i), 10.0)).isTrue();
    }

    // 2. Subgraph connecting edge geometry
    List<Element> lines = doc.getElementsByTag("line");
    assertThat(lines.size()).isEqualTo(1);
    Element seLine = lines.get(0);
    double lx1 = Double.parseDouble(seLine.getAttribute("x1"));
    double ly1 = Double.parseDouble(seLine.getAttribute("y1"));
    double lx2 = Double.parseDouble(seLine.getAttribute("x2"));
    double ly2 = Double.parseDouble(seLine.getAttribute("y2"));
    assertThat(Math.abs(lx1 - sg1.centerX())).isLessThan(1.0);
    assertThat(Math.abs(ly1 - sg1.bottom())).isLessThan(1.0);
    assertThat(Math.abs(lx2 - sg2.centerX())).isLessThan(1.0);
    assertThat(Math.abs(ly2 - sg2.y)).isLessThan(1.0);

    // 3. Intra-subgraph horizontal edge paths
    List<Element> paths = doc.getEdgePaths();
    assertThat(paths.size()).isEqualTo(4);
    for (Element p : paths) {
      String d = p.getAttribute("d");
      assertThat(d).startsWith("M ");
    }
  }

  @Test
  public void testIsolatedSubgraphInHorizontalGraph() {
    String code =
        "graph LR\n"
            + "  subgraph Garden [\"Flower Garden\"]\n"
            + "    A[Bright Tulip]\n"
            + "    B[Daisy Flower]\n"
            + "  end\n";
    SvgDoc doc = render(code);

    List<SvgDoc.Rect2D> sgs = doc.getSubgraphBoundingBoxes();
    assertThat(sgs.size()).isEqualTo(1);
    List<SvgDoc.Rect2D> nodes = doc.getNodeBoundingBoxes();
    assertThat(nodes.size()).isEqualTo(2);
    assertThat(sgs.get(0).contains(nodes.get(0), 10.0)).isTrue();
    assertThat(sgs.get(0).contains(nodes.get(1), 10.0)).isTrue();
  }

  @Test
  public void testNestedSubgraphsWithInternalSubgraphEdgeAndInheritedDirection() {
    String code =
        "graph TD\n"
            + "  subgraph MainBox [\"Toy Warehouse\"]\n"
            + "    direction LR\n"
            + "    subgraph BoxOne [\"Teddy Room\"]\n"
            + "      A[Brown Bear]\n"
            + "    end\n"
            + "    subgraph BoxTwo [\"Puppy Room\"]\n"
            + "      B[Happy Dog]\n"
            + "    end\n"
            + "    BoxOne --> BoxTwo\n"
            + "  end\n";
    SvgDoc doc = render(code);

    List<SvgDoc.Rect2D> sgs = doc.getSubgraphBoundingBoxes();
    assertThat(sgs.size()).isEqualTo(3);
  }
}
