// Copyright 2026 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.gitiles.doc;

import static com.google.common.base.Strings.isNullOrEmpty;

import com.google.common.base.Ascii;
import com.google.common.base.Splitter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import javax.annotation.Nullable;

/**
 * Pure-Java server-side renderer that converts Graphviz DOT diagrams (<code>```dot</code> and
 * <code>```graphviz</code> fenced blocks) into clean, responsive inline SVG.
 *
 * <p>Requires zero external dependencies, zero native binaries, and zero client-side JavaScript.
 * Enforces strict input size, node count, and edge count bounds, strips external resource and
 * navigation attributes, rejects HTML-like labels (<code>&lt;...&gt;</code>), and delegates
 * hierarchical Sugiyama DAG layout, compound cluster layout, CSS color allowlisting, relative
 * luminance contrast, and XML escaping to {@link DiagramLayoutEngine}.
 */
public class SimpleDotRenderer {

  private static final int MAX_INPUT_BYTES = 65_536;
  private static final int MAX_NODES = 400;
  private static final int MAX_EDGES = 1_000;
  private static final int MAX_SUBGRAPH_DEPTH = 16;

  private static final Pattern PORT_TAG_PATTERN = Pattern.compile("^\\s*<[^\\\\>]*>\\s*");

  private enum Direction {
    TB,
    BT,
    LR,
    RL
  }

  private enum NodeShape {
    ELLIPSE,
    BOX,
    ROUNDED_BOX,
    CIRCLE,
    DOUBLECIRCLE,
    DIAMOND,
    HEXAGON,
    CYLINDER,
    NOTE,
    FOLDER,
    COMPONENT,
    PLAINTEXT,
    RECORD,
    MRECORD
  }

  private enum LineAlign {
    CENTER,
    LEFT,
    RIGHT
  }

  private static class AlignedLine {
    final String text;
    final LineAlign align;

    AlignedLine(String text, LineAlign align) {
      this.text = text;
      this.align = align;
    }
  }

  private enum EdgeStroke {
    SOLID,
    DASHED,
    DOTTED,
    BOLD,
    INVIS
  }

  private static class Node extends DiagramLayoutEngine.BaseNode {
    String rawLabel;
    final List<AlignedLine> labelLines = new ArrayList<>();
    final List<String> recordCompartments = new ArrayList<>();
    NodeShape shape = NodeShape.ELLIPSE;
    boolean explicitFilled = false;
    boolean explicitRounded = false;
    boolean explicitDashed = false;
    boolean explicitDotted = false;
    @Nullable String customFill;
    @Nullable String customStroke;
    @Nullable String customFontColor;
    double fontSize = 11.5;
    double penWidth = 1.5;
    double marginX = 14.0;
    double marginY = 10.0;
    double minWidthPx = 0.0;
    double minHeightPx = 0.0;

    @Nullable Cluster parentCluster;
    boolean explicitlyPlacedInCluster = false;

    Node(String id) {
      super(id);
      this.width = 80;
      this.height = 40;
      setRawLabel(id);
    }

    @Override
    public boolean hasParentSubgraph() {
      return parentCluster != null;
    }

    void setRawLabel(String raw) {
      this.rawLabel = raw != null ? raw : "";
      this.labelLines.clear();
      this.recordCompartments.clear();
      if (this.shape == NodeShape.RECORD || this.shape == NodeShape.MRECORD) {
        parseRecordCompartments(this.rawLabel, this.recordCompartments);
        if (this.recordCompartments.isEmpty()) {
          this.recordCompartments.add("");
        }
        for (String comp : this.recordCompartments) {
          this.labelLines.add(new AlignedLine(comp, LineAlign.CENTER));
        }
      } else {
        parseAlignedLines(this.rawLabel, this.labelLines);
      }
    }

    void applyAttributes(Map<String, String> attrs) {
      if (attrs == null || attrs.isEmpty()) {
        return;
      }
      String shapeVal = getAttrIgnoreCase(attrs, "shape");
      if (shapeVal != null) {
        this.shape = parseNodeShape(shapeVal, this.explicitRounded);
      }

      String styleVal = getAttrIgnoreCase(attrs, "style");
      if (styleVal != null) {
        applyNodeStyle(styleVal);
      }

      String colorVal = getAttrIgnoreCase(attrs, "color");
      if (DiagramLayoutEngine.isValidCssColor(colorVal)) {
        this.customStroke = colorVal.trim();
      }

      String fillVal = getAttrIgnoreCase(attrs, "fillcolor");
      if (DiagramLayoutEngine.isValidCssColor(fillVal)) {
        this.customFill = fillVal.trim();
      }

      String bgVal = getAttrIgnoreCase(attrs, "bgcolor");
      if (DiagramLayoutEngine.isValidCssColor(bgVal) && this.customFill == null) {
        this.customFill = bgVal.trim();
      }

      String fontColorVal = getAttrIgnoreCase(attrs, "fontcolor");
      if (DiagramLayoutEngine.isValidCssColor(fontColorVal)) {
        this.customFontColor = fontColorVal.trim();
      }

      String fontSizeVal = getAttrIgnoreCase(attrs, "fontsize");
      if (fontSizeVal != null) {
        Double fs = tryParseDouble(fontSizeVal);
        if (fs != null && fs >= 6.0 && fs <= 36.0) {
          this.fontSize = fs;
        }
      }

      String penWidthVal = getAttrIgnoreCase(attrs, "penwidth");
      if (penWidthVal != null) {
        Double pw = tryParseDouble(penWidthVal);
        if (pw != null && pw >= 0.5 && pw <= 10.0) {
          this.penWidth = pw;
        }
      }

      String marginVal = getAttrIgnoreCase(attrs, "margin");
      if (marginVal != null) {
        parseNodeMargin(marginVal);
      }

      String widthVal = getAttrIgnoreCase(attrs, "width");
      if (widthVal != null) {
        Double wInches = tryParseDouble(widthVal);
        if (wInches != null && wInches > 0 && wInches <= 20.0) {
          this.minWidthPx = wInches * 72.0;
        }
      }

      String heightVal = getAttrIgnoreCase(attrs, "height");
      if (heightVal != null) {
        Double hInches = tryParseDouble(heightVal);
        if (hInches != null && hInches > 0 && hInches <= 20.0) {
          this.minHeightPx = hInches * 72.0;
        }
      }

      String labelVal = getAttrIgnoreCase(attrs, "label");
      if (labelVal != null) {
        setRawLabel(labelVal);
      } else {
        setRawLabel(this.rawLabel);
      }
    }

    private void applyNodeStyle(String styleVal) {
      for (String token : Splitter.on(',').trimResults().omitEmptyStrings().split(styleVal)) {
        String lower = Ascii.toLowerCase(token);
        switch (lower) {
          case "filled" -> this.explicitFilled = true;
          case "rounded" -> {
            this.explicitRounded = true;
            if (this.shape == NodeShape.BOX) {
              this.shape = NodeShape.ROUNDED_BOX;
            } else if (this.shape == NodeShape.RECORD) {
              this.shape = NodeShape.MRECORD;
            }
          }
          case "dashed" -> this.explicitDashed = true;
          case "dotted" -> this.explicitDotted = true;
          case "bold" -> {
            if (this.penWidth < 2.2) {
              this.penWidth = 2.4;
            }
          }
          default -> {}
        }
      }
    }

    private void parseNodeMargin(String marginVal) {
      List<String> parts = Splitter.on(',').trimResults().omitEmptyStrings().splitToList(marginVal);
      if (parts.size() == 1) {
        Double m = tryParseDouble(parts.get(0));
        if (m != null && m >= 0) {
          double px = m <= 2.0 ? m * 72.0 : m;
          this.marginX = Math.min(60.0, Math.max(6.0, px));
          this.marginY = Math.min(48.0, Math.max(4.0, px));
        }
      } else if (parts.size() >= 2) {
        Double mx = tryParseDouble(parts.get(0));
        Double my = tryParseDouble(parts.get(1));
        if (mx != null && mx >= 0) {
          double px = mx <= 2.0 ? mx * 72.0 : mx;
          this.marginX = Math.min(60.0, Math.max(6.0, px));
        }
        if (my != null && my >= 0) {
          double py = my <= 2.0 ? my * 72.0 : my;
          this.marginY = Math.min(48.0, Math.max(4.0, py));
        }
      }
    }
  }

  private static class Edge extends DiagramLayoutEngine.BaseEdge<Node> {
    final List<AlignedLine> labelLines = new ArrayList<>();
    EdgeStroke stroke = EdgeStroke.SOLID;
    boolean arrowEnd;
    boolean arrowStart = false;
    @Nullable String customColor;
    @Nullable String customFontColor;
    double fontSize = 10.5;
    double penWidth = 1.5;
    @Nullable String ltail;
    @Nullable String lhead;

    Edge(String fromId, String toId, boolean directedDefault) {
      super(fromId, toId, null);
      this.arrowEnd = directedDefault;
    }

    void setLabel(@Nullable String rawLabel) {
      this.label = rawLabel;
      this.labelLines.clear();
      if (rawLabel != null && !rawLabel.trim().isEmpty()) {
        parseAlignedLines(rawLabel, this.labelLines);
      }
    }

    @Override
    public boolean hasLabel() {
      return !labelLines.isEmpty();
    }

    @Override
    public double getBadgeWidth() {
      return computeEdgeBadgeDimensions(this)[0];
    }

    @Override
    public double getBadgeHeight() {
      return computeEdgeBadgeDimensions(this)[1];
    }

    @Override
    public double getFirstVirtualNodeWidth() {
      return Math.max(getBadgeWidth() + 12, 60);
    }

    @Override
    public double getFirstVirtualNodeHeight() {
      return Math.max(getBadgeHeight() + 6, 22);
    }

    @Override
    public boolean appliesGapToLayer(int l, int srcLayer, int dstLayer) {
      int minL = Math.min(srcLayer, dstLayer);
      int maxL = Math.max(srcLayer, dstLayer);
      return minL <= l && l <= maxL;
    }

    @Override
    public double getVerticalLayerGap(double baseGap) {
      return hasLabel() ? Math.max(baseGap, getBadgeHeight() + 36.0) : baseGap;
    }

    @Override
    public double getHorizontalLayerGap(double baseGap) {
      return hasLabel() ? Math.max(baseGap, getBadgeWidth() + 32.0) : baseGap;
    }

    @Override
    public double getUnitVerticalGap() {
      return hasLabel() ? getBadgeHeight() + 38.0 : 0.0;
    }

    @Override
    public double getUnitHorizontalGap() {
      return hasLabel() ? getBadgeWidth() + 32.0 : 0.0;
    }

    @Override
    public boolean usesInclusiveUnitLayerSpan() {
      return true;
    }

