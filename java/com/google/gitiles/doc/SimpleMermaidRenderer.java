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

import static com.google.common.base.Strings.isNullOrEmpty;
import static com.google.common.base.Strings.nullToEmpty;
import static com.google.common.primitives.Doubles.max;

import com.google.common.base.Ascii;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.annotation.Nullable;

/**
 * Server-side AST parser, layout engine, and SVG renderer for Mermaid flowchart and graph diagrams.
 *
 * <p>Implements a pure streaming character-scanner AST parser without regex splits, delegating
 * hierarchical Sugiyama DAG layout, compound subgraph layout, and SVG/XML security utilities to
 * {@link DiagramLayoutEngine}.
 */
public final class SimpleMermaidRenderer {

  /** Graph layout flow direction. */
  public enum Direction {
    LR,
    TD,
    TB,
    RL,
    BT
  }

  /** Visual shape of a graph node. */
  public enum NodeShape {
    RECTANGLE,
    ROUNDED,
    STADIUM,
    SUBROUTINE,
    CYLINDER,
    CIRCLE,
    DIAMOND,
    HEXAGON,
    FLAG
  }

  /** Stroke styling of a connecting edge. */
  public enum EdgeStroke {
    SOLID,
    DASHED,
    THICK
  }

  // =========================================================================
  // AST Model Objects
  // =========================================================================

  /** A node in the Mermaid diagram. */
  public static class Node extends DiagramLayoutEngine.BaseNode {
    public String label;
    public final List<String> labelLines = new ArrayList<>();
    public NodeShape shape = NodeShape.RECTANGLE;
    public Subgraph parentSubgraph;
    @Nullable public String customFill;
    @Nullable public String customStroke;
    @Nullable public String customColor;

    public Node(String id) {
      super(id);
      setLabel(id);
    }

    public void setLabel(String rawLabel) {
      this.label = rawLabel != null ? rawLabel : id;
      this.labelLines.clear();
      this.labelLines.addAll(parseLabelLines(this.label));
    }

    @Override
    public boolean hasParentSubgraph() {
      return parentSubgraph != null;
    }
  }

  /** A logical subgraph or cluster containing nodes and nested subgraphs. */
  public static class Subgraph extends DiagramLayoutEngine.BaseSubgraph<Node, Subgraph> {
    public Direction direction;
    @Nullable public String customColor;

    public Subgraph(String id, String title) {
      super(id, title, null);
    }

    @Override
    public boolean hasDirectionOverride() {
      return direction != null;
    }

    @Override
    public boolean isDirectionHorizontal() {
      return direction == Direction.LR || direction == Direction.RL;
    }
  }

  /** A directional or bidirectional edge between nodes. */
  public static class Edge extends DiagramLayoutEngine.BaseEdge<Node> {
    public final EdgeStroke stroke;
    public final boolean arrow;

    public Edge(String fromId, String toId, String label, EdgeStroke stroke, boolean arrow) {
      super(fromId, toId, label);
      this.stroke = stroke;
      this.arrow = arrow;
    }
  }

  /** An edge between subgraphs. */
  public static class SubgraphEdge extends DiagramLayoutEngine.BaseSubgraphEdge {
    public final EdgeStroke stroke;
    public final boolean arrow;

    public SubgraphEdge(
        String fromSgId, String toSgId, String label, EdgeStroke stroke, boolean arrow) {
      super(fromSgId, toSgId, label);
      this.stroke = stroke;
      this.arrow = arrow;
    }
  }

  /** Parsed Mermaid graph structure containing nodes, edges, and subgraphs. */
  public static class MermaidGraph {
    public final List<Subgraph> rootSubgraphs = new ArrayList<>();
    public Direction direction = Direction.TD;
    public final Map<String, Node> nodes = new LinkedHashMap<>();
    public final Map<String, Subgraph> subgraphsMap = new LinkedHashMap<>();
    public final List<Subgraph> allSubgraphs = new ArrayList<>();
    public final List<Edge> edges = new ArrayList<>();
    public final List<SubgraphEdge> subgraphEdges = new ArrayList<>();

    public Node ensureNode(String id, @Nullable Subgraph currentSubgraph) {
      Node node = nodes.get(id);
      if (node == null) {
        node = new Node(id);
        nodes.put(id, node);
        if (currentSubgraph != null) {
          node.parentSubgraph = currentSubgraph;
          currentSubgraph.nodes.add(node);
        }
      } else if (node.parentSubgraph == null && currentSubgraph != null) {
        node.parentSubgraph = currentSubgraph;
        currentSubgraph.nodes.add(node);
      }
      return node;
    }

    @Nullable
    public Subgraph lookupSubgraph(String name) {
      if (name == null) {
        return null;
      }
      String clean = name.trim();
      Subgraph sg = subgraphsMap.get(clean);
      if (sg != null) {
        return sg;
      }
      sg = subgraphsMap.get(stripWhitespace(clean));
      if (sg != null) {
        return sg;
      }
      sg = subgraphsMap.get(clean.toLowerCase(Locale.ROOT));
      if (sg != null) {
        return sg;
      }
      sg = subgraphsMap.get(stripWhitespace(clean).toLowerCase(Locale.ROOT));
      return sg;
    }
  }

  // =========================================================================
  // Character Stream Scanner & Tokenizer
  // =========================================================================

  private static class CharScanner {
    final String text;
    int pos;

    CharScanner(String text) {
      this.text = nullToEmpty(text);
      this.pos = 0;
    }

    boolean isEof() {
      return pos >= text.length();
    }

    char peek() {
      return isEof() ? '\0' : text.charAt(pos);
    }

    void advance() {
      if (!isEof()) {
        pos++;
      }
    }

    boolean startsWith(String prefix) {
      return text.startsWith(prefix, pos);
    }

    boolean startsWithIgnoreCase(String prefix) {
      if (text.length() - pos < prefix.length()) {
        return false;
      }
      return Ascii.equalsIgnoreCase(text.substring(pos, pos + prefix.length()), prefix);
    }

    void skip(String prefix) {
      if (startsWith(prefix)) {
        pos += prefix.length();
      }
    }

    void skipIgnoreCase(String prefix) {
      if (startsWithIgnoreCase(prefix)) {
        pos += prefix.length();
      }
    }

    boolean tryConsume(String prefix) {
      if (startsWith(prefix)) {
        pos += prefix.length();
        return true;
      }
      return false;
    }

    boolean tryConsumeIgnoreCase(String prefix) {
      if (startsWithIgnoreCase(prefix)) {
        pos += prefix.length();
        return true;
      }
      return false;
    }

    void skipWhitespace() {
      while (!isEof() && (text.charAt(pos) == ' ' || text.charAt(pos) == '\t')) {
        pos++;
      }
    }

    void skipWhitespaceAndNewlines() {
      while (!isEof()) {
        char c = text.charAt(pos);
        if (c == ' ' || c == '\t' || c == '\r' || c == '\n' || c == ';') {
          pos++;
        } else {
          break;
        }
      }
    }

    void skipLine() {
      while (!isEof()) {
        char c = text.charAt(pos++);
        if (c == '\n') {
          break;
        }
      }
    }

    void skipToStatementEnd() {
      while (!isEof()) {
        char c = text.charAt(pos);
        if (c == ';' || c == '\n') {
          pos++;
          break;
        }
        pos++;
      }
    }

    String scanIdentifier() {
      skipWhitespace();
      int start = pos;
      while (!isEof()) {
        char c = text.charAt(pos);
        if (Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '.') {
          pos++;
        } else {
          break;
        }
      }
      return text.substring(start, pos);
    }
  }

