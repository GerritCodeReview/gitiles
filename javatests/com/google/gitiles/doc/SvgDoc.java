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
import static com.google.common.truth.Truth.assertWithMessage;

import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import javax.annotation.Nullable;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/** Shared DOM and geometric assertion helper for Mermaid SVG test suites. */
public class SvgDoc {

  public static final ImmutableList<String> DANGEROUS_TAGS =
      ImmutableList.of(
          "script",
          "iframe",
          "foreignObject",
          "img",
          "a",
          "object",
          "embed",
          "applet",
          "audio",
          "video",
          "form",
          "input",
          "button",
          "style",
          "link",
          "meta",
          "use",
          "image",
          "animate",
          "set",
          "animateTransform",
          "feImage",
          "pattern",
          "xml-stylesheet");

  public final Document doc;
  public final Element root;

  public SvgDoc(String svg) {
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

  /**
   * Renders the given Mermaid diagram code and verifies all geometric and structural invariants.
   */
  public static SvgDoc render(String mermaidCode) {
    Optional<String> svgOpt = SimpleMermaidRenderer.renderToSvg(mermaidCode);
    assertThat(svgOpt).isPresent();
    SvgDoc doc = new SvgDoc(svgOpt.get());
    doc.assertAllInvariants();
    return doc;
  }

  /**
   * Renders the given Mermaid diagram code without asserting collision invariants (e.g. for
   * malformed tests).
   */
  public static SvgDoc renderRaw(String mermaidCode) {
    Optional<String> svgOpt = SimpleMermaidRenderer.renderToSvg(mermaidCode);
    assertThat(svgOpt).isPresent();
    return new SvgDoc(svgOpt.get());
  }

  /**
   * Renders the given Graphviz DOT diagram code and verifies all geometric and structural
   * invariants.
   */
  public static SvgDoc renderDot(String dotCode) {
    Optional<String> svgOpt = SimpleDotRenderer.renderToSvg(dotCode);
    assertThat(svgOpt).isPresent();
    SvgDoc doc = new SvgDoc(svgOpt.get());
    doc.assertAllDotInvariants();
    return doc;
  }

  /**
   * Renders the given Graphviz DOT diagram code without asserting collision invariants.
   */
  public static SvgDoc renderDotRaw(String dotCode) {
    Optional<String> svgOpt = SimpleDotRenderer.renderToSvg(dotCode);
    assertThat(svgOpt).isPresent();
    return new SvgDoc(svgOpt.get());
  }

  /**
   * Asserts root SVG structure, safe tags, and non-overlapping nodes, edge labels, and subgraphs.
   */
  public void assertAllInvariants() {
    assertRootSvg();
    assertNoDangerousTags();
    assertNoNodeOverlaps();
    assertNoLabelNodeOverlaps();
    assertSubgraphsDoNotOverlap();
  }

  /**
   * Asserts root Graphviz DOT SVG structure, safe tags, and non-overlapping nodes, edge labels, and
   * subgraphs.
   */
  public void assertAllDotInvariants() {
    assertRootDotSvg();
    assertNoDangerousTags();
    assertNoNodeOverlaps();
    assertNoLabelNodeOverlaps();
    assertSubgraphsDoNotOverlap();
  }

  public void assertRootSvg() {
    assertThat(root.getTagName()).isEqualTo("svg");
    assertThat(root.getAttribute("class")).isEqualTo("mermaid-svg");
    assertThat(root.getAttribute("xmlns")).isEqualTo("http://www.w3.org/2000/svg");
    assertThat(root.getAttribute("viewBox")).matches("^0 0 \\d+ \\d+$");
    assertThat(root.getAttribute("style")).contains("max-width:");
  }

  public void assertRootDotSvg() {
    assertThat(root.getTagName()).isEqualTo("svg");
    assertThat(root.getAttribute("class")).isEqualTo("graphviz-svg");
    assertThat(root.getAttribute("xmlns")).isEqualTo("http://www.w3.org/2000/svg");
    assertThat(root.getAttribute("viewBox")).matches("^0 0 \\d+ \\d+$");
    assertThat(root.getAttribute("style")).contains("max-width:");
  }

  public void assertDefs() {
    List<Element> defs = getElementsByTag("defs");
    assertThat(defs).hasSize(1);
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

  public void assertDotDefs() {
    List<Element> defs = getElementsByTag("defs");
    assertThat(defs).hasSize(1);
    Element def = defs.get(0);
    NodeList markers = def.getElementsByTagName("marker");
    assertThat(markers.getLength()).isAtLeast(1);
    Element marker = (Element) markers.item(0);
    assertThat(marker.getAttribute("id")).isEqualTo("graphviz-arrow");
  }

  public void assertNoDangerousTags() {
    assertThat(root.getTagName()).isEqualTo("svg");
    for (String tag : DANGEROUS_TAGS) {
      NodeList nl = doc.getElementsByTagName(tag);
      assertThat(nl.getLength()).isEqualTo(0);
    }
  }

  public List<Element> getElementsByTag(String tagName) {
    List<Element> list = new ArrayList<>();
    NodeList nl = doc.getElementsByTagName(tagName);
    for (int i = 0; i < nl.getLength(); i++) {
      Node n = nl.item(i);
      if (n instanceof Element element) {
        list.add(element);
      }
    }
    return list;
  }

  public List<Element> getEdgePaths() {
    List<Element> list = new ArrayList<>();
    for (Element p : getElementsByTag("path")) {
      if (!p.getAttribute("d").equals("M 0 1.5 L 10 5 L 0 8.5 z")) {
        list.add(p);
      }
    }
    return list;
  }

  public List<Element> findSubgraphRects() {
    List<Element> list = new ArrayList<>();
    for (Element r : getElementsByTag("rect")) {
      String cls = r.getAttribute("class");
      if (cls.contains("graphviz-node-shape")) {
        continue;
      }
      if (r.getAttribute("stroke-dasharray").equals("4,4")
          || cls.contains("graphviz-subgraph-box")) {
        list.add(r);
      }
    }
    return list;
  }

  @Nullable
  public Element findPolygonWithVertices(int count) {
    for (Element p : getElementsByTag("polygon")) {
      String pts = p.getAttribute("points").trim();
      if (!pts.isEmpty() && pts.split("\\s+").length == count) {
        return p;
      }
    }
    return null;
  }

  @Nullable
  public Element findText(String text) {
    String expected = text.replace("\0", "").trim();
    for (Element t : getElementsByTag("text")) {
      String full = t.getTextContent().trim().replaceAll("\\s+", " ");
      if (expected.equals(full)
          || expected.equals(t.getTextContent().trim())
          || full.contains(expected)) {
        return t;
      }
    }
    for (Element t : getElementsByTag("tspan")) {
      String full = t.getTextContent().trim();
      if (expected.equals(full) || full.contains(expected)) {
        return t;
      }
    }
    return null;
  }

  public double getAttrDouble(Element el, String attr) {
    return Double.parseDouble(el.getAttribute(attr));
  }

  public List<String> getAllTextContents() {
    List<String> list = new ArrayList<>();
    for (Element t : getElementsByTag("text")) {
      String txt = t.getTextContent().trim().replaceAll("\\s+", " ");
      if (!txt.isEmpty()) {
        list.add(txt);
      }
    }
    return list;
  }

  public static class Rect2D {
    public final double x;
    public final double y;
    public final double width;
    public final double height;
    public final String label;

    public Rect2D(double x, double y, double width, double height, String label) {
      this.x = x;
      this.y = y;
      this.width = width;
      this.height = height;
      this.label = label;
    }

    public double right() {
      return x + width;
    }

    public double bottom() {
      return y + height;
    }

    public double centerX() {
      return x + width / 2.0;
    }

    public double centerY() {
      return y + height / 2.0;
    }

    public boolean overlaps(Rect2D other, double tolerance) {
      return (this.x + this.width - tolerance > other.x)
          && (other.x + other.width - tolerance > this.x)
          && (this.y + this.height - tolerance > other.y)
          && (other.y + other.height - tolerance > this.y);
    }

    public boolean contains(Rect2D inner, double padding) {
      return inner.x >= this.x + padding - 0.5
          && inner.y >= this.y + padding - 0.5
          && inner.right() <= this.right() - padding + 0.5
          && inner.bottom() <= this.bottom() - padding + 0.5;
    }

    @Override
    public String toString() {
      return String.format("[%s: (%.1f, %.1f) %.1fx%.1f]", label, x, y, width, height);
    }
  }

  public List<Rect2D> getNodeBoundingBoxes() {
    List<Rect2D> list = new ArrayList<>();
    List<Element> seenGraphvizNodeGroups = new ArrayList<>();
    for (Element r : getElementsByTag("rect")) {
      String dash = r.getAttribute("stroke-dasharray");
      String opacity = r.getAttribute("fill-opacity");
      String cls = r.getAttribute("class");
      if (cls.contains("graphviz-subgraph-box")
          || cls.contains("graphviz-edge-label-bg")
          || cls.contains("graphviz-node-tab")
          || opacity.equals("0.95")
          || (dash.equals("4,4") && !cls.contains("graphviz-node-shape"))) {
        continue;
      }
      if (isSecondaryShapeInSameNodeGroup(r, seenGraphvizNodeGroups)) {
        continue;
      }
      double x = Double.parseDouble(r.getAttribute("x"));
      double y = Double.parseDouble(r.getAttribute("y"));
      double w = Double.parseDouble(r.getAttribute("width"));
      double h = Double.parseDouble(r.getAttribute("height"));
      list.add(new Rect2D(x, y, w, h, "NodeRect"));
    }
    for (Element e : getElementsByTag("ellipse")) {
      if (e.getAttribute("cx").isEmpty() || e.getAttribute("rx").isEmpty()) {
        continue;
      }
      if (isSecondaryShapeInSameNodeGroup(e, seenGraphvizNodeGroups)) {
        continue;
      }
      double cx = Double.parseDouble(e.getAttribute("cx"));
      double cy = Double.parseDouble(e.getAttribute("cy"));
      double rx = Double.parseDouble(e.getAttribute("rx"));
      double ry = Double.parseDouble(e.getAttribute("ry"));
      list.add(new Rect2D(cx - rx, cy - ry, rx * 2, ry * 2, "NodeEllipse"));
    }
    List<Element> circles = getElementsByTag("circle");
    for (int i = 0; i < circles.size(); i++) {
      Element c = circles.get(i);
      if (c.getAttribute("class").contains("graphviz-node-inner-circle")) {
        continue;
      }
      double cx = Double.parseDouble(c.getAttribute("cx"));
      double cy = Double.parseDouble(c.getAttribute("cy"));
      double cr = Double.parseDouble(c.getAttribute("r"));
      boolean isInnerConcentric = false;
      for (int j = 0; j < circles.size(); j++) {
        if (i == j) {
          continue;
        }
        Element other = circles.get(j);
        double ocx = Double.parseDouble(other.getAttribute("cx"));
        double ocy = Double.parseDouble(other.getAttribute("cy"));
        double ocr = Double.parseDouble(other.getAttribute("r"));
        if (Math.abs(cx - ocx) < 1.0
            && Math.abs(cy - ocy) < 1.0
            && (cr < ocr || (cr == ocr && i > j))) {
          isInnerConcentric = true;
          break;
        }
      }
      if (isInnerConcentric) {
        continue;
      }
      if (isSecondaryShapeInSameNodeGroup(c, seenGraphvizNodeGroups)) {
        continue;
      }
      list.add(new Rect2D(cx - cr, cy - cr, cr * 2, cr * 2, "NodeCircle"));
    }
    for (Element p : getElementsByTag("polygon")) {
      if (p.getParentNode() instanceof Element parent && parent.getTagName().equals("marker")) {
        continue;
      }
      if (isSecondaryShapeInSameNodeGroup(p, seenGraphvizNodeGroups)) {
        continue;
      }
      String pts = p.getAttribute("points").trim();
      if (!pts.isEmpty()) {
        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxX = Double.MIN_VALUE;
        double maxY = Double.MIN_VALUE;
        for (String pair : Splitter.on(Pattern.compile("\\s+")).omitEmptyStrings().split(pts)) {
          List<String> xy = Splitter.on(',').splitToList(pair);
          if (xy.size() == 2) {
            double px = Double.parseDouble(xy.get(0));
            double py = Double.parseDouble(xy.get(1));
            minX = Math.min(minX, px);
            minY = Math.min(minY, py);
            maxX = Math.max(maxX, px);
            maxY = Math.max(maxY, py);
          }
        }
        if (minX != Double.MAX_VALUE) {
          list.add(new Rect2D(minX, minY, maxX - minX, maxY - minY, "NodePolygon"));
        }
      }
    }
    return list;
  }

  private static boolean isSecondaryShapeInSameNodeGroup(
      Element shape, List<Element> seenGraphvizNodeGroups) {
    if (shape.getParentNode() instanceof Element parent
        && parent.getTagName().equals("g")
        && parent.getAttribute("class").contains("graphviz-node")
        && parent.getElementsByTagName("text").getLength() <= 1) {
      if (seenGraphvizNodeGroups.contains(parent)) {
        return true;
      }
      seenGraphvizNodeGroups.add(parent);
    }
    return false;
  }

  public List<Rect2D> getEdgeLabelBadgeBoundingBoxes() {
    List<Rect2D> list = new ArrayList<>();
    for (Element r : getElementsByTag("rect")) {
      if (r.getAttribute("fill-opacity").equals("0.95")
          || r.getAttribute("class").contains("graphviz-edge-label-bg")) {
        double x = Double.parseDouble(r.getAttribute("x"));
        double y = Double.parseDouble(r.getAttribute("y"));
        double w = Double.parseDouble(r.getAttribute("width"));
        double h = Double.parseDouble(r.getAttribute("height"));
        list.add(new Rect2D(x, y, w, h, "LabelBadge"));
      }
    }
    return list;
  }

  public List<Rect2D> getSubgraphBoundingBoxes() {
    List<Rect2D> list = new ArrayList<>();
    for (Element r : getElementsByTag("rect")) {
      String cls = r.getAttribute("class");
      if (cls.contains("graphviz-node-shape")) {
        continue;
      }
      if (r.getAttribute("stroke-dasharray").equals("4,4")
          || cls.contains("graphviz-subgraph-box")) {
        double x = Double.parseDouble(r.getAttribute("x"));
        double y = Double.parseDouble(r.getAttribute("y"));
        double w = Double.parseDouble(r.getAttribute("width"));
        double h = Double.parseDouble(r.getAttribute("height"));
        list.add(new Rect2D(x, y, w, h, "SubgraphBox"));
      }
    }
    return list;
  }

  public void assertNoNodeOverlaps() {
    List<Rect2D> nodes = getNodeBoundingBoxes();
    for (int i = 0; i < nodes.size(); i++) {
      for (int j = i + 1; j < nodes.size(); j++) {
        Rect2D a = nodes.get(i);
        Rect2D b = nodes.get(j);
        assertWithMessage("Node overlap detected between %s and %s", a, b)
            .that(a.overlaps(b, 2.0))
            .isFalse();
      }
    }
  }

  public void assertNoLabelNodeOverlaps() {
    List<Rect2D> nodes = getNodeBoundingBoxes();
    List<Rect2D> labels = getEdgeLabelBadgeBoundingBoxes();
    for (Rect2D badge : labels) {
      for (Rect2D node : nodes) {
        assertWithMessage("Edge label badge %s overlaps node %s", badge, node)
            .that(badge.overlaps(node, 2.0))
            .isFalse();
      }
    }
  }

  public void assertSubgraphsDoNotOverlap() {
    List<Rect2D> sgs = getSubgraphBoundingBoxes();
    for (int i = 0; i < sgs.size(); i++) {
      for (int j = i + 1; j < sgs.size(); j++) {
        Rect2D a = sgs.get(i);
        Rect2D b = sgs.get(j);
        boolean oneContainsOther = a.contains(b, 0) || b.contains(a, 0);
        if (!oneContainsOther) {
          assertWithMessage("Sibling subgraphs overlap: %s and %s", a, b)
              .that(a.overlaps(b, 2.0))
              .isFalse();
        }
      }
    }
  }
}