    void applyAttributes(Map<String, String> attrs, boolean isDigraph) {
      if (attrs == null || attrs.isEmpty()) {
        return;
      }
      String labelVal = getAttrIgnoreCase(attrs, "label");
      if (labelVal != null) {
        setLabel(labelVal);
      } else {
        String xlabelVal = getAttrIgnoreCase(attrs, "xlabel");
        if (xlabelVal != null && this.label == null) {
          setLabel(xlabelVal);
        }
      }

      String colorVal = getAttrIgnoreCase(attrs, "color");
      if (DiagramLayoutEngine.isValidCssColor(colorVal)) {
        this.customColor = colorVal.trim();
      }

      String fontColorVal = getAttrIgnoreCase(attrs, "fontcolor");
      if (DiagramLayoutEngine.isValidCssColor(fontColorVal)) {
        this.customFontColor = fontColorVal.trim();
      }

      String fontSizeVal = getAttrIgnoreCase(attrs, "fontsize");
      if (fontSizeVal != null) {
        Double fs = tryParseDouble(fontSizeVal);
        if (fs != null && fs >= 6.0 && fs <= 30.0) {
          this.fontSize = fs;
        }
      }

      String penWidthVal = getAttrIgnoreCase(attrs, "penwidth");
      if (penWidthVal != null) {
        Double pw = tryParseDouble(penWidthVal);
        if (pw != null && pw >= 0.5 && pw <= 10.0) {
          this.penWidth = pw;
        }
      }

      String styleVal = getAttrIgnoreCase(attrs, "style");
      if (styleVal != null) {
        for (String token : Splitter.on(',').trimResults().omitEmptyStrings().split(styleVal)) {
          String lower = Ascii.toLowerCase(token);
          switch (lower) {
            case "dashed" -> this.stroke = EdgeStroke.DASHED;
            case "dotted" -> this.stroke = EdgeStroke.DOTTED;
            case "bold" -> {
              this.stroke = EdgeStroke.BOLD;
              if (this.penWidth < 2.2) {
                this.penWidth = 2.4;
              }
            }
            case "invis", "invisible" -> this.stroke = EdgeStroke.INVIS;
            case "solid" -> this.stroke = EdgeStroke.SOLID;
            default -> {}
          }
        }
      }

      String dirVal = getAttrIgnoreCase(attrs, "dir");
      if (dirVal != null) {
        String d = Ascii.toLowerCase(dirVal.trim());
        switch (d) {
          case "both" -> {
            this.arrowStart = true;
            this.arrowEnd = true;
          }
          case "back" -> {
            this.arrowStart = true;
            this.arrowEnd = false;
          }
          case "none" -> {
            this.arrowStart = false;
            this.arrowEnd = false;
          }
          case "forward" -> {
            this.arrowStart = false;
            this.arrowEnd = isDigraph;
          }
          default -> {}
        }
      }

      String arrowheadVal = getAttrIgnoreCase(attrs, "arrowhead");
      if (arrowheadVal != null && Ascii.toLowerCase(arrowheadVal.trim()).equals("none")) {
        this.arrowEnd = false;
      }

      String arrowtailVal = getAttrIgnoreCase(attrs, "arrowtail");
      if (arrowtailVal != null && !Ascii.toLowerCase(arrowtailVal.trim()).equals("none")) {
        this.arrowStart = true;
      }

      String ltailVal = getAttrIgnoreCase(attrs, "ltail");
      if (ltailVal != null && !ltailVal.trim().isEmpty()) {
        this.ltail = ltailVal.trim();
      }

      String lheadVal = getAttrIgnoreCase(attrs, "lhead");
      if (lheadVal != null && !lheadVal.trim().isEmpty()) {
        this.lhead = lheadVal.trim();
      }

      String constraintVal = getAttrIgnoreCase(attrs, "constraint");
      if (constraintVal != null && Ascii.toLowerCase(constraintVal.trim()).equals("false")) {
        this.constraint = false;
      }
    }
  }

  private static class Cluster extends DiagramLayoutEngine.BaseSubgraph<Node, Cluster> {
    final boolean isCluster;
    @Nullable Direction direction;
    boolean rounded = true;
    boolean dashed = false;
    boolean dotted = false;
    @Nullable String customFontColor;
    double fontSize = 12.0;
    double margin = 18.0;

    Cluster(String id, boolean isCluster, @Nullable Cluster parent) {
      super(id, null, parent);
      this.isCluster = isCluster;
    }

    @Override
    public boolean hasDirectionOverride() {
      return direction != null;
    }

    @Override
    public boolean isDirectionHorizontal() {
      return direction == Direction.LR || direction == Direction.RL;
    }

    @Override
    public boolean isDirectionReversed() {
      return direction == Direction.BT || direction == Direction.RL;
    }

    @Override
    public double getPadding() {
      return Math.max(18.0, margin);
    }

    @Override
    public double getHeaderHeight() {
      return title != null && !title.isEmpty() ? 30.0 : 12.0;
    }

    @Override
    public double getEmptyWidth() {
      return 110.0;
    }

    @Override
    public double getTitleMinWidth(double padding) {
      return title != null && !title.isEmpty() ? title.length() * 7.2 + padding * 2 : 0.0;
    }

    void applyAttributes(Map<String, String> attrs) {
      if (attrs == null || attrs.isEmpty()) {
        return;
      }
      String labelVal = getAttrIgnoreCase(attrs, "label");
      if (labelVal != null) {
        this.title =
            unescapeDotEscapes(
                labelVal.replace("\\n", " ").replace("\\l", " ").replace("\\r", " ").trim());
      }

      String rankdirVal = getAttrIgnoreCase(attrs, "rankdir");
      if (rankdirVal != null) {
        Direction dir = parseDirection(rankdirVal);
        if (dir != null) {
          this.direction = dir;
        }
      }

      String styleVal = getAttrIgnoreCase(attrs, "style");
      if (styleVal != null) {
        for (String token : Splitter.on(',').trimResults().omitEmptyStrings().split(styleVal)) {
          String lower = Ascii.toLowerCase(token);
          switch (lower) {
            case "rounded" -> this.rounded = true;
            case "dashed" -> this.dashed = true;
            case "dotted" -> this.dotted = true;
            default -> {}
          }
        }
      }

      String colorVal = getAttrIgnoreCase(attrs, "color");
      if (DiagramLayoutEngine.isValidCssColor(colorVal)) {
        this.customStroke = colorVal.trim();
      }

      String fillVal = getAttrIgnoreCase(attrs, "fillcolor");
      if (DiagramLayoutEngine.isValidCssColor(fillVal)) {
        this.customFill = fillVal.trim();
      }

      String bgVal = getAttrIgnoreCase(attrs, "bgcolor");
      if (DiagramLayoutEngine.isValidCssColor(bgVal)) {
        this.customFill = bgVal.trim();
      }

      String fontColorVal = getAttrIgnoreCase(attrs, "fontcolor");
      if (DiagramLayoutEngine.isValidCssColor(fontColorVal)) {
        this.customFontColor = fontColorVal.trim();
      }

      String fontSizeVal = getAttrIgnoreCase(attrs, "fontsize");
      if (fontSizeVal != null) {
        Double fs = tryParseDouble(fontSizeVal);
        if (fs != null && fs >= 8.0 && fs <= 30.0) {
          this.fontSize = fs;
        }
      }

      String marginVal = getAttrIgnoreCase(attrs, "margin");
      if (marginVal != null) {
        List<String> parts =
            Splitter.on(',').trimResults().omitEmptyStrings().splitToList(marginVal);
        if (!parts.isEmpty()) {
          Double m = tryParseDouble(parts.get(0));
          if (m != null && m >= 4.0 && m <= 60.0) {
            this.margin = m;
          }
        }
      }
    }
  }

  private static class DotGraph {
    boolean isStrict = false;
    boolean isDigraph = true;
    Direction direction = Direction.TB;
    double nodeSep = 32.0;
    double rankSep = 48.0;
    @Nullable String title;
    final Map<String, Node> nodes = new LinkedHashMap<>();
    final List<Edge> edges = new ArrayList<>();
    final List<Cluster> allClusters = new ArrayList<>();
    final Map<String, Cluster> clusterMap = new LinkedHashMap<>();
    final List<List<String>> rankSameGroups = new ArrayList<>();
    boolean exceededLimits = false;

    Node ensureNode(
        String rawId,
        Map<String, String> defaultNodeAttrs,
        @Nullable Cluster currentCluster,
        boolean isStandaloneNodeStmt) {
      String id = rawId.trim();
      Node existing = nodes.get(id);
      if (existing != null) {
        if (isStandaloneNodeStmt
            && currentCluster != null
            && currentCluster.isCluster
            && existing.parentCluster == null
            && !existing.explicitlyPlacedInCluster) {
          existing.parentCluster = currentCluster;
          existing.explicitlyPlacedInCluster = true;
          if (!currentCluster.nodes.contains(existing)) {
            currentCluster.nodes.add(existing);
          }
        }
        return existing;
      }

      if (nodes.size() >= MAX_NODES) {
        exceededLimits = true;
        return new Node(id);
      }

      Node created = new Node(id);
      if (defaultNodeAttrs != null && !defaultNodeAttrs.isEmpty()) {
        created.applyAttributes(defaultNodeAttrs);
      }
      if (currentCluster != null && currentCluster.isCluster) {
        created.parentCluster = currentCluster;
        created.explicitlyPlacedInCluster = true;
        currentCluster.nodes.add(created);
      }
      nodes.put(id, created);
      return created;
    }

    void applyGraphAttributes(Map<String, String> attrs) {
      if (attrs == null || attrs.isEmpty()) {
        return;
      }
      String rankdirVal = getAttrIgnoreCase(attrs, "rankdir");
      if (rankdirVal != null) {
        Direction dir = parseDirection(rankdirVal);
        if (dir != null) {
          this.direction = dir;
        }
      }
      String nodesepVal = getAttrIgnoreCase(attrs, "nodesep");
      if (nodesepVal != null) {
        Double ns = tryParseDouble(nodesepVal);
        if (ns != null && ns > 0) {
          this.nodeSep = Math.min(120.0, Math.max(16.0, ns * 64.0));
        }
      }
      String ranksepVal = getAttrIgnoreCase(attrs, "ranksep");
      if (ranksepVal != null) {
        Double rs = tryParseDouble(ranksepVal);
        if (rs != null && rs > 0) {
          this.rankSep = Math.min(160.0, Math.max(28.0, rs * 64.0));
        }
      }
      String labelVal = getAttrIgnoreCase(attrs, "label");
      if (labelVal != null && !labelVal.trim().isEmpty()) {
        this.title = labelVal.trim();
      }
    }
  }

  /**
   * Renders the given Graphviz DOT source into an inline SVG string, or returns {@link
   * Optional#empty()} if the input is invalid, unsupported, or exceeds resource limits.
   */
  public static Optional<String> renderToSvg(String dotSource) {
    if (isNullOrEmpty(dotSource) || dotSource.length() > MAX_INPUT_BYTES) {
      return Optional.empty();
    }
    try {
      DotGraph graph = parseDot(dotSource);
      if (graph == null
          || graph.exceededLimits
          || graph.nodes.isEmpty()
          || graph.nodes.size() > MAX_NODES
          || graph.edges.size() > MAX_EDGES) {
        return Optional.empty();
      }
      if (graph.isStrict) {
        collapseStrictEdges(graph);
      }
      return Optional.of(layoutAndRenderSvg(graph));
    } catch (Exception e) {
      return Optional.empty();
    }
  }