  private static class RawNodeToken {
    final String id;
    final NodeShape shape;
    final String label;

    RawNodeToken(String id, NodeShape shape, @Nullable String label) {
      this.id = id;
      this.shape = shape;
      this.label = label;
    }
  }

  private static class RawEdgeToken {
    final EdgeStroke stroke;
    final boolean arrow;
    final String label;

    RawEdgeToken(EdgeStroke stroke, boolean arrow, @Nullable String label) {
      this.stroke = stroke;
      this.arrow = arrow;
      this.label = label;
    }
  }

  // =========================================================================
  // Parser Implementation
  // =========================================================================

  /**
   * Attempts to render a Mermaid code string into SVG XML.
   *
   * @param mermaidCode source Mermaid definition.
   * @return rendered SVG XML string, or empty if unsupported / invalid syntax.
   */
  public static Optional<String> renderToSvg(String mermaidCode) {
    if (mermaidCode == null || mermaidCode.trim().isEmpty()) {
      return Optional.empty();
    }

    Optional<MermaidGraph> graphOpt = parse(mermaidCode);
    if (graphOpt.isEmpty()) {
      return Optional.empty();
    }

    MermaidGraph graph = graphOpt.get();
    if (graph.nodes.isEmpty()) {
      return Optional.empty();
    }

    return Optional.of(layoutAndRenderSvg(graph));
  }

  /** Parses Mermaid source code into a {@link MermaidGraph} AST. */
  public static Optional<MermaidGraph> parse(String mermaidCode) {
    CharScanner s = new CharScanner(mermaidCode);
    MermaidGraph graph = new MermaidGraph();
    boolean headerFound = false;

    // Scan for diagram type and direction
    while (!s.isEof()) {
      s.skipWhitespaceAndNewlines();
      if (s.isEof()) {
        break;
      }

      if (s.startsWith("%%")) {
        s.skipLine();
        continue;
      }

      if (s.tryConsumeIgnoreCase("graph") || s.tryConsumeIgnoreCase("flowchart")) {
        s.skipWhitespace();
        String dirStr = s.scanIdentifier().toUpperCase(Locale.ROOT);
        try {
          if (!dirStr.isEmpty()) {
            graph.direction = Direction.valueOf(dirStr);
          } else {
            graph.direction = Direction.TD;
          }
        } catch (IllegalArgumentException e) {
          graph.direction = Direction.TD;
        }
        headerFound = true;
        s.skipToStatementEnd();
        break;
      }

      // Check unsupported non-graph diagrams for quick fallback
      if (s.startsWithIgnoreCase("sequenceDiagram")
          || s.startsWithIgnoreCase("classDiagram")
          || s.startsWithIgnoreCase("erDiagram")
          || s.startsWithIgnoreCase("gantt")
          || s.startsWithIgnoreCase("pie")
          || s.startsWithIgnoreCase("gitGraph")
          || s.startsWithIgnoreCase("xychart-beta")
          || s.startsWithIgnoreCase("stateDiagram")) {
        return Optional.empty();
      }

      s.skipLine();
    }

    if (!headerFound) {
      return Optional.empty();
    }

    Deque<Subgraph> subgraphStack = new ArrayDeque<>();

    // Parse diagram statements into AST
    while (!s.isEof()) {
      s.skipWhitespaceAndNewlines();
      if (s.isEof()) {
        break;
      }

      if (s.startsWith("%%")) {
        s.skipLine();
        continue;
      }

      if (s.startsWithIgnoreCase("style ")) {
        parseStyleDirective(s, graph);
        continue;
      }

      // Skip other meta directives
      if (s.startsWithIgnoreCase("classDef ")
          || s.startsWithIgnoreCase("class ")
          || s.startsWithIgnoreCase("click ")
          || s.startsWithIgnoreCase("linkStyle ")
          || s.startsWithIgnoreCase("accTitle")
          || s.startsWithIgnoreCase("accDescr")) {
        s.skipToStatementEnd();
        continue;
      }

      if (s.startsWithIgnoreCase("graph") || s.startsWithIgnoreCase("flowchart")) {
        s.skipToStatementEnd();
        continue;
      }

      if (s.tryConsumeIgnoreCase("direction")) {
        s.skipWhitespace();
        String dirStr = s.scanIdentifier().toUpperCase(Locale.ROOT);
        if (!subgraphStack.isEmpty() && !dirStr.isEmpty()) {
          try {
            subgraphStack.peek().direction = Direction.valueOf(dirStr);
          } catch (IllegalArgumentException e) {
            // ignore
          }
        }
        s.skipToStatementEnd();
        continue;
      }

      if (s.tryConsumeIgnoreCase("end")) {
        char nextC = s.peek();
        if (nextC == '\0' || Character.isWhitespace(nextC) || nextC == ';') {
          if (!subgraphStack.isEmpty()) {
            subgraphStack.pop();
          }
          s.skipToStatementEnd();
          continue;
        }
      }

      if (s.tryConsumeIgnoreCase("subgraph")) {
        parseSubgraphHeader(s, graph, subgraphStack);
        s.skipToStatementEnd();
        continue;
      }

      Subgraph currentSg = subgraphStack.isEmpty() ? null : subgraphStack.peek();
      parseStatement(s, graph, currentSg);
      s.skipToStatementEnd();
    }

    return Optional.of(graph);
  }

  private static void parseSubgraphHeader(
      CharScanner s, MermaidGraph graph, Deque<Subgraph> subgraphStack) {
    s.skipWhitespace();
    String sgId;
    String sgTitle;

    // Check for `subgraph "Title Only"`
    if (s.startsWith("\"")) {
      s.skip("\"");
      int start = s.pos;
      while (!s.isEof() && !s.startsWith("\"")) {
        s.advance();
      }
      sgTitle = s.text.substring(start, s.pos);
      s.skip("\"");
      sgId = "sg_" + graph.allSubgraphs.size();
    } else {
      int start = s.pos;
      while (!s.isEof()
          && !s.startsWith("[")
          && !s.startsWith("\"")
          && s.peek() != '\n'
          && s.peek() != ';') {
        s.advance();
      }
      String rawName = s.text.substring(start, s.pos).trim();
      s.skipWhitespace();
      if (s.startsWith("[")) {
        s.skip("[");
        s.skipWhitespace();
        boolean quoted = s.tryConsume("\"");
        int tstart = s.pos;
        if (quoted) {
          while (!s.isEof() && !s.startsWith("\"]") && !s.startsWith("\"")) {
            s.advance();
          }
          sgTitle = s.text.substring(tstart, s.pos);
          s.skip("\"");
          s.skip("]");
        } else {
          while (!s.isEof() && !s.startsWith("]")) {
            s.advance();
          }
          sgTitle = s.text.substring(tstart, s.pos);
          s.skip("]");
        }
        sgId = rawName;
      } else {
        sgTitle = rawName;
        sgId = rawName;
      }
    }

    Subgraph sg = new Subgraph(sgId, sgTitle);
    if (!subgraphStack.isEmpty()) {
      Subgraph parent = subgraphStack.peek();
      sg.parent = parent;
      parent.children.add(sg);
    } else {
      graph.rootSubgraphs.add(sg);
    }
    subgraphStack.push(sg);
    graph.subgraphsMap.put(sgId, sg);
    graph.subgraphsMap.put(sgTitle, sg);
    graph.subgraphsMap.put(sgId.toLowerCase(Locale.ROOT), sg);
    graph.subgraphsMap.put(sgTitle.toLowerCase(Locale.ROOT), sg);
    graph.allSubgraphs.add(sg);
  }

