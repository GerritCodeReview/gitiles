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
import com.google.common.collect.Iterables;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.regex.Pattern;
import javax.annotation.Nullable;

/**
 * Shared Sugiyama hierarchical DAG layout engine, compound subgraph/cluster layout, geometric SVG
 * path helpers, relative luminance contrast calculator, and XML/CSS security utilities used by
 * {@link SimpleMermaidRenderer} and {@link SimpleDotRenderer}.
 */
final class DiagramLayoutEngine {

  private static final Pattern SAFE_COLOR_PATTERN =
      Pattern.compile(
          "^(#[0-9a-fA-F]{3,8}|[a-zA-Z]{2,25}|(?:rgb|rgba|hsl|hsla)\\([0-9.,%\\s]{3,30}\\))$");

  // =========================================================================
  // Shared Base AST & Layout Model Classes
  // =========================================================================

  /** Base graph node with Sugiyama layer and coordinate properties. */
  static class BaseNode {
    public final String id;
    public int layer = 0;
    public double relX;
    public double relY;
    public double x;
    public double y;
    public double width = 160;
    public double height = 44;
    public double barycenter = 0;
    public boolean isVirtual = false;

    protected BaseNode(String id) {
      this.id = id;
    }

    public boolean hasParentSubgraph() {
      return false;
    }
  }

  /** Base logical subgraph or cluster container with recursive hierarchy and layout bounds. */
  static class BaseSubgraph<N extends BaseNode, S extends BaseSubgraph<N, S>> {
    public final String id;
    @Nullable public String title;
    @Nullable public S parent;
    public final List<S> children = new ArrayList<>();
    public final List<N> nodes = new ArrayList<>();
    public double relX;
    public double relY;
    public double x;
    public double y;
    public double width;
    public double height;
    @Nullable public String customFill;
    @Nullable public String customStroke;

    protected BaseSubgraph(String id, @Nullable String title, @Nullable S parent) {
      this.id = id;
      this.title = title;
      this.parent = parent;
    }

    public boolean hasDirectionOverride() {
      return false;
    }

    public boolean isDirectionHorizontal() {
      return false;
    }

    public boolean isDirectionReversed() {
      return false;
    }

    public double getPadding() {
      return 20.0;
    }

    public double getHeaderHeight() {
      return 28.0;
    }

    public double getEmptyWidth() {
      return 100.0;
    }

    public double getTitleMinWidth(double padding) {
      return 0.0;
    }
  }

  /** Base directed or undirected edge with virtual dummy node chain and label spacing hooks. */
  static class BaseEdge<N extends BaseNode> {
    public final String fromId;
    public final String toId;
    @Nullable public String label;
    public boolean isBackEdge = false;
    public boolean constraint = true;
    public final List<N> virtualNodes = new ArrayList<>();

    protected BaseEdge(String fromId, String toId, @Nullable String label) {
      this.fromId = fromId;
      this.toId = toId;
      this.label = label;
    }

    public boolean hasLabel() {
      return label != null && !label.trim().isEmpty();
    }

    public double getBadgeWidth() {
      return hasLabel() ? label.trim().length() * 6.5 + 16.0 : 0.0;
    }

    public double getBadgeHeight() {
      return hasLabel() ? 18.0 : 0.0;
    }

    public double getFirstVirtualNodeWidth() {
      return hasLabel() ? Math.max(label.trim().length() * 6.5 + 24.0, 60.0) : 24.0;
    }

    public double getFirstVirtualNodeHeight() {
      return 20.0;
    }

    public boolean appliesGapToLayer(int l, int srcLayer, int dstLayer) {
      return (virtualNodes.isEmpty() && srcLayer == l && dstLayer == l + 1)
          || (!virtualNodes.isEmpty() && srcLayer <= l && l < dstLayer);
    }

    public double getVerticalLayerGap(double baseGap) {
      return hasLabel() ? Math.max(baseGap, 60.0) : baseGap;
    }

    public double getHorizontalLayerGap(double baseGap) {
      if (!hasLabel()) {
        return baseGap;
      }
      double lw = label.trim().length() * 6.5 + 24.0;
      return Math.max(baseGap, lw + 24.0);
    }

    public double getUnitVerticalGap() {
      return hasLabel() ? 55.0 : 0.0;
    }

    public double getUnitHorizontalGap() {
      if (!hasLabel()) {
        return 0.0;
      }
      double lw = label.trim().length() * 6.5 + 24.0;
      return lw + 24.0;
    }

    public boolean usesInclusiveUnitLayerSpan() {
      return false;
    }
  }

  /** Base edge connecting two subgraphs directly. */
  static class BaseSubgraphEdge {
    public final String fromSgId;
    public final String toSgId;
    @Nullable public final String label;

    protected BaseSubgraphEdge(String fromSgId, String toSgId, @Nullable String label) {
      this.fromSgId = fromSgId;
      this.toSgId = toSgId;
      this.label = label;
    }

    public boolean hasLabel() {
      return label != null && !label.trim().isEmpty();
    }

    public double getUnitVerticalGap() {
      return hasLabel() ? 55.0 : 0.0;
    }

    public double getUnitHorizontalGap() {
      if (!hasLabel()) {
        return 0.0;
      }
      double lw = label.trim().length() * 6.5 + 24.0;
      return lw + 24.0;
    }
  }

  /** Connected component containing a subset of nodes, edges, and subgraphs/clusters. */
  static final class GraphComponent<
      N extends BaseNode, E extends BaseEdge<N>, S extends BaseSubgraph<N, S>> {
    final Map<String, N> nodes = new LinkedHashMap<>();
    final List<E> edges = new ArrayList<>();
    final List<S> subgraphs = new ArrayList<>();
    double width = 0;
    double height = 0;
  }

  /** Super-node layout unit wrapping either a child subgraph/cluster or a standalone node. */
  static final class LayoutUnit<N extends BaseNode, S extends BaseSubgraph<N, S>> {
    final String id;
    @Nullable final S subgraph;
    @Nullable final N node;
    final double width;
    final double height;
    double x;
    double y;
    int layer = 0;
    double barycenter = 0;

    LayoutUnit(S subgraph) {
      this.id = "sg_" + subgraph.id;
      this.subgraph = subgraph;
      this.node = null;
      this.width = subgraph.width;
      this.height = subgraph.height;
    }

    LayoutUnit(N node) {
      this.id = "n_" + node.id;
      this.subgraph = null;
      this.node = node;
      this.width = node.width;
      this.height = node.height;
    }
  }

  /** Meta-edge between two {@link LayoutUnit} instances in a compound graph. */
  static final class UnitEdge extends BaseEdge<BaseNode> {
    double badgeWidth;
    double badgeHeight;
    double unitVerticalGap;
    double unitHorizontalGap;
    final boolean inclusiveLayerSpan;

    UnitEdge(
        String fromId,
        String toId,
        @Nullable String label,
        double badgeWidth,
        double badgeHeight,
        double unitVerticalGap,
        double unitHorizontalGap,
        boolean inclusiveLayerSpan) {
      super(fromId, toId, label);
      this.badgeWidth = badgeWidth;
      this.badgeHeight = badgeHeight;
      this.unitVerticalGap = unitVerticalGap;
      this.unitHorizontalGap = unitHorizontalGap;
      this.inclusiveLayerSpan = inclusiveLayerSpan;
    }
  }

  /** Collision-free placement for an edge label badge. */
  static final class BadgePlacement<E> {
    final E edge;
    double cx;
    double cy;
    final double width;
    final double height;

    BadgePlacement(E edge, double cx, double cy, double width, double height) {
      this.edge = edge;
      this.cx = cx;
      this.cy = cy;
      this.width = width;
      this.height = height;
    }
  }

  /** Configuration parameters for Sugiyama hierarchical DAG and compound layout. */
  static final class SugiyamaConfig {
    final double nodeSep;
    final double rankSep;
    final double minHorizontalLayerWidth;
    final double unitVerticalBaseGap;
    final double unitHorizontalBaseGap;
    final boolean alignSingleNodeToParents;
    final boolean reserveLoopSpaceInSubgraphs;
    final List<List<String>> rankSameGroups;

    private SugiyamaConfig(
        double nodeSep,
        double rankSep,
        double minHorizontalLayerWidth,
        double unitVerticalBaseGap,
        double unitHorizontalBaseGap,
        boolean alignSingleNodeToParents,
        boolean reserveLoopSpaceInSubgraphs,
        List<List<String>> rankSameGroups) {
      this.nodeSep = nodeSep;
      this.rankSep = rankSep;
      this.minHorizontalLayerWidth = minHorizontalLayerWidth;
      this.unitVerticalBaseGap = unitVerticalBaseGap;
      this.unitHorizontalBaseGap = unitHorizontalBaseGap;
      this.alignSingleNodeToParents = alignSingleNodeToParents;
      this.reserveLoopSpaceInSubgraphs = reserveLoopSpaceInSubgraphs;
      this.rankSameGroups = rankSameGroups;
    }

