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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.annotation.Nullable;

/**
 * Server-side AST parser, layout engine, and SVG renderer for Mermaid flowchart and graph diagrams.
 *
 * <p>Implements a pure streaming character-scanner AST parser without regex splits, hierarchical
 * Sugiyama DAG layout with cycle breaking, crossing reduction, arbitrary nested subgraphs,
 * dynamic edge clearances, bidirectional curved paths, and responsive SVG emission.
 */
public class SimpleMermaidRenderer {

  public enum Direction {
    LR,
    TD,
    TB,
    RL,
    BT
  }

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

  public enum EdgeStroke {
    SOLID,
    DASHED,
    THICK
  }

  // =========================================================================
  // AST Model Objects
  // =========================================================================

  public static class Node {
    public final String id;
    public String label;
    public final List<String> labelLines = new ArrayList<>();
    public NodeShape shape = NodeShape.RECTANGLE;
    public int layer = 0;
    public double relX;
    public double relY;
    public double x;
    public double y;
    public double width = 160;
    public double height = 44;
    public Subgraph parentSubgraph;
    public double barycenter = 0;
    public boolean isVirtual = false;
    public @Nullable String customFill;
    public @Nullable String customStroke;

    public Node(String id) {
      this.id = id;
      setLabel(id);
    }

    public void setLabel(String rawLabel) {
      this.label = rawLabel != null ? rawLabel : id;
      this.labelLines.clear();
      this.labelLines.addAll(parseLabelLines(this.label));
    }
  }

  public static class Subgraph {
    public final String id;
    public String title;
    public Direction direction;
    public Subgraph parent;
    public final List<Subgraph> children = new ArrayList<>();
    public final List<Node> nodes = new ArrayList<>();
    public double relX;
    public double relY;
    public double x;
    public double y;
    public double width;
    public double height;
    public @Nullable String customFill;
    public @Nullable String customStroke;

    public Subgraph(String id, String title) {
      this.id = id;
      this.title = title;
    }
  }

  public static class Edge {
    public final String fromId;
    public final String toId;
    public final String label;
    public final EdgeStroke stroke;
    public final boolean arrow;
    public boolean isBackEdge = false;
    public final List<Node> virtualNodes = new ArrayList<>();

    public Edge(String fromId, String toId, String label, EdgeStroke stroke, boolean arrow) {
      this.fromId = fromId;
      this.toId = toId;
      this.label = label;
      this.stroke = stroke;
      this.arrow = arrow;
    }
  }

  public static class SubgraphEdge {
    public final String fromSgId;
    public final String toSgId;
    public final String label;
    public final EdgeStroke stroke;
    public final boolean arrow;

    public SubgraphEdge(
        String fromSgId, String toSgId, String label, EdgeStroke stroke, boolean arrow) {
      this.fromSgId = fromSgId;
      this.toSgId = toSgId;
      this.label = label;
      this.stroke = stroke;
      this.arrow = arrow;
    }
  }

  public static class MermaidGraph {
    public Direction direction = Direction.TD;
    public final Map<String, Node> nodes = new LinkedHashMap<>();
    public final Map<String, Subgraph> subgraphsMap = new LinkedHashMap<>();
    public final List<Subgraph> rootSubgraphs = new ArrayList<>();
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

    public @Nullable Subgraph lookupSubgraph(String name) {
      if (name == null) return null;
      String clean = name.trim();
      Subgraph sg = subgraphsMap.get(clean);
      if (sg != null) return sg;
      sg = subgraphsMap.get(stripWhitespace(clean));
      if (sg != null) return sg;
      sg = subgraphsMap.get(clean.toLowerCase());
      if (sg != null) return sg;
      sg = subgraphsMap.get(stripWhitespace(clean).toLowerCase());
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
      this.text = text != null ? text : "";
      this.pos = 0;
    }

    boolean isEof() {
      return pos >= text.length();
    }

    char peek() {
      return isEof() ? '\0' : text.charAt(pos);
    }

    char next() {
      return isEof() ? '\0' : text.charAt(pos++);
    }

    boolean startsWith(String prefix) {
      return text.startsWith(prefix, pos);
    }

    boolean startsWithIgnoreCase(String prefix) {
      if (text.length() - pos < prefix.length()) return false;
      return text.substring(pos, pos + prefix.length()).equalsIgnoreCase(prefix);
    }

    boolean consume(String prefix) {
      if (startsWith(prefix)) {
        pos += prefix.length();
        return true;
      }
      return false;
    }