  private static void parseStyleDirective(CharScanner s, MermaidGraph graph) {
    s.skipIgnoreCase("style");
    s.skipWhitespace();
    String targetId = s.scanIdentifier();
    if (targetId.isEmpty()) {
      s.skipToStatementEnd();
      return;
    }
    s.skipWhitespace();
    int start = s.pos;
    while (!s.isEof()) {
      char c = s.peek();
      if (c == '\n' || c == '\r' || c == ';') {
        break;
      }
      s.pos++;
    }
    String rest = s.text.substring(start, s.pos);
    s.skipToStatementEnd();

    String fill = null;
    String stroke = null;
    String color = null;
    int p = 0;
    while (p < rest.length()) {
      int nextSep = rest.length();
      for (int i = p; i < rest.length(); i++) {
        char ch = rest.charAt(i);
        if (ch == ',' || ch == ';') {
          nextSep = i;
          break;
        }
      }
      String part = rest.substring(p, nextSep).trim();
      int colonIdx = part.indexOf(':');
      if (colonIdx != -1) {
        String key = part.substring(0, colonIdx).trim().toLowerCase(Locale.ROOT);
        String val = part.substring(colonIdx + 1).trim();
        if (key.equals("fill")) {
          fill = val;
        } else if (key.equals("stroke")) {
          stroke = val;
        } else if (key.equals("color")) {
          color = val;
        }
      }
      p = nextSep + 1;
    }

    if (fill != null && !DiagramLayoutEngine.isValidCssColor(fill)) {
      fill = null;
    }
    if (stroke != null && !DiagramLayoutEngine.isValidCssColor(stroke)) {
      stroke = null;
    }
    if (color != null && !DiagramLayoutEngine.isValidCssColor(color)) {
      color = null;
    }

    Subgraph sg = graph.lookupSubgraph(targetId);
    if (sg != null) {
      if (fill != null) {
        sg.customFill = fill;
      }
      if (stroke != null) {
        sg.customStroke = stroke;
      }
      if (color != null) {
        sg.customColor = color;
      }
    }
    Node n = graph.nodes.get(targetId);
    if (n != null) {
      if (fill != null) {
        n.customFill = fill;
      }
      if (stroke != null) {
        n.customStroke = stroke;
      }
      if (color != null) {
        n.customColor = color;
      }
    }
  }

  private static void parseStatement(
      CharScanner s, MermaidGraph graph, @Nullable Subgraph currentSubgraph) {
    List<RawNodeToken> prevGroup = scanNodeGroup(s);
    if (prevGroup.isEmpty()) {
      return;
    }

    for (RawNodeToken token : prevGroup) {
      applyNodeToken(token, graph, currentSubgraph);
    }

    while (!s.isEof()) {
      char c = s.peek();
      if (c == ';' || c == '\n' || c == '\r') {
        break;
      }

      RawEdgeToken edge = scanEdgeToken(s);
      if (edge == null) {
        break;
      }

      List<RawNodeToken> nextGroup = scanNodeGroup(s);
      if (nextGroup.isEmpty()) {
        break;
      }

      for (RawNodeToken token : nextGroup) {
        applyNodeToken(token, graph, currentSubgraph);
      }

      for (RawNodeToken fromToken : prevGroup) {
        for (RawNodeToken toToken : nextGroup) {
          Subgraph fromSg = graph.lookupSubgraph(fromToken.id);
          Subgraph toSg = graph.lookupSubgraph(toToken.id);

          if (fromSg != null && toSg != null) {
            graph.subgraphEdges.add(
                new SubgraphEdge(fromSg.id, toSg.id, edge.label, edge.stroke, edge.arrow));
          } else if (fromSg == null && toSg != null) {
            if (!toSg.nodes.isEmpty()) {
              Node targetNode = toSg.nodes.get(toSg.nodes.size() / 2);
              graph.edges.add(
                  new Edge(fromToken.id, targetNode.id, edge.label, edge.stroke, edge.arrow));
            }
          } else if (fromSg != null && toSg == null) {
            if (!fromSg.nodes.isEmpty()) {
              Node sourceNode = fromSg.nodes.get(fromSg.nodes.size() / 2);
              graph.edges.add(
                  new Edge(sourceNode.id, toToken.id, edge.label, edge.stroke, edge.arrow));
            }
          } else {
            graph.edges.add(
                new Edge(fromToken.id, toToken.id, edge.label, edge.stroke, edge.arrow));
          }
        }
      }

      prevGroup = nextGroup;
    }
  }

  private static List<RawNodeToken> scanNodeGroup(CharScanner s) {
    List<RawNodeToken> group = new ArrayList<>();
    RawNodeToken first = scanNodeToken(s);
    if (first == null) {
      return group;
    }
    group.add(first);

    while (!s.isEof()) {
      s.skipWhitespace();
      if (s.startsWith("&")) {
        s.skip("&");
        s.skipWhitespace();
        RawNodeToken next = scanNodeToken(s);
        if (next != null) {
          group.add(next);
        } else {
          break;
        }
      } else {
        break;
      }
    }
    return group;
  }

  private static void applyNodeToken(
      RawNodeToken token, MermaidGraph graph, @Nullable Subgraph currentSubgraph) {
    if (graph.lookupSubgraph(token.id) != null) {
      return;
    }
    Node node = graph.ensureNode(token.id, currentSubgraph);
    if (token.label != null) {
      node.shape = token.shape;
      node.setLabel(token.label);
      if (node.shape == NodeShape.DIAMOND && node.labelLines.size() == 1) {
        node.labelLines.clear();
        node.labelLines.addAll(wrapDiamondLabel(node.label));
      }
    }
  }

  private static List<String> wrapDiamondLabel(String text) {
    List<String> result = new ArrayList<>();
    String trimmed = text != null ? text.trim() : "";
    if (trimmed.length() <= 16 || !trimmed.contains(" ")) {
      result.add(trimmed);
      return result;
    }
    List<String> words = new ArrayList<>();
    StringBuilder curWord = new StringBuilder();
    for (int i = 0; i < trimmed.length(); i++) {
      char c = trimmed.charAt(i);
      if (Character.isWhitespace(c)) {
        if (curWord.length() > 0) {
          words.add(curWord.toString());
          curWord.setLength(0);
        }
      } else {
        curWord.append(c);
      }
    }
    if (curWord.length() > 0) {
      words.add(curWord.toString());
    }

    int targetLines = Math.max(2, (int) Math.ceil(trimmed.length() / 16.0));
    int targetLen = (int) Math.ceil((double) trimmed.length() / targetLines);

    StringBuilder cur = new StringBuilder();
    for (String w : words) {
      if (cur.isEmpty()) {
        cur.append(w);
      } else if (cur.length() + 1 + w.length() <= Math.max(targetLen + 4, 18)) {
        cur.append(" ").append(w);
      } else {
        result.add(cur.toString());
        cur = new StringBuilder(w);
      }
    }
    if (cur.length() > 0) {
      result.add(cur.toString());
    }
    return result;
  }

