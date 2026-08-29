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
  public void testConstructorInstantiation() throws Exception {
    java.lang.reflect.Constructor<SimpleMermaidRenderer> c =
        SimpleMermaidRenderer.class.getDeclaredConstructor();
    c.setAccessible(true);
    assertThat(c.newInstance()).isNotNull();
  }

  @Test
  public void testBasicGraphTDExactSvgStructure() {
    SvgDoc svg = render("graph TD\n  A --> B\n");
    svg.assertDefs();

    // Verify exactly 2 node rects and 1 edge path
    assertThat(svg.getElementsByTag("rect")).hasSize(2);
    assertThat(svg.getElementsByTag("path")).hasSize(2); // 1 arrow in defs + 1 edge

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
        """
        graph TD
          A[Rectangle Box]
          B(Rounded Ball)
          C([Stadium Ring])
          D[[Subroutine Cart]]
          E[(Cylinder Drum)]
          F((Circle Star))
          G{{Hexagon Block}}
          H{Diamond Kite}
          I>Asymmetric Flag]
        """;
    SvgDoc svg = render(code);

    // Exact text elements
    List<String> texts = svg.getAllTextContents();
    assertThat(texts)
        .containsExactly(
            "Rectangle Box",
            "Rounded Ball",
            "Stadium Ring",
            "Subroutine Cart",
            "Cylinder Drum",
            "Circle Star",
            "Hexagon Block",
            "Diamond Kite",
            "Asymmetric Flag")
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
    assertThat(lines).hasSize(2);

    // Verify Cylinder has 2 paths (body + top rim arc)
    List<Element> paths = svg.getEdgePaths();
    assertThat(paths).hasSize(2);
  }

  @Test
  public void testMultilineNodeLabelsWithTspans() {
    String code =
        """
        graph TD
          A["Sunny Blue Sky<br/>Warm Golden Sun<br/>Soft Green Grass"]
          B["Little Red Apple"]
          A --> B
        """;
    SvgDoc svg = render(code);

    List<Element> tspans = svg.getElementsByTag("tspan");
    assertThat(tspans).hasSize(3);
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
        """
        graph TD
          A["Play with teddy bear"]
          B["Find [Puppy] in cozy room"]
          C{"Is kitten <tiny> & 'sweet'?"}
          A --> B --> C
        """;
    SvgDoc svg = render(code);

    // XML parsing confirms correct unescaping of &lt;, &gt;, &apos;, &amp;
    assertThat(svg.getAllTextContents())
        .containsExactly(
            "Play with teddy bear", "Find [Puppy] in cozy room", "Is kitten <tiny> & 'sweet'?")
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
        """
        graph LR
          A -->|Yellow Duck| B
          B ---|Blue Bird| C
          C -.->|Green Frog| D
          D ==>|Red Puppy| E
          E -- Orange Kitten --> F
          F -- Purple Bunny --- G
          G == White Lamb ==> H
          H == Pink Piggy === I
          I -. Brown Bear .-> J
          J -. Gray Mouse .- K
          K -.- L
          L === M
          M <--> N
        """;
    SvgDoc svg = render(code);

    List<Element> edgePaths = svg.getEdgePaths();
    assertThat(edgePaths).hasSize(13);

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
        """
        graph TD
          subgraph Playground Park
            direction LR
            E1(puppy)
            E2(kitten)
          end
          subgraph ToyHouse ["Magic Toy House"]
            direction INVALID_DIR
            S1[(Teddy)]
          end
          subgraph "Music Tree Castle"
            T1[Wooden Blocks]
          end
          subgraph MeadowHill [Sunny Meadow Hill]
            U1[Little Duck]
          end
          Baby --> E1
          E1 --> ToyHouse
          ToyHouse --> T1
          T1 --> MeadowHill
        """;
    SvgDoc svg = render(code);
    svg.assertNoLabelNodeOverlaps();
    svg.assertSubgraphsDoNotOverlap();

    // Verify 4 subgraph boundary rects (stroke-dasharray="4,4")
    List<Element> subgraphs = svg.findSubgraphRects();
    assertThat(subgraphs).hasSize(4);
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
    String codeTd =
        """
        graph TD
          subgraph SubA ["Garden A"]
            A1[Daisy Flower]
          end
          subgraph SubB ["Garden B"]
            B1[Tulip Flower]
          end
          SubA -->|Garden Link TD| SubB
          SubA -->|Flower Link| NodeC[Red Rose]
          NodeC -->|Petal Link| SubB
        """;
    SvgDoc svgTd = render(codeTd);
    assertThat(svgTd.findText("Garden Link TD")).isNotNull();
    assertThat(svgTd.findText("Flower Link")).isNotNull();
    assertThat(svgTd.findText("Petal Link")).isNotNull();
    assertThat(svgTd.getElementsByTag("path")).isNotEmpty();

    String codeLr =
        """
        graph LR
          subgraph SubA ["Garden A"]
            A1[Daisy Flower]
          end
          subgraph SubB ["Garden B"]
            B1[Tulip Flower]
          end
          SubA -->|Garden Link LR| SubB
        """;
    SvgDoc svgLr = render(codeLr);
    assertThat(svgLr.findText("Garden Link LR")).isNotNull();

    String codeLrWithBlockedNode =
        """
        graph LR
          subgraph SubA ["Garden A"]
            A1[Daisy Flower]
          end
          subgraph SubB ["Garden B"]
            B1[Tulip Flower]
          end
          SubA -->|Garden Link LR Blocked| SubB
          SubA -->|Flower Link LR| NodeC[Red Rose]
          NodeC -->|Petal Link LR| SubB
        """;
    SvgDoc svgLrBlocked = render(codeLrWithBlockedNode);
    assertThat(svgLrBlocked.findText("Garden Link LR Blocked")).isNotNull();
    assertThat(svgLrBlocked.findText("Flower Link LR")).isNotNull();
    assertThat(svgLrBlocked.findText("Petal Link LR")).isNotNull();
  }

  @Test
  public void testNestedSubgraphsExactHierarchy() {
    String code =
        """
        graph TD
          subgraph Sandbox ["Play Sandbox"]
            subgraph SandCastle ["Sand Castle"]
              SPA["Red Bucket"]
            end
            subgraph ToyPond ["Toy Pond"]
              CS["Yellow Boat"]
            end
          end
          SPA -->|Water Splash| CS
        """;
    SvgDoc svg = render(code);

    // Exactly 3 subgraph boxes (1 outer + 2 inner)
    List<Element> subgraphs = svg.findSubgraphRects();
    assertThat(subgraphs).hasSize(3);

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
        """
        graph LR
          subgraph Outer ["Playhouse"]
            subgraph InnerA ["Kitten Corner"]
              A1[Soft Pillow]
            end
            subgraph InnerB ["Puppy Corner"]
              B1[Squeaky Ball]
            end
          end
          A1 -->|Play Time| B1
        """;
    SvgDoc svg = render(code);
    assertThat(svg.findText("Play Time")).isNotNull();
  }

  @Test
  public void testSubgraphWithMixedChildrenAndDirectNodes() {
    String codeTd =
        """
        graph TD
          subgraph OuterTD ["Tree House TD"]
            subgraph InnerTD ["Bird Nest TD"]
              A1[Baby Bird TD]
            end
            D1[Little Squirrel TD]
          end
          A1 --> D1
        """;
    SvgDoc svgTd = render(codeTd);
    assertThat(svgTd.findText("Tree House TD")).isNotNull();
    assertThat(svgTd.findText("Bird Nest TD")).isNotNull();
    assertThat(svgTd.findText("Little Squirrel TD")).isNotNull();

    String codeLr =
        """
        graph LR
          subgraph OuterLR ["Tree House LR"]
            subgraph InnerLR ["Bird Nest LR"]
              A1[Baby Bird LR]
            end
            D1[Little Squirrel LR]
          end
          A1 --> D1
        """;
    SvgDoc svgLr = render(codeLr);
    assertThat(svgLr.findText("Tree House LR")).isNotNull();
    assertThat(svgLr.findText("Bird Nest LR")).isNotNull();
    assertThat(svgLr.findText("Little Squirrel LR")).isNotNull();
  }

  @Test
  public void testNestedSubgraphWithLabeledAdjacentEdge() {
    String codeTd =
        """
        graph TD
          subgraph SubTD ["Animal Farm TD"]
            A[Happy Lamb]
            B[Little Pony]
            A -->|Green Grass TD| B
          end
        """;
    SvgDoc svgTd = render(codeTd);
    assertThat(svgTd.findText("Green Grass TD")).isNotNull();

    String codeLr =
        """
        graph LR
          subgraph SubLR ["Animal Farm LR"]
            A[Happy Lamb]
            B[Little Pony]
            A -->|Green Grass LR| B
          end
        """;
    SvgDoc svgLr = render(codeLr);
    assertThat(svgLr.findText("Green Grass LR")).isNotNull();
  }

  @Test
  public void testSugiyamaLateralAdjacentLabeledEdge() {
    String code =
        """
        graph TD
          A1[Fuzzy Panda] --> B1[Baby Giraffe]
          A2[Little Koala] --> B2[Tiny Hamster]
          A1 -->|Sunny Day| A2
          B1 -->|Happy Play| B2
        """;
    SvgDoc svg = render(code);
    assertThat(svg.findText("Sunny Day")).isNotNull();
    assertThat(svg.findText("Happy Play")).isNotNull();
  }

  @Test
  public void testBidirectionalMutualEdgesBothOrientations() {
    String codeTd =
        """
        graph TD
          A["Little Lamb"]
          B["Sweet Bunny"]
          A -->|Hop Down| B
          B -->|Jump Up| A
        """;
    SvgDoc svgTd = render(codeTd);
    assertThat(svgTd.findText("Hop Down")).isNotNull();
    assertThat(svgTd.findText("Jump Up")).isNotNull();
    // Exactly 2 mutual curved edge paths
    assertThat(svgTd.getEdgePaths()).hasSize(2);

    String codeLr =
        """
        graph LR
          A["Little Lamb"]
          B["Sweet Bunny"]
          A -->|Run Forward| B
          B -->|Run Backward| A
        """;
    SvgDoc svgLr = render(codeLr);
    assertThat(svgLr.findText("Run Forward")).isNotNull();
    assertThat(svgLr.findText("Run Backward")).isNotNull();
    assertThat(svgLr.getEdgePaths()).hasSize(2);
  }

  @Test
  public void testCycleDetectionAndLoopbackBothOrientations() {
    String codeTd =
        """
        graph TD
          A --> B
          B --> C
          C -->|Loop TD| A
        """;
    SvgDoc svgTd = render(codeTd);
    assertThat(svgTd.findText("Loop TD")).isNotNull();
    assertThat(svgTd.getEdgePaths()).hasSize(3);

    String codeLr =
        """
        graph LR
          A --> B
          B --> C
          C -->|Loop LR| A
        """;
    SvgDoc svgLr = render(codeLr);
    assertThat(svgLr.findText("Loop LR")).isNotNull();
    assertThat(svgLr.getEdgePaths()).hasSize(3);
  }

  @Test
  public void testSkipLayerBypassBothOrientations() {
    String codeTd =
        """
        graph TD
          A --> B
          B --> C
          A -->|Skip TD| C
        """;
    SvgDoc svgTd = render(codeTd);
    assertThat(svgTd.findText("Skip TD")).isNotNull();

    String codeLr =
        """
        graph LR
          A --> B
          B --> C
          A -->|Skip LR| C
        """;
    SvgDoc svgLr = render(codeLr);
    assertThat(svgLr.findText("Skip LR")).isNotNull();
  }

  @Test
  public void testHorizontalGraphWithCycleAndLongSpanEdge() {
    String code =
        """
        graph LR
          A[Happy Kitten] --> B[Playful Puppy]
          B --> C[Cozy Hamster]
          C -->|Run Back| A
          A -->|Long Leap| C
        """;
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
        """
        graph TD
          subgraph Meadow ["Sunny Green Meadow"]
            A[Bright Buttercup] --> B[Busy Ant]
            B --> C[Tall Oak Tree]
            D[Quiet Snail] --> C
          end
        """;
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
        """
        graph TD
          accTitle: Cheerful Morning Playground
          accDescr: Story of fluffy puppy and kitten
          classDef default fill:#f9f,stroke:#333;
          classDef special fill:#bbf,stroke:#333;
          class A special
          style B fill:#dfd,stroke:#333;
          click A href "https://example.com"
          linkStyle 0 stroke:#ff3,stroke-width:4px;
          A[Little Kitten] --> B[Fluffy Bunny]
        """;
    SvgDoc svg = render(code);
    assertThat(svg.getAllTextContents()).containsExactly("Little Kitten", "Fluffy Bunny").inOrder();
  }

  @Test
  public void testDisconnectedNodes() {
    SvgDoc svg = render("graph TD\n  A[Quiet Mouse]\n  B[Sleeping Turtle]\n  C --> D\n");
    assertThat(svg.getAllTextContents())
        .containsExactly("Quiet Mouse", "Sleeping Turtle", "C", "D")
        .inOrder();
    assertThat(svg.getElementsByTag("rect")).hasSize(4);
    assertThat(svg.getEdgePaths()).hasSize(1);
  }

  @Test
  public void testSelfLoop() {
    SvgDoc svg = render("graph TD\n  A --> A\n");
    assertThat(svg.getAllTextContents()).containsExactly("A");
  }

  @Test
  public void testWhitespaceAndEmptyBlocks() {
    assertThat(SimpleMermaidRenderer.renderToSvg("   \n\n\t")).isEmpty();
    assertThat(SimpleMermaidRenderer.renderToSvg("graph TD\n")).isEmpty();
    assertThat(SimpleMermaidRenderer.renderToSvg("graph TD\n%% only comments\n")).isEmpty();
    assertThat(SimpleMermaidRenderer.renderToSvg("")).isEmpty();
    assertThat(SimpleMermaidRenderer.renderToSvg(null)).isEmpty();
  }

  @Test
  public void testEmptyLinesAndCommentsAtStart() {
    String code = "\n\n%% Leading comment\n  \ngraph TD\n\n%% Inner comment\n  A --> B\n";
    SvgDoc svg = render(code);
    assertThat(svg.getAllTextContents()).containsExactly("A", "B").inOrder();

    assertThat(SimpleMermaidRenderer.renderToSvg("%% only comments\nsome random text\n")).isEmpty();
  }

  @Test
  public void testMalformedDelimitersGracefulHandling() {
    String code = "graph TD\n  A[\"Unclosed String\n  B --> A\n";
    SvgDoc svg = render(code);
    assertThat(svg.getAllTextContents()).contains("B");
  }

  @Test
  public void testAllUnsupportedDiagramTypesReturnEmpty() {
    assertThat(SimpleMermaidRenderer.renderToSvg("sequenceDiagram\nAlice->>Bob: Hello\n"))
        .isEmpty();
    assertThat(SimpleMermaidRenderer.renderToSvg("classDiagram\nClass01 <|-- Class02\n")).isEmpty();
    assertThat(SimpleMermaidRenderer.renderToSvg("erDiagram\nCUSTOMER ||--o{ ORDER : places\n"))
        .isEmpty();
    assertThat(SimpleMermaidRenderer.renderToSvg("gantt\ntitle A Gantt Diagram\n")).isEmpty();
    assertThat(SimpleMermaidRenderer.renderToSvg("pie title Pets\n\"Dogs\" : 386\n")).isEmpty();
    assertThat(SimpleMermaidRenderer.renderToSvg("gitGraph\ncommit\n")).isEmpty();
    assertThat(SimpleMermaidRenderer.renderToSvg("xychart-beta\ntitle \"Score\"\n")).isEmpty();
    assertThat(SimpleMermaidRenderer.renderToSvg("stateDiagram\n[*] --> Still\n")).isEmpty();
    assertThat(SimpleMermaidRenderer.renderToSvg("stateDiagram-v2\n[*] --> Still\n")).isEmpty();
  }

  @Test
  public void testNodeReassignmentToSubgraph() {
    String code =
        """
        graph TD
          A[Singing Robin]
          subgraph Sub
            A
            B[Flying Bluebird]
          end
          A --> B
        """;
    SvgDoc svg = render(code);
    assertThat(svg.getAllTextContents())
        .containsExactly("Sub", "Singing Robin", "Flying Bluebird")
        .inOrder();
  }

  @Test
  public void testRemainingEdgeCasesForFullCoverage() {
    // Quoted pipe label triggering cleanLabel unwrap
    String code1 = "graph TD\n  A -->|\"Sweet Honey Pie\"| B\n";
    SvgDoc doc1 = render(code1);
    assertThat(doc1.findText("Sweet Honey Pie")).isNotNull();

    // Reverse edge in vertical nested subgraph
    String code2 =
        "graph TD\n"
            + "  subgraph Sub\n"
            + "    A[Baby Chick]\n"
            + "    B[Mama Hen]\n"
            + "    B -->|Chirp Vert| A\n"
            + "  end\n";
    SvgDoc doc2 = render(code2);
    assertThat(doc2.findText("Chirp Vert")).isNotNull();

    // Reverse edge in horizontal nested subgraph
    String code3 =
        "graph LR\n"
            + "  subgraph Sub\n"
            + "    A[Baby Chick]\n"
            + "    B[Mama Hen]\n"
            + "    B -->|Chirp Horiz| A\n"
            + "  end\n";
    SvgDoc doc3 = render(code3);
    assertThat(doc3.findText("Chirp Horiz")).isNotNull();

    // Mutual same-layer edge with label
    String code5 = "graph TD\n  A --> B\n  B --> A\n  A -->|Golden Star| B\n  C --> D\n";
    assertThat(render(code5)).isNotNull();

    // Trailing non-edge characters to hit scanEdgeToken default return null
    String code6 = "graph TD\n  A 12345\n";
    assertThat(SimpleMermaidRenderer.renderToSvg(code6)).isPresent();
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
    assertThat(SimpleMermaidRenderer.renderToSvg(code2)).isPresent();

    // Direct AST Node empty label setter
    SimpleMermaidRenderer.Node n = new SimpleMermaidRenderer.Node("testNode");
    n.setLabel("");
    assertThat(n.labelLines).containsExactly("");

    // Double quotes in label and title to exercise escapeXml
    String code3 =
        "graph TD\n"
            + "  subgraph Sg [\"Magic Castle with \\\"Stars\\\"\"]\n"
            + "    A[\"Has \\\"Glitter\\\" in pocket\"]\n"
            + "  end\n";
    assertThat(SimpleMermaidRenderer.renderToSvg(code3)).isPresent();
  }

  @Test
  public void testSecurityNoScriptOrIframeExecutionInNodeLabels() {
    String code =
        """
        graph TD
          A["<script>alert('xss-script')</script>"]
          B["<iframe src='javascript:alert(1)'></iframe>"]
          C["<img src=x onerror=alert('img-onerror')>"]
          D["<svg onload=alert('svg-onload')>"]
          E["<foreignObject><iframe src='https://evil.com'></iframe></foreignObject>"]
          F["<a href='javascript:alert(1)'>Click Me</a>"]
          A --> B --> C --> D --> E --> F
        """;

    Optional<String> svgOpt = SimpleMermaidRenderer.renderToSvg(code);
    assertThat(svgOpt).isPresent();

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
    assertThat(
            svg.findText("<foreignObject><iframe src='https://evil.com'></iframe></foreignObject>"))
        .isNotNull();
    assertThat(svg.findText("<a href='javascript:alert(1)'>Click Me</a>")).isNotNull();
  }

  @Test
  public void testSecurityNoScriptOrIframeInEdgeLabels() {
    String code =
        """
        graph TD
          A -->|"<script>alert('edge-pipe')</script>"| B
          B -- "<iframe src='http://evil.com'></iframe>" --> C
          C == "<img src=x onerror=alert('thick-edge')>" ==> D
          D -. "<svg onload=alert('dashed-edge')>" .-> E
        """;
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
        """
        graph TD
          subgraph Sg1 ["<script>alert('subgraph-title')</script>"]
            A[Node A]
          end
          subgraph Sg2 ["<iframe src='javascript:alert(2)'></iframe>"]
            B[Node B]
          end
          A --> B
        """;
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
        """
        graph TD
          A[Node A] --> B[Node B]
          click A href "javascript:alert('click-href')"
          click B call alert('click-call')
          click A "javascript:alert('positional-href')"
          style A fill:url(javascript:alert(1))
          classDef evil fill:red,color:white;
          linkStyle 0 stroke:red;
        """;
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
        """
        graph TD
          A["</text></svg><script>alert('breakout')</script><svg><text>"]
          B["'"><script>alert('quote-breakout')</script>"]
          A --> B
        """;
    SvgDoc svg = render(code);

    // Verify the document root remains the only SVG element and no script elements were injected
    assertThat(svg.getElementsByTag("script")).isEmpty();
    assertThat(svg.getElementsByTag("svg")).hasSize(1);

    String raw = SimpleMermaidRenderer.renderToSvg(code).get();
    assertThat(raw).doesNotContain("<script");
    assertThat(raw).doesNotContain("</svg><script>");
  }

  @Test
  public void testIsolatedSubgraphAlongsideMainDagTree() {
    String code =
        """
        graph TD
            ClientApp[Little Puppy Plays] --> Extras(Sweet Kitten)
            ClientApp --> Utils(Happy Bunny)
           \s
            Utils --> ServiceDiscovery[Red Apple Berry]
            Utils --> ModelManager[Yellow Banana Snack]
           \s
            Extras --> Recognition(Fluffy Duckling)
           \s
            Recognition --> SODA(Green Frog Jump)
            Recognition --> S3(Sunny Daisy Flower)
           \s
            subgraph Play Park Garden
                Executors(Teddy Bear)
                Errors(Wooden Blocks)
                Protos(Toy Wagon)
            end
           \s
            Recognition -.-> PlayParkGarden
        """;
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

    // Verify vertical stack in Play Park Garden subgraph (Teddy Bear above Wooden Blocks above Toy
    // Wagon)
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
        """
        graph TD
            A[Little Star] --> CheckJDAA{Is Puppy Sleepy?}
            CheckJDAA -- No --> InstallJDA[Play With Soft Ball]
            InstallJDA --> CheckJDAA
            CheckJDAA -- Yes --> B{Wants Sweet Cookie?}
            B -- Yes --> C[Drink Warm Milk Cup]
            B -- No --> D[Sing Happy Lullaby]
            D --> E[Cuddle Warm Blanket]
            D --> F[Hug Fluffy Panda]
            D --> G[Close Shiny Eyes]
            E & F & G --> H[Sweet Dreams Forest]
            C & H --> I[Gentle Good Night]
            I --> J[Sleep Until Morning]
            J --> K[Wake Up Happy Sun]
        """;
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
    assertThat(multiDoc.getElementsByTag("path"))
        .hasSize(5); // 1 marker path in <defs> + 4 edge paths
  }

  @Test
  public void testSubgraphDirectionOverrideWithCrossEdges() {
    String code =
        """
        graph TD
          subgraph Castle ["Toy Castle"]
            direction LR
            A[Happy Bear] --> B[Silly Goose]
          end
          subgraph Garden ["Flower Garden"]
            C[Sunny Daisy]
          end
          B --> C
        """;
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
        """
        graph LR
          subgraph ToyBox ["Big Toy Box"]
            subgraph PuzzleA ["Puppy Puzzle"]
              A[Little Dog]
            end
            subgraph PuzzleB ["Kitten Puzzle"]
              B[Little Cat]
            end
            A -->|Friendly Meow| B
          end
          subgraph BedTime ["Sleepy Pillow"]
            C[Cozy Blanket]
          end
          B -->|Soft Hug| C
        """;
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
        """
        graph TD
          subgraph WonderLand ["Magic Wonderland"]
            subgraph EmptyBox ["Empty Treasure Chest"]
            end
            subgraph ZoneA ["Butterfly Valley"]
              A[Shiny Butterfly]
            end
            subgraph ZoneB ["Rainbow Hill"]
              B[Glowing Rainbow]
            end
            B -->|Sweet Melody| A
          end
        """;
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
        """
        graph TD
          A[Teddy Bear] -->|Blue Balloon| B[Silly Monkey]
          A -->|Red Apple| C[Happy Puppy]
          B --> C
          C --> D[Little Kitten]
        """;
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
        """
        graph LR
          A[Teddy Bear] -->|Blue Balloon| B[Silly Monkey]
          A -->|Red Apple| C[Happy Puppy]
          B --> C
          C --> D[Little Kitten]
        """;
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
        """
        graph LR
          subgraph CastleBox ["Play Castle"]
            direction TB
            ToyA[Magic Wand] <--> ToyB[Cozy Teddy]
          end
          subgraph GardenBox ["Flower Garden"]
            ToyC[Pink Blossom] --> ToyD[Sweet Daisy]
          end
          ToyB --> ToyC
          ToyE[Happy Butterfly] --> ToyB
          style CastleBox fill:#e3f2fd,stroke:#1e88e5
          style GardenBox fill:rgb(240,250,240),stroke:#43a047
          style ToyE fill:hsl(120,50%,90%),stroke:blue
          style ToyA fill:rgba(255,255,255,0.8),stroke:purple
          style ToyC fill:#ffb300,stroke:#333333
          style "" fill:#fff
          style NonExistent fill:#fff
        """;
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
        """
        graph TD
          N1((Sun Ball)) --> N2{Magic Gem}
          N2 --> N3{{Toy Boat}}
          N3 --> N4[(Toy Castle)]
          N4 --> N5>Sweet Candy]
          N5 --> N6[[Puppy House]]
          style N1 fill:#ffecb3,stroke:#ffa000
          style N2 fill:#e1bee7,stroke:#8e24aa
          style N3 fill:#c8e6c9,stroke:#388e3c
          style N4 fill:#b2ebf2,stroke:#00838f
          style N5 fill:#ffcdd2,stroke:#c62828
          style N6 fill:#d1c4e9,stroke:#512da8
        """;
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
        """
        graph TD
          subgraph SoloBox ["Secret Clubhouse"]
            KidA[Little Star] --> KidB[Bright Moon]
          end
        """;
    SvgDoc doc = render(code);
    assertThat(doc.findText("Secret Clubhouse")).isNotNull();
    assertThat(doc.findText("Little Star")).isNotNull();
    assertThat(doc.findText("Bright Moon")).isNotNull();
  }

  @Test
  public void testSequentialSubgraphsWithoutInternalDag() {
    String code =
        """
        graph TD
          subgraph VertBox ["Stacking Blocks"]
            BoxA[Red Block]
            BoxB[Blue Block]
            BoxC[Green Block]
          end
          subgraph HorizBox ["Toy Train"]
            direction LR
            CarA[Train Engine]
            CarB[Train Caboose]
          end
        """;
    SvgDoc doc = render(code);
    assertThat(doc.findText("Stacking Blocks")).isNotNull();
    assertThat(doc.findText("Toy Train")).isNotNull();
  }

  @Test
  public void testSequentialSubgraphsWithLabels() {
    String code =
        """
        graph TD
          subgraph VertBox ["Stacking Blocks"]
            BoxA[Red Block]
            BoxB[Blue Block]
          end
          subgraph HorizBox ["Toy Train"]
            direction LR
            CarA[Train Engine]
            CarB[Train Caboose]
          end
          BoxA -->|Stack On| BoxB
          CarA -->|Pull Car| CarB
          style BoxA fill:#112233;stroke:#445566
          style BoxB fill:#112233,stroke:#445566
        """;
    SvgDoc doc = render(code);
    assertThat(doc.findText("Stacking Blocks")).isNotNull();
    assertThat(doc.findText("Toy Train")).isNotNull();
  }

  @Test
  public void testMultiLayerCompoundComponent() {
    String code =
        """
        graph LR
          subgraph Box1 ["First Box"]
            A[Puppy Dog]
          end
          subgraph Box2 ["Second Box"]
            B[Kitty Cat]
          end
          subgraph Box3 ["Third Box"]
            C[Bunny Rabbit]
          end
          A --> B
          B --> C
        """;
    SvgDoc doc = render(code);
    assertThat(doc.findText("First Box")).isNotNull();
    assertThat(doc.findText("Second Box")).isNotNull();
    assertThat(doc.findText("Third Box")).isNotNull();
  }

  @Test
  public void testTripleNestedSubgraphsWithCrossChildEdges() {
    String code =
        """
        graph TD
          subgraph OuterCastle ["Giant Castle"]
            subgraph MidTower ["High Tower"]
              subgraph InnerRoom ["Secret Room"]
                Gem[Magic Ruby]
              end
            end
            subgraph SecondTower ["Low Tower"]
              OtherGem[Shiny Emerald]
            end
            Gem -->|Sparkle Magic| OtherGem
          end
          Dragon[Friendly Dragon] --> Gem
        """;
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
        """
        graph TD
          A[Little Bunny Play] --> B{Choose Sweet Snack}
          B -->|Crisp Red Apple| C[Happy Bunny Chew]
          B -->|Sweet Yellow Banana| D[Joyful Bunny Hop]
          B -->|Crunchy Orange Carrot| E[Cheerful Bunny Munch]
          E -->|Ask For More Treats| B
          E -->|Tired Little Nap| F[Sleepy Cozy Blanket]
        """;
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
    assertThat(rects).hasSize(1);
    double rectWidth = Double.parseDouble(rects.get(0).getAttribute("width"));
    double expectedMin = longLabel.length() * 7.5;
    assertThat(rectWidth).isGreaterThan(expectedMin);
    assertThat(rectWidth).isGreaterThan(350.0);
  }

  @Test
  public void testNestedSubgraphWithSiblingNodesAndCrossLayerEdges() {
    String code =
        """
        graph TD
          subgraph ToyBox ["Big Toy Box"]
            A[Magic Wand] --> B[Golden Crown]
            B --> C[Shiny Sparkles]
            D[Toy Train] --> E{Has Train Track?}
            E -->|Yes| F[Start Train Engine]
            subgraph TrainCars ["Little Train Cars"]
              F --> G[Red Caboose]
              G --> H[Blue Engine]
            end
          end
          subgraph Playroom ["Sunny Playroom"]
            Target[Happy Child Playing]
          end
          H --> Target
        """;
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
        """
        graph TD
          A[("Honey Pot <br> Sweet & Yummy")] -->|Morning Buzz| B[Busy Little Bumblebee]
          B -->|Happy Flight| C[Flower Garden Patch]
          D[Playful Garden Snail] -->|Slow Crawl| C
          C -->|Gather Nectar| A
          C -->|Pollinate Plants| E[Bright Sunflower]
        """;
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
        for (String part :
            Splitter.on(java.util.regex.Pattern.compile("[,\\s]+")).omitEmptyStrings().split(d)) {
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
        """
        flowchart TD
          subgraph StoryOne ["Teddy Bear Adventure"]
            direction LR
            P1[Cozy Blanket] ---|Soft Fluffy Hug| P2[Sweet Dream]
            P2 ---|Gentle Night Song| P3[Morning Sun]
          end
          subgraph StoryTwo ["Puppy Playground"]
            direction LR
            Q1[Rubber Ball] ---|Happy Bouncy Leap| Q2[Flying Frisbee]
            Q2 ---|Wagging Tail Jump| Q3[Green Lawn]
          end
          StoryOne ==>|Wake Up Early| StoryTwo
        """;
    SvgDoc doc = render(code);

    // 1. Subgraph container layout and node containment checks
    List<SvgDoc.Rect2D> sgs = doc.getSubgraphBoundingBoxes();
    assertThat(sgs).hasSize(2);
    SvgDoc.Rect2D sg1 = sgs.get(0);
    SvgDoc.Rect2D sg2 = sgs.get(1);

    // StoryOne must be completely vertically above StoryTwo
    assertThat(sg1.bottom()).isLessThan(sg2.y);

    List<SvgDoc.Rect2D> nodes = doc.getNodeBoundingBoxes();
    assertThat(nodes).hasSize(6);
    for (int i = 0; i < 3; i++) {
      assertThat(sg1.contains(nodes.get(i), 10.0)).isTrue();
    }
    for (int i = 3; i < 6; i++) {
      assertThat(sg2.contains(nodes.get(i), 10.0)).isTrue();
    }

    // 2. Subgraph connecting edge geometry
    List<Element> lines = doc.getElementsByTag("line");
    assertThat(lines).hasSize(1);
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
    assertThat(paths).hasSize(4);
    for (Element p : paths) {
      String d = p.getAttribute("d");
      assertThat(d).startsWith("M ");
    }
  }

  @Test
  public void testIsolatedSubgraphInHorizontalGraph() {
    String code =
        """
        graph LR
          subgraph Garden ["Flower Garden"]
            A[Bright Tulip]
            B[Daisy Flower]
          end
        """;
    SvgDoc doc = render(code);

    List<SvgDoc.Rect2D> sgs = doc.getSubgraphBoundingBoxes();
    assertThat(sgs).hasSize(1);
    List<SvgDoc.Rect2D> nodes = doc.getNodeBoundingBoxes();
    assertThat(nodes).hasSize(2);
    assertThat(sgs.get(0).contains(nodes.get(0), 10.0)).isTrue();
    assertThat(sgs.get(0).contains(nodes.get(1), 10.0)).isTrue();
  }

  @Test
  public void testNestedSubgraphsWithInternalSubgraphEdgeAndInheritedDirection() {
    String code =
        """
        graph TD
          subgraph MainBox ["Toy Warehouse"]
            direction LR
            subgraph BoxOne ["Teddy Room"]
              A[Brown Bear]
            end
            subgraph BoxTwo ["Puppy Room"]
              B[Happy Dog]
            end
            BoxOne --> BoxTwo
          end
        """;
    SvgDoc doc = render(code);

    List<SvgDoc.Rect2D> sgs = doc.getSubgraphBoundingBoxes();
    assertThat(sgs).hasSize(3);
  }
}