    static SugiyamaConfig forMermaid() {
      return new SugiyamaConfig(
          /* nodeSep= */ 32.0,
          /* rankSep= */ 48.0,
          /* minHorizontalLayerWidth= */ 120.0,
          /* unitVerticalBaseGap= */ 45.0,
          /* unitHorizontalBaseGap= */ 45.0,
          /* alignSingleNodeToParents= */ true,
          /* reserveLoopSpaceInSubgraphs= */ false,
          Collections.emptyList());
    }

    static SugiyamaConfig forDot(
        double nodeSep, double rankSep, List<List<String>> rankSameGroups) {
      return new SugiyamaConfig(
          nodeSep,
          rankSep,
          /* minHorizontalLayerWidth= */ 90.0,
          /* unitVerticalBaseGap= */ Math.max(48.0, rankSep),
          /* unitHorizontalBaseGap= */ Math.max(55.0, rankSep),
          /* alignSingleNodeToParents= */ false,
          /* reserveLoopSpaceInSubgraphs= */ true,
          rankSameGroups);
    }
  }

  // =========================================================================
  // 1. Security, Color Validation & Dark-Mode Relative Luminance Contrast
  // =========================================================================

  /** Validates that a CSS color value matches the safe allowlist without URLs or expressions. */
  static boolean isValidCssColor(@Nullable String val) {
    if (isNullOrEmpty(val)) {
      return false;
    }
    String c = val.trim();
    if (!SAFE_COLOR_PATTERN.matcher(c).matches()) {
      return false;
    }
    String lower = Ascii.toLowerCase(c);
    // Check for "url(" (with opening parenthesis) rather than bare "url" so that standard CSS
    // named colors containing the substring "url" (such as "burlywood") are not falsely rejected,
    // while functional url(...) references remain blocked as defense-in-depth alongside
    // SAFE_COLOR_PATTERN.
    return !lower.contains("url(") && !lower.contains("expression");
  }

  /**
   * Computes whether a CSS color has light relative luminance ({@code Y >= 0.5}), supporting
   * 3/4/6/8-digit hex, {@code rgb()/rgba()}, {@code hsl()/hsla()}, and named CSS/X11 colors.
   */
  static boolean isLightColor(@Nullable String color) {
    if (isNullOrEmpty(color)) {
      return true;
    }
    String c = Ascii.toLowerCase(color.trim());
    if (c.startsWith("#")) {
      try {
        String hex = c.substring(1);
        int r;
        int g;
        int b;
        if (hex.length() == 3 || hex.length() == 4) {
          r = Integer.parseInt(hex.substring(0, 1) + hex.substring(0, 1), 16);
          g = Integer.parseInt(hex.substring(1, 2) + hex.substring(1, 2), 16);
          b = Integer.parseInt(hex.substring(2, 3) + hex.substring(2, 3), 16);
        } else if (hex.length() >= 6) {
          r = Integer.parseInt(hex.substring(0, 2), 16);
          g = Integer.parseInt(hex.substring(2, 4), 16);
          b = Integer.parseInt(hex.substring(4, 6), 16);
        } else {
          return true;
        }
        return (0.299 * r + 0.587 * g + 0.114 * b) >= 127.5;
      } catch (NumberFormatException e) {
        return true;
      }
    }
    if (c.startsWith("rgb")) {
      int open = c.indexOf('(');
      int close = c.indexOf(')');
      if (open != -1 && close > open) {
        List<String> parts =
            Splitter.onPattern("[,\\s/]+").omitEmptyStrings().splitToList(c.substring(open + 1, close));
        if (parts.size() >= 3) {
          try {
            double r = parseColorComponent(parts.get(0));
            double g = parseColorComponent(parts.get(1));
            double b = parseColorComponent(parts.get(2));
            return (0.299 * r + 0.587 * g + 0.114 * b) >= 127.5;
          } catch (NumberFormatException e) {
            return true;
          }
        }
      }
    }
    if (c.startsWith("hsl")) {
      int open = c.indexOf('(');
      int close = c.indexOf(')');
      if (open != -1 && close > open) {
        List<String> parts =
            Splitter.onPattern("[,\\s/]+").omitEmptyStrings().splitToList(c.substring(open + 1, close));
        if (parts.size() >= 3) {
          try {
            String lStr = parts.get(2).trim().replace("%", "");
            double l = Double.parseDouble(lStr);
            return l >= 50.0;
          } catch (NumberFormatException e) {
            return true;
          }
        }
      }
    }
    return switch (c) {
      case "black",
          "navy",
          "darkblue",
          "mediumblue",
          "blue",
          "darkgreen",
          "green",
          "teal",
          "darkcyan",
          "darkred",
          "maroon",
          "purple",
          "indigo",
          "darkmagenta",
          "darkviolet",
          "darkslateblue",
          "saddlebrown",
          "sienna",
          "brown",
          "darkslategray",
          "darkslategrey",
          "midnightblue",
          "crimson",
          "firebrick",
          "royalblue",
          "steelblue",
          "forestgreen",
          "seagreen",
          "darkolivegreen",
          "chocolate",
          "slategray",
          "slategrey",
          "charcoal",
          "gray",
          "grey",
          "dimgray",
          "dimgrey" ->
          false;
      default -> true;
    };
  }

  /** Parses an RGB channel value (0..255 or 0%..100%). */
  static double parseColorComponent(String part) {
    String p = part.trim();
    if (p.endsWith("%")) {
      return Double.parseDouble(p.substring(0, p.length() - 1)) * 2.55;
    }
    return Double.parseDouble(p);
  }