  private static void collapseStrictEdges(DotGraph graph) {
    Map<String, Edge> merged = new LinkedHashMap<>();
    for (Edge e : graph.edges) {
      String key;
      if (graph.isDigraph) {
        key = e.fromId + "\0->\0" + e.toId;
      } else {
        String a = e.fromId.compareTo(e.toId) <= 0 ? e.fromId : e.toId;
        String b = e.fromId.compareTo(e.toId) <= 0 ? e.toId : e.fromId;
        key = a + "\0--\0" + b;
      }
      Edge existing = merged.get(key);
      if (existing == null) {
        merged.put(key, e);
      } else {
        if (e.label != null && !e.label.isEmpty()) {
          existing.setLabel(e.label);
        }
        if (e.customColor != null) {
          existing.customColor = e.customColor;
        }
        if (e.customFontColor != null) {
          existing.customFontColor = e.customFontColor;
        }
        if (e.stroke != EdgeStroke.SOLID) {
          existing.stroke = e.stroke;
        }
        if (e.ltail != null) {
          existing.ltail = e.ltail;
        }
        if (e.lhead != null) {
          existing.lhead = e.lhead;
        }
      }
    }
    graph.edges.clear();
    graph.edges.addAll(merged.values());
  }

  // =========================================================================
  // Stage A: Lexer & Recursive-Descent Parser
  // =========================================================================

  private enum TokenType {
    ID,
    EDGE_OP,
    PUNCT
  }

  private static class Token {
    final TokenType type;
    final String value;

    Token(TokenType type, String value) {
      this.type = type;
      this.value = value;
    }
  }

  @Nullable
  private static List<Token> tokenize(String src) {
    List<Token> rawTokens = new ArrayList<>();
    int i = 0;
    int n = src.length();
    boolean atLineStart = true;

    while (i < n) {
      char c = src.charAt(i);

      if (c == '\n' || c == '\r') {
        atLineStart = true;
        i++;
        continue;
      }
      if (Character.isWhitespace(c)) {
        i++;
        continue;
      }

      // Single-line comment //
      if (c == '/' && i + 1 < n && src.charAt(i + 1) == '/') {
        i += 2;
        while (i < n && src.charAt(i) != '\n' && src.charAt(i) != '\r') {
          i++;
        }
        continue;
      }

      // Multi-line comment /* ... */
      if (c == '/' && i + 1 < n && src.charAt(i + 1) == '*') {
        i += 2;
        boolean closed = false;
        while (i + 1 < n) {
          if (src.charAt(i) == '*' && src.charAt(i + 1) == '/') {
            i += 2;
            closed = true;
            break;
          }
          i++;
        }
        if (!closed) {
          return null;
        }
        continue;
      }

      // Preprocessor line # at start of line
      if (c == '#' && atLineStart) {
        while (i < n && src.charAt(i) != '\n' && src.charAt(i) != '\r') {
          i++;
        }
        continue;
      }

      atLineStart = false;

      // Reject HTML-like labels <...>
      if (c == '<') {
        return null;
      }

      // Edge operators -> and --
      if (c == '-' && i + 1 < n && (src.charAt(i + 1) == '>' || src.charAt(i + 1) == '-')) {
        rawTokens.add(new Token(TokenType.EDGE_OP, src.substring(i, i + 2)));
        i += 2;
        continue;
      }

      // Punctuation
      if (c == '{'
          || c == '}'
          || c == '['
          || c == ']'
          || c == '='
          || c == ';'
          || c == ','
          || c == ':'
          || c == '+') {
        rawTokens.add(new Token(TokenType.PUNCT, String.valueOf(c)));
        i++;
        continue;
      }

      // Double-quoted string "..."
      if (c == '"') {
        i++;
        StringBuilder sb = new StringBuilder();
        boolean closed = false;
        while (i < n) {
          char ch = src.charAt(i);
          if (ch == '\\' && i + 1 < n) {
            char next = src.charAt(i + 1);
            switch (next) {
              case '"' -> {
                sb.append('"');
                i += 2;
              }
              case '\\' -> {
                sb.append("\\\\");
                i += 2;
              }
              case 'n' -> {
                sb.append("\\n");
                i += 2;
              }
              case 'l' -> {
                sb.append("\\l");
                i += 2;
              }
              case 'r' -> {
                sb.append("\\r");
                i += 2;
              }
              case 'N', 'G', 'E', 'T', 'H' -> {
                sb.append('\\').append(next);
                i += 2;
              }
              case '\n' -> i += 2;
              case '\r' -> {
                i += 2;
                if (i < n && src.charAt(i) == '\n') {
                  i++;
                }
              }
              default -> {
                sb.append('\\').append(next);
                i += 2;
              }
            }
          } else if (ch == '"') {
            i++;
            closed = true;
            break;
          } else {
            sb.append(ch);
            i++;
          }
        }
        if (!closed) {
          return null;
        }
        rawTokens.add(new Token(TokenType.ID, sb.toString()));
        continue;
      }

      // Bare identifier / numeral
      if (isIdChar(c)) {
        int start = i;
        while (i < n && isIdChar(src.charAt(i))) {
          if (src.charAt(i) == '-'
              && i + 1 < n
              && (src.charAt(i + 1) == '>' || src.charAt(i + 1) == '-')) {
            break;
          }
          i++;
        }
        rawTokens.add(new Token(TokenType.ID, src.substring(start, i)));
        continue;
      }

      // Unexpected character
      return null;
    }

    // Fold string concatenation: ID + ID -> ID
    List<Token> folded = new ArrayList<>(rawTokens.size());
    for (int idx = 0; idx < rawTokens.size(); idx++) {
      Token cur = rawTokens.get(idx);
      if (cur.type == TokenType.ID) {
        StringBuilder combined = new StringBuilder(cur.value);
        while (idx + 2 < rawTokens.size()
            && rawTokens.get(idx + 1).type == TokenType.PUNCT
            && rawTokens.get(idx + 1).value.equals("+")
            && rawTokens.get(idx + 2).type == TokenType.ID) {
          combined.append(rawTokens.get(idx + 2).value);
          idx += 2;
        }
        folded.add(new Token(TokenType.ID, combined.toString()));
      } else if (cur.type == TokenType.PUNCT && cur.value.equals("+")) {
        // Ignore stray '+' if not between IDs
      } else {
        folded.add(cur);
      }
    }
    return folded;
  }

  private static boolean isIdChar(char c) {
    return (c >= 'a' && c <= 'z')
        || (c >= 'A' && c <= 'Z')
        || (c >= '0' && c <= '9')
        || c == '_'
        || c == '.'
        || c == '-'
        || c == '#'
        || c == '/'
        || c == '%'
        || c >= 0x80;
  }

  private static class ScopeState {
    final Map<String, String> graphAttrs = new LinkedHashMap<>();
    final Map<String, String> nodeAttrs = new LinkedHashMap<>();
    final Map<String, String> edgeAttrs = new LinkedHashMap<>();
    @Nullable final Cluster cluster;
    final int depth;

    ScopeState(@Nullable Cluster cluster, int depth) {
      this.cluster = cluster;
      this.depth = depth;
    }

    ScopeState childScope(@Nullable Cluster childCluster) {
      ScopeState child = new ScopeState(childCluster, this.depth + 1);
      child.graphAttrs.putAll(this.graphAttrs);
      child.nodeAttrs.putAll(this.nodeAttrs);
      child.edgeAttrs.putAll(this.edgeAttrs);
      return child;
    }
  }

  private static class DotParser {
    private final List<Token> tokens;
    private int pos = 0;
    private final DotGraph graph = new DotGraph();
    private boolean invalid = false;
    private int anonClusterCounter = 0;

    DotParser(List<Token> tokens) {
      this.tokens = tokens;
    }

    @Nullable
    private Token peek() {
      return pos < tokens.size() ? tokens.get(pos) : null;
    }

    @Nullable
    private Token peekAt(int offset) {
      int idx = pos + offset;
      return idx >= 0 && idx < tokens.size() ? tokens.get(idx) : null;
    }

    private Token consume() {
      return tokens.get(pos++);
    }

    private boolean matchPunct(char ch) {
      Token t = peek();
      if (t != null
          && t.type == TokenType.PUNCT
          && t.value.length() == 1
          && t.value.charAt(0) == ch) {
        pos++;
        return true;
      }
      return false;
    }

    @Nullable
    private String peekEdgeOp() {
      Token t = peek();
      if (t != null && t.type == TokenType.EDGE_OP) {
        return t.value;
      }
      return null;
    }

    @Nullable
    DotGraph parseGraph() {
      Token t = peek();
      if (t == null) {
        return null;
      }
      if (t.type == TokenType.ID && Ascii.equalsIgnoreCase(t.value, "strict")) {
        graph.isStrict = true;
        consume();
        t = peek();
      }
      if (t == null || t.type != TokenType.ID) {
        return null;
      }
      if (Ascii.equalsIgnoreCase(t.value, "digraph")) {
        graph.isDigraph = true;
        consume();
      } else if (Ascii.equalsIgnoreCase(t.value, "graph")) {
        graph.isDigraph = false;
        consume();
      } else {
        return null;
      }

      // Optional graph ID
      Token maybeId = peek();
      if (maybeId != null && maybeId.type == TokenType.ID) {
        consume();
      }

      if (!matchPunct('{')) {
        return null;
      }

      ScopeState rootScope = new ScopeState(null, 0);
      parseStmtList(rootScope, null);

      if (!matchPunct('}')) {
        return null;
      }

      if (invalid || graph.exceededLimits) {
        return null;
      }
      graph.applyGraphAttributes(rootScope.graphAttrs);
      return graph;
    }

    private void parseStmtList(ScopeState scope, @Nullable List<String> collector) {
      while (peek() != null && !invalid && !graph.exceededLimits) {
        Token t = peek();
        if (t.type == TokenType.PUNCT && t.value.equals("}")) {
          break;
        }
        int before = pos;
        parseStatement(scope, collector);
        while (matchPunct(';') || matchPunct(',')) {
          // Consume statement separators
        }
        if (pos == before) {
          invalid = true;
          break;
        }
      }
    }