  @Nullable
  private static RawNodeToken scanNodeToken(CharScanner s) {
    s.skipWhitespace();
    if (s.isEof()) {
      return null;
    }

    String id = s.scanIdentifier();
    if (id.isEmpty()) {
      return null;
    }

    s.skipWhitespace();
    NodeShape shape = NodeShape.RECTANGLE;
    String label = null;

    String[][] delims = {
      {"[[", "]]", "SUBROUTINE"},
      {"[(", ")]", "CYLINDER"},
      {"([", "])", "STADIUM"},
      {"((", "))", "CIRCLE"},
      {"{{", "}}", "HEXAGON"},
      {"[", "]", "RECTANGLE"},
      {"(", ")", "ROUNDED"},
      {"{", "}", "DIAMOND"},
      {">", "]", "FLAG"}
    };

    for (String[] d : delims) {
      String open = d[0];
      String close = d[1];
      String shapeName = d[2];
      if (s.startsWith(open)) {
        s.skip(open);
        shape = NodeShape.valueOf(shapeName);
        s.skipWhitespace();
        if (s.startsWith("\"")) {
          s.skip("\"");
          int start = s.pos;
          while (!s.isEof() && s.peek() != '\n' && s.peek() != '\r') {
            if (s.startsWith("\\\"")) {
              s.pos += 2;
            } else if (s.startsWith("\"")) {
              break;
            } else {
              s.advance();
            }
          }
          label = s.text.substring(start, s.pos);
          s.skip("\"");
          s.skipWhitespace();
          s.skip(close);
        } else {
          int start = s.pos;
          while (!s.isEof() && s.peek() != '\n' && !s.startsWith(close)) {
            s.advance();
          }
          label = s.text.substring(start, s.pos);
          s.skip(close);
        }
        break;
      }
    }

    return new RawNodeToken(id, shape, label != null ? cleanLabel(label) : null);
  }

  @Nullable
  private static RawEdgeToken scanEdgeToken(CharScanner s) {
    s.skipWhitespace();
    if (s.isEof()) {
      return null;
    }

    // 1. Infix labels: -- label -->, -- "label" -->, == label ==>, -. label .->, -- label ---
    if ((s.startsWith("-- ") || s.startsWith("--\"") || s.startsWith("--\t"))
        && !s.startsWith("-->")
        && !s.startsWith("---|")) {
      s.skip("--");
      s.skipWhitespace();
      int start = s.pos;
      while (!s.isEof() && s.peek() != '\n' && !s.startsWith("-->") && !s.startsWith("---")) {
        s.advance();
      }
      String label = cleanLabel(s.text.substring(start, s.pos));
      boolean arrow = s.tryConsume("-->");
      if (!arrow) {
        s.skip("---");
      }
      return new RawEdgeToken(EdgeStroke.SOLID, arrow, label);
    }

    if ((s.startsWith("== ") || s.startsWith("==\"") || s.startsWith("==\t"))
        && !s.startsWith("==>")
        && !s.startsWith("===|")) {
      s.skip("==");
      s.skipWhitespace();
      int start = s.pos;
      while (!s.isEof() && s.peek() != '\n' && !s.startsWith("==>") && !s.startsWith("===")) {
        s.advance();
      }
      String label = cleanLabel(s.text.substring(start, s.pos));
      boolean arrow = s.tryConsume("==>");
      if (!arrow) {
        s.skip("===");
      }
      return new RawEdgeToken(EdgeStroke.THICK, arrow, label);
    }

    if (s.startsWith("-. ") || s.startsWith("-.\"") || s.startsWith("-.\t")) {
      s.skip("-.");
      s.skipWhitespace();
      int start = s.pos;
      while (!s.isEof() && s.peek() != '\n' && !s.startsWith(".->") && !s.startsWith(".-")) {
        s.advance();
      }
      String label = cleanLabel(s.text.substring(start, s.pos));
      boolean arrow = s.tryConsume(".->");
      if (!arrow) {
        s.skip(".-");
      }
      return new RawEdgeToken(EdgeStroke.DASHED, arrow, label);
    }

    // 2. Standard edge operators with optional |pipe label|
    String[][] ops = {
      {"-.->", "DASHED", "true"},
      {"-.-", "DASHED", "false"},
      {"==>", "THICK", "true"},
      {"===", "THICK", "false"},
      {"-->", "SOLID", "true"},
      {"---", "SOLID", "false"},
      {"<-->", "SOLID", "true"}
    };

    for (String[] op : ops) {
      String prefix = op[0];
      EdgeStroke stroke = EdgeStroke.valueOf(op[1]);
      boolean arrow = Boolean.parseBoolean(op[2]);
      if (s.startsWith(prefix)) {
        s.skip(prefix);
        s.skipWhitespace();
        String label = null;
        if (s.startsWith("|")) {
          s.skip("|");
          int start = s.pos;
          while (!s.isEof() && s.peek() != '\n' && !s.startsWith("|")) {
            s.advance();
          }
          label = cleanLabel(s.text.substring(start, s.pos));
          s.skip("|");
        }
        return new RawEdgeToken(stroke, arrow, label);
      }
    }

    return null;
  }

  private static String cleanLabel(String raw) {
    if (raw == null) {
      return "";
    }
    String s = raw.trim();
    if (s.startsWith("\"") && s.endsWith("\"") && s.length() >= 2) {
      s = s.substring(1, s.length() - 1);
    } else if (s.startsWith("\\\"") && s.endsWith("\\\"") && s.length() >= 4) {
      s = s.substring(2, s.length() - 2);
    }
    if (s.contains("\\\"")) {
      s = s.replace("\\\"", "\"");
    }
    return s;
  }