  /**
   * Strips XML 1.0 illegal control characters ({@code \u0000}..{@code \u001F} except {@code \t},
   * {@code \n}, {@code \r}, and {@code \u007F}) and escapes {@code &}, {@code <}, {@code >},
   * {@code "}, and {@code '}.
   */
  static String escapeXml(String text) {
    if (text == null) {
      return "";
    }
    StringBuilder sb = new StringBuilder(text.length() + 16);
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if ((c < 0x20 && c != '\t' && c != '\n' && c != '\r') || c == 0x7F) {
        continue;
      }
      switch (c) {
        case '&' -> sb.append("&amp;");
        case '<' -> sb.append("&lt;");
        case '>' -> sb.append("&gt;");
        case '"' -> sb.append("&quot;");
        case '\'' -> sb.append("&apos;");
        default -> sb.append(c);
      }
    }
    return sb.toString();
  }

  // =========================================================================
  // 2. Disjoint-Set (Union-Find) & Geometric / SVG Path Helpers
  // =========================================================================

  /** Finds the representative root of {@code id} with path compression. */
  static String findRoot(Map<String, String> parent, String id) {
    String p = parent.get(id);
    if (p == null || p.equals(id)) {
      return id;
    }
    String root = findRoot(parent, p);
    parent.put(id, root);
    return root;
  }

  /** Unions the disjoint sets containing {@code id1} and {@code id2}. */
  static void unionSets(Map<String, String> parent, String id1, String id2) {
    String r1 = findRoot(parent, id1);
    String r2 = findRoot(parent, id2);
    if (!r1.equals(r2)) {
      parent.put(r1, r2);
    }
  }

  /** Checks whether two axis-aligned rectangles overlap within {@code margin} padding. */
  static boolean rectsOverlap(
      double x1,
      double y1,
      double w1,
      double h1,
      double x2,
      double y2,
      double w2,
      double h2,
      double margin) {
    return x1 + w1 + margin > x2
        && x2 + w2 + margin > x1
        && y1 + h1 + margin > y2
        && y2 + h2 + margin > y1;
  }

  /** Appends the root {@code <svg>} opening tag and shared {@code <defs>} arrow/shadow elements. */
  static void appendSvgHeaderAndDefs(
      StringBuilder svg,
      String svgClass,
      double width,
      double height,
      String markerId,
      String arrowPathClass,
      String arrowFill,
      String filterId,
      String shadowClass) {
    svg.append(
        String.format(
            Locale.ROOT,
            "<svg class=\"%s\" xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 %.0f"
                + " %.0f\" style=\"max-width: %.0fpx; width: 100%%; height: auto;\">\n",
            svgClass,
            width,
            height,
            width));

    svg.append("  <defs>\n");
    svg.append(
        String.format(
            Locale.ROOT,
            "    <marker id=\"%s\" viewBox=\"0 0 10 10\" refX=\"8\" refY=\"5\""
                + " markerWidth=\"7\" markerHeight=\"7\" orient=\"auto-start-reverse\">\n",
            markerId));
    svg.append(
        String.format(
            Locale.ROOT,
            "      <path class=\"%s\" d=\"M 0 1.5 L 10 5 L 0 8.5 z\" fill=\"%s\" />\n",
            arrowPathClass,
            arrowFill));
    svg.append("    </marker>\n");
    svg.append(
        String.format(
            Locale.ROOT,
            "    <filter id=\"%s\" x=\"-5%%\" y=\"-5%%\" width=\"115%%\" height=\"120%%\">\n",
            filterId));
    svg.append(
        String.format(
            Locale.ROOT,
            "      <feDropShadow class=\"%s\" dx=\"0\" dy=\"1.5\" stdDeviation=\"2\""
                + " flood-color=\"#0f172a\" flood-opacity=\"0.06\" />\n",
            shadowClass));
    svg.append("    </filter>\n");
    svg.append("  </defs>\n");
  }

  /** Builds a multi-segment cubic Bézier SVG path string across virtual node waypoints. */
  static String buildMultiSegmentBezierPath(
      List<Double> px, List<Double> py, boolean isHorizontal) {
    StringBuilder pathD = new StringBuilder();
    pathD.append(String.format(Locale.ROOT, "M %.1f %.1f", px.get(0), py.get(0)));
    for (int i = 0; i < px.size() - 1; i++) {
      double xA = px.get(i);
      double yA = py.get(i);
      double xB = px.get(i + 1);
      double yB = py.get(i + 1);
      if (!isHorizontal) {
        double dy = yB - yA;
        pathD.append(
            String.format(
                Locale.ROOT,
                " C %.1f %.1f, %.1f %.1f, %.1f %.1f",
                xA,
                yA + dy * 0.5,
                xB,
                yB - dy * 0.5,
                xB,
                yB));
      } else {
        double dx = xB - xA;
        pathD.append(
            String.format(
                Locale.ROOT,
                " C %.1f %.1f, %.1f %.1f, %.1f %.1f",
                xA + dx * 0.5,
                yA,
                xB - dx * 0.5,
                yB,
                xB,
                yB));
      }
    }
    return pathD.toString();
  }

  /** Computes the 8 cubic Bézier coordinates {@code [x1, y1, cp1x, cp1y, cp2x, cp2y, x2, y2]} for a self-loop. */
  static double[] computeSelfLoopControlPoints(
      double sx,
      double sy,
      double sw,
      double sh,
      boolean isHorizontal,
      double loopReach,
      double spread) {
    if (!isHorizontal) {
      double x1 = sx + sw;
      double y1 = sy + sh * 0.3;
      double x2 = sx + sw;
      double y2 = sy + sh * 0.7;
      return new double[] {
        x1, y1, x1 + loopReach, y1 - spread, x2 + loopReach, y2 + spread, x2, y2
      };
    } else {
      double x1 = sx + sw * 0.3;
      double y1 = sy;
      double x2 = sx + sw * 0.7;
      double y2 = sy;
      return new double[] {
        x1, y1, x1 - spread, y1 - loopReach, x2 + spread, y2 - loopReach, x2, y2
      };
    }
  }

  /** Computes the 8 cubic Bézier coordinates {@code [x1, y1, cp1x, cp1y, cp2x, cp2y, x2, y2]} for a forward edge. */
  static double[] computeForwardBezierControlPoints(
      double sx,
      double sy,
      double sw,
      double sh,
      double tx,
      double ty,
      double tw,
      double th,
      boolean isHorizontal,
      boolean isReversed) {
    if (!isHorizontal) {
      double x1 = sx + sw / 2.0;
      double y1 = isReversed ? sy : sy + sh;
      double x2 = tx + tw / 2.0;
      double y2 = isReversed ? ty + th : ty;
      double dy = y2 - y1;
      return new double[] {x1, y1, x1, y1 + dy * 0.5, x2, y1 + dy * 0.5, x2, y2};
    } else {
      double x1 = isReversed ? sx : sx + sw;
      double y1 = sy + sh / 2.0;
      double x2 = isReversed ? tx + tw : tx;
      double y2 = ty + th / 2.0;
      double dx = x2 - x1;
      return new double[] {x1, y1, x1 + dx * 0.5, y1, x1 + dx * 0.5, y2, x2, y2};
    }
  }

  /** Evaluates a 1D cubic Bézier curve at parameter {@code t} in {@code [0, 1]}. */
  static double evalCubicBezier(double p0, double cp1, double cp2, double p3, double t) {
    double u = 1.0 - t;
    return u * u * u * p0 + 3 * u * u * t * cp1 + 3 * u * t * t * cp2 + t * t * t * p3;
  }

  /** Formats SVG polygon points for a 4-vertex diamond. */
  static String formatDiamondPoints(double x, double y, double width, double height) {
    double cx = x + width / 2.0;
    double cy = y + height / 2.0;
    return String.format(
        Locale.ROOT,
        "%.1f,%.1f %.1f,%.1f %.1f,%.1f %.1f,%.1f",
        cx,
        y,
        x + width,
        cy,
        cx,
        y + height,
        x,
        cy);
  }

  /** Formats SVG polygon points for a 6-vertex hexagon. */
  static String formatHexagonPoints(
      double x, double y, double width, double height, double indent) {
    double h2 = height / 2.0;
    return String.format(
        Locale.ROOT,
        "%.1f,%.1f %.1f,%.1f %.1f,%.1f %.1f,%.1f %.1f,%.1f %.1f,%.1f",
        x + indent,
        y,
        x + width - indent,
        y,
        x + width,
        y + h2,
        x + width - indent,
        y + height,
        x + indent,
        y + height,
        x,
        y + h2);
  }

  /** Formats the outer SVG path {@code d} attribute for a 3D cylinder node. */
  static String formatCylinderBodyPath(
      double x, double y, double width, double height, double ry) {
    double rxCyl = width / 2.0;
    return String.format(
        Locale.ROOT,
        "M %.1f %.1f a %.1f,%.1f 0 1,0 %.1f,0 a %.1f,%.1f 0 1,0 -%.1f,0 l 0,%.1f a"
            + " %.1f,%.1f 0 0,0 %.1f,0 l 0,-%.1f Z",
        x,
        y + ry,
        rxCyl,
        ry,
        width,
        rxCyl,
        ry,
        width,
        height - ry * 2,
        rxCyl,
        ry,
        width,
        height - ry * 2);
  }

  /** Formats the inner top-rim arc SVG path {@code d} attribute for a 3D cylinder node. */
  static String formatCylinderRimPath(double x, double y, double width, double ry) {
    double rxCyl = width / 2.0;
    return String.format(
        Locale.ROOT, "M %.1f %.1f a %.1f,%.1f 0 0,0 %.1f,0", x, y + ry, rxCyl, ry, width);
  }

  /**
   * Finds a collision-free center coordinate for an edge label badge along or near its cubic
   * Bézier curve, avoiding all node bounding boxes and previously placed badges.
   */
  static <N extends BaseNode, E> double[] findCollisionFreeBadgeCenter(
      double initialX,
      double initialY,
      double x1,
      double y1,
      double cp1x,
      double cp1y,
      double cp2x,
      double cp2y,
      double x2,
      double y2,
      double bw,
      double bh,
      Collection<N> nodes,
      List<BadgePlacement<E>> placedBadges) {
    if (!overlapsAnyNodeOrBadge(initialX, initialY, bw, bh, nodes, placedBadges)) {
      return new double[] {initialX, initialY};
    }

    double[] ts = {0.5, 0.65, 0.35, 0.75, 0.25, 0.84, 0.16, 0.90, 0.10};
    for (double t : ts) {
      double bx = evalCubicBezier(x1, cp1x, cp2x, x2, t);
      double by = evalCubicBezier(y1, cp1y, cp2y, y2, t);
      if (!overlapsAnyNodeOrBadge(bx, by, bw, bh, nodes, placedBadges)) {
        return new double[] {bx, by};
      }
    }

    double[] anchorTs = {0.5, 0.78, 0.22};
    for (double t : anchorTs) {
      double ax = evalCubicBezier(x1, cp1x, cp2x, x2, t);
      double ay = evalCubicBezier(y1, cp1y, cp2y, y2, t);
      for (int step = 1; step <= 20; step++) {
        double dy = step * 14.0;
        double dx = step * 22.0;
        double[][] candidates = {
          {ax, ay - dy},
          {ax, ay + dy},
          {ax + dx, ay},
          {ax - dx, ay},
          {ax + dx, ay - dy},
          {ax + dx, ay + dy},
          {ax - dx, ay - dy},
          {ax - dx, ay + dy}
        };
        for (double[] c : candidates) {
          if (!overlapsAnyNodeOrBadge(c[0], c[1], bw, bh, nodes, placedBadges)) {
            return c;
          }
        }
      }
    }

    return new double[] {initialX, initialY};
  }

  private static <N extends BaseNode, E> boolean overlapsAnyNodeOrBadge(
      double cx,
      double cy,
      double bw,
      double bh,
      Collection<N> nodes,
      List<BadgePlacement<E>> placedBadges) {
    double bx = cx - bw / 2.0;
    double by = cy - bh / 2.0;
    double margin = 4.0;
    for (N n : nodes) {
      if (rectsOverlap(bx, by, bw, bh, n.x, n.y, n.width, n.height, margin)) {
        return true;
      }
    }
    for (BadgePlacement<E> bp : placedBadges) {
      if (rectsOverlap(
          bx,
          by,
          bw,
          bh,
          bp.cx - bp.width / 2.0,
          bp.cy - bp.height / 2.0,
          bp.width,
          bp.height,
          2.0)) {
        return true;
      }
    }
    return false;
  }

  // =========================================================================
  // 3. Sugiyama Hierarchical DAG & Compound Subgraph/Cluster Layout
  // =========================================================================

  /** Initializes Union-Find parent map and merges connected nodes and subgraphs/clusters. */
  static <N extends BaseNode, E extends BaseEdge<N>, S extends BaseSubgraph<N, S>>
      Map<String, String> initUnionFind(
          Map<String, N> nodes, List<E> edges, List<S> allSubgraphs) {
    Map<String, String> parent = new HashMap<>();
    for (String id : nodes.keySet()) {
      parent.put(id, id);
    }
    for (E e : edges) {
      if (parent.containsKey(e.fromId) && parent.containsKey(e.toId)) {
        unionSets(parent, e.fromId, e.toId);
      }
    }
    for (S sg : allSubgraphs) {
      String sampleId = getSubgraphSampleNodeId(sg);
      if (sg.nodes.size() > 1) {
        String firstId = sg.nodes.get(0).id;
        for (int i = 1; i < sg.nodes.size(); i++) {
          unionSets(parent, firstId, sg.nodes.get(i).id);
        }
      }
      for (S child : sg.children) {
        String childSample = getSubgraphSampleNodeId(child);
        if (sampleId != null && childSample != null) {
          unionSets(parent, sampleId, childSample);
        }
      }
    }
    return parent;
  }

  /** Groups nodes, edges, and subgraphs into sorted connected components. */
  static <N extends BaseNode, E extends BaseEdge<N>, S extends BaseSubgraph<N, S>>
      List<GraphComponent<N, E, S>> buildComponents(
          Map<String, N> nodes,
          List<E> edges,
          List<S> allSubgraphs,
          Map<String, String> parent) {
    Map<String, GraphComponent<N, E, S>> compMap = new LinkedHashMap<>();
    for (N n : nodes.values()) {
      String root = findRoot(parent, n.id);
      GraphComponent<N, E, S> comp = compMap.computeIfAbsent(root, k -> new GraphComponent<>());
      comp.nodes.put(n.id, n);
    }
    for (E e : edges) {
      String root = findRoot(parent, e.fromId);
      GraphComponent<N, E, S> comp = compMap.get(root);
      if (comp != null && comp.nodes.containsKey(e.fromId) && comp.nodes.containsKey(e.toId)) {
        comp.edges.add(e);
      }
    }
    for (S sg : allSubgraphs) {
      String sampleId = getSubgraphSampleNodeId(sg);
      if (sampleId != null) {
        String root = findRoot(parent, sampleId);
        GraphComponent<N, E, S> comp = compMap.get(root);
        if (comp != null && !comp.subgraphs.contains(sg)) {
          comp.subgraphs.add(sg);
        }
      }
    }
    List<GraphComponent<N, E, S>> components = new ArrayList<>(compMap.values());
    components.sort((c1, c2) -> Boolean.compare(!c2.subgraphs.isEmpty(), !c1.subgraphs.isEmpty()));
    return components;
  }

  /** Normalizes a laid-out component so its minimum X/Y origin starts at (0, 0). */
  static <N extends BaseNode, E extends BaseEdge<N>, S extends BaseSubgraph<N, S>>
      void normalizeComponentBounds(GraphComponent<N, E, S> comp) {
    double cMinX = Double.MAX_VALUE;
    double cMinY = Double.MAX_VALUE;
    double cMaxX = -Double.MAX_VALUE;
    double cMaxY = -Double.MAX_VALUE;
    for (N n : comp.nodes.values()) {
      cMinX = Math.min(cMinX, n.x);
      cMinY = Math.min(cMinY, n.y);
      cMaxX = Math.max(cMaxX, n.x + n.width);
      cMaxY = Math.max(cMaxY, n.y + n.height);
    }
    for (S sg : comp.subgraphs) {
      cMinX = Math.min(cMinX, sg.x);
      cMinY = Math.min(cMinY, sg.y);
      cMaxX = Math.max(cMaxX, sg.x + sg.width);
      cMaxY = Math.max(cMaxY, sg.y + sg.height);
    }

    if (cMinX != Double.MAX_VALUE) {
      comp.width = cMaxX - cMinX;
      comp.height = cMaxY - cMinY;
      for (N n : comp.nodes.values()) {
        n.x -= cMinX;
        n.y -= cMinY;
      }
      for (S sg : comp.subgraphs) {
        sg.x -= cMinX;
        sg.y -= cMinY;
      }
      for (E e : comp.edges) {
        for (N v : e.virtualNodes) {
          v.x -= cMinX;
          v.y -= cMinY;
        }
      }
    }
  }

  /** Stacks disconnected graph components side-by-side (vertical flow) or top-to-bottom (horizontal flow). */
  static <N extends BaseNode, E extends BaseEdge<N>, S extends BaseSubgraph<N, S>>
      void stackComponents(
          List<GraphComponent<N, E, S>> components, boolean isHorizontal, double gap) {
    if (!isHorizontal) {
      double curX = 0;
      for (GraphComponent<N, E, S> comp : components) {
        for (N n : comp.nodes.values()) {
          n.x += curX;
        }
        for (S sg : comp.subgraphs) {
          sg.x += curX;
        }
        for (E e : comp.edges) {
          for (N v : e.virtualNodes) {
            v.x += curX;
          }
        }
        curX += comp.width + gap;
      }
    } else {
      double curY = 0;
      for (GraphComponent<N, E, S> comp : components) {
        for (N n : comp.nodes.values()) {
          n.y += curY;
        }
        for (S sg : comp.subgraphs) {
          sg.y += curY;
        }
        for (E e : comp.edges) {
          for (N v : e.virtualNodes) {
            v.y += curY;
          }
        }
        curY += comp.height + gap;
      }
    }
  }

  /** Computes {@code [minX, minY, maxX, maxY]} across all nodes, subgraphs, and virtual nodes. */
  static <N extends BaseNode, E extends BaseEdge<N>, S extends BaseSubgraph<N, S>>
      double[] computeGraphBounds(
          Collection<N> nodes, Collection<S> subgraphs, Collection<E> edges) {
    double minX = Double.MAX_VALUE;
    double minY = Double.MAX_VALUE;
    double maxX = -Double.MAX_VALUE;
    double maxY = -Double.MAX_VALUE;

    for (N n : nodes) {
      minX = Math.min(minX, n.x);
      minY = Math.min(minY, n.y);
      maxX = Math.max(maxX, n.x + n.width);
      maxY = Math.max(maxY, n.y + n.height);
    }
    for (S sg : subgraphs) {
      minX = Math.min(minX, sg.x);
      minY = Math.min(minY, sg.y);
      maxX = Math.max(maxX, sg.x + sg.width);
      maxY = Math.max(maxY, sg.y + sg.height);
    }
    for (E e : edges) {
      for (N v : e.virtualNodes) {
        minX = Math.min(minX, v.x);
        minY = Math.min(minY, v.y);
        maxX = Math.max(maxX, v.x + v.width);
        maxY = Math.max(maxY, v.y + v.height);
      }
    }
    return new double[] {minX, minY, maxX, maxY};
  }

  /** Translates all nodes, subgraphs, and virtual nodes by {@code (offsetX, offsetY)}. */
  static <N extends BaseNode, E extends BaseEdge<N>, S extends BaseSubgraph<N, S>>
      void translateGraph(
          Collection<N> nodes,
          Collection<S> subgraphs,
          Collection<E> edges,
          double offsetX,
          double offsetY) {
    for (N n : nodes) {
      n.x += offsetX;
      n.y += offsetY;
    }
    for (S sg : subgraphs) {
      sg.x += offsetX;
      sg.y += offsetY;
    }
    for (E e : edges) {
      for (N v : e.virtualNodes) {
        v.x += offsetX;
        v.y += offsetY;
      }
    }
  }

  /** Performs Sugiyama hierarchical DAG layout on a flat set of nodes and edges. */
  static <N extends BaseNode, E extends BaseEdge<N>> void layoutBySugiyamaDag(
      SugiyamaConfig config,
      boolean isHorizontal,
      boolean isReversed,
      Map<String, N> allNodes,
      List<E> edges,
      Function<String, N> virtualNodeFactory) {

    // 1. Cycle Breaking via DFS
    Map<String, List<E>> adj = new HashMap<>();
    for (String id : allNodes.keySet()) {
      adj.put(id, new ArrayList<>());
    }
    for (E e : edges) {
      if (!e.fromId.equals(e.toId) && adj.containsKey(e.fromId) && allNodes.containsKey(e.toId)) {
        adj.get(e.fromId).add(e);
      }
    }

    Map<String, Integer> color = new HashMap<>();
    for (String id : allNodes.keySet()) {
      if (color.getOrDefault(id, 0) == 0) {
        findCyclesDfs(id, adj, color);
      }
    }

    // 2. Layer Assignment (Longest Path in DAG)
    for (N n : allNodes.values()) {
      n.layer = 0;
    }
    boolean changed = true;
    int maxIterations = allNodes.size() + 2;
    int iter = 0;
    while (changed && iter++ < maxIterations) {
      changed = false;
      for (E e : edges) {
        if (!e.isBackEdge && !e.fromId.equals(e.toId) && e.constraint) {
          N src = allNodes.get(e.fromId);
          N dst = allNodes.get(e.toId);
          if (src != null && dst != null && dst.layer < src.layer + 1) {
            dst.layer = src.layer + 1;
            changed = true;
          }
        }
      }
    }

    // 2a. Source Node Sinking / Compaction (ALAP for loose source nodes)
    for (N n : allNodes.values()) {
      int inCount = 0;
      int minOutLayer = Integer.MAX_VALUE;
      for (E e : edges) {
        if (!e.isBackEdge && !e.fromId.equals(e.toId) && e.constraint) {
          if (e.toId.equals(n.id)) {
            inCount++;
          }
          if (e.fromId.equals(n.id)) {
            N dst = allNodes.get(e.toId);
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

    // 2b. Apply rank=same groups and re-propagate downstream layers
    if (!config.rankSameGroups.isEmpty()) {
      boolean rankRaised = false;
      for (List<String> group : config.rankSameGroups) {
        int groupLayer = -1;
        for (String id : group) {
          N n = allNodes.get(id);
          if (n != null) {
            groupLayer = Math.max(groupLayer, n.layer);
          }
        }
        if (groupLayer >= 0) {
          for (String id : group) {
            N n = allNodes.get(id);
            if (n != null && n.layer < groupLayer) {
              n.layer = groupLayer;
              rankRaised = true;
            }
          }
        }
      }
      if (rankRaised) {
        boolean propChanged = true;
        int propIter = 0;
        while (propChanged && propIter++ < maxIterations) {
          propChanged = false;
          for (E e : edges) {
            if (!e.isBackEdge
                && !e.fromId.equals(e.toId)
                && e.constraint
                && !areInSameRankGroup(e.fromId, e.toId, config.rankSameGroups)) {
              N src = allNodes.get(e.fromId);
              N dst = allNodes.get(e.toId);
              if (src != null && dst != null && dst.layer < src.layer + 1) {
                dst.layer = src.layer + 1;
                propChanged = true;
              }
            }
          }
          for (List<String> group : config.rankSameGroups) {
            int groupLayer = -1;
            for (String id : group) {
              N n = allNodes.get(id);
              if (n != null) {
                groupLayer = Math.max(groupLayer, n.layer);
              }
            }
            if (groupLayer >= 0) {
              for (String id : group) {
                N n = allNodes.get(id);
                if (n != null && n.layer < groupLayer) {
                  n.layer = groupLayer;
                  propChanged = true;
                }
              }
            }
          }
        }
      }
    }

    // 2c. Virtual Dummy Node Insertion for Long Edges
    for (E e : edges) {
      e.virtualNodes.clear();
      if (!e.isBackEdge && !e.fromId.equals(e.toId)) {
        N src = allNodes.get(e.fromId);
        N dst = allNodes.get(e.toId);
        if (src != null && dst != null && dst.layer - src.layer > 1) {
          for (int l = src.layer + 1; l < dst.layer; l++) {
            N dummy = virtualNodeFactory.apply("__v_" + src.id + "_" + dst.id + "_" + l);
            dummy.isVirtual = true;
            dummy.layer = l;
            if (e.hasLabel() && l == src.layer + 1) {
              dummy.width = e.getFirstVirtualNodeWidth();
              dummy.height = e.getFirstVirtualNodeHeight();
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
    Map<Integer, List<N>> layerMap = new TreeMap<>();
    for (N n : allNodes.values()) {
      layerMap.computeIfAbsent(n.layer, k -> new ArrayList<>()).add(n);
    }
    for (E e : edges) {
      for (N v : e.virtualNodes) {
        layerMap.computeIfAbsent(v.layer, k -> new ArrayList<>()).add(v);
      }
    }

    int maxLayer = layerMap.isEmpty() ? 0 : Collections.max(layerMap.keySet());
    for (int l = 1; l <= maxLayer; l++) {
      List<N> currentLayer = layerMap.get(l);
      List<N> prevLayer = layerMap.get(l - 1);
      if (currentLayer == null) {
        continue;
      }
      for (N n : currentLayer) {
        double sum = 0;
        int count = 0;
        if (prevLayer != null) {
          if (n.isVirtual) {
            for (E e : edges) {
              int vIdx = e.virtualNodes.indexOf(n);
              if (vIdx != -1) {
                N pred = vIdx == 0 ? allNodes.get(e.fromId) : e.virtualNodes.get(vIdx - 1);
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
            for (E e : edges) {
              if (e.toId.equals(n.id) && !e.isBackEdge && !e.fromId.equals(e.toId)) {
                N pred =
                    !e.virtualNodes.isEmpty()
                        ? Iterables.getLast(e.virtualNodes)
                        : allNodes.get(e.fromId);
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

    List<Integer> orderedLayers = new ArrayList<>(layerMap.keySet());
    if (isReversed) {
      Collections.reverse(orderedLayers);
    }

    // 4. Coordinate Assignment
    if (!isHorizontal) {
      double maxGraphWidth = 0;
      Map<Integer, Double> layerWidths = new HashMap<>();
      Map<Integer, Double> layerMaxHeights = new HashMap<>();

      for (int l : orderedLayers) {
        List<N> nodes = layerMap.get(l);
        double totalW = 0;
        double maxH = 38;
        for (int i = 0; i < nodes.size(); i++) {
          N n = nodes.get(i);
          totalW += n.width + (i < nodes.size() - 1 ? config.nodeSep : 0);
          if (!n.isVirtual) {
            maxH = Math.max(maxH, n.height);
          }
        }
        layerWidths.put(l, totalW);
        layerMaxHeights.put(l, maxH);
        maxGraphWidth = Math.max(maxGraphWidth, totalW);
      }

      double curY = 0;
      for (int l : orderedLayers) {
        List<N> nodes = layerMap.get(l);
        double w = layerWidths.get(l);
        double curX = (maxGraphWidth - w) / 2.0;
        double maxH = layerMaxHeights.get(l);

        if (config.alignSingleNodeToParents && nodes.size() == 1 && l > 0) {
          N single = nodes.get(0);
          double parentAvgX = 0;
          int parentCount = 0;
          for (E e : edges) {
            if (e.toId.equals(single.id)) {
              N p = allNodes.get(e.fromId);
              if (p != null && p.layer == l - 1) {
                parentAvgX += p.x + p.width / 2.0;
                parentCount++;
              }
            }
          }
          if (parentCount > 0) {
            double targetX = parentAvgX / parentCount - single.width / 2.0;
            curX = Math.max(0, Math.min(targetX, maxGraphWidth - single.width));
          }
        }

        for (N n : nodes) {
          n.x = curX;
          n.y = curY + (maxH - n.height) / 2.0;
          curX += n.width + config.nodeSep;
        }

        double layerGap = config.rankSep;
        for (E e : edges) {
          N src = allNodes.get(e.fromId);
          N dst = allNodes.get(e.toId);
          if (src != null
              && dst != null
              && e.hasLabel()
              && e.appliesGapToLayer(l, src.layer, dst.layer)) {
            layerGap = e.getVerticalLayerGap(layerGap);
          }
        }
        curY += maxH + layerGap;
      }
    } else {
      double maxGraphHeight = 0;
      Map<Integer, Double> layerHeights = new HashMap<>();
      Map<Integer, Double> layerMaxWidths = new HashMap<>();

      for (int l : orderedLayers) {
        List<N> nodes = layerMap.get(l);
        double totalH = 0;
        double maxW = config.minHorizontalLayerWidth;
        for (int i = 0; i < nodes.size(); i++) {
          N n = nodes.get(i);
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
      for (int l : orderedLayers) {
        List<N> nodes = layerMap.get(l);
        double h = layerHeights.get(l);
        double curY = (maxGraphHeight - h) / 2.0;
        double maxW = layerMaxWidths.get(l);

        if (config.alignSingleNodeToParents && nodes.size() == 1 && l > 0) {
          N single = nodes.get(0);
          double parentAvgY = 0;
          int parentCount = 0;
          for (E e : edges) {
            if (e.toId.equals(single.id)) {
              N p = allNodes.get(e.fromId);
              if (p != null && p.layer == l - 1) {
                parentAvgY += p.y + p.height / 2.0;
                parentCount++;
              }
            }
          }
          if (parentCount > 0) {
            double targetY = parentAvgY / parentCount - single.height / 2.0;
            curY = Math.max(0, Math.min(targetY, maxGraphHeight - single.height));
          }
        }

        for (N n : nodes) {
          n.x = curX + (maxW - n.width) / 2.0;
          n.y = curY;
          curY += n.height + 24;
        }

        double layerGap = Math.max(55.0, config.rankSep);
        for (E e : edges) {
          N src = allNodes.get(e.fromId);
          N dst = allNodes.get(e.toId);
          if (src != null
              && dst != null
              && e.hasLabel()
              && e.appliesGapToLayer(l, src.layer, dst.layer)) {
            layerGap = e.getHorizontalLayerGap(layerGap);
          }
        }
        curX += maxW + layerGap;
      }
    }
  }

  /** Performs compound layout for a component containing nested subgraphs/clusters and nodes. */
  static <
          N extends BaseNode,
          E extends BaseEdge<N>,
          S extends BaseSubgraph<N, S>,
          SE extends BaseSubgraphEdge>
      void layoutCompoundComponent(
          SugiyamaConfig config,
          boolean compHorizontal,
          boolean compReversed,
          GraphComponent<N, E, S> comp,
          List<SE> subgraphEdges) {

    // 1. Find root subgraphs and recursively compute internal sizes
    List<S> rootSgs = new ArrayList<>();
    for (S sg : comp.subgraphs) {
      if (sg.parent == null || !comp.subgraphs.contains(sg.parent)) {
        rootSgs.add(sg);
      }
    }
    for (S sg : rootSgs) {
      boolean sgHoriz =
          (rootSgs.size() == 1 && sg.hasDirectionOverride())
              ? sg.isDirectionHorizontal()
              : compHorizontal;
      boolean sgRev =
          (rootSgs.size() == 1 && sg.hasDirectionOverride())
              ? sg.isDirectionReversed()
              : compReversed;
      computeSubgraphSizes(config, sg, sgHoriz, sgRev, comp.edges, subgraphEdges);
    }

    // 2. Build LayoutUnits for root subgraphs and standalone nodes
    List<LayoutUnit<N, S>> units = new ArrayList<>();
    Map<String, LayoutUnit<N, S>> unitMap = new LinkedHashMap<>();

    for (S sg : rootSgs) {
      LayoutUnit<N, S> u = new LayoutUnit<>(sg);
      units.add(u);
      unitMap.put(u.id, u);
    }
    for (N n : comp.nodes.values()) {
      if (!n.hasParentSubgraph()) {
        LayoutUnit<N, S> u = new LayoutUnit<>(n);
        units.add(u);
        unitMap.put(u.id, u);
      }
    }

    if (units.size() == 1) {
      LayoutUnit<N, S> u = units.get(0);
      u.x = 0;
      u.y = 0;
      if (u.subgraph != null) {
        u.subgraph.x = 0;
        u.subgraph.y = 0;
        assignAbsoluteCoordinates(u.subgraph, 0, 0);
      } else if (u.node != null) {
        u.node.x = 0;
        u.node.y = 0;
      }
      return;
    }

    // 3. Map each Node ID to its root LayoutUnit
    Map<String, LayoutUnit<N, S>> nodeToUnit = new HashMap<>();
    for (LayoutUnit<N, S> u : units) {
      if (u.node != null) {
        nodeToUnit.put(u.node.id, u);
      } else if (u.subgraph != null) {
        registerNodesToUnit(u.subgraph, u, nodeToUnit);
      }
    }

    // 4. Build Unit Edges (meta-graph)
    List<UnitEdge> unitEdges = new ArrayList<>();
    Set<String> seenUnitEdges = new HashSet<>();
    for (E e : comp.edges) {
      LayoutUnit<N, S> u1 = nodeToUnit.get(e.fromId);
      LayoutUnit<N, S> u2 = nodeToUnit.get(e.toId);
      if (u1 != null && u2 != null && !u1.id.equals(u2.id)) {
        String key = u1.id + "->" + u2.id;
        if (seenUnitEdges.add(key)) {
          unitEdges.add(
              new UnitEdge(
                  u1.id,
                  u2.id,
                  e.label,
                  e.getBadgeWidth(),
                  e.getBadgeHeight(),
                  e.getUnitVerticalGap(),
                  e.getUnitHorizontalGap(),
                  e.usesInclusiveUnitLayerSpan()));
        } else if (config.reserveLoopSpaceInSubgraphs && e.hasLabel()) {
          for (UnitEdge existingUe : unitEdges) {
            if (existingUe.fromId.equals(u1.id) && existingUe.toId.equals(u2.id)) {
              if (existingUe.label == null || e.label.length() > existingUe.label.length()) {
                existingUe.label = e.label;
                existingUe.badgeWidth = e.getBadgeWidth();
                existingUe.badgeHeight = e.getBadgeHeight();
                existingUe.unitVerticalGap = e.getUnitVerticalGap();
                existingUe.unitHorizontalGap = e.getUnitHorizontalGap();
              }
            }
          }
        }
      }
    }
    for (SE se : subgraphEdges) {
      S sg1 = lookupSubgraphInForest(rootSgs, se.fromSgId);
      S sg2 = lookupSubgraphInForest(rootSgs, se.toSgId);
      if (sg1 != null && sg2 != null) {
        String s1Id = getSubgraphSampleNodeId(sg1);
        String s2Id = getSubgraphSampleNodeId(sg2);
        LayoutUnit<N, S> u1 = s1Id != null ? nodeToUnit.get(s1Id) : unitMap.get("sg_" + sg1.id);
        LayoutUnit<N, S> u2 = s2Id != null ? nodeToUnit.get(s2Id) : unitMap.get("sg_" + sg2.id);
        if (u1 != null && u2 != null && !u1.id.equals(u2.id)) {
          String key = u1.id + "->" + u2.id;
          if (seenUnitEdges.add(key)) {
            unitEdges.add(
                new UnitEdge(
                    u1.id,
                    u2.id,
                    se.label,
                    0.0,
                    0.0,
                    se.getUnitVerticalGap(),
                    se.getUnitHorizontalGap(),
                    false));
          }
        }
      }
    }

    // 5. Run Sugiyama Layout on Units
    layoutUnits(config, compHorizontal, compReversed, units, unitMap, nodeToUnit, unitEdges);

    // 6. Assign Absolute Coordinates
    for (LayoutUnit<N, S> u : units) {
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

  /** Recursively computes the size and internal relative coordinates of a subgraph/cluster. */
  static <
          N extends BaseNode,
          E extends BaseEdge<N>,
          S extends BaseSubgraph<N, S>,
          SE extends BaseSubgraphEdge>
      void computeSubgraphSizes(
          SugiyamaConfig config,
          S sg,
          boolean parentHorizontal,
          boolean parentReversed,
          List<E> edges,
          List<SE> subgraphEdges) {
    boolean isHorizontal =
        sg.hasDirectionOverride() ? sg.isDirectionHorizontal() : parentHorizontal;
    boolean isReversed = sg.hasDirectionOverride() ? sg.isDirectionReversed() : parentReversed;

    // 1. Recursively compute internal sizes of all child subgraphs
    for (S child : sg.children) {
      computeSubgraphSizes(config, child, isHorizontal, isReversed, edges, subgraphEdges);
    }

    double padding = sg.getPadding();
    double headerH = sg.getHeaderHeight();

    // 2. Build LayoutUnits for direct child subgraphs and direct child nodes
    List<LayoutUnit<N, S>> units = new ArrayList<>();
    Map<String, LayoutUnit<N, S>> unitMap = new LinkedHashMap<>();

    for (S child : sg.children) {
      LayoutUnit<N, S> u = new LayoutUnit<>(child);
      units.add(u);
      unitMap.put(u.id, u);
    }
    for (N n : sg.nodes) {
      LayoutUnit<N, S> u = new LayoutUnit<>(n);
      units.add(u);
      unitMap.put(u.id, u);
    }

    if (units.isEmpty()) {
      sg.width = sg.getEmptyWidth();
      sg.height = 60;
      return;
    }

    if (units.size() == 1) {
      LayoutUnit<N, S> u = units.get(0);
      double titleMinW = sg.getTitleMinWidth(padding);
      sg.width = Math.max(titleMinW, u.width + padding * 2);
      sg.height = u.height + padding * 2 + headerH;
      u.x = (sg.width - u.width) / 2.0;
      u.y = headerH + padding;
      if (u.subgraph != null) {
        u.subgraph.relX = u.x;
        u.subgraph.relY = u.y;
      } else if (u.node != null) {
        u.node.relX = u.x;
        u.node.relY = u.y;
      }
      return;
    }

    // 3. Map each Node ID to its immediate LayoutUnit inside sg
    Map<String, LayoutUnit<N, S>> nodeToUnit = new HashMap<>();
    for (LayoutUnit<N, S> u : units) {
      if (u.node != null) {
        nodeToUnit.put(u.node.id, u);
      } else if (u.subgraph != null) {
        registerNodesToUnit(u.subgraph, u, nodeToUnit);
      }
    }

    // 4. Build Unit Edges (meta-graph) for edges where both endpoints are in sg
    List<UnitEdge> unitEdges = new ArrayList<>();
    Set<String> seenUnitEdges = new HashSet<>();
    double extraRightForLoops = 0;
    for (E e : edges) {
      LayoutUnit<N, S> u1 = nodeToUnit.get(e.fromId);
      LayoutUnit<N, S> u2 = nodeToUnit.get(e.toId);
      if (u1 != null && u2 != null) {
        if (!u1.id.equals(u2.id)) {
          String key = u1.id + "->" + u2.id;
          if (seenUnitEdges.add(key)) {
            unitEdges.add(
                new UnitEdge(
                    u1.id,
                    u2.id,
                    e.label,
                    e.getBadgeWidth(),
                    e.getBadgeHeight(),
                    e.getUnitVerticalGap(),
                    e.getUnitHorizontalGap(),
                    e.usesInclusiveUnitLayerSpan()));
          }
        } else if (config.reserveLoopSpaceInSubgraphs && e.fromId.equals(e.toId)) {
          extraRightForLoops = Math.max(extraRightForLoops, 42.0 + e.getBadgeWidth());
        }
      }
    }
    for (SE se : subgraphEdges) {
      S sg1 = lookupSubgraphInTree(sg, se.fromSgId);
      S sg2 = lookupSubgraphInTree(sg, se.toSgId);
      if (sg1 != null && sg2 != null) {
        String s1Id = getSubgraphSampleNodeId(sg1);
        String s2Id = getSubgraphSampleNodeId(sg2);
        LayoutUnit<N, S> u1 = s1Id != null ? nodeToUnit.get(s1Id) : unitMap.get("sg_" + sg1.id);
        LayoutUnit<N, S> u2 = s2Id != null ? nodeToUnit.get(s2Id) : unitMap.get("sg_" + sg2.id);
        if (u1 != null && u2 != null && !u1.id.equals(u2.id)) {
          String key = u1.id + "->" + u2.id;
          if (seenUnitEdges.add(key)) {
            unitEdges.add(
                new UnitEdge(
                    u1.id,
                    u2.id,
                    se.label,
                    0.0,
                    0.0,
                    se.getUnitVerticalGap(),
                    se.getUnitHorizontalGap(),
                    false));
          }
        }
      }
    }

    // 5. Run Sugiyama DAG layout on units
    layoutUnits(config, isHorizontal, isReversed, units, unitMap, nodeToUnit, unitEdges);

    if (config.reserveLoopSpaceInSubgraphs) {
      for (UnitEdge ue : unitEdges) {
        if (ue.isBackEdge) {
          extraRightForLoops = Math.max(extraRightForLoops, 46.0 + ue.badgeWidth);
        }
      }
      for (E e : edges) {
        LayoutUnit<N, S> u1 = nodeToUnit.get(e.fromId);
        LayoutUnit<N, S> u2 = nodeToUnit.get(e.toId);
        if (u1 != null && u2 != null && !u1.id.equals(u2.id) && u1.layer > u2.layer) {
          e.isBackEdge = true;
        }
      }
    }

    // 6. Assign relative coordinates inside sg and compute sg dimensions
    double minX = Double.MAX_VALUE;
    double minY = Double.MAX_VALUE;
    double maxX = -Double.MAX_VALUE;
    double maxY = -Double.MAX_VALUE;
    for (LayoutUnit<N, S> u : units) {
      minX = Math.min(minX, u.x);
      minY = Math.min(minY, u.y);
      maxX = Math.max(maxX, u.x + u.width);
      maxY = Math.max(maxY, u.y + u.height);
    }

    double contentW = (maxX - minX) + extraRightForLoops;
    double titleMinW = sg.getTitleMinWidth(padding);
    sg.width = Math.max(titleMinW, contentW + padding * 2);
    sg.height = (maxY - minY) + padding * 2 + headerH;

    double extraCenterOffset = Math.max(0.0, (sg.width - padding * 2 - contentW) / 2.0);
    for (LayoutUnit<N, S> u : units) {
      double rx = padding + extraCenterOffset + (u.x - minX);
      double ry = headerH + padding + (u.y - minY);
      if (u.subgraph != null) {
        u.subgraph.relX = rx;
        u.subgraph.relY = ry;
      } else if (u.node != null) {
        u.node.relX = rx;
        u.node.relY = ry;
      }
    }
  }

  /** Runs Sugiyama DAG layout on a list of {@link LayoutUnit} super-nodes. */
  static <N extends BaseNode, S extends BaseSubgraph<N, S>> void layoutUnits(
      SugiyamaConfig config,
      boolean isHorizontal,
      boolean isReversed,
      List<LayoutUnit<N, S>> units,
      Map<String, LayoutUnit<N, S>> unitMap,
      Map<String, LayoutUnit<N, S>> nodeToUnit,
      List<UnitEdge> unitEdges) {
    if (units.size() <= 1) {
      return;
    }

    // 1. Cycle Breaking
    Map<String, List<UnitEdge>> uAdj = new HashMap<>();
    for (LayoutUnit<N, S> u : units) {
      uAdj.put(u.id, new ArrayList<>());
    }
    for (UnitEdge ue : unitEdges) {
      if (uAdj.containsKey(ue.fromId)) {
        uAdj.get(ue.fromId).add(ue);
      }
    }
    Map<String, Integer> uColor = new HashMap<>();
    for (LayoutUnit<N, S> u : units) {
      if (uColor.getOrDefault(u.id, 0) == 0) {
        findCyclesDfs(u.id, uAdj, uColor);
      }
    }

    // 2. Layer Assignment
    for (LayoutUnit<N, S> u : units) {
      u.layer = 0;
    }
    boolean changed = true;
    int maxIter = units.size() + 2;
    int iter = 0;
    while (changed && iter++ < maxIter) {
      changed = false;
      for (UnitEdge ue : unitEdges) {
        if (!ue.isBackEdge) {
          LayoutUnit<N, S> src = unitMap.get(ue.fromId);
          LayoutUnit<N, S> dst = unitMap.get(ue.toId);
          if (src != null && dst != null && dst.layer < src.layer + 1) {
            dst.layer = src.layer + 1;
            changed = true;
          }
        }
      }
    }

    // 2a. Source Unit Sinking / Compaction
    for (LayoutUnit<N, S> u : units) {
      int inCount = 0;
      int minOutLayer = Integer.MAX_VALUE;
      for (UnitEdge ue : unitEdges) {
        if (!ue.isBackEdge) {
          LayoutUnit<N, S> src = unitMap.get(ue.fromId);
          LayoutUnit<N, S> dst = unitMap.get(ue.toId);
          if (dst != null && dst.id.equals(u.id) && (src == null || !src.id.equals(u.id))) {
            inCount++;
          }
          if (src != null && src.id.equals(u.id) && dst != null && !dst.id.equals(u.id)) {
            minOutLayer = Math.min(minOutLayer, dst.layer);
          }
        }
      }
      if (inCount == 0 && minOutLayer != Integer.MAX_VALUE && minOutLayer - 1 > u.layer) {
        u.layer = minOutLayer - 1;
      }
    }

    // 2b. Apply rank=same groups for units in this scope and re-propagate downstream unit layers
    if (!config.rankSameGroups.isEmpty()) {
      boolean rankRaised = false;
      for (List<String> group : config.rankSameGroups) {
        int groupLayer = -1;
        for (String nodeId : group) {
          LayoutUnit<N, S> u = nodeToUnit.get(nodeId);
          if (u != null && u.node != null) {
            groupLayer = Math.max(groupLayer, u.layer);
          }
        }
        if (groupLayer >= 0) {
          for (String nodeId : group) {
            LayoutUnit<N, S> u = nodeToUnit.get(nodeId);
            if (u != null && u.node != null && u.layer < groupLayer) {
              u.layer = groupLayer;
              rankRaised = true;
            }
          }
        }
      }
      if (rankRaised) {
        boolean propChanged = true;
        int propIter = 0;
        while (propChanged && propIter++ < maxIter) {
          propChanged = false;
          for (UnitEdge ue : unitEdges) {
            if (!ue.isBackEdge) {
              LayoutUnit<N, S> src = unitMap.get(ue.fromId);
              LayoutUnit<N, S> dst = unitMap.get(ue.toId);
              if (src != null
                  && dst != null
                  && !(src.node != null
                      && dst.node != null
                      && areInSameRankGroup(src.node.id, dst.node.id, config.rankSameGroups))
                  && dst.layer < src.layer + 1) {
                dst.layer = src.layer + 1;
                propChanged = true;
              }
            }
          }
          for (List<String> group : config.rankSameGroups) {
            int groupLayer = -1;
            for (String nodeId : group) {
              LayoutUnit<N, S> u = nodeToUnit.get(nodeId);
              if (u != null && u.node != null) {
                groupLayer = Math.max(groupLayer, u.layer);
              }
            }
            if (groupLayer >= 0) {
              for (String nodeId : group) {
                LayoutUnit<N, S> u = nodeToUnit.get(nodeId);
                if (u != null && u.node != null && u.layer < groupLayer) {
                  u.layer = groupLayer;
                  propChanged = true;
                }
              }
            }
          }
        }
      }
    }

    // 3. Layer Map & Barycentric Ordering
    Map<Integer, List<LayoutUnit<N, S>>> layerMap = new TreeMap<>();
    for (LayoutUnit<N, S> u : units) {
      layerMap.computeIfAbsent(u.layer, k -> new ArrayList<>()).add(u);
    }
    int maxLayer = layerMap.isEmpty() ? 0 : Collections.max(layerMap.keySet());
    for (int l = 1; l <= maxLayer; l++) {
      List<LayoutUnit<N, S>> currentLayer = layerMap.get(l);
      List<LayoutUnit<N, S>> prevLayer = layerMap.get(l - 1);
      if (currentLayer == null) {
        continue;
      }
      for (LayoutUnit<N, S> u : currentLayer) {
        double sum = 0;
        int count = 0;
        if (prevLayer != null) {
          for (UnitEdge ue : unitEdges) {
            if (ue.toId.equals(u.id) && !ue.isBackEdge) {
              LayoutUnit<N, S> src = unitMap.get(ue.fromId);
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

    List<Integer> orderedLayers = new ArrayList<>(layerMap.keySet());
    if (isReversed) {
      Collections.reverse(orderedLayers);
    }

    // 4. Coordinate Assignment
    if (!isHorizontal) {
      double maxW = 0;
      Map<Integer, Double> layerWidths = new HashMap<>();
      Map<Integer, Double> layerMaxHeights = new HashMap<>();
      for (int l : orderedLayers) {
        List<LayoutUnit<N, S>> lUnits = layerMap.get(l);
        double tw = 0;
        double mh = 0;
        for (int i = 0; i < lUnits.size(); i++) {
          LayoutUnit<N, S> u = lUnits.get(i);
          tw += u.width + (i < lUnits.size() - 1 ? 36 : 0);
          mh = Math.max(mh, u.height);
        }
        layerWidths.put(l, tw);
        layerMaxHeights.put(l, mh);
        maxW = Math.max(maxW, tw);
      }

      double curY = 0;
      for (int l : orderedLayers) {
        List<LayoutUnit<N, S>> lUnits = layerMap.get(l);
        double w = layerWidths.get(l);
        double curX = (maxW - w) / 2.0;
        double mh = layerMaxHeights.get(l);
        for (LayoutUnit<N, S> u : lUnits) {
          u.x = curX;
          u.y = curY + (mh - u.height) / 2.0;
          curX += u.width + 36;
        }
        double layerGap = config.unitVerticalBaseGap;
        for (UnitEdge ue : unitEdges) {
          LayoutUnit<N, S> src = unitMap.get(ue.fromId);
          LayoutUnit<N, S> dst = unitMap.get(ue.toId);
          if (src != null && dst != null && ue.unitVerticalGap > 0) {
            boolean spans =
                ue.inclusiveLayerSpan
                    ? (Math.min(src.layer, dst.layer) <= l && l <= Math.max(src.layer, dst.layer))
                    : (src.layer <= l && l < dst.layer);
            if (spans) {
              layerGap = Math.max(layerGap, ue.unitVerticalGap);
            }
          }
        }
        curY += mh + layerGap;
      }
    } else {
      double maxH = 0;
      Map<Integer, Double> layerHeights = new HashMap<>();
      Map<Integer, Double> layerMaxWidths = new HashMap<>();
      for (int l : orderedLayers) {
        List<LayoutUnit<N, S>> lUnits = layerMap.get(l);
        double th = 0;
        double mw = 0;
        for (int i = 0; i < lUnits.size(); i++) {
          LayoutUnit<N, S> u = lUnits.get(i);
          th += u.height + (i < lUnits.size() - 1 ? 36 : 0);
          mw = Math.max(mw, u.width);
        }
        layerHeights.put(l, th);
        layerMaxWidths.put(l, mw);
        maxH = Math.max(maxH, th);
      }

      double curX = 0;
      for (int l : orderedLayers) {
        List<LayoutUnit<N, S>> lUnits = layerMap.get(l);
        double h = layerHeights.get(l);
        double curY = (maxH - h) / 2.0;
        double mw = layerMaxWidths.get(l);
        for (LayoutUnit<N, S> u : lUnits) {
          u.x = curX + (mw - u.width) / 2.0;
          u.y = curY;
          curY += u.height + 36;
        }
        double layerGap = config.unitHorizontalBaseGap;
        for (UnitEdge ue : unitEdges) {
          LayoutUnit<N, S> src = unitMap.get(ue.fromId);
          LayoutUnit<N, S> dst = unitMap.get(ue.toId);
          if (src != null && dst != null && ue.unitHorizontalGap > 0) {
            boolean spans =
                ue.inclusiveLayerSpan
                    ? (Math.min(src.layer, dst.layer) <= l && l <= Math.max(src.layer, dst.layer))
                    : (src.layer <= l && l < dst.layer);
            if (spans) {
              layerGap = Math.max(layerGap, ue.unitHorizontalGap);
            }
          }
        }
        curX += mw + layerGap;
      }
    }
  }

  /** Lays out isolated subgraphs that have no edges connecting their nodes. */
  static <N extends BaseNode, S extends BaseSubgraph<N, S>> void layoutIsolatedSubgraphs(
      boolean isHorizontal, List<S> subgraphs) {
    double padding = 20;
    double headerH = 22;

    for (S sg : subgraphs) {
      if (sg.nodes.isEmpty()) {
        continue;
      }
      if (!isHorizontal) {
        double maxW = 0;
        for (N n : sg.nodes) {
          maxW = Math.max(maxW, n.width);
        }
        double curY = headerH + padding;
        for (N n : sg.nodes) {
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
        for (N n : sg.nodes) {
          maxH = Math.max(maxH, n.height);
        }
        double curX = padding;
        for (N n : sg.nodes) {
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

  /** DFS cycle detector that marks back-edges in directed graphs. */
  static <E extends BaseEdge<?>> void findCyclesDfs(
      String u, Map<String, List<E>> adj, Map<String, Integer> color) {
    color.put(u, 1);
    List<E> uEdges = adj.get(u);
    if (uEdges != null) {
      for (E e : uEdges) {
        String v = e.toId;
        int vColor = color.getOrDefault(v, 0);
        if (vColor == 1) {
          e.isBackEdge = true;
        } else if (vColor == 0) {
          findCyclesDfs(v, adj, color);
        }
      }
    }
    color.put(u, 2);
  }

  /** Recursively maps all descendant nodes of {@code sg} to super-node {@code u}. */
  static <N extends BaseNode, S extends BaseSubgraph<N, S>> void registerNodesToUnit(
      S sg, LayoutUnit<N, S> u, Map<String, LayoutUnit<N, S>> map) {
    for (N n : sg.nodes) {
      map.put(n.id, u);
    }
    for (S child : sg.children) {
      registerNodesToUnit(child, u, map);
    }
  }

  /** Recursively converts relative subgraph and node coordinates into absolute SVG coordinates. */
  static <N extends BaseNode, S extends BaseSubgraph<N, S>> void assignAbsoluteCoordinates(
      S sg, double parentAbsX, double parentAbsY) {
    for (S child : sg.children) {
      child.x = parentAbsX + child.relX;
      child.y = parentAbsY + child.relY;
      assignAbsoluteCoordinates(child, child.x, child.y);
    }
    for (N n : sg.nodes) {
      n.x = parentAbsX + n.relX;
      n.y = parentAbsY + n.relY;
    }
  }

  /** Returns the first sample node ID contained in {@code sg} or any of its descendants. */
  @Nullable
  static <N extends BaseNode, S extends BaseSubgraph<N, S>> String getSubgraphSampleNodeId(S sg) {
    if (!sg.nodes.isEmpty()) {
      return sg.nodes.get(0).id;
    }
    for (S child : sg.children) {
      String id = getSubgraphSampleNodeId(child);
      if (id != null) {
        return id;
      }
    }
    return null;
  }

  /** Returns the nesting depth of {@code sg} (0 for root subgraphs/clusters). */
  static <N extends BaseNode, S extends BaseSubgraph<N, S>> int getSubgraphDepth(S sg) {
    int depth = 0;
    S cur = sg.parent;
    while (cur != null) {
      depth++;
      cur = cur.parent;
    }
    return depth;
  }

  /** Resolves whether {@code sg} (or its nearest overriding ancestor) flows horizontally. */
  static <N extends BaseNode, S extends BaseSubgraph<N, S>> boolean isEffectiveHorizontal(
      @Nullable S sg, boolean fallbackHorizontal) {
    S cur = sg;
    while (cur != null) {
      if (cur.hasDirectionOverride()) {
        return cur.isDirectionHorizontal();
      }
      cur = cur.parent;
    }
    return fallbackHorizontal;
  }

  /** Resolves whether {@code sg} (or its nearest overriding ancestor) flows in reverse order. */
  static <N extends BaseNode, S extends BaseSubgraph<N, S>> boolean isEffectiveReversed(
      @Nullable S sg, boolean fallbackReversed) {
    S cur = sg;
    while (cur != null) {
      if (cur.hasDirectionOverride()) {
        return cur.isDirectionReversed();
      }
      cur = cur.parent;
    }
    return fallbackReversed;
  }

  @Nullable
  private static <N extends BaseNode, S extends BaseSubgraph<N, S>> S lookupSubgraphInForest(
      List<S> roots, String id) {
    for (S root : roots) {
      S found = lookupSubgraphInTree(root, id);
      if (found != null) {
        return found;
      }
    }
    return null;
  }

  @Nullable
  private static <N extends BaseNode, S extends BaseSubgraph<N, S>> S lookupSubgraphInTree(
      S root, String id) {
    if (root.id.equals(id)) {
      return root;
    }
    for (S child : root.children) {
      S res = lookupSubgraphInTree(child, id);
      if (res != null) {
        return res;
      }
    }
    return null;
  }

  private static boolean areInSameRankGroup(
      String id1, String id2, List<List<String>> rankSameGroups) {
    for (List<String> group : rankSameGroups) {
      if (group.contains(id1) && group.contains(id2)) {
        return true;
      }
    }
    return false;
  }

  private DiagramLayoutEngine() {}
}