    private void parseStatement(ScopeState scope, @Nullable List<String> collector) {
      Token t = peek();
      if (t == null) {
        return;
      }

      // 1. Default attribute statements: graph [...], node [...], edge [...]
      if (t.type == TokenType.ID) {
        Token next = peekAt(1);
        if (next != null && next.type == TokenType.PUNCT && next.value.equals("[")) {
          if (Ascii.equalsIgnoreCase(t.value, "graph")) {
            consume();
            Map<String, String> attrs = parseAttrList();
            scope.graphAttrs.putAll(attrs);
            if (scope.cluster != null) {
              scope.cluster.applyAttributes(attrs);
            } else {
              graph.applyGraphAttributes(attrs);
            }
            return;
          } else if (Ascii.equalsIgnoreCase(t.value, "node")) {
            consume();
            Map<String, String> attrs = parseAttrList();
            scope.nodeAttrs.putAll(attrs);
            return;
          } else if (Ascii.equalsIgnoreCase(t.value, "edge")) {
            consume();
            Map<String, String> attrs = parseAttrList();
            scope.edgeAttrs.putAll(attrs);
            return;
          }
        }
      }

      // 2. Top-level / scope key = value assignment (e.g. rankdir=LR; label="Title"; rank=same;)
      if (t.type == TokenType.ID) {
        Token next = peekAt(1);
        if (next != null && next.type == TokenType.PUNCT && next.value.equals("=")) {
          String key = consume().value;
          consume(); // '='
          Token valTok = peek();
          if (valTok == null || valTok.type != TokenType.ID) {
            invalid = true;
            return;
          }
          String val = consume().value;
          scope.graphAttrs.put(key, val);
          if (scope.cluster != null) {
            scope.cluster.applyAttributes(Collections.singletonMap(key, val));
          } else {
            graph.applyGraphAttributes(Collections.singletonMap(key, val));
          }
          return;
        }
      }

      // 3. Endpoint group (single node, subgraph, or anonymous { ... } group), possibly followed by
      // edge chain
      boolean startsAsBareNode =
          t.type == TokenType.ID && !Ascii.equalsIgnoreCase(t.value, "subgraph");
      List<String> lhs = parseEndpointGroup(scope, collector);
      if (invalid || lhs.isEmpty()) {
        return;
      }

      String op = peekEdgeOp();
      if (op != null) {
        List<List<String>> chainGroups = new ArrayList<>();
        chainGroups.add(lhs);
        while ((op = peekEdgeOp()) != null) {
          consume(); // edge op
          if ((graph.isDigraph && !op.equals("->")) || (!graph.isDigraph && !op.equals("--"))) {
            invalid = true;
            return;
          }
          List<String> rhs = parseEndpointGroup(scope, collector);
          if (invalid || rhs.isEmpty()) {
            invalid = true;
            return;
          }
          chainGroups.add(rhs);
        }

        Map<String, String> edgeAttrs = parseAttrList();
        Map<String, String> combinedEdgeAttrs = new LinkedHashMap<>(scope.edgeAttrs);
        combinedEdgeAttrs.putAll(edgeAttrs);

        for (int i = 0; i < chainGroups.size() - 1; i++) {
          List<String> fromGroup = chainGroups.get(i);
          List<String> toGroup = chainGroups.get(i + 1);
          for (String u : fromGroup) {
            for (String v : toGroup) {
              if (graph.edges.size() >= MAX_EDGES) {
                graph.exceededLimits = true;
                return;
              }
              Edge edge = new Edge(u, v, graph.isDigraph);
              edge.applyAttributes(combinedEdgeAttrs, graph.isDigraph);
              graph.edges.add(edge);
            }
          }
        }
      } else if (startsAsBareNode && lhs.size() == 1) {
        Node node =
            graph.ensureNode(
                lhs.get(0), scope.nodeAttrs, scope.cluster, /* isStandaloneNodeStmt= */ true);
        Map<String, String> nodeAttrs = parseAttrList();
        if (!nodeAttrs.isEmpty()) {
          node.applyAttributes(nodeAttrs);
          if (node.rawLabel != null && node.rawLabel.contains("\\N")) {
            node.setRawLabel(node.rawLabel.replace("\\N", node.id));
          }
        }
      }
    }

    private List<String> parseEndpointGroup(ScopeState scope, @Nullable List<String> collector) {
      Token t = peek();
      if (t == null) {
        return new ArrayList<>();
      }

      // Subgraph or anonymous block: [subgraph [ID]] { ... }
      if ((t.type == TokenType.ID && Ascii.equalsIgnoreCase(t.value, "subgraph"))
          || (t.type == TokenType.PUNCT && t.value.equals("{"))) {
        return parseSubgraph(scope, collector);
      }

      // Single node reference: ID [:port[:compass]]
      if (t.type == TokenType.ID) {
        String nodeId = parseNodeRef();
        if (nodeId == null) {
          return new ArrayList<>();
        }
        graph.ensureNode(nodeId, scope.nodeAttrs, scope.cluster, /* isStandaloneNodeStmt= */ false);
        if (collector != null && !collector.contains(nodeId)) {
          collector.add(nodeId);
        }
        List<String> single = new ArrayList<>(1);
        single.add(nodeId);
        return single;
      }

      invalid = true;
      return new ArrayList<>();
    }

    private List<String> parseSubgraph(
        ScopeState parentScope, @Nullable List<String> outerCollector) {
      if (parentScope.depth >= MAX_SUBGRAPH_DEPTH) {
        graph.exceededLimits = true;
        return new ArrayList<>();
      }

      String sgId = null;
      Token t = peek();
      if (t != null && t.type == TokenType.ID && Ascii.equalsIgnoreCase(t.value, "subgraph")) {
        consume();
        Token maybeId = peek();
        if (maybeId != null && maybeId.type == TokenType.ID) {
          sgId = consume().value;
        }
      }

      if (sgId == null) {
        anonClusterCounter++;
        sgId = "__anon_" + anonClusterCounter;
      }

      boolean isCluster = Ascii.toLowerCase(sgId).startsWith("cluster");
      Cluster targetCluster = parentScope.cluster;
      if (isCluster) {
        Cluster existing = graph.clusterMap.get(sgId);
        if (existing != null) {
          targetCluster = existing;
        } else {
          targetCluster = new Cluster(sgId, true, parentScope.cluster);
          if (parentScope.cluster != null) {
            parentScope.cluster.children.add(targetCluster);
          }
          graph.allClusters.add(targetCluster);
          graph.clusterMap.put(sgId, targetCluster);
        }
      }

      if (!matchPunct('{')) {
        // Subgraph reference without body: subgraph cluster_x;
        Cluster existing = graph.clusterMap.get(sgId);
        if (existing != null) {
          List<String> members = new ArrayList<>();
          collectClusterNodeIds(existing, members);
          return members;
        }
        return new ArrayList<>();
      }

      ScopeState childScope = parentScope.childScope(targetCluster);
      List<String> subMembers = new ArrayList<>();
      parseStmtList(childScope, subMembers);

      if (!matchPunct('}')) {
        invalid = true;
        return new ArrayList<>();
      }

      String rankAttr = getAttrIgnoreCase(childScope.graphAttrs, "rank");
      if (rankAttr != null) {
        String rLower = Ascii.toLowerCase(rankAttr.trim());
        if ((rLower.equals("same")
                || rLower.equals("min")
                || rLower.equals("max")
                || rLower.equals("source")
                || rLower.equals("sink"))
            && subMembers.size() > 1) {
          graph.rankSameGroups.add(new ArrayList<>(subMembers));
        }
      }

      if (outerCollector != null) {
        for (String m : subMembers) {
          if (!outerCollector.contains(m)) {
            outerCollector.add(m);
          }
        }
      }
      return subMembers;
    }

    @Nullable
    private String parseNodeRef() {
      Token t = peek();
      if (t == null || t.type != TokenType.ID) {
        return null;
      }
      String id = consume().value;
      while (matchPunct(':')) {
        Token portOrCompass = peek();
        if (portOrCompass != null && portOrCompass.type == TokenType.ID) {
          consume();
        }
      }
      return id;
    }

    private Map<String, String> parseAttrList() {
      Map<String, String> attrs = new LinkedHashMap<>();
      while (matchPunct('[')) {
        while (peek() != null && !matchPunct(']')) {
          Token keyTok = peek();
          if (keyTok == null || keyTok.type != TokenType.ID) {
            invalid = true;
            return attrs;
          }
          String key = consume().value;
          if (matchPunct('=')) {
            Token valTok = peek();
            if (valTok == null || valTok.type != TokenType.ID) {
              invalid = true;
              return attrs;
            }
            String val = consume().value;
            attrs.put(key, val);
          } else {
            attrs.put(key, "true");
          }
          while (matchPunct(',') || matchPunct(';')) {
            // Consume attribute separators
          }
        }
      }
      return attrs;
    }
  }

  private static void collectClusterNodeIds(Cluster c, List<String> out) {
    for (Node n : c.nodes) {
      if (!out.contains(n.id)) {
        out.add(n.id);
      }
    }
    for (Cluster child : c.children) {
      collectClusterNodeIds(child, out);
    }
  }

  @Nullable
  private static DotGraph parseDot(String dotSource) {
    List<Token> tokens = tokenize(dotSource);
    if (tokens == null || tokens.isEmpty()) {
      return null;
    }
    DotParser parser = new DotParser(tokens);
    return parser.parseGraph();
  }

  // =========================================================================
  // Label & Attribute Parsing Helpers
  // =========================================================================

  private static void parseAlignedLines(String raw, List<AlignedLine> out) {
    out.clear();
    if (raw == null || raw.isEmpty()) {
      out.add(new AlignedLine("", LineAlign.CENTER));
      return;
    }
    StringBuilder cur = new StringBuilder();
    int i = 0;
    int n = raw.length();
    while (i < n) {
      char c = raw.charAt(i);
      if (c == '\\' && i + 1 < n) {
        char next = raw.charAt(i + 1);
        if (next == 'n') {
          out.add(new AlignedLine(cur.toString(), LineAlign.CENTER));
          cur.setLength(0);
          i += 2;
          continue;
        } else if (next == 'l') {
          out.add(new AlignedLine(cur.toString(), LineAlign.LEFT));
          cur.setLength(0);
          i += 2;
          continue;
        } else if (next == 'r') {
          out.add(new AlignedLine(cur.toString(), LineAlign.RIGHT));
          cur.setLength(0);
          i += 2;
          continue;
        } else if (next == '\\') {
          cur.append('\\');
          i += 2;
          continue;
        } else if (next == '"') {
          cur.append('"');
          i += 2;
          continue;
        } else {
          cur.append(next);
          i += 2;
          continue;
        }
      } else if (c == '\n') {
        out.add(new AlignedLine(cur.toString(), LineAlign.CENTER));
        cur.setLength(0);
        i++;
        continue;
      } else if (c == '\r') {
        i++;
        continue;
      }
      cur.append(c);
      i++;
    }
    if (cur.length() > 0 || out.isEmpty()) {
      out.add(new AlignedLine(cur.toString(), LineAlign.CENTER));
    }
  }