    boolean consumeIgnoreCase(String prefix) {
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
        if (c == '\n') break;
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
    if (!graphOpt.isPresent()) {
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
      if (s.isEof()) break;

      if (s.startsWith("%%")) {
        s.skipLine();
        continue;
      }

      if (s.consumeIgnoreCase("graph") || s.consumeIgnoreCase("flowchart")) {
        s.skipWhitespace();
        String dirStr = s.scanIdentifier().toUpperCase();
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
      if (s.isEof()) break;

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

      if (s.consumeIgnoreCase("direction")) {
        s.skipWhitespace();
        String dirStr = s.scanIdentifier().toUpperCase();
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

      if (s.consumeIgnoreCase("end")) {
        char nextC = s.peek();
        if (nextC == '\0' || Character.isWhitespace(nextC) || nextC == ';') {
          if (!subgraphStack.isEmpty()) {
            subgraphStack.pop();
          }
          s.skipToStatementEnd();
          continue;
        }
      }

      if (s.consumeIgnoreCase("subgraph")) {
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
    String sgId, sgTitle;

    // Check for `subgraph "Title Only"`
    if (s.startsWith("\"")) {
      s.consume("\"");
      int start = s.pos;
      while (!s.isEof() && !s.startsWith("\"")) s.next();
      sgTitle = s.text.substring(start, s.pos);
      s.consume("\"");
      sgId = "sg_" + graph.allSubgraphs.size();
    } else {
      int start = s.pos;
      while (!s.isEof() && !s.startsWith("[") && !s.startsWith("\"") && s.peek() != '\n' && s.peek() != ';') {
        s.next();
      }
      String rawName = s.text.substring(start, s.pos).trim();
      s.skipWhitespace();
      if (s.startsWith("[")) {
        s.consume("[");
        s.skipWhitespace();
        boolean quoted = s.consume("\"");
        int tstart = s.pos;
        if (quoted) {
          while (!s.isEof() && !s.startsWith("\"]") && !s.startsWith("\"")) s.next();
          sgTitle = s.text.substring(tstart, s.pos);
          s.consume("\"");
          s.consume("]");
        } else {
          while (!s.isEof() && !s.startsWith("]")) s.next();
          sgTitle = s.text.substring(tstart, s.pos);
          s.consume("]");
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
    graph.subgraphsMap.put(sgId.toLowerCase(), sg);
    graph.subgraphsMap.put(sgTitle.toLowerCase(), sg);
    graph.allSubgraphs.add(sg);
  }

  private static void parseStyleDirective(CharScanner s, MermaidGraph graph) {
    s.consumeIgnoreCase("style");
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
      if (c == '\n' || c == '\r' || c == ';') break;
      s.pos++;
    }
    String rest = s.text.substring(start, s.pos);
    s.skipToStatementEnd();

    String fill = null;
    String stroke = null;
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
        String key = part.substring(0, colonIdx).trim().toLowerCase();
        String val = part.substring(colonIdx + 1).trim();
        if (key.equals("fill")) {
          fill = val;
        } else if (key.equals("stroke")) {
          stroke = val;
        }
      }
      p = nextSep + 1;
    }

    if (fill != null && !isValidCssColor(fill)) {
      fill = null;
    }
    if (stroke != null && !isValidCssColor(stroke)) {
      stroke = null;
    }

    Subgraph sg = graph.lookupSubgraph(targetId);
    if (sg != null) {
      if (fill != null) sg.customFill = fill;
      if (stroke != null) sg.customStroke = stroke;
    }
    Node n = graph.nodes.get(targetId);
    if (n != null) {
      if (fill != null) n.customFill = fill;
      if (stroke != null) n.customStroke = stroke;
    }
  }

  private static boolean isValidCssColor(@Nullable String val) {
    if (val == null || val.isEmpty()) return false;
    String v = val.trim().toLowerCase();
    if (v.startsWith("javascript:")
        || v.startsWith("data:")
        || v.contains("url(")
        || v.contains("\"")
        || v.contains("'")
        || v.contains("<")
        || v.contains(">")) {
      return false;
    }
    if (v.matches("^#[0-9a-f]{3,8}$")) {
      return true;
    }
    if (v.matches("^[a-z]{3,20}$")) {
      return true;
    }
    return v.matches("^(rgb|hsl)a?\\([0-9%,. ]+\\)$");
  }

  private static void parseStatement(
      CharScanner s, MermaidGraph graph, @Nullable Subgraph currentSubgraph) {
    List<RawNodeToken> prevGroup = scanNodeGroup(s);
    if (prevGroup.isEmpty()) return;

    for (RawNodeToken token : prevGroup) {
      applyNodeToken(token, graph, currentSubgraph);
    }

    while (!s.isEof()) {
      char c = s.peek();
      if (c == ';' || c == '\n' || c == '\r') break;

      RawEdgeToken edge = scanEdgeToken(s);
      if (edge == null) break;

      List<RawNodeToken> nextGroup = scanNodeGroup(s);
      if (nextGroup.isEmpty()) break;

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
    if (first == null) return group;
    group.add(first);

    while (!s.isEof()) {
      s.skipWhitespace();
      if (s.startsWith("&")) {
        s.consume("&");
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
    String trimmed = (text != null) ? text.trim() : "";
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
      if (cur.length() == 0) {
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

  private static @Nullable RawNodeToken scanNodeToken(CharScanner s) {
    s.skipWhitespace();
    if (s.isEof()) return null;

    String id = s.scanIdentifier();
    if (id.isEmpty()) return null;

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
        s.consume(open);
        shape = NodeShape.valueOf(shapeName);
        s.skipWhitespace();
        if (s.startsWith("\"")) {
          s.consume("\"");
          int start = s.pos;
          while (!s.isEof() && s.peek() != '\n' && s.peek() != '\r') {
            if (s.startsWith("\\\"")) {
              s.pos += 2;
            } else if (s.startsWith("\"")) {
              break;
            } else {
              s.next();
            }
          }
          label = s.text.substring(start, s.pos);
          s.consume("\"");
          s.skipWhitespace();
          s.consume(close);
        } else {
          int start = s.pos;
          while (!s.isEof() && s.peek() != '\n' && !s.startsWith(close)) {
            s.next();
          }
          label = s.text.substring(start, s.pos);
          s.consume(close);
        }
        break;
      }
    }

    return new RawNodeToken(id, shape, label != null ? cleanLabel(label) : null);
  }

  private static @Nullable RawEdgeToken scanEdgeToken(CharScanner s) {
    s.skipWhitespace();
    if (s.isEof()) return null;

    // 1. Infix labels: -- label -->, -- "label" -->, == label ==>, -. label .->, -- label ---
    if ((s.startsWith("-- ") || s.startsWith("--\"") || s.startsWith("--\t"))
        && !s.startsWith("-->")
        && !s.startsWith("---|")) {
      s.consume("--");
      s.skipWhitespace();
      int start = s.pos;
      while (!s.isEof() && s.peek() != '\n' && !s.startsWith("-->") && !s.startsWith("---")) {
        s.next();
      }
      String label = cleanLabel(s.text.substring(start, s.pos));
      boolean arrow = s.consume("-->");
      if (!arrow) s.consume("---");
      return new RawEdgeToken(EdgeStroke.SOLID, arrow, label);
    }

    if ((s.startsWith("== ") || s.startsWith("==\"") || s.startsWith("==\t"))
        && !s.startsWith("==>")
        && !s.startsWith("===|")) {
      s.consume("==");
      s.skipWhitespace();
      int start = s.pos;
      while (!s.isEof() && s.peek() != '\n' && !s.startsWith("==>") && !s.startsWith("===")) {
        s.next();
      }
      String label = cleanLabel(s.text.substring(start, s.pos));
      boolean arrow = s.consume("==>");
      if (!arrow) s.consume("===");
      return new RawEdgeToken(EdgeStroke.THICK, arrow, label);
    }

    if (s.startsWith("-. ") || s.startsWith("-.\"") || s.startsWith("-.\t")) {
      s.consume("-.");
      s.skipWhitespace();
      int start = s.pos;
      while (!s.isEof() && s.peek() != '\n' && !s.startsWith(".->") && !s.startsWith(".-")) {
        s.next();
      }
      String label = cleanLabel(s.text.substring(start, s.pos));
      boolean arrow = s.consume(".->");
      if (!arrow) s.consume(".-");
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
        s.consume(prefix);
        s.skipWhitespace();
        String label = null;
        if (s.startsWith("|")) {
          s.consume("|");
          int start = s.pos;
          while (!s.isEof() && s.peek() != '\n' && !s.startsWith("|")) {
            s.next();
          }
          label = cleanLabel(s.text.substring(start, s.pos));
          s.consume("|");
        }
        return new RawEdgeToken(stroke, arrow, label);
      }
    }

    return null;
  }

  private static String cleanLabel(String raw) {
    if (raw == null) return "";
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
    if (s == null) return "";
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
    if (label == null || label.isEmpty()) {
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
  // Layout Engine
  // =========================================================================

  private static String layoutAndRenderSvg(MermaidGraph graph) {
    boolean isHorizontal = (graph.direction == Direction.LR || graph.direction == Direction.RL);

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
        n.height = Math.max(50, Math.max(th * 2.2 + 24, n.width * 0.65));
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
    Map<String, String> parent = new HashMap<>();
    for (String id : graph.nodes.keySet()) {
      parent.put(id, id);
    }
    for (Edge e : graph.edges) {
      if (parent.containsKey(e.fromId) && parent.containsKey(e.toId)) {
        unionSets(parent, e.fromId, e.toId);
      }
    }
    for (Subgraph sg : graph.allSubgraphs) {
      String sampleId = getSubgraphSampleNodeId(sg);
      if (sg.nodes.size() > 1) {
        String firstId = sg.nodes.get(0).id;
        for (int i = 1; i < sg.nodes.size(); i++) {
          unionSets(parent, firstId, sg.nodes.get(i).id);
        }
      }
      for (Subgraph child : sg.children) {
        String childSample = getSubgraphSampleNodeId(child);
        if (sampleId != null && childSample != null) {
          unionSets(parent, sampleId, childSample);
        }
      }
    }
    for (SubgraphEdge se : graph.subgraphEdges) {
      Subgraph fromSg = graph.lookupSubgraph(se.fromSgId);
      Subgraph toSg = graph.lookupSubgraph(se.toSgId);
      if (fromSg != null && toSg != null) {
        String fromSample = getSubgraphSampleNodeId(fromSg);
        String toSample = getSubgraphSampleNodeId(toSg);
        if (fromSample != null && toSample != null) {
          unionSets(parent, fromSample, toSample);
        }
      }
    }

    Map<String, GraphComponent> compMap = new LinkedHashMap<>();
    for (Node n : graph.nodes.values()) {
      String root = findRoot(parent, n.id);
      GraphComponent comp = compMap.computeIfAbsent(root, k -> new GraphComponent());
      comp.nodes.put(n.id, n);
    }

    for (Edge e : graph.edges) {
      String root = findRoot(parent, e.fromId);
      GraphComponent comp = compMap.get(root);
      if (comp != null && comp.nodes.containsKey(e.fromId) && comp.nodes.containsKey(e.toId)) {
        comp.edges.add(e);
      }
    }

    for (Subgraph sg : graph.allSubgraphs) {
      String sampleId = getSubgraphSampleNodeId(sg);
      if (sampleId != null) {
        String root = findRoot(parent, sampleId);
        GraphComponent comp = compMap.get(root);
        if (comp != null && !comp.subgraphs.contains(sg)) {
          comp.subgraphs.add(sg);
        }
      }
    }

    List<GraphComponent> components = new ArrayList<>(compMap.values());
    components.sort((c1, c2) -> Boolean.compare(!c2.subgraphs.isEmpty(), !c1.subgraphs.isEmpty()));

    for (GraphComponent comp : components) {
      if (!comp.subgraphs.isEmpty() && comp.edges.isEmpty() && graph.subgraphEdges.isEmpty()) {
        layoutIsolatedSubgraphs(graph.direction, comp.subgraphs);
      } else if (!comp.subgraphs.isEmpty()) {
        layoutCompoundComponent(graph, graph.direction, isHorizontal, comp);
      } else {
        layoutBySugiyamaDAG(isHorizontal, comp.nodes, comp.edges);
      }

      double cMinX = Double.MAX_VALUE, cMinY = Double.MAX_VALUE;
      double cMaxX = Double.MIN_VALUE, cMaxY = Double.MIN_VALUE;
      for (Node n : comp.nodes.values()) {
        cMinX = Math.min(cMinX, n.x);
        cMinY = Math.min(cMinY, n.y);
        cMaxX = Math.max(cMaxX, n.x + n.width);
        cMaxY = Math.max(cMaxY, n.y + n.height);
      }
      for (Subgraph sg : comp.subgraphs) {
        cMinX = Math.min(cMinX, sg.x);
        cMinY = Math.min(cMinY, sg.y);
        cMaxX = Math.max(cMaxX, sg.x + sg.width);
        cMaxY = Math.max(cMaxY, sg.y + sg.height);
      }

      if (cMinX != Double.MAX_VALUE) {
        comp.width = cMaxX - cMinX;
        comp.height = cMaxY - cMinY;
        for (Node n : comp.nodes.values()) {
          n.x -= cMinX;
          n.y -= cMinY;
        }
        for (Subgraph sg : comp.subgraphs) {
          sg.x -= cMinX;
          sg.y -= cMinY;
        }
        for (Edge e : comp.edges) {
          for (Node v : e.virtualNodes) {
            v.x -= cMinX;
            v.y -= cMinY;
          }
        }
      }
    }

    if (!isHorizontal) {
      double curX = 0;
      for (GraphComponent comp : components) {
        for (Node n : comp.nodes.values()) {
          n.x += curX;
        }
        for (Subgraph sg : comp.subgraphs) {
          sg.x += curX;
        }
        for (Edge e : comp.edges) {
          for (Node v : e.virtualNodes) {
            v.x += curX;
          }
        }
        curX += comp.width + 48;
      }
    } else {
      double curY = 0;
      for (GraphComponent comp : components) {
        for (Node n : comp.nodes.values()) {
          n.y += curY;
        }
        for (Subgraph sg : comp.subgraphs) {
          sg.y += curY;
        }
        for (Edge e : comp.edges) {
          for (Node v : e.virtualNodes) {
            v.y += curY;
          }
        }
        curY += comp.height + 48;
      }
    }

    // Compute bounding box
    double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE;
    double maxX = Double.MIN_VALUE, maxY = Double.MIN_VALUE;

    for (Node n : graph.nodes.values()) {
      minX = Math.min(minX, n.x);
      minY = Math.min(minY, n.y);
      maxX = Math.max(maxX, n.x + n.width);
      maxY = Math.max(maxY, n.y + n.height);
    }
    for (Subgraph sg : graph.allSubgraphs) {
      minX = Math.min(minX, sg.x);
      minY = Math.min(minY, sg.y);
      maxX = Math.max(maxX, sg.x + sg.width);
      maxY = Math.max(maxY, sg.y + sg.height);
    }
    for (Edge e : graph.edges) {
      for (Node v : e.virtualNodes) {
        minX = Math.min(minX, v.x);
        minY = Math.min(minY, v.y);
        maxX = Math.max(maxX, v.x + v.width);
        maxY = Math.max(maxY, v.y + v.height);
      }
    }

    for (Edge e : graph.edges) {
      Node src = graph.nodes.get(e.fromId);
      Node dst = graph.nodes.get(e.toId);
      if (src != null && dst != null) {
        double labelW =
            (e.label != null && !e.label.trim().isEmpty()) ? (e.label.length() * 6.5 + 16) : 0;
        if (e.isBackEdge || src.layer > dst.layer) {
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

    for (Node n : graph.nodes.values()) {
      n.x += offsetX;
      n.y += offsetY;
    }
    for (Subgraph sg : graph.allSubgraphs) {
      sg.x += offsetX;
      sg.y += offsetY;
    }

    return renderSvg(graph, isHorizontal, totalWidth, totalHeight);
  }

  private static void layoutBySugiyamaDAG(
      boolean isHorizontal,
      Map<String, Node> allNodes,
      List<Edge> edges) {

    // 1. Cycle Breaking via DFS
    Map<String, List<Edge>> adj = new HashMap<>();
    for (String id : allNodes.keySet()) {
      adj.put(id, new ArrayList<>());
    }
    for (Edge e : edges) {
      if (adj.containsKey(e.fromId)) {
        adj.get(e.fromId).add(e);
      }
    }

    Map<String, Integer> color = new HashMap<>();
    for (String id : allNodes.keySet()) {
      if (color.getOrDefault(id, 0) == 0) {
        findCyclesDFS(id, adj, color);
      }
    }

    // 2. Layer Assignment (Longest Path in DAG)
    for (Node n : allNodes.values()) {
      n.layer = 0;
    }
    boolean changed = true;
    int maxIterations = allNodes.size() + 1;
    int iter = 0;
    while (changed && iter++ < maxIterations) {
      changed = false;
      for (Edge e : edges) {
        if (!e.isBackEdge) {
          Node src = allNodes.get(e.fromId);
          Node dst = allNodes.get(e.toId);
          if (src != null && dst != null) {
            if (dst.layer < src.layer + 1) {
              dst.layer = src.layer + 1;
              changed = true;
            }
          }
        }
      }
    }

    // 2a. Source Node Sinking / Compaction (ALAP for loose source nodes)
    for (Node n : allNodes.values()) {
      int inCount = 0;
      int minOutLayer = Integer.MAX_VALUE;
      for (Edge e : edges) {
        if (!e.isBackEdge) {
          if (e.toId.equals(n.id)) inCount++;
          if (e.fromId.equals(n.id)) {
            Node dst = allNodes.get(e.toId);
            if (dst != null) {
              minOutLayer = Math.min(minOutLayer, dst.layer);
            }
          }
        }
      }
      if (inCount == 0 && minOutLayer != Integer.MAX_VALUE && minOutLayer - 1 > n.layer) {
        n.layer = minOutLayer - 1;
      }
    }

    // 2b. Virtual Dummy Node Insertion for Long Edges
    for (Edge e : edges) {
      e.virtualNodes.clear();
      if (!e.isBackEdge) {
        Node src = allNodes.get(e.fromId);
        Node dst = allNodes.get(e.toId);
        if (src != null && dst != null && dst.layer - src.layer > 1) {
          for (int l = src.layer + 1; l < dst.layer; l++) {
            Node dummy = new Node("__v_" + src.id + "_" + dst.id + "_" + l);
            dummy.isVirtual = true;
            dummy.layer = l;
            if (e.label != null && !e.label.trim().isEmpty() && l == src.layer + 1) {
              dummy.width = Math.max(e.label.trim().length() * 6.5 + 24, 60);
              dummy.height = 20;
            } else {
              dummy.width = 24;
              dummy.height = 20;
            }
            e.virtualNodes.add(dummy);
          }
        }
      }
    }

    // 3. Layer Ordering & Barycentric Crossing Reduction
    Map<Integer, List<Node>> layerMap = new TreeMap<>();
    for (Node n : allNodes.values()) {
      layerMap.computeIfAbsent(n.layer, k -> new ArrayList<>()).add(n);
    }
    for (Edge e : edges) {
      for (Node v : e.virtualNodes) {
        layerMap.computeIfAbsent(v.layer, k -> new ArrayList<>()).add(v);
      }
    }

    int maxLayer = layerMap.isEmpty() ? 0 : Collections.max(layerMap.keySet());
    for (int l = 1; l <= maxLayer; l++) {
      List<Node> currentLayer = layerMap.get(l);
      List<Node> prevLayer = layerMap.get(l - 1);
      if (currentLayer == null) continue;
      for (Node n : currentLayer) {
        double sum = 0;
        int count = 0;
        if (prevLayer != null) {
          if (n.isVirtual) {
            for (Edge e : edges) {
              int vIdx = e.virtualNodes.indexOf(n);
              if (vIdx != -1) {
                Node pred = vIdx == 0 ? allNodes.get(e.fromId) : e.virtualNodes.get(vIdx - 1);
                if (pred != null) {
                  int pos = prevLayer.indexOf(pred);
                  if (pos != -1) {
                    sum += pos;
                    count++;
                  }
                }
                break;
              }
            }
          } else {
            for (Edge e : edges) {
              if (e.toId.equals(n.id) && !e.isBackEdge) {
                Node pred;
                if (!e.virtualNodes.isEmpty()) {
                  pred = e.virtualNodes.get(e.virtualNodes.size() - 1);
                } else {
                  pred = allNodes.get(e.fromId);
                }
                if (pred != null && pred.layer == l - 1) {
                  int pos = prevLayer.indexOf(pred);
                  if (pos != -1) {
                    sum += pos;
                    count++;
                  }
                }
              }
            }
          }
        }
        n.barycenter = count > 0 ? sum / count : currentLayer.indexOf(n);
      }
      currentLayer.sort(Comparator.comparingDouble(n -> n.barycenter));
    }

    // 4. Coordinate Assignment
    if (!isHorizontal) {
      // Top-Down
      double maxGraphWidth = 0;
      Map<Integer, Double> layerWidths = new HashMap<>();
      Map<Integer, Double> layerMaxHeights = new HashMap<>();

      for (Map.Entry<Integer, List<Node>> entry : layerMap.entrySet()) {
        int l = entry.getKey();
        List<Node> nodes = entry.getValue();
        double totalW = 0;
        double maxH = 38;
        for (int i = 0; i < nodes.size(); i++) {
          Node n = nodes.get(i);
          totalW += n.width + (i < nodes.size() - 1 ? 32 : 0);
          if (!n.isVirtual) {
            maxH = Math.max(maxH, n.height);
          }
        }
        layerWidths.put(l, totalW);
        layerMaxHeights.put(l, maxH);
        maxGraphWidth = Math.max(maxGraphWidth, totalW);
      }

      double curY = 0;
      for (Map.Entry<Integer, List<Node>> entry : layerMap.entrySet()) {
        int l = entry.getKey();
        List<Node> nodes = entry.getValue();
        double w = layerWidths.get(l);
        double curX = (maxGraphWidth - w) / 2.0;
        double maxH = layerMaxHeights.get(l);

        if (nodes.size() == 1 && l > 0) {
          Node single = nodes.get(0);
          double parentAvgX = 0;
          int parentCount = 0;
          for (Edge e : edges) {
            if (e.toId.equals(single.id)) {
              Node p = allNodes.get(e.fromId);
              if (p != null && p.layer == l - 1) {
                parentAvgX += p.x + p.width / 2.0;
                parentCount++;
              }
            }
          }
          if (parentCount > 0) {
            double targetX = (parentAvgX / parentCount) - single.width / 2.0;
            curX = Math.max(0, Math.min(targetX, maxGraphWidth - single.width));
          }
        }

        for (int i = 0; i < nodes.size(); i++) {
          Node n = nodes.get(i);
          n.x = curX;
          n.y = curY + (maxH - n.height) / 2.0;
          curX += n.width + 32;
        }

        double layerGap = 48;
        for (Edge e : edges) {
          Node src = allNodes.get(e.fromId);
          Node dst = allNodes.get(e.toId);
          if (src != null && dst != null) {
            if ((e.virtualNodes.isEmpty() && src.layer == l && dst.layer == l + 1)
                || (!e.virtualNodes.isEmpty() && src.layer == l)) {
              if (e.label != null && !e.label.trim().isEmpty()) {
                layerGap = Math.max(layerGap, 60);
              }
            }
          }
        }
        curY += maxH + layerGap;
      }
    } else {
      // Left-to-Right
      double maxGraphHeight = 0;
      Map<Integer, Double> layerHeights = new HashMap<>();
      Map<Integer, Double> layerMaxWidths = new HashMap<>();

      for (Map.Entry<Integer, List<Node>> entry : layerMap.entrySet()) {
        int l = entry.getKey();
        List<Node> nodes = entry.getValue();
        double totalH = 0;
        double maxW = 120;
        for (int i = 0; i < nodes.size(); i++) {
          Node n = nodes.get(i);
          totalH += n.height + (i < nodes.size() - 1 ? 24 : 0);
          if (!n.isVirtual) {
            maxW = Math.max(maxW, n.width);
          }
        }
        layerHeights.put(l, totalH);
        layerMaxWidths.put(l, maxW);
        maxGraphHeight = Math.max(maxGraphHeight, totalH);
      }

      double curX = 0;
      for (Map.Entry<Integer, List<Node>> entry : layerMap.entrySet()) {
        int l = entry.getKey();
        List<Node> nodes = entry.getValue();
        double h = layerHeights.get(l);
        double curY = (maxGraphHeight - h) / 2.0;
        double maxW = layerMaxWidths.get(l);

        if (nodes.size() == 1 && l > 0) {
          Node single = nodes.get(0);
          double parentAvgY = 0;
          int parentCount = 0;
          for (Edge e : edges) {
            if (e.toId.equals(single.id)) {
              Node p = allNodes.get(e.fromId);
              if (p != null && p.layer == l - 1) {
                parentAvgY += p.y + p.height / 2.0;
                parentCount++;
              }
            }
          }
          if (parentCount > 0) {
            double targetY = (parentAvgY / parentCount) - single.height / 2.0;
            curY = Math.max(0, Math.min(targetY, maxGraphHeight - single.height));
          }
        }

        for (int i = 0; i < nodes.size(); i++) {
          Node n = nodes.get(i);
          n.x = curX + (maxW - n.width) / 2.0;
          n.y = curY;
          curY += n.height + 24;
        }

        double layerGap = 55;
        for (Edge e : edges) {
          Node src = allNodes.get(e.fromId);
          Node dst = allNodes.get(e.toId);
          if (src != null && dst != null) {
            if ((e.virtualNodes.isEmpty() && src.layer == l && dst.layer == l + 1)
                || (!e.virtualNodes.isEmpty() && src.layer == l)) {
              if (e.label != null && !e.label.trim().isEmpty()) {
                double lw = e.label.trim().length() * 6.5 + 24;
                layerGap = Math.max(layerGap, lw + 24);
              }
            }
          }
        }
        curX += maxW + layerGap;
      }
    }
  }

  private static void findCyclesDFS(
      String u, Map<String, List<Edge>> adj, Map<String, Integer> color) {
    color.put(u, 1); // Gray
    List<Edge> uEdges = adj.get(u);
    if (uEdges != null) {
      for (Edge e : uEdges) {
        String v = e.toId;
        int vColor = color.getOrDefault(v, 0);
        if (vColor == 1) {
          e.isBackEdge = true;
        } else if (vColor == 0) {
          findCyclesDFS(v, adj, color);
        }
      }
    }
    color.put(u, 2); // Black
  }

  private static @Nullable String getSubgraphSampleNodeId(Subgraph sg) {
    if (!sg.nodes.isEmpty()) {
      return sg.nodes.get(0).id;
    }
    for (Subgraph child : sg.children) {
      String id = getSubgraphSampleNodeId(child);
      if (id != null) return id;
    }
    return null;
  }

  private static int getSubgraphDepth(Subgraph sg) {
    int depth = 0;
    Subgraph cur = sg.parent;
    while (cur != null) {
      depth++;
      cur = cur.parent;
    }
    return depth;
  }

  private static class LayoutUnit {
    final String id;
    final @Nullable Subgraph subgraph;
    final @Nullable Node node;
    double width;
    double height;
    double x;
    double y;
    int layer = 0;
    double barycenter = 0;

    LayoutUnit(Subgraph sg) {
      this.id = "sg_" + sg.id;
      this.subgraph = sg;
      this.node = null;
      this.width = sg.width;
      this.height = sg.height;
    }

    LayoutUnit(Node n) {
      this.id = "n_" + n.id;
      this.subgraph = null;
      this.node = n;
      this.width = n.width;
      this.height = n.height;
    }
  }

  private static void registerNodesToUnit(
      Subgraph sg, LayoutUnit u, Map<String, LayoutUnit> map) {
    for (Node n : sg.nodes) {
      map.put(n.id, u);
    }
    for (Subgraph child : sg.children) {
      registerNodesToUnit(child, u, map);
    }
  }

  private static void layoutUnits(
      boolean isHorizontal,
      List<LayoutUnit> units,
      Map<String, LayoutUnit> unitMap,
      List<Edge> unitEdges) {
    if (units.size() <= 1) return;

    // 1. Cycle Breaking
    Map<String, List<Edge>> uAdj = new HashMap<>();
    for (LayoutUnit u : units) {
      uAdj.put(u.id, new ArrayList<>());
    }
    for (Edge ue : unitEdges) {
      if (uAdj.containsKey(ue.fromId)) {
        uAdj.get(ue.fromId).add(ue);
      }
    }
    Map<String, Integer> uColor = new HashMap<>();
    for (LayoutUnit u : units) {
      if (uColor.getOrDefault(u.id, 0) == 0) {
        findCyclesDFS(u.id, uAdj, uColor);
      }
    }

    // 2. Layer Assignment
    for (LayoutUnit u : units) {
      u.layer = 0;
    }
    boolean changed = true;
    int maxIter = units.size() + 1;
    int iter = 0;
    while (changed && iter++ < maxIter) {
      changed = false;
      for (Edge ue : unitEdges) {
        if (!ue.isBackEdge) {
          LayoutUnit src = unitMap.get(ue.fromId);
          LayoutUnit dst = unitMap.get(ue.toId);
          if (src != null && dst != null) {
            if (dst.layer < src.layer + 1) {
              dst.layer = src.layer + 1;
              changed = true;
            }
          }
        }
      }
    }

    // 2a. Source Unit Sinking / Compaction
    for (LayoutUnit u : units) {
      int inCount = 0;
      int minOutLayer = Integer.MAX_VALUE;
      for (Edge ue : unitEdges) {
        if (!ue.isBackEdge) {
          LayoutUnit src = unitMap.get(ue.fromId);
          LayoutUnit dst = unitMap.get(ue.toId);
          if (dst != null && dst.id.equals(u.id) && (src == null || !src.id.equals(u.id))) inCount++;
          if (src != null && src.id.equals(u.id) && dst != null && !dst.id.equals(u.id)) {
            minOutLayer = Math.min(minOutLayer, dst.layer);
          }
        }
      }
      if (inCount == 0 && minOutLayer != Integer.MAX_VALUE && minOutLayer - 1 > u.layer) {
        u.layer = minOutLayer - 1;
      }
    }

    // 3. Layer Map & Barycentric Ordering
    Map<Integer, List<LayoutUnit>> layerMap = new TreeMap<>();
    for (LayoutUnit u : units) {
      layerMap.computeIfAbsent(u.layer, k -> new ArrayList<>()).add(u);
    }
    int maxLayer = layerMap.isEmpty() ? 0 : Collections.max(layerMap.keySet());
    for (int l = 1; l <= maxLayer; l++) {
      List<LayoutUnit> currentLayer = layerMap.get(l);
      List<LayoutUnit> prevLayer = layerMap.get(l - 1);
      if (currentLayer == null) continue;
      for (LayoutUnit u : currentLayer) {
        double sum = 0;
        int count = 0;
        if (prevLayer != null) {
          for (Edge ue : unitEdges) {
            if (ue.toId.equals(u.id) && !ue.isBackEdge) {
              LayoutUnit src = unitMap.get(ue.fromId);
              if (src != null && src.layer == l - 1) {
                int pos = prevLayer.indexOf(src);
                if (pos != -1) {
                  sum += pos;
                  count++;
                }
              }
            }
          }
        }
        u.barycenter = count > 0 ? sum / count : currentLayer.indexOf(u);
      }
      currentLayer.sort(Comparator.comparingDouble(u -> u.barycenter));
    }

    // 4. Coordinate Assignment
    if (!isHorizontal) {
      // Top-Down
      double maxW = 0;
      Map<Integer, Double> layerWidths = new HashMap<>();
      Map<Integer, Double> layerMaxHeights = new HashMap<>();
      for (Map.Entry<Integer, List<LayoutUnit>> entry : layerMap.entrySet()) {
        int l = entry.getKey();
        List<LayoutUnit> lUnits = entry.getValue();
        double tw = 0;
        double mh = 0;
        for (int i = 0; i < lUnits.size(); i++) {
          LayoutUnit u = lUnits.get(i);
          tw += u.width + (i < lUnits.size() - 1 ? 36 : 0);
          mh = Math.max(mh, u.height);
        }
        layerWidths.put(l, tw);
        layerMaxHeights.put(l, mh);
        maxW = Math.max(maxW, tw);
      }

      double curY = 0;
      for (Map.Entry<Integer, List<LayoutUnit>> entry : layerMap.entrySet()) {
        int l = entry.getKey();
        List<LayoutUnit> lUnits = entry.getValue();
        double w = layerWidths.get(l);
        double curX = (maxW - w) / 2.0;
        double mh = layerMaxHeights.get(l);
        for (LayoutUnit u : lUnits) {
          u.x = curX;
          u.y = curY + (mh - u.height) / 2.0;
          curX += u.width + 36;
        }
        double layerGap = 45;
        for (Edge ue : unitEdges) {
          LayoutUnit src = unitMap.get(ue.fromId);
          LayoutUnit dst = unitMap.get(ue.toId);
          if (src != null && dst != null && src.layer == l && dst.layer == l + 1) {
            if (ue.label != null && !ue.label.trim().isEmpty()) {
              layerGap = Math.max(layerGap, 55);
            }
          }
        }
        curY += mh + layerGap;
      }
    } else {
      // Left-to-Right
      double maxH = 0;
      Map<Integer, Double> layerHeights = new HashMap<>();
      Map<Integer, Double> layerMaxWidths = new HashMap<>();
      for (Map.Entry<Integer, List<LayoutUnit>> entry : layerMap.entrySet()) {
        int l = entry.getKey();
        List<LayoutUnit> lUnits = entry.getValue();
        double th = 0;
        double mw = 0;
        for (int i = 0; i < lUnits.size(); i++) {
          LayoutUnit u = lUnits.get(i);
          th += u.height + (i < lUnits.size() - 1 ? 36 : 0);
          mw = Math.max(mw, u.width);
        }
        layerHeights.put(l, th);
        layerMaxWidths.put(l, mw);
        maxH = Math.max(maxH, th);
      }

      double curX = 0;
      for (Map.Entry<Integer, List<LayoutUnit>> entry : layerMap.entrySet()) {
        int l = entry.getKey();
        List<LayoutUnit> lUnits = entry.getValue();
        double h = layerHeights.get(l);
        double curY = (maxH - h) / 2.0;
        double mw = layerMaxWidths.get(l);
        for (LayoutUnit u : lUnits) {
          u.x = curX + (mw - u.width) / 2.0;
          u.y = curY;
          curY += u.height + 36;
        }

        double layerGap = 45;
        for (Edge ue : unitEdges) {
          LayoutUnit src = unitMap.get(ue.fromId);
          LayoutUnit dst = unitMap.get(ue.toId);
          if (src != null && dst != null && src.layer == l && dst.layer == l + 1) {
            if (ue.label != null && !ue.label.trim().isEmpty()) {
              double lw = ue.label.trim().length() * 6.5 + 24;
              layerGap = Math.max(layerGap, lw + 24);
            }
          }
        }
        curX += mw + layerGap;
      }
    }
  }

  private static void layoutCompoundComponent(
      MermaidGraph graph, Direction compDir, boolean isHorizontal, GraphComponent comp) {

    // 1. Find root subgraphs and recursively compute internal sizes
    List<Subgraph> rootSgs = new ArrayList<>();
    for (Subgraph sg : comp.subgraphs) {
      if (sg.parent == null || !comp.subgraphs.contains(sg.parent)) {
        rootSgs.add(sg);
      }
    }
    for (Subgraph sg : rootSgs) {
      computeSubgraphSizes(
          sg,
          rootSgs.size() == 1 && sg.direction != null ? sg.direction : compDir,
          comp.edges,
          graph.subgraphEdges,
          comp.nodes);
    }

    // 2. Build LayoutUnits for root subgraphs and standalone nodes
    List<LayoutUnit> units = new ArrayList<>();
    Map<String, LayoutUnit> unitMap = new LinkedHashMap<>();

    for (Subgraph sg : rootSgs) {
      LayoutUnit u = new LayoutUnit(sg);
      units.add(u);
      unitMap.put(u.id, u);
    }
    for (Node n : comp.nodes.values()) {
      if (n.parentSubgraph == null) {
        LayoutUnit u = new LayoutUnit(n);
        units.add(u);
        unitMap.put(u.id, u);
      }
    }

    if (units.size() == 1) {
      LayoutUnit u = units.get(0);
      u.x = 0;
      u.y = 0;
      if (u.subgraph != null) {
        u.subgraph.x = 0;
        u.subgraph.y = 0;
        assignAbsoluteCoordinates(u.subgraph, 0, 0);
      }
      return;
    }

    // 3. Map each Node ID to its root LayoutUnit
    Map<String, LayoutUnit> nodeToUnit = new HashMap<>();
    for (LayoutUnit u : units) {
      if (u.node != null) {
        nodeToUnit.put(u.node.id, u);
      } else if (u.subgraph != null) {
        registerNodesToUnit(u.subgraph, u, nodeToUnit);
      }
    }

    // 4. Build Unit Edges (meta-graph)
    List<Edge> unitEdges = new ArrayList<>();
    Set<String> seenUnitEdges = new HashSet<>();
    for (Edge e : comp.edges) {
      LayoutUnit u1 = nodeToUnit.get(e.fromId);
      LayoutUnit u2 = nodeToUnit.get(e.toId);
      if (u1 != null && u2 != null && !u1.id.equals(u2.id)) {
        String key = u1.id + "->" + u2.id;
        if (!seenUnitEdges.contains(key)) {
          seenUnitEdges.add(key);
          Edge ue = new Edge(u1.id, u2.id, e.label, e.stroke, e.arrow);
          unitEdges.add(ue);
        }
      }
    }
    for (SubgraphEdge se : graph.subgraphEdges) {
      Subgraph sg1 = graph.lookupSubgraph(se.fromSgId);
      Subgraph sg2 = graph.lookupSubgraph(se.toSgId);
      if (sg1 != null && sg2 != null) {
        String s1Id = getSubgraphSampleNodeId(sg1);
        String s2Id = getSubgraphSampleNodeId(sg2);
        LayoutUnit u1 = s1Id != null ? nodeToUnit.get(s1Id) : unitMap.get("sg_" + sg1.id);
        LayoutUnit u2 = s2Id != null ? nodeToUnit.get(s2Id) : unitMap.get("sg_" + sg2.id);
        if (u1 != null && u2 != null && !u1.id.equals(u2.id)) {
          String key = u1.id + "->" + u2.id;
          if (!seenUnitEdges.contains(key)) {
            seenUnitEdges.add(key);
            Edge ue = new Edge(u1.id, u2.id, se.label, se.stroke, se.arrow);
            unitEdges.add(ue);
          }
        }
      }
    }

    // 5. Run Sugiyama Layout on Units
    layoutUnits(isHorizontal, units, unitMap, unitEdges);

    // 6. Assign Absolute Coordinates
    for (LayoutUnit u : units) {
      if (u.subgraph != null) {
        u.subgraph.x = u.x;
        u.subgraph.y = u.y;
        assignAbsoluteCoordinates(u.subgraph, u.x, u.y);
      } else if (u.node != null) {
        u.node.x = u.x;
        u.node.y = u.y;
      }
    }
  }

  private static void computeSubgraphSizes(
      Subgraph sg,
      Direction parentDirection,
      List<Edge> edges,
      List<SubgraphEdge> subgraphEdges,
      Map<String, Node> allNodes) {
    Direction dir = sg.direction != null ? sg.direction : parentDirection;
    boolean isHorizontal = (dir == Direction.LR || dir == Direction.RL);

    // 1. Recursively compute internal sizes of all child subgraphs
    for (Subgraph child : sg.children) {
      computeSubgraphSizes(child, dir, edges, subgraphEdges, allNodes);
    }

    double padding = 20;
    double headerH = 28;

    // 2. Build LayoutUnits for direct child subgraphs and direct child nodes
    List<LayoutUnit> units = new ArrayList<>();
    Map<String, LayoutUnit> unitMap = new LinkedHashMap<>();

    for (Subgraph child : sg.children) {
      LayoutUnit u = new LayoutUnit(child);
      units.add(u);
      unitMap.put(u.id, u);
    }
    for (Node n : sg.nodes) {
      LayoutUnit u = new LayoutUnit(n);
      units.add(u);
      unitMap.put(u.id, u);
    }

    if (units.isEmpty()) {
      sg.width = 100;
      sg.height = 60;
      return;
    }

    if (units.size() == 1) {
      LayoutUnit u = units.get(0);
      u.x = padding;
      u.y = headerH + padding;
      if (u.subgraph != null) {
        u.subgraph.relX = u.x;
        u.subgraph.relY = u.y;
      } else if (u.node != null) {
        u.node.relX = u.x;
        u.node.relY = u.y;
      }
      sg.width = u.width + padding * 2;
      sg.height = u.height + padding * 2 + headerH;
      return;
    }

    // 3. Map each Node ID to its immediate LayoutUnit inside sg
    Map<String, LayoutUnit> nodeToUnit = new HashMap<>();
    for (LayoutUnit u : units) {
      if (u.node != null) {
        nodeToUnit.put(u.node.id, u);
      } else if (u.subgraph != null) {
        registerNodesToUnit(u.subgraph, u, nodeToUnit);
      }
    }

    // 4. Build Unit Edges (meta-graph) for edges where both endpoints are in sg
    List<Edge> unitEdges = new ArrayList<>();
    Set<String> seenUnitEdges = new HashSet<>();
    for (Edge e : edges) {
      LayoutUnit u1 = nodeToUnit.get(e.fromId);
      LayoutUnit u2 = nodeToUnit.get(e.toId);
      if (u1 != null && u2 != null && !u1.id.equals(u2.id)) {
        String key = u1.id + "->" + u2.id;
        if (!seenUnitEdges.contains(key)) {
          seenUnitEdges.add(key);
          Edge ue = new Edge(u1.id, u2.id, e.label, e.stroke, e.arrow);
          unitEdges.add(ue);
        }
      }
    }
    for (SubgraphEdge se : subgraphEdges) {
      Subgraph sg1 = lookupSubgraphInTree(sg, se.fromSgId);
      Subgraph sg2 = lookupSubgraphInTree(sg, se.toSgId);
      if (sg1 != null && sg2 != null) {
        String s1Id = getSubgraphSampleNodeId(sg1);
        String s2Id = getSubgraphSampleNodeId(sg2);
        LayoutUnit u1 = s1Id != null ? nodeToUnit.get(s1Id) : unitMap.get("sg_" + sg1.id);
        LayoutUnit u2 = s2Id != null ? nodeToUnit.get(s2Id) : unitMap.get("sg_" + sg2.id);
        if (u1 != null && u2 != null && !u1.id.equals(u2.id)) {
          String key = u1.id + "->" + u2.id;
          if (!seenUnitEdges.contains(key)) {
            seenUnitEdges.add(key);
            Edge ue = new Edge(u1.id, u2.id, se.label, se.stroke, se.arrow);
            unitEdges.add(ue);
          }
        }
      }
    }

    // 5. Run Sugiyama DAG layout on units
    layoutUnits(isHorizontal, units, unitMap, unitEdges);

    // 6. Assign relative coordinates inside sg and compute sg dimensions
    double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE;
    double maxX = Double.MIN_VALUE, maxY = Double.MIN_VALUE;
    for (LayoutUnit u : units) {
      minX = Math.min(minX, u.x);
      minY = Math.min(minY, u.y);
      maxX = Math.max(maxX, u.x + u.width);
      maxY = Math.max(maxY, u.y + u.height);
    }

    for (LayoutUnit u : units) {
      double rx = padding + (u.x - minX);
      double ry = headerH + padding + (u.y - minY);
      if (u.subgraph != null) {
        u.subgraph.relX = rx;
        u.subgraph.relY = ry;
      } else if (u.node != null) {
        u.node.relX = rx;
        u.node.relY = ry;
      }
    }

    sg.width = (maxX - minX) + padding * 2;
    sg.height = (maxY - minY) + padding * 2 + headerH;
  }

  private static @Nullable Subgraph lookupSubgraphInTree(Subgraph root, String id) {
    if (root.id.equals(id)) return root;
    for (Subgraph child : root.children) {
      Subgraph res = lookupSubgraphInTree(child, id);
      if (res != null) return res;
    }
    return null;
  }

  private static void assignAbsoluteCoordinates(Subgraph sg, double parentAbsX, double parentAbsY) {
    for (Subgraph child : sg.children) {
      child.x = parentAbsX + child.relX;
      child.y = parentAbsY + child.relY;
      assignAbsoluteCoordinates(child, child.x, child.y);
    }
    for (Node n : sg.nodes) {
      n.x = parentAbsX + n.relX;
      n.y = parentAbsY + n.relY;
    }
  }

  private static Direction getSubgraphEffectiveDirection(@Nullable Subgraph sg, Direction fallback) {
    Subgraph cur = sg;
    while (cur != null) {
      if (cur.direction != null) return cur.direction;
      cur = cur.parent;
    }
    return fallback;
  }

  // =========================================================================
  // SVG Renderer
  // =========================================================================

  private static String renderSvg(
      MermaidGraph graph,
      boolean isHorizontal,
      double width,
      double height) {

    StringBuilder svg = new StringBuilder(4096);
    svg.append(
        String.format(
            "<svg class=\"mermaid-svg\" xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 %.0f %.0f\" style=\"max-width: %.0fpx; width: 100%%; height: auto;\">\n",
            width, height, width));

    svg.append("  <defs>\n");
    svg.append(
        "    <marker id=\"mermaid-arrow\" viewBox=\"0 0 10 10\" refX=\"8\" refY=\"5\" markerWidth=\"7\" markerHeight=\"7\" orient=\"auto-start-reverse\">\n");
    svg.append("      <path d=\"M 0 1.5 L 10 5 L 0 8.5 z\" fill=\"#64748b\" />\n");
    svg.append("    </marker>\n");
    svg.append("    <filter id=\"node-shadow\" x=\"-5%\" y=\"-5%\" width=\"115%\" height=\"120%\">\n");
    svg.append("      <feDropShadow dx=\"0\" dy=\"1.5\" stdDeviation=\"2\" flood-color=\"#0f172a\" flood-opacity=\"0.06\" />\n");
    svg.append("    </filter>\n");
    svg.append("  </defs>\n");

    // 1. Render Subgraphs (sorted by depth so parent containers render before child containers)
    List<Subgraph> sortedSubgraphs = new ArrayList<>(graph.allSubgraphs);
    sortedSubgraphs.sort(Comparator.comparingInt(SimpleMermaidRenderer::getSubgraphDepth));
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
          Direction sgDir = getSubgraphEffectiveDirection(sg1.parent, graph.direction);
          sgEdgeHorizontal = (sgDir == Direction.LR || sgDir == Direction.RL);
        }
        renderSubgraphEdge(svg, sgEdgeHorizontal, sg1, sg2, se);
      }
    }

    // 3. Render Node Edges
    for (Edge e : graph.edges) {
      Node src = graph.nodes.get(e.fromId);
      Node dst = graph.nodes.get(e.toId);
      if (src != null && dst != null) {
        boolean edgeHorizontal = isHorizontal;
        if (src.parentSubgraph != null && src.parentSubgraph.equals(dst.parentSubgraph)) {
          Direction sgDir = getSubgraphEffectiveDirection(src.parentSubgraph, graph.direction);
          edgeHorizontal = (sgDir == Direction.LR || sgDir == Direction.RL);
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
    int depth = getSubgraphDepth(sg);
    String fill = sg.customFill != null ? sg.customFill : (depth % 2 == 0 ? "#fafafa" : "#f8fafc");
    String stroke = sg.customStroke != null ? sg.customStroke : "#cbd5e1";
    svg.append(
        String.format(
            "  <rect x=\"%.1f\" y=\"%.1f\" width=\"%.1f\" height=\"%.1f\" rx=\"8\" fill=\"%s\" stroke=\"%s\" stroke-width=\"1.5\" stroke-dasharray=\"4,4\" />\n",
            sg.x, sg.y, sg.width, sg.height, fill, stroke));
    if (sg.title != null && !sg.title.isEmpty()) {
      svg.append(
          String.format(
              "  <text x=\"%.1f\" y=\"%.1f\" font-size=\"12\" font-weight=\"600\" fill=\"#334155\">%s</text>\n",
              sg.x + 14, sg.y + 18, escapeXml(sg.title)));
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

    // Shape Geometry
    if (n.shape == NodeShape.CIRCLE) {
      double r = n.width / 2.0;
      svg.append(
          String.format(
              "  <circle cx=\"%.1f\" cy=\"%.1f\" r=\"%.1f\" fill=\"%s\" stroke=\"%s\" stroke-width=\"1.5\" filter=\"url(#node-shadow)\" />\n",
              n.x + r, n.y + r, r, fill, stroke));
    } else if (n.shape == NodeShape.DIAMOND) {
      double cx = n.x + n.width / 2.0;
      double cy = n.y + n.height / 2.0;
      svg.append(
          String.format(
              "  <polygon points=\"%.1f,%.1f %.1f,%.1f %.1f,%.1f %.1f,%.1f\" fill=\"%s\" stroke=\"%s\" stroke-width=\"1.5\" filter=\"url(#node-shadow)\" />\n",
              cx, n.y, n.x + n.width, cy, cx, n.y + n.height, n.x, cy, fill, stroke));
    } else if (n.shape == NodeShape.HEXAGON) {
      double h2 = n.height / 2.0;
      double indent = 16;
      svg.append(
          String.format(
              "  <polygon points=\"%.1f,%.1f %.1f,%.1f %.1f,%.1f %.1f,%.1f %.1f,%.1f %.1f,%.1f\" fill=\"%s\" stroke=\"%s\" stroke-width=\"1.5\" filter=\"url(#node-shadow)\" />\n",
              n.x + indent, n.y,
              n.x + n.width - indent, n.y,
              n.x + n.width, n.y + h2,
              n.x + n.width - indent, n.y + n.height,
              n.x + indent, n.y + n.height,
              n.x, n.y + h2, fill, stroke));
    } else if (n.shape == NodeShape.CYLINDER) {
      double ry = 7.0;
      double rxCyl = n.width / 2.0;
      double h = n.height;
      svg.append(
          String.format(
              "  <path d=\"M %.1f %.1f a %.1f,%.1f 0 1,0 %.1f,0 a %.1f,%.1f 0 1,0 -%.1f,0 l 0,%.1f a %.1f,%.1f 0 0,0 %.1f,0 l 0,-%.1f Z\" fill=\"%s\" stroke=\"%s\" stroke-width=\"1.5\" filter=\"url(#node-shadow)\" />\n",
              n.x, n.y + ry, rxCyl, ry, n.width, rxCyl, ry, n.width, h - ry * 2, rxCyl, ry, n.width, h - ry * 2, fill, stroke));
      svg.append(
          String.format(
              "  <path d=\"M %.1f %.1f a %.1f,%.1f 0 0,0 %.1f,0\" fill=\"none\" stroke=\"%s\" stroke-width=\"1.5\" />\n",
              n.x, n.y + ry, rxCyl, ry, n.width, stroke));
    } else if (n.shape == NodeShape.FLAG) {
      double notch = 12;
      svg.append(
          String.format(
              "  <polygon points=\"%.1f,%.1f %.1f,%.1f %.1f,%.1f %.1f,%.1f %.1f,%.1f\" fill=\"%s\" stroke=\"%s\" stroke-width=\"1.5\" filter=\"url(#node-shadow)\" />\n",
              n.x, n.y, n.x + n.width, n.y, n.x + n.width - notch, n.y + n.height / 2.0, n.x + n.width, n.y + n.height, n.x, n.y + n.height, fill, stroke));
    } else if (n.shape == NodeShape.SUBROUTINE) {
      svg.append(
          String.format(
              "  <rect x=\"%.1f\" y=\"%.1f\" width=\"%.1f\" height=\"%.1f\" rx=\"4\" fill=\"%s\" stroke=\"%s\" stroke-width=\"1.5\" filter=\"url(#node-shadow)\" />\n",
              n.x, n.y, n.width, n.height, fill, stroke));
      svg.append(
          String.format(
              "  <line x1=\"%.1f\" y1=\"%.1f\" x2=\"%.1f\" y2=\"%.1f\" stroke=\"%s\" stroke-width=\"1.5\" />\n",
              n.x + 10, n.y, n.x + 10, n.y + n.height, stroke));
      svg.append(
          String.format(
              "  <line x1=\"%.1f\" y1=\"%.1f\" x2=\"%.1f\" y2=\"%.1f\" stroke=\"%s\" stroke-width=\"1.5\" />\n",
              n.x + n.width - 10, n.y, n.x + n.width - 10, n.y + n.height, stroke));
    } else {
      svg.append(
          String.format(
              "  <rect x=\"%.1f\" y=\"%.1f\" width=\"%.1f\" height=\"%.1f\" rx=\"%.1f\" fill=\"%s\" stroke=\"%s\" stroke-width=\"1.5\" filter=\"url(#node-shadow)\" />\n",
              n.x, n.y, n.width, n.height, rx, fill, stroke));
    }

    // Node Text using structured AST labelLines
    double cx = n.x + n.width / 2.0;
    double textYOffset = n.shape == NodeShape.CYLINDER ? 4.0 : 0.0;
    double startTextY = n.y + textYOffset + (n.height - (n.labelLines.size() - 1) * 16) / 2.0;

    if (n.labelLines.size() == 1) {
      svg.append(
          String.format(
              "  <text x=\"%.1f\" y=\"%.1f\" font-size=\"12\" font-weight=\"500\" fill=\"#0f172a\" text-anchor=\"middle\" dominant-baseline=\"central\">%s</text>\n",
              cx, n.y + textYOffset + n.height / 2.0, escapeXml(n.labelLines.get(0).trim())));
    } else {
      svg.append(
          String.format(
              "  <text x=\"%.1f\" y=\"%.1f\" font-size=\"12\" text-anchor=\"middle\">\n",
              cx, startTextY));
      for (int i = 0; i < n.labelLines.size(); i++) {
        String weight = i == 0 ? "600" : "400";
        String textColor = i == 0 ? "#0f172a" : "#475569";
        String fontSize = i == 0 ? "12" : "10.5";
        svg.append(
            String.format(
                "    <tspan x=\"%.1f\" dy=\"%s\" font-size=\"%s\" font-weight=\"%s\" fill=\"%s\">%s</tspan>\n",
                cx, i == 0 ? "0" : "16", fontSize, weight, textColor, escapeXml(n.labelLines.get(i).trim())));
      }
      svg.append("  </text>\n");
    }
  }

  private static void renderSubgraphEdge(
      StringBuilder svg,
      boolean isHorizontal,
      Subgraph sg1,
      Subgraph sg2,
      SubgraphEdge se) {

    String strokeDash = se.stroke == EdgeStroke.DASHED ? "stroke-dasharray=\"4,4\" " : "";
    String strokeWidth = se.stroke == EdgeStroke.THICK ? "2.5" : "1.5";
    String marker = se.arrow ? "marker-end=\"url(#mermaid-arrow)\" " : "";

    double startX, startY, endX, endY;
    if (isHorizontal) {
      startX = sg1.x + sg1.width;
      startY = sg1.y + sg1.height / 2.0;
      endX = sg2.x;
      endY = sg2.y + sg2.height / 2.0;
    } else {
      startX = sg1.x + sg1.width / 2.0;
      startY = sg1.y + sg1.height;
      endX = sg2.x + sg2.width / 2.0;
      endY = sg2.y;
    }

    svg.append(
        String.format(
            "  <line x1=\"%.1f\" y1=\"%.1f\" x2=\"%.1f\" y2=\"%.1f\" stroke=\"#64748b\" stroke-width=\"%s\" %s%s/>\n",
            startX, startY, endX, endY, strokeWidth, strokeDash, marker));

    if (se.label != null && !se.label.trim().isEmpty()) {
      double midX = (startX + endX) / 2.0;
      double midY = (startY + endY) / 2.0;
      renderEdgeLabelBadge(svg, midX, midY, se.label.trim());
    }
  }

  private static void renderEdge(
      StringBuilder svg,
      boolean isHorizontal,
      MermaidGraph graph,
      Node src,
      Node dst,
      Edge e) {

    String strokeDash = e.stroke == EdgeStroke.DASHED ? "stroke-dasharray=\"4,4\" " : "";
    String strokeWidth = e.stroke == EdgeStroke.THICK ? "2.5" : "1.5";
    String marker = e.arrow ? "marker-end=\"url(#mermaid-arrow)\" " : "";

    double x1, y1, x2, y2;
    double cp1x, cp1y, cp2x, cp2y;

    if (!isHorizontal) {
      if (src.id.equals(dst.id)) {
        // Self-loop
        x1 = src.x + src.width;
        y1 = src.y + src.height * 0.3;
        x2 = src.x + src.width;
        y2 = src.y + src.height * 0.7;
        cp1x = x1 + 35;
        cp1y = y1 - 20;
        cp2x = x2 + 35;
        cp2y = y2 + 20;
      } else if (e.isBackEdge || src.layer > dst.layer) {
        // Cycle back-edge loop
        x1 = src.x + src.width;
        y1 = src.y + src.height / 2.0;
        x2 = dst.x + dst.width;
        y2 = dst.y + dst.height / 2.0;
        double maxRight = Math.max(x1, x2);
        int minL = Math.min(src.layer, dst.layer);
        int maxL = Math.max(src.layer, dst.layer);
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
            (e.label != null && !e.label.trim().isEmpty()) ? (e.label.trim().length() * 6.5 + 16) : 0;
        double loopOffset =
            Math.max(45.0, labelW / 2.0 + 36.0) + (maxRight - Math.min(x1, x2)) * 0.4;
        cp1x = maxRight + loopOffset;
        cp1y = y1;
        cp2x = maxRight + loopOffset;
        cp2y = y2;
      } else if (!e.virtualNodes.isEmpty()) {
        List<Double> px = new ArrayList<>();
        List<Double> py = new ArrayList<>();
        px.add(src.x + src.width / 2.0);
        py.add(src.y + src.height);
        for (Node v : e.virtualNodes) {
          px.add(v.x + v.width / 2.0);
          py.add(v.y + v.height / 2.0);
        }
        px.add(dst.x + dst.width / 2.0);
        py.add(dst.y);

        StringBuilder pathD = new StringBuilder();
        pathD.append(String.format("M %.1f %.1f", px.get(0), py.get(0)));
        for (int i = 0; i < px.size() - 1; i++) {
          double xA = px.get(i), yA = py.get(i);
          double xB = px.get(i + 1), yB = py.get(i + 1);
          double dy = yB - yA;
          pathD.append(
              String.format(
                  " C %.1f %.1f, %.1f %.1f, %.1f %.1f",
                  xA, yA + dy * 0.5, xB, yB - dy * 0.5, xB, yB));
        }
        svg.append(
            String.format(
                "  <path d=\"%s\" fill=\"none\" stroke=\"#64748b\" stroke-width=\"%s\" %s%s/>\n",
                pathD.toString(), strokeWidth, strokeDash, marker));

        if (e.label != null && !e.label.trim().isEmpty()) {
          Node firstV = e.virtualNodes.get(0);
          renderEdgeLabelBadge(svg, firstV.x + firstV.width / 2.0, firstV.y + firstV.height / 2.0, e.label.trim());
        }
        return;
      } else {
        // Standard forward edge
        x1 = src.x + src.width / 2.0;
        y1 = src.y + src.height;
        x2 = dst.x + dst.width / 2.0;
        y2 = dst.y;
        double dy = y2 - y1;
        cp1x = x1;
        cp1y = y1 + dy * 0.5;
        cp2x = x2;
        cp2y = y1 + dy * 0.5;
      }
    } else {
      // Horizontal (LR)
      if (src.id.equals(dst.id)) {
        x1 = src.x + src.width * 0.3;
        y1 = src.y;
        x2 = src.x + src.width * 0.7;
        y2 = src.y;
        cp1x = x1 - 20;
        cp1y = y1 - 35;
        cp2x = x2 + 20;
        cp2y = y2 - 35;
      } else if (e.isBackEdge || src.layer > dst.layer) {
        x1 = src.x + src.width / 2.0;
        y1 = src.y;
        x2 = dst.x + dst.width / 2.0;
        y2 = dst.y;
        double minTop = Math.min(y1, y2);
        int minL = Math.min(src.layer, dst.layer);
        int maxL = Math.max(src.layer, dst.layer);
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
        double loopOffset =
            Math.max(45.0, labelH + 36.0) + (Math.max(y1, y2) - minTop) * 0.4;
        cp1x = x1;
        cp1y = minTop - loopOffset;
        cp2x = x2;
        cp2y = minTop - loopOffset;
      } else if (!e.virtualNodes.isEmpty()) {
        List<Double> px = new ArrayList<>();
        List<Double> py = new ArrayList<>();
        px.add(src.x + src.width);
        py.add(src.y + src.height / 2.0);
        for (Node v : e.virtualNodes) {
          px.add(v.x + v.width / 2.0);
          py.add(v.y + v.height / 2.0);
        }
        px.add(dst.x);
        py.add(dst.y + dst.height / 2.0);

        StringBuilder pathD = new StringBuilder();
        pathD.append(String.format("M %.1f %.1f", px.get(0), py.get(0)));
        for (int i = 0; i < px.size() - 1; i++) {
          double xA = px.get(i), yA = py.get(i);
          double xB = px.get(i + 1), yB = py.get(i + 1);
          double dx = xB - xA;
          pathD.append(
              String.format(
                  " C %.1f %.1f, %.1f %.1f, %.1f %.1f",
                  xA + dx * 0.5, yA, xB - dx * 0.5, yB, xB, yB));
        }
        svg.append(
            String.format(
                "  <path d=\"%s\" fill=\"none\" stroke=\"#64748b\" stroke-width=\"%s\" %s%s/>\n",
                pathD.toString(), strokeWidth, strokeDash, marker));

        if (e.label != null && !e.label.trim().isEmpty()) {
          Node firstV = e.virtualNodes.get(0);
          renderEdgeLabelBadge(svg, firstV.x + firstV.width / 2.0, firstV.y + firstV.height / 2.0, e.label.trim());
        }
        return;
      } else {
        x1 = src.x + src.width;
        y1 = src.y + src.height / 2.0;
        x2 = dst.x;
        y2 = dst.y + dst.height / 2.0;
        double dx = x2 - x1;
        cp1x = x1 + dx * 0.5;
        cp1y = y1;
        cp2x = x1 + dx * 0.5;
        cp2y = y2;
      }
    }

    svg.append(
        String.format(
            "  <path d=\"M %.1f %.1f C %.1f %.1f, %.1f %.1f, %.1f %.1f\" fill=\"none\" stroke=\"#64748b\" stroke-width=\"%s\" %s%s/>\n",
            x1, y1, cp1x, cp1y, cp2x, cp2y, x2, y2, strokeWidth, strokeDash, marker));

    if (e.label != null && !e.label.trim().isEmpty()) {
      // Evaluate Cubic Bézier midpoint at t = 0.5
      double midX = 0.125 * x1 + 0.375 * cp1x + 0.375 * cp2x + 0.125 * x2;
      double midY = 0.125 * y1 + 0.375 * cp1y + 0.375 * cp2y + 0.125 * y2;
      renderEdgeLabelBadge(svg, midX, midY, e.label.trim());
    }
  }

  private static void renderEdgeLabelBadge(StringBuilder svg, double midX, double midY, String label) {
    double textLen = label.length() * 6.5;
    double rectW = textLen + 12;
    double rectH = 18;
    svg.append(
        String.format(
            "  <rect x=\"%.1f\" y=\"%.1f\" width=\"%.1f\" height=\"%.1f\" rx=\"3\" fill=\"#ffffff\" fill-opacity=\"0.95\" />\n",
            midX - rectW / 2.0, midY - rectH / 2.0, rectW, rectH));
    svg.append(
        String.format(
            "  <text x=\"%.1f\" y=\"%.1f\" font-size=\"10.5\" fill=\"#475569\" text-anchor=\"middle\" dominant-baseline=\"central\">%s</text>\n",
            midX, midY, escapeXml(label)));
  }

  private static String escapeXml(String text) {
    if (text == null) return "";
    StringBuilder sb = new StringBuilder(text.length() + 16);
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      // Strip invalid XML 1.0 control characters (valid chars: 0x9, 0xA, 0xD, 0x20+)
      if (c < 0x20 && c != '\t' && c != '\n' && c != '\r') {
        continue;
      }
      switch (c) {
        case '&':
          sb.append("&amp;");
          break;
        case '<':
          sb.append("&lt;");
          break;
        case '>':
          sb.append("&gt;");
          break;
        case '"':
          sb.append("&quot;");
          break;
        case '\'':
          sb.append("&apos;");
          break;
        default:
          sb.append(c);
          break;
      }
    }
    return sb.toString();
  }

  private static class GraphComponent {
    final Map<String, Node> nodes = new LinkedHashMap<>();
    final List<Edge> edges = new ArrayList<>();
    final List<Subgraph> subgraphs = new ArrayList<>();
    double width = 0;
    double height = 0;
  }

  private static String findRoot(Map<String, String> parent, String id) {
    String p = parent.get(id);
    if (p == null || p.equals(id)) {
      return id;
    }
    String root = findRoot(parent, p);
    parent.put(id, root);
    return root;
  }

  private static void unionSets(Map<String, String> parent, String id1, String id2) {
    String r1 = findRoot(parent, id1);
    String r2 = findRoot(parent, id2);
    if (!r1.equals(r2)) {
      parent.put(r1, r2);
    }
  }

  private static void layoutIsolatedSubgraphs(Direction dir, List<Subgraph> subgraphs) {
    boolean isHorizontal = (dir == Direction.LR || dir == Direction.RL);
    double padding = 20;
    double headerH = 22;

    for (Subgraph sg : subgraphs) {
      if (sg.nodes.isEmpty()) continue;
      if (!isHorizontal) {
        double maxW = 0;
        for (Node n : sg.nodes) {
          maxW = Math.max(maxW, n.width);
        }
        double curY = headerH + padding;
        for (Node n : sg.nodes) {
          n.x = padding + (maxW - n.width) / 2.0;
          n.y = curY;
          curY += n.height + 24;
        }
        sg.x = 0;
        sg.y = 0;
        sg.width = maxW + padding * 2;
        sg.height = curY + padding;
      } else {
        double maxH = 0;
        for (Node n : sg.nodes) {
          maxH = Math.max(maxH, n.height);
        }
        double curX = padding;
        for (Node n : sg.nodes) {
          n.x = curX;
          n.y = headerH + padding + (maxH - n.height) / 2.0;
          curX += n.width + 32;
        }
        sg.x = 0;
        sg.y = 0;
        sg.width = curX + padding;
        sg.height = maxH + padding * 2 + headerH;
      }
    }
  }
}