  private static String stripWhitespace(String s) {
    if (s == null) {
      return "";
    }
    StringBuilder sb = new StringBuilder(s.length());
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (!Character.isWhitespace(c)) {
        sb.append(c);
      }
    }
    return sb.toString();
  }

  private static final Pattern BR_PATTERN = Pattern.compile("(?i)<br\\s*/?>");

  private static List<String> parseLabelLines(String label) {
    List<String> lines = new ArrayList<>();
    if (isNullOrEmpty(label)) {
      lines.add("");
      return lines;
    }
    Matcher matcher = BR_PATTERN.matcher(label);
    int lastEnd = 0;
    while (matcher.find()) {
      lines.add(label.substring(lastEnd, matcher.start()).trim());
      lastEnd = matcher.end();
    }
    lines.add(label.substring(lastEnd).trim());
    return lines;
  }

  // =========================================================================
  // Layout Engine (Delegated to DiagramLayoutEngine)
  // =========================================================================

  private static String layoutAndRenderSvg(MermaidGraph graph) {
    boolean isHorizontal = graph.direction == Direction.LR || graph.direction == Direction.RL;

    // Calculate node dimensions using structured AST labelLines
    for (Node n : graph.nodes.values()) {
      int maxLineLen = 0;
      for (String l : n.labelLines) {
        maxLineLen = Math.max(maxLineLen, l.trim().length());
      }
      if (n.shape == NodeShape.DIAMOND) {
        double tw = maxLineLen * 7.5;
        double th = n.labelLines.size() * 18.0;
        n.width = Math.max(90, tw * 1.5 + 36);
        n.height = max(50, th * 2.2 + 24, n.width * 0.65);
      } else if (n.shape == NodeShape.CYLINDER) {
        n.width = Math.max(80, maxLineLen * 7.5 + 32);
        n.height = Math.max(50, n.labelLines.size() * 18 + 26);
      } else {
        n.width = Math.max(70, maxLineLen * 7.5 + 28);
        n.height = Math.max(38, n.labelLines.size() * 18 + 16);
        if (n.shape == NodeShape.HEXAGON) {
          n.width += 36;
          n.height += 16;
        } else if (n.shape == NodeShape.CIRCLE) {
          double d = Math.max(n.width, n.height) + 10;
          n.width = d;
          n.height = d;
        }
      }
    }

    // 1. Partition graph into connected components
    Map<String, String> parent =
        DiagramLayoutEngine.initUnionFind(graph.nodes, graph.edges, graph.allSubgraphs);
    for (SubgraphEdge se : graph.subgraphEdges) {
      Subgraph fromSg = graph.lookupSubgraph(se.fromSgId);
      Subgraph toSg = graph.lookupSubgraph(se.toSgId);
      if (fromSg != null && toSg != null) {
        String fromSample = DiagramLayoutEngine.getSubgraphSampleNodeId(fromSg);
        String toSample = DiagramLayoutEngine.getSubgraphSampleNodeId(toSg);
        if (fromSample != null && toSample != null) {
          DiagramLayoutEngine.unionSets(parent, fromSample, toSample);
        }
      }
    }

    List<DiagramLayoutEngine.GraphComponent<Node, Edge, Subgraph>> components =
        DiagramLayoutEngine.buildComponents(graph.nodes, graph.edges, graph.allSubgraphs, parent);
    
    for (DiagramLayoutEngine.GraphComponent<Node, Edge, Subgraph> comp : components) {
      if (!comp.subgraphs.isEmpty() && comp.edges.isEmpty() && graph.subgraphEdges.isEmpty()) {
        DiagramLayoutEngine.layoutIsolatedSubgraphs(isHorizontal, comp.subgraphs);
      } else if (!comp.subgraphs.isEmpty()) {
        DiagramLayoutEngine.layoutCompoundComponent(
            isHorizontal, comp, graph.subgraphEdges);
      } else {
        DiagramLayoutEngine.layoutBySugiyamaDag(
            isHorizontal, comp.nodes, comp.edges, Node::new);
      }
      DiagramLayoutEngine.normalizeComponentBounds(comp);
    }

    DiagramLayoutEngine.stackComponents(components, isHorizontal, 48.0);

    // Compute bounding box
    double[] bounds =
        DiagramLayoutEngine.computeGraphBounds(
            graph.nodes.values(), graph.allSubgraphs, graph.edges);
    double minX = bounds[0];
    double minY = bounds[1];
    double maxX = bounds[2];
    double maxY = bounds[3];

    for (Edge e : graph.edges) {
      Node src = graph.nodes.get(e.fromId);
      Node dst = graph.nodes.get(e.toId);
      if (src != null && dst != null) {
        double labelW =
            e.label != null && !e.label.trim().isEmpty() ? e.label.length() * 6.5 + 16 : 0;
        if (src.id.equals(dst.id) || e.isBackEdge || src.layer > dst.layer) {
          int minL = Math.min(src.layer, dst.layer);
          int maxL = Math.max(src.layer, dst.layer);
          if (!isHorizontal) {
            double maxRight = Math.max(src.x + src.width, dst.x + dst.width);
            for (Node n : graph.nodes.values()) {
              if (n.layer >= minL && n.layer <= maxL) {
                maxRight = Math.max(maxRight, n.x + n.width);
              }
            }
            for (Edge oe : graph.edges) {
              for (Node v : oe.virtualNodes) {
                if (v.layer >= minL && v.layer <= maxL) {
                  maxRight = Math.max(maxRight, v.x + v.width);
                }
              }
            }
            double loopOffset =
                Math.max(45.0, labelW / 2.0 + 36.0)
                    + (maxRight - Math.min(src.x + src.width / 2.0, dst.x + dst.width / 2.0)) * 0.4;
            maxX = Math.max(maxX, maxRight + loopOffset + labelW / 2.0 + 12);
          } else {
            double minTop = Math.min(src.y, dst.y);
            for (Node n : graph.nodes.values()) {
              if (n.layer >= minL && n.layer <= maxL) {
                minTop = Math.min(minTop, n.y);
              }
            }
            for (Edge oe : graph.edges) {
              for (Node v : oe.virtualNodes) {
                if (v.layer >= minL && v.layer <= maxL) {
                  minTop = Math.min(minTop, v.y);
                }
              }
            }
            double loopOffset =
                Math.max(45.0, 18.0 + 36.0)
                    + (Math.max(src.y + src.height / 2.0, dst.y + dst.height / 2.0) - minTop) * 0.4;
            minY = Math.min(minY, minTop - loopOffset - 24);
          }
        }
      }
    }

    double padding = 28;
    double offsetX = padding - minX;
    double offsetY = padding - minY;
    double totalWidth = (maxX - minX) + padding * 2;
    double totalHeight = (maxY - minY) + padding * 2;

    DiagramLayoutEngine.translateGraph(
        graph.nodes.values(), graph.allSubgraphs, graph.edges, offsetX, offsetY);

    return renderSvg(graph, isHorizontal, totalWidth, totalHeight);
  }

  // =========================================================================
  // SVG Renderer
  // =========================================================================

  private static String renderSvg(
      MermaidGraph graph, boolean isHorizontal, double width, double height) {

    StringBuilder svg = new StringBuilder(4096);
    DiagramLayoutEngine.appendSvgHeaderAndDefs(
        svg,
        "mermaid-svg",
        width,
        height,
        "mermaid-arrow",
        "mermaid-arrow",
        "#64748b",
        "node-shadow",
        "mermaid-shadow");

    // 1. Render Subgraphs (sorted by depth so parent containers render before child containers)
    List<Subgraph> sortedSubgraphs = new ArrayList<>(graph.allSubgraphs);
    sortedSubgraphs.sort(Comparator.comparingInt(DiagramLayoutEngine::getSubgraphDepth));
    for (Subgraph sg : sortedSubgraphs) {
      renderSubgraph(svg, sg);
    }

    // 2. Render Subgraph Edges
    for (SubgraphEdge se : graph.subgraphEdges) {
      Subgraph sg1 = graph.subgraphsMap.get(se.fromSgId);
      Subgraph sg2 = graph.subgraphsMap.get(se.toSgId);
      if (sg1 != null && sg2 != null) {
        boolean sgEdgeHorizontal = isHorizontal;
        if (sg1.parent != null && sg1.parent.equals(sg2.parent)) {
          sgEdgeHorizontal = DiagramLayoutEngine.isEffectiveHorizontal(sg1.parent, isHorizontal);
        }
        renderSubgraphEdge(svg, sgEdgeHorizontal, graph, sg1, sg2, se);
      }
    }

    // 3. Render Node Edges
    for (Edge e : graph.edges) {
      Node src = graph.nodes.get(e.fromId);
      Node dst = graph.nodes.get(e.toId);
      if (src != null && dst != null) {
        boolean edgeHorizontal = isHorizontal;
        if (src.parentSubgraph != null && src.parentSubgraph.equals(dst.parentSubgraph)) {
          edgeHorizontal =
              DiagramLayoutEngine.isEffectiveHorizontal(src.parentSubgraph, isHorizontal);
        }
        renderEdge(svg, edgeHorizontal, graph, src, dst, e);
      }
    }

    // 4. Render Nodes
    for (Node n : graph.nodes.values()) {
      renderNode(svg, n);
    }

    svg.append("</svg>");
    return svg.toString();
  }

  private static void renderSubgraph(StringBuilder svg, Subgraph sg) {
    int depth = DiagramLayoutEngine.getSubgraphDepth(sg);
    String fill = sg.customFill != null ? sg.customFill : (depth % 2 == 0 ? "#fafafa" : "#f8fafc");
    String stroke = sg.customStroke != null ? sg.customStroke : "#cbd5e1";
    String sgClass =
        sg.customFill == null && sg.customStroke == null
            ? (depth % 2 == 0 ? "mermaid-subgraph" : "mermaid-subgraph mermaid-subgraph--alt")
            : (sg.customFill == null
                ? (depth % 2 == 0
                    ? "mermaid-subgraph-fill"
                    : "mermaid-subgraph-fill mermaid-subgraph-fill--alt")
                : (sg.customStroke == null ? "mermaid-subgraph-stroke" : ""));
    String classAttr = sgClass.isEmpty() ? "" : String.format(" class=\"%s\"", sgClass);
    svg.append(
        String.format(
            Locale.ROOT,
            "  <rect%s x=\"%.1f\" y=\"%.1f\" width=\"%.1f\" height=\"%.1f\" rx=\"8\" fill=\"%s\""
                + " stroke=\"%s\" stroke-width=\"1.5\" stroke-dasharray=\"4,4\" />\n",
            classAttr,
            sg.x,
            sg.y,
            sg.width,
            sg.height,
            fill,
            stroke));
    if (sg.title != null && !sg.title.isEmpty()) {
      String titleColor;
      String titleClassAttr;
      if (sg.customColor != null) {
        titleColor = sg.customColor;
        titleClassAttr = "";
      } else if (sg.customFill != null) {
        boolean light = DiagramLayoutEngine.isLightColor(sg.customFill);
        titleColor = light ? "#334155" : "#bdc1c6";
        titleClassAttr = "";
      } else {
        titleColor = "#334155";
        titleClassAttr = " class=\"mermaid-subgraph-title\"";
      }
      svg.append(
          String.format(
              Locale.ROOT,
              "  <text%s x=\"%.1f\" y=\"%.1f\" font-size=\"12\" font-weight=\"600\""
                  + " fill=\"%s\">%s</text>\n",
              titleClassAttr,
              sg.x + 14,
              sg.y + 18,
              titleColor,
              DiagramLayoutEngine.escapeXml(sg.title)));
    }
  }

  private static void renderNode(StringBuilder svg, Node n) {
    double rx = 6;
    if (n.shape == NodeShape.ROUNDED) {
      rx = 10;
    } else if (n.shape == NodeShape.STADIUM) {
      rx = n.height / 2.0;
    }

    String fill = n.customFill != null ? n.customFill : "#ffffff";
    String stroke = n.customStroke != null ? n.customStroke : "#64748b";
    String nodeClass =
        n.customFill == null && n.customStroke == null
            ? "mermaid-node"
            : (n.customFill == null
                ? "mermaid-node-fill"
                : (n.customStroke == null ? "mermaid-node-stroke" : ""));
    String classAttr = nodeClass.isEmpty() ? "" : String.format(" class=\"%s\"", nodeClass);
    String strokeClassAttr = n.customStroke == null ? " class=\"mermaid-node-stroke\"" : "";

    // Shape Geometry
    switch (n.shape) {
      case NodeShape.CIRCLE -> {
        double r = n.width / 2.0;
        svg.append(
            String.format(
                Locale.ROOT,
                "  <circle%s cx=\"%.1f\" cy=\"%.1f\" r=\"%.1f\" fill=\"%s\" stroke=\"%s\""
                    + " stroke-width=\"1.5\" filter=\"url(#node-shadow)\" />\n",
                classAttr,
                n.x + r,
                n.y + r,
                r,
                fill,
                stroke));
      }
      case NodeShape.DIAMOND ->
          svg.append(
              String.format(
                  Locale.ROOT,
                  "  <polygon%s points=\"%s\" fill=\"%s\""
                      + " stroke=\"%s\" stroke-width=\"1.5\" filter=\"url(#node-shadow)\" />\n",
                  classAttr,
                  DiagramLayoutEngine.formatDiamondPoints(n.x, n.y, n.width, n.height),
                  fill,
                  stroke));
      case NodeShape.HEXAGON ->
          svg.append(
              String.format(
                  Locale.ROOT,
                  "  <polygon%s points=\"%s\""
                      + " fill=\"%s\" stroke=\"%s\" stroke-width=\"1.5\" filter=\"url(#node-shadow)\""
                      + " />\n",
                  classAttr,
                  DiagramLayoutEngine.formatHexagonPoints(n.x, n.y, n.width, n.height, 16.0),
                  fill,
                  stroke));
      case NodeShape.CYLINDER -> {
        double ry = 7.0;
        svg.append(
            String.format(
                Locale.ROOT,
                "  <path%s d=\"%s\" fill=\"%s\" stroke=\"%s\""
                    + " stroke-width=\"1.5\" filter=\"url(#node-shadow)\" />\n",
                classAttr,
                DiagramLayoutEngine.formatCylinderBodyPath(n.x, n.y, n.width, n.height, ry),
                fill,
                stroke));
        svg.append(
            String.format(
                Locale.ROOT,
                "  <path%s d=\"%s\" fill=\"none\" stroke=\"%s\" stroke-width=\"1.5\" />\n",
                strokeClassAttr,
                DiagramLayoutEngine.formatCylinderRimPath(n.x, n.y, n.width, ry),
                stroke));
      }
      case NodeShape.FLAG -> {
        double notch = 12;
        svg.append(
            String.format(
                Locale.ROOT,
                "  <polygon%s points=\"%.1f,%.1f %.1f,%.1f %.1f,%.1f %.1f,%.1f %.1f,%.1f\""
                    + " fill=\"%s\" stroke=\"%s\" stroke-width=\"1.5\" filter=\"url(#node-shadow)\""
                    + " />\n",
                classAttr,
                n.x,
                n.y,
                n.x + n.width,
                n.y,
                n.x + n.width - notch,
                n.y + n.height / 2.0,
                n.x + n.width,
                n.y + n.height,
                n.x,
                n.y + n.height,
                fill,
                stroke));
      }
      case NodeShape.SUBROUTINE -> {
        svg.append(
            String.format(
                Locale.ROOT,
                "  <rect%s x=\"%.1f\" y=\"%.1f\" width=\"%.1f\" height=\"%.1f\" rx=\"4\" fill=\"%s\""
                    + " stroke=\"%s\" stroke-width=\"1.5\" filter=\"url(#node-shadow)\" />\n",
                classAttr,
                n.x,
                n.y,
                n.width,
                n.height,
                fill,
                stroke));
        svg.append(
            String.format(
                Locale.ROOT,
                "  <line%s x1=\"%.1f\" y1=\"%.1f\" x2=\"%.1f\" y2=\"%.1f\" stroke=\"%s\""
                    + " stroke-width=\"1.5\" />\n",
                strokeClassAttr,
                n.x + 10,
                n.y,
                n.x + 10,
                n.y + n.height,
                stroke));
        svg.append(
            String.format(
                Locale.ROOT,
                "  <line%s x1=\"%.1f\" y1=\"%.1f\" x2=\"%.1f\" y2=\"%.1f\" stroke=\"%s\""
                    + " stroke-width=\"1.5\" />\n",
                strokeClassAttr,
                n.x + n.width - 10,
                n.y,
                n.x + n.width - 10,
                n.y + n.height,
                stroke));
      }
      case null, default ->
          svg.append(
              String.format(
                  Locale.ROOT,
                  "  <rect%s x=\"%.1f\" y=\"%.1f\" width=\"%.1f\" height=\"%.1f\" rx=\"%.1f\""
                      + " fill=\"%s\" stroke=\"%s\" stroke-width=\"1.5\""
                      + " filter=\"url(#node-shadow)\" />\n",
                  classAttr,
                  n.x,
                  n.y,
                  n.width,
                  n.height,
                  rx,
                  fill,
                  stroke));
    }

    // Determine text colors and classes
    String primaryTextColor;
    String subtextColor;
    String primaryTextClass;
    String subtextClass;

    if (n.customColor != null) {
      primaryTextColor = n.customColor;
      subtextColor = n.customColor;
      primaryTextClass = "";
      subtextClass = "";
    } else if (n.customFill != null) {
      boolean light = DiagramLayoutEngine.isLightColor(n.customFill);
      primaryTextColor = light ? "#0f172a" : "#e8eaed";
      subtextColor = light ? "#475569" : "#94a3b8";
      primaryTextClass = "";
      subtextClass = "";
    } else {
      primaryTextColor = "#0f172a";
      subtextColor = "#475569";
      primaryTextClass = "mermaid-node-text";
      subtextClass = "mermaid-node-subtext";
    }

    String textClassAttr =
        primaryTextClass.isEmpty() ? "" : String.format(" class=\"%s\"", primaryTextClass);

    // Node Text using structured AST labelLines
    double cx = n.x + n.width / 2.0;
    double textYOffset = n.shape == NodeShape.CYLINDER ? 4.0 : 0.0;
    double startTextY = n.y + textYOffset + (n.height - (n.labelLines.size() - 1) * 16) / 2.0;

    if (n.labelLines.size() == 1) {
      svg.append(
          String.format(
              Locale.ROOT,
              "  <text%s x=\"%.1f\" y=\"%.1f\" font-size=\"12\" font-weight=\"500\" fill=\"%s\""
                  + " text-anchor=\"middle\" dominant-baseline=\"central\">%s</text>\n",
              textClassAttr,
              cx,
              n.y + textYOffset + n.height / 2.0,
              primaryTextColor,
              DiagramLayoutEngine.escapeXml(n.labelLines.get(0).trim())));
    } else {
      svg.append(
          String.format(
              Locale.ROOT,
              "  <text x=\"%.1f\" y=\"%.1f\" font-size=\"12\" text-anchor=\"middle\">\n",
              cx,
              startTextY));
      for (int i = 0; i < n.labelLines.size(); i++) {
        String weight = i == 0 ? "600" : "400";
        String textColor = i == 0 ? primaryTextColor : subtextColor;
        String tspanClass = i == 0 ? primaryTextClass : subtextClass;
        String tspanClassAttr =
            tspanClass.isEmpty() ? "" : String.format(" class=\"%s\"", tspanClass);
        String fontSize = i == 0 ? "12" : "10.5";
        svg.append(
            String.format(
                Locale.ROOT,
                "    <tspan%s x=\"%.1f\" dy=\"%s\" font-size=\"%s\" font-weight=\"%s\""
                    + " fill=\"%s\">%s</tspan>\n",
                tspanClassAttr,
                cx,
                i == 0 ? "0" : "16",
                fontSize,
                weight,
                textColor,
                DiagramLayoutEngine.escapeXml(n.labelLines.get(i).trim())));
      }
      svg.append("  </text>\n");
    }
  }

  private static void renderSubgraphEdge(
      StringBuilder svg,
      boolean isHorizontal,
      MermaidGraph graph,
      Subgraph sg1,
      Subgraph sg2,
      SubgraphEdge se) {

    String strokeDash = se.stroke == EdgeStroke.DASHED ? "stroke-dasharray=\"4,4\" " : "";
    String strokeWidth = se.stroke == EdgeStroke.THICK ? "2.5" : "1.5";
    String marker = se.arrow ? "marker-end=\"url(#mermaid-arrow)\" " : "";

    double startX;
    double startY;
    double endX;
    double endY;
    if (isHorizontal) {
      startX = sg1.x + sg1.width;
      startY = sg1.y + sg1.height / 2.0;
      endX = sg2.x;
      endY = sg2.y + sg2.height / 2.0;

      double minY = Math.min(startY, endY) - 10;
      double maxY = Math.max(startY, endY) + 10;
      boolean blocked = false;
      double maxBottom = Math.max(sg1.y + sg1.height, sg2.y + sg2.height);
      for (Node n : graph.nodes.values()) {
        if (n.x >= startX - 10
            && n.x + n.width <= endX + 10
            && n.y <= maxY
            && n.y + n.height >= minY) {
          blocked = true;
          maxBottom = Math.max(maxBottom, n.y + n.height);
        }
      }

      if (blocked) {
        double labelW =
            se.label != null && !se.label.trim().isEmpty()
                ? se.label.trim().length() * 6.5 + 12
                : 20;
        double loopOffset = Math.max(35.0, labelW / 2.0 + 20.0);
        double cpY = maxBottom + loopOffset;
        svg.append(
            String.format(
                Locale.ROOT,
                "  <path class=\"mermaid-edge\" d=\"M %.1f %.1f C %.1f %.1f, %.1f %.1f, %.1f %.1f\" fill=\"none\""
                    + " stroke=\"#64748b\" stroke-width=\"%s\" %s%s/>\n",
                startX,
                startY,
                startX + 20,
                cpY,
                endX - 20,
                cpY,
                endX,
                endY,
                strokeWidth,
                strokeDash,
                marker));
        if (se.label != null && !se.label.trim().isEmpty()) {
          double midX = (startX + endX) / 2.0;
          renderEdgeLabelBadge(svg, midX, cpY, se.label.trim());
        }
        return;
      }
    } else {
      startX = sg1.x + sg1.width / 2.0;
      startY = sg1.y + sg1.height;
      endX = sg2.x + sg2.width / 2.0;
      endY = sg2.y;

      double minX = Math.min(startX, endX) - 10;
      double maxX = Math.max(startX, endX) + 10;
      boolean blocked = false;
      double maxRight = Math.max(sg1.x + sg1.width, sg2.x + sg2.width);
      for (Node n : graph.nodes.values()) {
        if (n.y >= startY - 10
            && n.y + n.height <= endY + 10
            && n.x <= maxX
            && n.x + n.width >= minX) {
          blocked = true;
          maxRight = Math.max(maxRight, n.x + n.width);
        }
      }

      if (blocked) {
        double labelW =
            se.label != null && !se.label.trim().isEmpty()
                ? se.label.trim().length() * 6.5 + 12
                : 20;
        double loopOffset = Math.max(35.0, labelW / 2.0 + 20.0);
        double cpX = maxRight + loopOffset;
        svg.append(
            String.format(
                Locale.ROOT,
                "  <path class=\"mermaid-edge\" d=\"M %.1f %.1f C %.1f %.1f, %.1f %.1f, %.1f %.1f\" fill=\"none\""
                    + " stroke=\"#64748b\" stroke-width=\"%s\" %s%s/>\n",
                startX,
                startY,
                cpX,
                startY + 20,
                cpX,
                endY - 20,
                endX,
                endY,
                strokeWidth,
                strokeDash,
                marker));
        if (se.label != null && !se.label.trim().isEmpty()) {
          double midY = (startY + endY) / 2.0;
          renderEdgeLabelBadge(svg, cpX, midY, se.label.trim());
        }
        return;
      }
    }

    svg.append(
        String.format(
            Locale.ROOT,
            "  <line class=\"mermaid-edge\" x1=\"%.1f\" y1=\"%.1f\" x2=\"%.1f\" y2=\"%.1f\" stroke=\"#64748b\""
                + " stroke-width=\"%s\" %s%s/>\n",
            startX,
            startY,
            endX,
            endY,
            strokeWidth,
            strokeDash,
            marker));

    if (se.label != null && !se.label.trim().isEmpty()) {
      double midX = (startX + endX) / 2.0;
      double midY = (startY + endY) / 2.0;
      renderEdgeLabelBadge(svg, midX, midY, se.label.trim());
    }
  }

  private static void renderEdge(
      StringBuilder svg, boolean isHorizontal, MermaidGraph graph, Node src, Node dst, Edge e) {

    String strokeDash = e.stroke == EdgeStroke.DASHED ? "stroke-dasharray=\"4,4\" " : "";
    String strokeWidth = e.stroke == EdgeStroke.THICK ? "2.5" : "1.5";
    String marker = e.arrow ? "marker-end=\"url(#mermaid-arrow)\" " : "";

    // Long forward edges spanning multiple ranks route through a chain of Sugiyama virtual dummy
    // nodes as a multi-segment spline and return early here, before the single-segment cubic
    // Bezier endpoint/control-point variables (x1..cp2y) are declared below.
    if (!e.virtualNodes.isEmpty()
        && !src.id.equals(dst.id)
        && !e.isBackEdge
        && src.layer <= dst.layer) {
      List<Double> px = new ArrayList<>();
      List<Double> py = new ArrayList<>();
      if (!isHorizontal) {
        px.add(src.x + src.width / 2.0);
        py.add(src.y + src.height);
        for (Node v : e.virtualNodes) {
          px.add(v.x + v.width / 2.0);
          py.add(v.y + v.height / 2.0);
        }
        px.add(dst.x + dst.width / 2.0);
        py.add(dst.y);
      } else {
        px.add(src.x + src.width);
        py.add(src.y + src.height / 2.0);
        for (Node v : e.virtualNodes) {
          px.add(v.x + v.width / 2.0);
          py.add(v.y + v.height / 2.0);
        }
        px.add(dst.x);
        py.add(dst.y + dst.height / 2.0);
      }

      String pathD = DiagramLayoutEngine.buildMultiSegmentBezierPath(px, py, isHorizontal);
      svg.append(
          String.format(
              Locale.ROOT,
              "  <path class=\"mermaid-edge\" d=\"%s\" fill=\"none\" stroke=\"#64748b\" stroke-width=\"%s\" %s%s/>\n",
              pathD,
              strokeWidth,
              strokeDash,
              marker));

      if (e.label != null && !e.label.trim().isEmpty()) {
        Node firstV = e.virtualNodes.get(0);
        renderEdgeLabelBadge(
            svg, firstV.x + firstV.width / 2.0, firstV.y + firstV.height / 2.0, e.label.trim());
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
              src.x, src.y, src.width, src.height, isHorizontal, 35.0, 20.0);
      x1 = pts[0];
      y1 = pts[1];
      cp1x = pts[2];
      cp1y = pts[3];
      cp2x = pts[4];
      cp2y = pts[5];
      x2 = pts[6];
      y2 = pts[7];
    } else if (e.isBackEdge || src.layer > dst.layer) {
      int minL = Math.min(src.layer, dst.layer);
      int maxL = Math.max(src.layer, dst.layer);
      if (!isHorizontal) {
        x1 = src.x + src.width;
        y1 = src.y + src.height / 2.0;
        x2 = dst.x + dst.width;
        y2 = dst.y + dst.height / 2.0;
        double maxRight = Math.max(x1, x2);
        for (Node n : graph.nodes.values()) {
          if (n.layer >= minL && n.layer <= maxL) {
            maxRight = Math.max(maxRight, n.x + n.width);
          }
        }
        for (Edge oe : graph.edges) {
          for (Node v : oe.virtualNodes) {
            if (v.layer >= minL && v.layer <= maxL) {
              maxRight = Math.max(maxRight, v.x + v.width);
            }
          }
        }
        double labelW =
            e.label != null && !e.label.trim().isEmpty() ? e.label.trim().length() * 6.5 + 16 : 0;
        double loopOffset =
            Math.max(45.0, labelW / 2.0 + 36.0) + (maxRight - Math.min(x1, x2)) * 0.4;
        cp1x = maxRight + loopOffset;
        cp1y = y1;
        cp2x = maxRight + loopOffset;
        cp2y = y2;
      } else {
        x1 = src.x + src.width / 2.0;
        y1 = src.y;
        x2 = dst.x + dst.width / 2.0;
        y2 = dst.y;
        double minTop = Math.min(y1, y2);
        for (Node n : graph.nodes.values()) {
          if (n.layer >= minL && n.layer <= maxL) {
            minTop = Math.min(minTop, n.y);
          }
        }
        for (Edge oe : graph.edges) {
          for (Node v : oe.virtualNodes) {
            if (v.layer >= minL && v.layer <= maxL) {
              minTop = Math.min(minTop, v.y);
            }
          }
        }
        double labelH = 18.0;
        double loopOffset = Math.max(45.0, labelH + 36.0) + (Math.max(y1, y2) - minTop) * 0.4;
        cp1x = x1;
        cp1y = minTop - loopOffset;
        cp2x = x2;
        cp2y = minTop - loopOffset;
      }
    } else {
      double srcAttachW = isHorizontal ? src.width : src.width;
      double[] pts =
          DiagramLayoutEngine.computeForwardBezierControlPoints(
              src.x,
              src.y,
              srcAttachW,
              src.height,
              dst.x,
              dst.y,
              dst.width,
              dst.height,
              isHorizontal);
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
            "  <path class=\"mermaid-edge\" d=\"M %.1f %.1f C %.1f %.1f, %.1f %.1f, %.1f %.1f\" fill=\"none\""
                + " stroke=\"#64748b\" stroke-width=\"%s\" %s%s/>\n",
            x1,
            y1,
            cp1x,
            cp1y,
            cp2x,
            cp2y,
            x2,
            y2,
            strokeWidth,
            strokeDash,
            marker));

    if (e.label != null && !e.label.trim().isEmpty()) {
      double midX = DiagramLayoutEngine.evalCubicBezier(x1, cp1x, cp2x, x2, 0.5);
      double midY = DiagramLayoutEngine.evalCubicBezier(y1, cp1y, cp2y, y2, 0.5);
      renderEdgeLabelBadge(svg, midX, midY, e.label.trim());
    }
  }

  private static void renderEdgeLabelBadge(
      StringBuilder svg, double midX, double midY, String label) {
    double textLen = label.length() * 6.5;
    double rectW = textLen + 12;
    double rectH = 18;
    svg.append(
        String.format(
            Locale.ROOT,
            "  <rect class=\"mermaid-edge-label-bg\" x=\"%.1f\" y=\"%.1f\" width=\"%.1f\" height=\"%.1f\" rx=\"3\" fill=\"#ffffff\""
                + " fill-opacity=\"0.95\" />\n",
            midX - rectW / 2.0,
            midY - rectH / 2.0,
            rectW,
            rectH));
    svg.append(
        String.format(
            Locale.ROOT,
            "  <text class=\"mermaid-edge-label-text\" x=\"%.1f\" y=\"%.1f\" font-size=\"10.5\" fill=\"#475569\""
                + " text-anchor=\"middle\" dominant-baseline=\"central\">%s</text>\n",
            midX,
            midY,
            DiagramLayoutEngine.escapeXml(label)));
  }


  private SimpleMermaidRenderer() {}
}