  private static void parseRecordCompartments(String raw, List<String> out) {
    out.clear();
    if (raw == null) {
      return;
    }
    String s = raw.trim();
    while (s.startsWith("{") && s.endsWith("}") && s.length() >= 2) {
      s = s.substring(1, s.length() - 1).trim();
    }
    StringBuilder cur = new StringBuilder();
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c == '\\' && i + 1 < s.length()) {
        cur.append('\\').append(s.charAt(i + 1));
        i++;
      } else if (c == '|') {
        out.add(cleanRecordField(cur.toString()));
        cur.setLength(0);
      } else if (c == '{' || c == '}') {
        // Flatten nested record braces
      } else {
        cur.append(c);
      }
    }
    out.add(cleanRecordField(cur.toString()));
  }

  private static String cleanRecordField(String field) {
    String stripped = PORT_TAG_PATTERN.matcher(field).replaceAll("");
    String lineBreaksReplaced =
        stripped.replace("\\n", " ").replace("\\l", " ").replace("\\r", " ");
    return unescapeDotEscapes(lineBreaksReplaced).trim();
  }

  private static String unescapeDotEscapes(String s) {
    if (s == null || s.indexOf('\\') == -1) {
      return s;
    }
    StringBuilder sb = new StringBuilder(s.length());
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c == '\\' && i + 1 < s.length()) {
        sb.append(s.charAt(i + 1));
        i++;
      } else {
        sb.append(c);
      }
    }
    return sb.toString();
  }

  private static NodeShape parseNodeShape(String shapeVal, boolean alreadyRounded) {
    String s = Ascii.toLowerCase(shapeVal.trim());
    return switch (s) {
      case "box", "rect", "rectangle", "square" ->
          alreadyRounded ? NodeShape.ROUNDED_BOX : NodeShape.BOX;
      case "ellipse", "oval" -> NodeShape.ELLIPSE;
      case "circle", "point" -> NodeShape.CIRCLE;
      case "doublecircle" -> NodeShape.DOUBLECIRCLE;
      case "diamond" -> NodeShape.DIAMOND;
      case "hexagon", "polygon" -> NodeShape.HEXAGON;
      case "cylinder" -> NodeShape.CYLINDER;
      case "note" -> NodeShape.NOTE;
      case "folder", "tab" -> NodeShape.FOLDER;
      case "component" -> NodeShape.COMPONENT;
      case "plaintext", "plain", "none" -> NodeShape.PLAINTEXT;
      case "record" -> alreadyRounded ? NodeShape.MRECORD : NodeShape.RECORD;
      case "mrecord" -> NodeShape.MRECORD;
      default -> alreadyRounded ? NodeShape.ROUNDED_BOX : NodeShape.BOX;
    };
  }

  @Nullable
  private static Direction parseDirection(String val) {
    if (val == null) {
      return null;
    }
    return switch (Ascii.toUpperCase(val.trim())) {
      case "TB", "TD" -> Direction.TB;
      case "BT" -> Direction.BT;
      case "LR" -> Direction.LR;
      case "RL" -> Direction.RL;
      default -> null;
    };
  }

  @Nullable
  private static String getAttrIgnoreCase(Map<String, String> attrs, String targetKey) {
    String exact = attrs.get(targetKey);
    if (exact != null) {
      return exact;
    }
    for (Map.Entry<String, String> entry : attrs.entrySet()) {
      if (Ascii.equalsIgnoreCase(entry.getKey(), targetKey)) {
        return entry.getValue();
      }
    }
    return null;
  }

  @Nullable
  private static Double tryParseDouble(String val) {
    if (val == null) {
      return null;
    }
    try {
      return Double.parseDouble(val.trim());
    } catch (NumberFormatException e) {
      return null;
    }
  }

  // =========================================================================
  // Stage B: Sugiyama Hierarchical DAG & Compound Cluster Layout (Delegated)
  // =========================================================================

  private static void computeNodeDimensions(DotGraph graph) {
    for (Node n : graph.nodes.values()) {
      double charW = n.fontSize * 0.62;
      double lineH = Math.max(15.0, n.fontSize * 1.42);

      if (n.shape == NodeShape.RECORD || n.shape == NodeShape.MRECORD) {
        double totalW = 0;
        for (String comp : n.recordCompartments) {
          totalW += Math.max(48.0, comp.length() * charW + 20.0);
        }
        n.width = Math.max(n.minWidthPx, Math.max(80.0, totalW));
        n.height = Math.max(n.minHeightPx, Math.max(38.0, lineH + n.marginY * 2 + 4));
        continue;
      }

      int maxLineLen = 0;
      for (AlignedLine al : n.labelLines) {
        maxLineLen = Math.max(maxLineLen, al.text.length());
      }
      int numLines = Math.max(1, n.labelLines.size());
      double textW = maxLineLen * charW;
      double textH = numLines * lineH;

      switch (n.shape) {
        case DIAMOND -> {
          n.width = Math.max(n.minWidthPx, Math.max(92.0, textW * 1.55 + n.marginX * 2 + 18));
          n.height =
              Math.max(
                  n.minHeightPx,
                  Math.max(52.0, Math.max(textH * 1.9 + n.marginY * 2 + 12, n.width * 0.58)));
        }
        case CIRCLE -> {
          double d = Math.max(52.0, Math.max(textW + 22, textH + 22));
          d = Math.max(d, Math.max(n.minWidthPx, n.minHeightPx));
          n.width = d;
          n.height = d;
        }
        case DOUBLECIRCLE -> {
          double d = Math.max(60.0, Math.max(textW + 30, textH + 30));
          d = Math.max(d, Math.max(n.minWidthPx, n.minHeightPx));
          n.width = d;
          n.height = d;
        }
        case HEXAGON -> {
          n.width = Math.max(n.minWidthPx, Math.max(88.0, textW + n.marginX * 2 + 32));
          n.height = Math.max(n.minHeightPx, Math.max(42.0, textH + n.marginY * 2 + 8));
        }
        case CYLINDER -> {
          n.width = Math.max(n.minWidthPx, Math.max(82.0, textW + n.marginX * 2 + 12));
          n.height = Math.max(n.minHeightPx, Math.max(50.0, textH + n.marginY * 2 + 18));
        }
        case COMPONENT, FOLDER, NOTE -> {
          n.width = Math.max(n.minWidthPx, Math.max(84.0, textW + n.marginX * 2 + 18));
          n.height = Math.max(n.minHeightPx, Math.max(42.0, textH + n.marginY * 2 + 8));
        }
        case ELLIPSE -> {
          n.width = Math.max(n.minWidthPx, Math.max(76.0, textW * 1.18 + n.marginX * 2 + 12));
          n.height = Math.max(n.minHeightPx, Math.max(40.0, textH * 1.18 + n.marginY * 2 + 6));
        }
        case PLAINTEXT -> {
          n.width = Math.max(n.minWidthPx, Math.max(54.0, textW + 16));
          n.height = Math.max(n.minHeightPx, Math.max(30.0, textH + 10));
        }
        default -> {
          n.width = Math.max(n.minWidthPx, Math.max(76.0, textW + n.marginX * 2 + 6));
          n.height = Math.max(n.minHeightPx, Math.max(38.0, textH + n.marginY * 2));
        }
      }
    }
  }

  private static String layoutAndRenderSvg(DotGraph graph) {
    boolean isHorizontal = graph.direction == Direction.LR || graph.direction == Direction.RL;
    boolean isReversed = graph.direction == Direction.BT || graph.direction == Direction.RL;
    computeNodeDimensions(graph);

    // 1. Partition into connected components
    Map<String, String> ufParent =
        DiagramLayoutEngine.initUnionFind(graph.nodes, graph.edges, graph.allClusters);
    for (List<String> rankGroup : graph.rankSameGroups) {
      if (rankGroup.size() > 1) {
        String first = rankGroup.get(0);
        for (int i = 1; i < rankGroup.size(); i++) {
          String other = rankGroup.get(i);
          if (ufParent.containsKey(first) && ufParent.containsKey(other)) {
            DiagramLayoutEngine.unionSets(ufParent, first, other);
          }
        }
      }
    }

    List<DiagramLayoutEngine.GraphComponent<Node, Edge, Cluster>> components =
        DiagramLayoutEngine.buildComponents(
            graph.nodes, graph.edges, graph.allClusters, ufParent);
    DiagramLayoutEngine.SugiyamaConfig config =
        DiagramLayoutEngine.SugiyamaConfig.forDot(
            graph.nodeSep, graph.rankSep, graph.rankSameGroups);

    for (DiagramLayoutEngine.GraphComponent<Node, Edge, Cluster> comp : components) {
      if (!comp.subgraphs.isEmpty()) {
        DiagramLayoutEngine.layoutCompoundComponent(
            config, isHorizontal, isReversed, comp, Collections.emptyList());
      } else {
        DiagramLayoutEngine.layoutBySugiyamaDag(
            config, isHorizontal, isReversed, comp.nodes, comp.edges, Node::new);
      }
      DiagramLayoutEngine.normalizeComponentBounds(comp);
    }

    DiagramLayoutEngine.stackComponents(components, isHorizontal, 48.0);

    // Compute overall bounding box including nodes, clusters, virtual nodes, loops, and badges
    double[] bounds =
        DiagramLayoutEngine.computeGraphBounds(
            graph.nodes.values(), graph.allClusters, graph.edges);
    double minX = bounds[0];
    double minY = bounds[1];
    double maxX = bounds[2];
    double maxY = bounds[3];

    // Expand bounds for self-loops and back-edges
    for (Edge e : graph.edges) {
      if (e.stroke == EdgeStroke.INVIS) {
        continue;
      }
      Node src = graph.nodes.get(e.fromId);
      Node dst = graph.nodes.get(e.toId);
      if (src == null || dst == null) {
        continue;
      }
      double[] badgeDim = computeEdgeBadgeDimensions(e);
      if (src.id.equals(dst.id)) {
        if (!isHorizontal) {
          maxX = Math.max(maxX, src.x + src.width + 48 + badgeDim[0]);
        } else {
          minY = Math.min(minY, src.y - 48 - badgeDim[1]);
        }
      } else if (e.isBackEdge) {
        if (!isHorizontal) {
          double maxRight = Math.max(src.x + src.width, dst.x + dst.width) + 48 + badgeDim[0];
          maxX = Math.max(maxX, maxRight);
        } else {
          double minTop = Math.min(src.y, dst.y) - 48 - badgeDim[1];
          minY = Math.min(minY, minTop);
        }
      }
    }

    double titleOffset = graph.title != null && !graph.title.isEmpty() ? 28.0 : 0.0;
    double padding = 28.0;
    double offsetX = padding - minX;
    double offsetY = padding - minY;

    DiagramLayoutEngine.translateGraph(
        graph.nodes.values(), graph.allClusters, graph.edges, offsetX, offsetY);

    double totalWidth = Math.max(120.0, (maxX - minX) + padding * 2);
    double totalHeight = Math.max(80.0, (maxY - minY) + padding * 2 + titleOffset);

    return renderSvg(graph, totalWidth, totalHeight);
  }

  // =========================================================================
  // Stage C: Sanitized SVG & Theme Emitter
  // =========================================================================

  private static String renderSvg(DotGraph graph, double width, double height) {
    StringBuilder svg = new StringBuilder(4096);
    DiagramLayoutEngine.appendSvgHeaderAndDefs(
        svg,
        "graphviz-svg",
        width,
        height,
        "graphviz-arrow",
        "graphviz-arrow-head",
        "#656d76",
        "graphviz-shadow",
        "graphviz-shadow");

    // 1. Render Cluster Subgraphs (sorted by depth so outer containers precede inner containers)
    List<Cluster> sortedClusters = new ArrayList<>(graph.allClusters);
    sortedClusters.sort(Comparator.comparingInt(DiagramLayoutEngine::getSubgraphDepth));
    for (Cluster c : sortedClusters) {
      if (c.isCluster) {
        renderCluster(svg, c);
      }
    }

    // 2. Render Edges and collect collision-free Edge Label Badges
    boolean defaultHoriz = graph.direction == Direction.LR || graph.direction == Direction.RL;
    boolean defaultRev = graph.direction == Direction.BT || graph.direction == Direction.RL;
    List<DiagramLayoutEngine.BadgePlacement<Edge>> placedBadges = new ArrayList<>();
    for (Edge e : graph.edges) {
      if (e.stroke == EdgeStroke.INVIS) {
        continue;
      }
      Node src = graph.nodes.get(e.fromId);
      Node dst = graph.nodes.get(e.toId);
      if (src != null && dst != null) {
        boolean edgeHoriz = defaultHoriz;
        boolean edgeRev = defaultRev;
        if (src.parentCluster != null && src.parentCluster.equals(dst.parentCluster)) {
          edgeHoriz = DiagramLayoutEngine.isEffectiveHorizontal(src.parentCluster, defaultHoriz);
          edgeRev = DiagramLayoutEngine.isEffectiveReversed(src.parentCluster, defaultRev);
        }
        renderEdge(svg, edgeHoriz, edgeRev, graph, src, dst, e, placedBadges);
      }
    }

    // 3. Render Nodes
    for (Node n : graph.nodes.values()) {
      renderNode(svg, n);
    }

    // 4. Render Edge Label Badges above edges/nodes at guaranteed collision-free coordinates
    for (DiagramLayoutEngine.BadgePlacement<Edge> bp : placedBadges) {
      renderEdgeLabelBadge(svg, bp);
    }

    // 5. Optional Graph Title
    if (graph.title != null && !graph.title.isEmpty()) {
      svg.append(
          String.format(
              Locale.ROOT,
              "  <text class=\"graphviz-subgraph-title\" x=\"%.1f\" y=\"%.1f\""
                  + " font-size=\"12.5\" font-weight=\"600\" fill=\"#1f2328\""
                  + " text-anchor=\"middle\">%s</text>\n",
              width / 2.0,
              height - 10.0,
              DiagramLayoutEngine.escapeXml(graph.title)));
    }

    svg.append("</svg>");
    return svg.toString();
  }

  private static void renderCluster(StringBuilder svg, Cluster c) {
    int depth = DiagramLayoutEngine.getSubgraphDepth(c);
    String fill = c.customFill != null ? c.customFill : (depth % 2 == 0 ? "#f8fafc" : "#f1f5f9");
    String stroke = c.customStroke != null ? c.customStroke : "#cbd5e1";

    StringBuilder cls = new StringBuilder("graphviz-subgraph-box");
    if (c.customFill == null && c.customStroke == null && depth % 2 == 1) {
      cls.append(" graphviz-subgraph-box--alt");
    } else if (c.customFill != null && c.customStroke != null) {
      cls.append(" graphviz-subgraph-custom");
    } else if (c.customFill != null) {
      cls.append(" graphviz-subgraph-custom-fill");
    } else if (c.customStroke != null) {
      cls.append(" graphviz-subgraph-custom-stroke");
    }

    double rx = c.rounded ? 8.0 : 2.0;
    String dashAttr = "";
    if (c.dashed) {
      dashAttr = " stroke-dasharray=\"5,4\"";
    } else if (c.dotted) {
      dashAttr = " stroke-dasharray=\"2,3\"";
    } else if (c.customFill == null && c.customStroke == null) {
      dashAttr = " stroke-dasharray=\"4,4\"";
    }

    svg.append(
        String.format(
            Locale.ROOT,
            "  <rect class=\"%s\" x=\"%.1f\" y=\"%.1f\" width=\"%.1f\" height=\"%.1f\""
                + " rx=\"%.1f\" fill=\"%s\" stroke=\"%s\" stroke-width=\"1.5\"%s />\n",
            cls,
            c.x,
            c.y,
            c.width,
            c.height,
            rx,
            fill,
            stroke,
            dashAttr));

    if (c.title != null && !c.title.isEmpty()) {
      String titleColor;
      String titleClass;
      if (c.customFontColor != null) {
        titleColor = c.customFontColor;
        titleClass = "graphviz-subgraph-title-custom";
      } else if (c.customFill != null) {
        titleColor = DiagramLayoutEngine.isLightColor(c.customFill) ? "#1f2328" : "#f0f6fc";
        titleClass = "graphviz-subgraph-title-custom";
      } else {
        titleColor = "#1f2328";
        titleClass = "graphviz-subgraph-title";
      }
      svg.append(
          String.format(
              Locale.ROOT,
              "  <text class=\"%s\" x=\"%.1f\" y=\"%.1f\" font-size=\"%.1f\""
                  + " font-weight=\"600\" fill=\"%s\">%s</text>\n",
              titleClass,
              c.x + 14.0,
              c.y + 19.0,
              c.fontSize,
              titleColor,
              DiagramLayoutEngine.escapeXml(c.title)));
    }
  }

  private static void renderNode(StringBuilder svg, Node n) {
    svg.append("  <g class=\"graphviz-node\">\n");

    boolean hasCustomFill = n.customFill != null;
    boolean hasCustomStroke = n.customStroke != null;
    String fill = hasCustomFill ? n.customFill : (n.explicitFilled ? "#f1f5f9" : "#ffffff");
    String stroke = hasCustomStroke ? n.customStroke : "#64748b";

    StringBuilder shapeClass = new StringBuilder("graphviz-node-shape");
    if (hasCustomFill && hasCustomStroke) {
      shapeClass.append(" graphviz-node-custom");
    } else if (hasCustomFill) {
      shapeClass.append(" graphviz-node-custom-fill");
    } else if (hasCustomStroke) {
      shapeClass.append(" graphviz-node-custom-stroke");
    }

    String dashAttr = "";
    if (n.explicitDashed) {
      dashAttr = " stroke-dasharray=\"5,4\"";
    } else if (n.explicitDotted) {
      dashAttr = " stroke-dasharray=\"2,3\"";
    }

    switch (n.shape) {
      case ELLIPSE -> {
        double cx = n.x + n.width / 2.0;
        double cy = n.y + n.height / 2.0;
        svg.append(
            String.format(
                Locale.ROOT,
                "    <ellipse class=\"%s\" cx=\"%.1f\" cy=\"%.1f\" rx=\"%.1f\" ry=\"%.1f\""
                    + " fill=\"%s\" stroke=\"%s\" stroke-width=\"%.1f\"%s"
                    + " filter=\"url(#graphviz-shadow)\" />\n",
                shapeClass,
                cx,
                cy,
                n.width / 2.0,
                n.height / 2.0,
                fill,
                stroke,
                n.penWidth,
                dashAttr));
      }
      case CIRCLE -> {
        double r = n.width / 2.0;
        svg.append(
            String.format(
                Locale.ROOT,
                "    <circle class=\"%s\" cx=\"%.1f\" cy=\"%.1f\" r=\"%.1f\" fill=\"%s\""
                    + " stroke=\"%s\" stroke-width=\"%.1f\"%s"
                    + " filter=\"url(#graphviz-shadow)\" />\n",
                shapeClass,
                n.x + r,
                n.y + r,
                r,
                fill,
                stroke,
                n.penWidth,
                dashAttr));
      }
      case DOUBLECIRCLE -> {
        double r = n.width / 2.0;
        double innerR = Math.max(4.0, r - 4.5);
        svg.append(
            String.format(
                Locale.ROOT,
                "    <circle class=\"%s\" cx=\"%.1f\" cy=\"%.1f\" r=\"%.1f\" fill=\"%s\""
                    + " stroke=\"%s\" stroke-width=\"%.1f\"%s"
                    + " filter=\"url(#graphviz-shadow)\" />\n",
                shapeClass,
                n.x + r,
                n.y + r,
                r,
                fill,
                stroke,
                n.penWidth,
                dashAttr));
        svg.append(
            String.format(
                Locale.ROOT,
                "    <circle class=\"graphviz-node-inner-circle\" cx=\"%.1f\" cy=\"%.1f\""
                    + " r=\"%.1f\" fill=\"none\" stroke=\"%s\" stroke-width=\"%.1f\"%s />\n",
                n.x + r,
                n.y + r,
                innerR,
                stroke,
                n.penWidth,
                dashAttr));
      }
      case DIAMOND ->
          svg.append(
              String.format(
                  Locale.ROOT,
                  "    <polygon class=\"%s\" points=\"%s\""
                      + " fill=\"%s\" stroke=\"%s\" stroke-width=\"%.1f\"%s"
                      + " filter=\"url(#graphviz-shadow)\" />\n",
                  shapeClass,
                  DiagramLayoutEngine.formatDiamondPoints(n.x, n.y, n.width, n.height),
                  fill,
                  stroke,
                  n.penWidth,
                  dashAttr));
      case HEXAGON -> {
        double indent = Math.min(16.0, n.width * 0.2);
        svg.append(
            String.format(
                Locale.ROOT,
                "    <polygon class=\"%s\" points=\"%s\""
                    + " fill=\"%s\" stroke=\"%s\" stroke-width=\"%.1f\"%s"
                    + " filter=\"url(#graphviz-shadow)\" />\n",
                shapeClass,
                DiagramLayoutEngine.formatHexagonPoints(n.x, n.y, n.width, n.height, indent),
                fill,
                stroke,
                n.penWidth,
                dashAttr));
      }
      case CYLINDER -> {
        double ry = 7.0;
        svg.append(
            String.format(
                Locale.ROOT,
                "    <path class=\"%s\" d=\"%s\" fill=\"%s\""
                    + " stroke=\"%s\" stroke-width=\"%.1f\"%s"
                    + " filter=\"url(#graphviz-shadow)\" />\n",
                shapeClass,
                DiagramLayoutEngine.formatCylinderBodyPath(n.x, n.y, n.width, n.height, ry),
                fill,
                stroke,
                n.penWidth,
                dashAttr));
        svg.append(
            String.format(
                Locale.ROOT,
                "    <path d=\"%s\" fill=\"none\" stroke=\"%s\" stroke-width=\"%.1f\" />\n",
                DiagramLayoutEngine.formatCylinderRimPath(n.x, n.y, n.width, ry),
                stroke,
                n.penWidth));
      }
      case NOTE -> {
        double fold = 10.0;
        svg.append(
            String.format(
                Locale.ROOT,
                "    <polygon class=\"%s\""
                    + " points=\"%.1f,%.1f %.1f,%.1f %.1f,%.1f %.1f,%.1f %.1f,%.1f\" fill=\"%s\""
                    + " stroke=\"%s\" stroke-width=\"%.1f\"%s"
                    + " filter=\"url(#graphviz-shadow)\" />\n",
                shapeClass,
                n.x,
                n.y,
                n.x + n.width - fold,
                n.y,
                n.x + n.width,
                n.y + fold,
                n.x + n.width,
                n.y + n.height,
                n.x,
                n.y + n.height,
                fill,
                stroke,
                n.penWidth,
                dashAttr));
        svg.append(
            String.format(
                Locale.ROOT,
                "    <path d=\"M %.1f %.1f L %.1f %.1f L %.1f %.1f\" fill=\"none\""
                    + " stroke=\"%s\" stroke-width=\"%.1f\" />\n",
                n.x + n.width - fold,
                n.y,
                n.x + n.width - fold,
                n.y + fold,
                n.x + n.width,
                n.y + fold,
                stroke,
                n.penWidth));
      }
      case FOLDER -> {
        double tabW = Math.min(34.0, n.width * 0.38);
        double tabH = 7.0;
        svg.append(
            String.format(
                Locale.ROOT,
                "    <polygon class=\"%s\" points=\"%.1f,%.1f %.1f,%.1f %.1f,%.1f %.1f,%.1f"
                    + " %.1f,%.1f %.1f,%.1f %.1f,%.1f\" fill=\"%s\" stroke=\"%s\""
                    + " stroke-width=\"%.1f\"%s filter=\"url(#graphviz-shadow)\" />\n",
                shapeClass,
                n.x,
                n.y + tabH,
                n.x,
                n.y,
                n.x + tabW,
                n.y,
                n.x + tabW + 5.0,
                n.y + tabH,
                n.x + n.width,
                n.y + tabH,
                n.x + n.width,
                n.y + n.height,
                n.x,
                n.y + n.height,
                fill,
                stroke,
                n.penWidth,
                dashAttr));
      }
      case COMPONENT -> {
        svg.append(
            String.format(
                Locale.ROOT,
                "    <rect class=\"%s\" x=\"%.1f\" y=\"%.1f\" width=\"%.1f\" height=\"%.1f\""
                    + " rx=\"3.0\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%.1f\"%s"
                    + " filter=\"url(#graphviz-shadow)\" />\n",
                shapeClass,
                n.x,
                n.y,
                n.width,
                n.height,
                fill,
                stroke,
                n.penWidth,
                dashAttr));
        svg.append(
            String.format(
                Locale.ROOT,
                "    <rect class=\"graphviz-node-tab\" x=\"%.1f\" y=\"%.1f\" width=\"12.0\""
                    + " height=\"7.0\" rx=\"1.5\" fill=\"%s\" stroke=\"%s\""
                    + " stroke-width=\"%.1f\" />\n",
                n.x - 6.0,
                n.y + 8.0,
                fill,
                stroke,
                n.penWidth));
        svg.append(
            String.format(
                Locale.ROOT,
                "    <rect class=\"graphviz-node-tab\" x=\"%.1f\" y=\"%.1f\" width=\"12.0\""
                    + " height=\"7.0\" rx=\"1.5\" fill=\"%s\" stroke=\"%s\""
                    + " stroke-width=\"%.1f\" />\n",
                n.x - 6.0,
                n.y + n.height - 15.0,
                fill,
                stroke,
                n.penWidth));
      }
      case PLAINTEXT -> {
        // No border or background shape for plaintext nodes
      }
      case RECORD, MRECORD -> {
        double rx = n.shape == NodeShape.MRECORD ? 8.0 : 2.0;
        svg.append(
            String.format(
                Locale.ROOT,
                "    <rect class=\"%s\" x=\"%.1f\" y=\"%.1f\" width=\"%.1f\" height=\"%.1f\""
                    + " rx=\"%.1f\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%.1f\"%s"
                    + " filter=\"url(#graphviz-shadow)\" />\n",
                shapeClass,
                n.x,
                n.y,
                n.width,
                n.height,
                rx,
                fill,
                stroke,
                n.penWidth,
                dashAttr));
        int count = Math.max(1, n.recordCompartments.size());
        double compW = n.width / count;
        for (int i = 1; i < count; i++) {
          double divX = n.x + i * compW;
          svg.append(
              String.format(
                  Locale.ROOT,
                  "    <line class=\"graphviz-node-divider\" x1=\"%.1f\" y1=\"%.1f\""
                      + " x2=\"%.1f\" y2=\"%.1f\" stroke=\"%s\" stroke-width=\"%.1f\" />\n",
                  divX,
                  n.y,
                  divX,
                  n.y + n.height,
                  stroke,
                  n.penWidth));
        }
      }
      default -> {
        double rx = n.shape == NodeShape.ROUNDED_BOX || n.explicitRounded ? 8.0 : 2.5;
        svg.append(
            String.format(
                Locale.ROOT,
                "    <rect class=\"%s\" x=\"%.1f\" y=\"%.1f\" width=\"%.1f\" height=\"%.1f\""
                    + " rx=\"%.1f\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%.1f\"%s"
                    + " filter=\"url(#graphviz-shadow)\" />\n",
                shapeClass,
                n.x,
                n.y,
                n.width,
                n.height,
                rx,
                fill,
                stroke,
                n.penWidth,
                dashAttr));
      }
    }

    // Determine text color and class
    String textColor;
    String textClass;
    if (n.customFontColor != null) {
      textColor = n.customFontColor;
      textClass =
          hasCustomFill
              ? "graphviz-node-label graphviz-node-label-custom-fill"
              : "graphviz-node-label";
    } else if (hasCustomFill) {
      textColor = DiagramLayoutEngine.isLightColor(n.customFill) ? "#1f2328" : "#f0f6fc";
      textClass = "graphviz-node-label graphviz-node-label-custom-fill";
    } else {
      textColor = "#1f2328";
      textClass = "graphviz-node-label";
    }

    if (n.shape == NodeShape.RECORD || n.shape == NodeShape.MRECORD) {
      int count = Math.max(1, n.recordCompartments.size());
      double compW = n.width / count;
      double cy = n.y + n.height / 2.0;
      for (int i = 0; i < count; i++) {
        double compCx = n.x + (i + 0.5) * compW;
        svg.append(
            String.format(
                Locale.ROOT,
                "    <text class=\"%s\" x=\"%.1f\" y=\"%.1f\" font-size=\"%.1f\""
                    + " fill=\"%s\" text-anchor=\"middle\""
                    + " dominant-baseline=\"central\">%s</text>\n",
                textClass,
                compCx,
                cy,
                n.fontSize,
                textColor,
                DiagramLayoutEngine.escapeXml(n.recordCompartments.get(i))));
      }
      svg.append("  </g>\n");
      return;
    }

    double lineH = Math.max(15.0, n.fontSize * 1.42);
    double textYOffset = n.shape == NodeShape.CYLINDER ? 4.0 : 0.0;
    int numLines = n.labelLines.size();

    if (numLines == 1 && n.labelLines.get(0).align == LineAlign.CENTER) {
      double cx = n.x + n.width / 2.0;
      double cy = n.y + textYOffset + n.height / 2.0;
      svg.append(
          String.format(
              Locale.ROOT,
              "    <text class=\"%s\" x=\"%.1f\" y=\"%.1f\" font-size=\"%.1f\" fill=\"%s\""
                  + " text-anchor=\"middle\" dominant-baseline=\"central\">%s</text>\n",
              textClass,
              cx,
              cy,
              n.fontSize,
              textColor,
              DiagramLayoutEngine.escapeXml(n.labelLines.get(0).text.trim())));
    } else {
      double startY = n.y + textYOffset + (n.height - (numLines - 1) * lineH) / 2.0;
      int maxLineLen = 0;
      for (AlignedLine al : n.labelLines) {
        maxLineLen = Math.max(maxLineLen, al.text.trim().length());
      }
      double charW = n.fontSize * 0.61;
      double textBlockW =
          Math.max(16.0, Math.min(n.width - 2.0 * n.marginX, maxLineLen * charW));
      double cx = n.x + n.width / 2.0;
      for (int i = 0; i < numLines; i++) {
        AlignedLine al = n.labelLines.get(i);
        double lineY = startY + i * lineH;
        double lineX;
        String anchor;
        String displayTxt;
        switch (al.align) {
          case LEFT -> {
            lineX = cx - textBlockW / 2.0;
            anchor = "start";
            displayTxt = al.text.stripLeading();
          }
          case RIGHT -> {
            lineX = cx + textBlockW / 2.0;
            anchor = "end";
            displayTxt = al.text.stripTrailing();
          }
          default -> {
            lineX = cx;
            anchor = "middle";
            displayTxt = al.text.trim();
          }
        }
        if (displayTxt.isEmpty()) {
          continue;
        }
        String weight = i == 0 && numLines > 1 ? "600" : "400";
        svg.append(
            String.format(
                Locale.ROOT,
                "    <text class=\"%s\" x=\"%.1f\" y=\"%.1f\" font-size=\"%.1f\""
                    + " font-weight=\"%s\" fill=\"%s\" text-anchor=\"%s\""
                    + " dominant-baseline=\"central\"><tspan x=\"%.1f\" dy=\"0\""
                    + " text-anchor=\"%s\" fill=\"%s\">%s</tspan></text>\n",
                textClass,
                lineX,
                lineY,
                n.fontSize,
                weight,
                textColor,
                anchor,
                lineX,
                anchor,
                textColor,
                DiagramLayoutEngine.escapeXml(displayTxt)));
      }
    }

    svg.append("  </g>\n");
  }

  private static void renderEdge(
      StringBuilder svg,
      boolean isHorizontal,
      boolean isReversed,
      DotGraph graph,
      Node src,
      Node dst,
      Edge e,
      List<DiagramLayoutEngine.BadgePlacement<Edge>> placedBadges) {
    String strokeColor = e.customColor != null ? e.customColor : "#656d76";
    String edgeClass =
        e.customColor != null ? "graphviz-edge graphviz-edge-custom" : "graphviz-edge";

    String dashAttr = "";
    if (e.stroke == EdgeStroke.DASHED) {
      dashAttr = " stroke-dasharray=\"5,4\"";
    } else if (e.stroke == EdgeStroke.DOTTED) {
      dashAttr = " stroke-dasharray=\"2,3\"";
    }

    String markerStartAttr = e.arrowStart ? " marker-start=\"url(#graphviz-arrow)\"" : "";
    String markerEndAttr = e.arrowEnd ? " marker-end=\"url(#graphviz-arrow)\"" : "";

    Cluster tailCluster = e.ltail != null ? graph.clusterMap.get(e.ltail) : null;
    Cluster headCluster = e.lhead != null ? graph.clusterMap.get(e.lhead) : null;

    double sx;
    double sy;
    double sw;
    double sh;
    if (tailCluster != null) {
      if (!isHorizontal) {
        sx =
            Math.max(
                tailCluster.x,
                Math.min(src.x, tailCluster.x + Math.max(0.0, tailCluster.width - src.width)));
        sw = src.width;
        sy = tailCluster.y;
        sh = tailCluster.height;
      } else {
        sx = tailCluster.x;
        sw = tailCluster.width;
        sy =
            Math.max(
                tailCluster.y,
                Math.min(src.y, tailCluster.y + Math.max(0.0, tailCluster.height - src.height)));
        sh = src.height;
      }
    } else {
      sx = src.x;
      sy = src.y;
      sw = src.width;
      sh = src.height;
    }

    double tx;
    double ty;
    double tw;
    double th;
    if (headCluster != null) {
      if (!isHorizontal) {
        tx =
            Math.max(
                headCluster.x,
                Math.min(dst.x, headCluster.x + Math.max(0.0, headCluster.width - dst.width)));
        tw = dst.width;
        ty = headCluster.y;
        th = headCluster.height;
      } else {
        tx = headCluster.x;
        tw = headCluster.width;
        ty =
            Math.max(
                headCluster.y,
                Math.min(dst.y, headCluster.y + Math.max(0.0, headCluster.height - dst.height)));
        th = dst.height;
      }
    } else {
      tx = dst.x;
      ty = dst.y;
      tw = dst.width;
      th = dst.height;
    }

    // Long forward edges spanning multiple ranks route through a chain of Sugiyama virtual dummy
    // nodes as a multi-segment spline and return early here, before the single-segment cubic
    // Bezier endpoint/control-point variables (x1..cp2y) are declared below.
    if (!e.virtualNodes.isEmpty() && !src.id.equals(dst.id) && !e.isBackEdge) {
      List<Double> px = new ArrayList<>();
      List<Double> py = new ArrayList<>();
      if (!isHorizontal) {
        px.add(sx + sw / 2.0);
        py.add(isReversed ? sy : sy + sh);
        for (Node v : e.virtualNodes) {
          px.add(v.x + v.width / 2.0);
          py.add(v.y + v.height / 2.0);
        }
        px.add(tx + tw / 2.0);
        py.add(isReversed ? ty + th : ty);
      } else {
        px.add(isReversed ? sx : sx + sw);
        py.add(sy + sh / 2.0);
        for (Node v : e.virtualNodes) {
          px.add(v.x + v.width / 2.0);
          py.add(v.y + v.height / 2.0);
        }
        px.add(isReversed ? tx + tw : tx);
        py.add(ty + th / 2.0);
      }

      String pathD = DiagramLayoutEngine.buildMultiSegmentBezierPath(px, py, isHorizontal);
      svg.append(
          String.format(
              Locale.ROOT,
              "  <path class=\"%s\" d=\"%s\" fill=\"none\" stroke=\"%s\""
                  + " stroke-width=\"%.1f\"%s%s%s />\n",
              edgeClass,
              pathD,
              strokeColor,
              e.penWidth,
              dashAttr,
              markerStartAttr,
              markerEndAttr));

      if (!e.labelLines.isEmpty()) {
        Node firstV = e.virtualNodes.get(0);
        double[] badgeDim = computeEdgeBadgeDimensions(e);
        double[] pos =
            DiagramLayoutEngine.findCollisionFreeBadgeCenter(
                firstV.x + firstV.width / 2.0,
                firstV.y + firstV.height / 2.0,
                px.get(0),
                py.get(0),
                px.get(0),
                py.get(0),
                px.get(px.size() - 1),
                py.get(py.size() - 1),
                px.get(px.size() - 1),
                py.get(py.size() - 1),
                badgeDim[0],
                badgeDim[1],
                graph.nodes.values(),
                placedBadges);
        placedBadges.add(
            new DiagramLayoutEngine.BadgePlacement<>(e, pos[0], pos[1], badgeDim[0], badgeDim[1]));
      }
      return;
    }

    // Single-segment cubic Bezier endpoints and control points for self-loops, back-edges, and
    // single-rank forward edges.
    double x1;
    double y1;
    double x2;
    double y2;
    double cp1x;
    double cp1y;
    double cp2x;
    double cp2y;

    if (src.id.equals(dst.id)) {
      double[] pts =
          DiagramLayoutEngine.computeSelfLoopControlPoints(
              sx, sy, sw, sh, isHorizontal, 38.0, 18.0);
      x1 = pts[0];
      y1 = pts[1];
      cp1x = pts[2];
      cp1y = pts[3];
      cp2x = pts[4];
      cp2y = pts[5];
      x2 = pts[6];
      y2 = pts[7];
    } else if (e.isBackEdge) {
      double[] badgeDim = computeEdgeBadgeDimensions(e);
      if (!isHorizontal) {
        x1 = sx + sw;
        y1 = sy + sh / 2.0;
        x2 = tx + tw;
        y2 = ty + th / 2.0;
        double maxRight = Math.max(x1, x2);
        double minY = Math.min(y1, y2) - 10;
        double maxY = Math.max(y1, y2) + 10;
        for (Node n : graph.nodes.values()) {
          if (n.y + n.height >= minY && n.y <= maxY) {
            maxRight = Math.max(maxRight, n.x + n.width);
          }
        }
        double loopOffset = Math.max(42.0, badgeDim[0] / 2.0 + 26.0);
        cp1x = maxRight + loopOffset;
        cp1y = y1;
        cp2x = maxRight + loopOffset;
        cp2y = y2;
      } else {
        x1 = sx + sw / 2.0;
        y1 = sy;
        x2 = tx + tw / 2.0;
        y2 = ty;
        double minTop = Math.min(y1, y2);
        double minX = Math.min(x1, x2) - 10;
        double maxX = Math.max(x1, x2) + 10;
        for (Node n : graph.nodes.values()) {
          if (n.x + n.width >= minX && n.x <= maxX) {
            minTop = Math.min(minTop, n.y);
          }
        }
        double loopOffset = Math.max(42.0, badgeDim[1] + 24.0);
        cp1x = x1;
        cp1y = minTop - loopOffset;
        cp2x = x2;
        cp2y = minTop - loopOffset;
      }
    } else {
      double[] pts =
          DiagramLayoutEngine.computeForwardBezierControlPoints(
              sx, sy, sw, sh, tx, ty, tw, th, isHorizontal, isReversed);
      x1 = pts[0];
      y1 = pts[1];
      cp1x = pts[2];
      cp1y = pts[3];
      cp2x = pts[4];
      cp2y = pts[5];
      x2 = pts[6];
      y2 = pts[7];
    }

    svg.append(
        String.format(
            Locale.ROOT,
            "  <path class=\"%s\" d=\"M %.1f %.1f C %.1f %.1f, %.1f %.1f, %.1f %.1f\""
                + " fill=\"none\" stroke=\"%s\" stroke-width=\"%.1f\"%s%s%s />\n",
            edgeClass,
            x1,
            y1,
            cp1x,
            cp1y,
            cp2x,
            cp2y,
            x2,
            y2,
            strokeColor,
            e.penWidth,
            dashAttr,
            markerStartAttr,
            markerEndAttr));

    if (!e.labelLines.isEmpty()) {
      double midX = DiagramLayoutEngine.evalCubicBezier(x1, cp1x, cp2x, x2, 0.5);
      double midY = DiagramLayoutEngine.evalCubicBezier(y1, cp1y, cp2y, y2, 0.5);
      double[] badgeDim = computeEdgeBadgeDimensions(e);
      double[] pos =
          DiagramLayoutEngine.findCollisionFreeBadgeCenter(
              midX,
              midY,
              x1,
              y1,
              cp1x,
              cp1y,
              cp2x,
              cp2y,
              x2,
              y2,
              badgeDim[0],
              badgeDim[1],
              graph.nodes.values(),
              placedBadges);
      placedBadges.add(
          new DiagramLayoutEngine.BadgePlacement<>(e, pos[0], pos[1], badgeDim[0], badgeDim[1]));
    }
  }

  private static double[] computeEdgeBadgeDimensions(Edge e) {
    if (e.labelLines.isEmpty()) {
      return new double[] {0, 0};
    }
    int maxLen = 0;
    for (AlignedLine al : e.labelLines) {
      maxLen = Math.max(maxLen, al.text.trim().length());
    }
    double charW = e.fontSize * 0.62;
    double lineH = Math.max(14.0, e.fontSize * 1.35);
    double rectW = maxLen * charW + 14.0;
    double rectH = e.labelLines.size() * lineH + 6.0;
    return new double[] {rectW, rectH};
  }

  private static void renderEdgeLabelBadge(
      StringBuilder svg, DiagramLayoutEngine.BadgePlacement<Edge> bp) {
    Edge e = bp.edge;
    double midX = bp.cx;
    double midY = bp.cy;
    double rectW = bp.width;
    double rectH = bp.height;

    svg.append(
        String.format(
            Locale.ROOT,
            "  <rect class=\"graphviz-edge-label-bg\" x=\"%.1f\" y=\"%.1f\" width=\"%.1f\""
                + " height=\"%.1f\" rx=\"3\" fill=\"#ffffff\" fill-opacity=\"0.95\" />\n",
            midX - rectW / 2.0,
            midY - rectH / 2.0,
            rectW,
            rectH));

    String textColor = e.customFontColor != null ? e.customFontColor : "#475569";
    String textClass =
        e.customFontColor != null
            ? "graphviz-edge-label-text graphviz-edge-label-custom"
            : "graphviz-edge-label-text";

    double lineH = Math.max(14.0, e.fontSize * 1.35);
    if (e.labelLines.size() == 1) {
      svg.append(
          String.format(
              Locale.ROOT,
              "  <text class=\"%s\" x=\"%.1f\" y=\"%.1f\" font-size=\"%.1f\" fill=\"%s\""
                  + " text-anchor=\"middle\" dominant-baseline=\"central\">%s</text>\n",
              textClass,
              midX,
              midY,
              e.fontSize,
              textColor,
              DiagramLayoutEngine.escapeXml(e.labelLines.get(0).text.trim())));
    } else {
      double startY = midY - ((e.labelLines.size() - 1) * lineH) / 2.0;
      svg.append(
          String.format(
              Locale.ROOT,
              "  <text class=\"%s\" x=\"%.1f\" y=\"%.1f\" font-size=\"%.1f\" fill=\"%s\""
                  + " text-anchor=\"middle\" dominant-baseline=\"central\">\n",
              textClass,
              midX,
              startY,
              e.fontSize,
              textColor));
      for (int i = 0; i < e.labelLines.size(); i++) {
        svg.append(
            String.format(
                Locale.ROOT,
                "    <tspan x=\"%.1f\" dy=\"%s\" fill=\"%s\">%s</tspan>\n",
                midX,
                i == 0 ? "0" : String.format(Locale.ROOT, "%.1f", lineH),
                textColor,
                DiagramLayoutEngine.escapeXml(e.labelLines.get(i).text.trim())));
      }
      svg.append("  </text>\n");
    }
  }

  private SimpleDotRenderer() {}
}
