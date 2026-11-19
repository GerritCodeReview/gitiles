// Copyright (C) 2026 Google Inc. All Rights Reserved.
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

package com.google.gitiles.lean;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.html.types.SafeHtml;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.gitiles.doc.MarkdownConfig;
import com.google.gitiles.doc.html.SoyHtmlBuilder;
import java.lang.Character.UnicodeScript;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import javax.annotation.Nullable;
import org.eclipse.jgit.lib.Config;

/**
 * Security helpers for {@link LeanHtml}: bounded {@link SoyHtmlBuilder} output, hardened Markdown
 * configuration, Trojan Source / invisible-character neutralization, homoglyph declaration
 * detection, and normalized line-ending checks.
 */
final class LeanSecurity {
  private static final int MAX_BANNER_DECL_NAMES = 5;
  private static final int MAX_MARKDOWN_NESTING_DEPTH = 64;

  private static final ImmutableSet<Integer> GREEK_CONFUSABLES =
      ImmutableSet.of(
          0x0391, // Α
          0x0392, // Β
          0x0395, // Ε
          0x0396, // Ζ
          0x0397, // Η
          0x0399, // Ι
          0x039A, // Κ
          0x039C, // Μ
          0x039D, // Ν
          0x039F, // Ο
          0x03A1, // Ρ
          0x03A4, // Τ
          0x03A5, // Υ
          0x03A7, // Χ
          0x03BF, // ο
          0x03F2, // ϲ
          0x03F9 // Ϲ
          );

  private static final ImmutableSet<Integer> EXTRA_INVISIBLE_CODEPOINTS =
      ImmutableSet.of(
          0x034F, // COMBINING GRAPHEME JOINER
          0x115F, // HANGUL CHOSEONG FILLER
          0x1160, // HANGUL JUNGSEONG FILLER
          0x180E, // MONGOLIAN VOWEL SEPARATOR
          0x2028, // LINE SEPARATOR
          0x2029, // PARAGRAPH SEPARATOR
          0x3164, // HANGUL FILLER
          0xFFA0 // HALFWIDTH HANGUL FILLER
          );

  private LeanSecurity() {}

  /** Thrown immediately when HTML generation exceeds {@link LeanRendering#MAX_OUTPUT_CHARS}. */
  static final class OutputLimitExceeded extends RuntimeException {
    static final OutputLimitExceeded INSTANCE = new OutputLimitExceeded();

    private OutputLimitExceeded() {}

    @Override
    public synchronized Throwable fillInStackTrace() {
      return this;
    }
  }

  /**
   * Wrapper around {@link SoyHtmlBuilder} that tracks exact HTML character growth and aborts early
   * via {@link OutputLimitExceeded} once {@link LeanRendering#MAX_OUTPUT_CHARS} is exceeded.
   */
  static final class BoundedHtml {
    private final SoyHtmlBuilder builder = new SoyHtmlBuilder();
    private final int maxChars;
    private final @Nullable LeanRequestBudget budget;
    private int approxChars;
    private int opCount;

    BoundedHtml(int maxChars) {
      this(maxChars, null);
    }

    BoundedHtml(int maxChars, @Nullable LeanRequestBudget budget) {
      this.maxChars = Math.min(LeanRendering.MAX_OUTPUT_CHARS, Math.max(0, maxChars));
      this.budget = budget;
    }

    @CanIgnoreReturnValue
    BoundedHtml open(String tag) {
      consume(tag.length() + 2);
      builder.open(tag);
      return this;
    }

    @CanIgnoreReturnValue
    BoundedHtml attribute(String att, String val) {
      if (!Strings.isNullOrEmpty(val)) {
        consume(att.length() + 4 + escapedLength(val));
        builder.attribute(att, val);
      }
      return this;
    }

    @CanIgnoreReturnValue
    BoundedHtml close(String tag) {
      consume(tag.length() + 3);
      builder.close(tag);
      return this;
    }

    @CanIgnoreReturnValue
    BoundedHtml appendAndEscape(CharSequence text) {
      consume(escapedLength(text));
      builder.appendAndEscape(text);
      return this;
    }

    @CanIgnoreReturnValue
    BoundedHtml space() {
      consume(1);
      builder.space();
      return this;
    }

    @CanIgnoreReturnValue
    BoundedHtml append(SafeHtml safeHtml) {
      consume(safeHtml.getSafeHtmlString().length());
      builder.append(safeHtml);
      return this;
    }

    SafeHtml toSoy() {
      if (budget != null && budget.isCpuDeadlineExceeded()) {
        throw OutputLimitExceeded.INSTANCE;
      }
      SafeHtml result = builder.toSoy();
      if (result.getSafeHtmlString().length() > maxChars) {
        throw OutputLimitExceeded.INSTANCE;
      }
      return result;
    }

    private void consume(int delta) {
      if (delta < 0 || (long) approxChars + delta > maxChars) {
        throw OutputLimitExceeded.INSTANCE;
      }
      approxChars += delta;
      if ((++opCount & 0xFF) == 0 && budget != null && budget.isCpuDeadlineExceeded()) {
        throw OutputLimitExceeded.INSTANCE;
      }
    }

    private static int escapedLength(CharSequence text) {
      int extra = 0;
      int len = text.length();
      for (int i = 0; i < len; i++) {
        char c = text.charAt(i);
        switch (c) {
          case '<', '>' -> extra += 3;
          case '&', '"', '\'' -> extra += 4;
          default -> {}
        }
      }
      return len + extra;
    }
  }

  /** Summary of Trojan Source / invisible-character and homoglyph risks detected in a render. */
  record TrojanScan(int hiddenChars, boolean hasBidi, ImmutableList<String> homoglyphDecls) {
    boolean hasAny() {
      return hiddenChars > 0 || !homoglyphDecls.isEmpty();
    }
  }

  /**
   * Builds a {@link MarkdownConfig} for Lean docstrings with {@code allowiframe}, {@code mermaid},
   * {@code safehtml}, and {@code namedanchor} forced off so host configs cannot inject {@code
   * <iframe>} or raw {@code <svg>} blocks and docstrings cannot hijack numeric line anchors.
   */
  static MarkdownConfig leanMarkdownConfig(@Nullable Config config) {
    Config base = config != null ? config : new Config();
    Config hardened = new Config(base);
    hardened.setBoolean("markdown", null, "mermaid", false);
    hardened.setBoolean("markdown", null, "safehtml", false);
    hardened.setBoolean("markdown", null, "namedanchor", false);
    hardened.setStringList("markdown", null, "allowiframe", ImmutableList.of());
    MarkdownConfig parsed = MarkdownConfig.get(hardened);
    if (parsed.inputLimit > LeanRendering.MAX_BYTES) {
      hardened.setInt("markdown", null, "inputLimit", LeanRendering.MAX_BYTES);
      return MarkdownConfig.get(hardened);
    }
    return parsed;
  }

  static boolean isBidiControl(int cp) {
    return (cp >= 0x202A && cp <= 0x202E)
        || (cp >= 0x2066 && cp <= 0x2069)
        || cp == 0x200E
        || cp == 0x200F
        || cp == 0x061C;
  }

  static boolean isHiddenTrojanCodePoint(int cp) {
    return isBidiControl(cp)
        || Character.getType(cp) == Character.FORMAT
        || EXTRA_INVISIBLE_CODEPOINTS.contains(cp)
        || cp == 0
        || (Character.isISOControl(cp) && cp != '\t' && cp != '\n' && cp != '\r');
  }

  static boolean containsHiddenOrSurrogate(String text) {
    int len = text.length();
    for (int i = 0; i < len; ) {
      char ch = text.charAt(i);
      if (Character.isHighSurrogate(ch)) {
        if (i + 1 < len && Character.isLowSurrogate(text.charAt(i + 1))) {
          int cp = Character.toCodePoint(ch, text.charAt(i + 1));
          if (isHiddenTrojanCodePoint(cp)) {
            return true;
          }
          i += 2;
        } else {
          return true;
        }
      } else if (Character.isLowSurrogate(ch)) {
        return true;
      } else {
        if (isHiddenTrojanCodePoint(ch)) {
          return true;
        }
        i++;
      }
    }
    return false;
  }

  static boolean isHomoglyphDeclName(@Nullable String name) {
    if (Strings.isNullOrEmpty(name)) {
      return false;
    }
    boolean hasLatin = false;
    boolean hasNonMathScript = false;
    int len = name.length();
    for (int i = 0; i < len; ) {
      int cp = name.codePointAt(i);
      i += Character.charCount(cp);
      if (GREEK_CONFUSABLES.contains(cp)
          || (cp >= 0xFF01 && cp <= 0xFF5E)
          || (cp >= 0x1D400 && cp <= 0x1D7FF)
          || cp == 0x0131
          || cp == 0x0237
          || cp == 0x017F
          || cp == 0x01C0
          || cp == 0x01C3) {
        return true;
      }
      UnicodeScript script = UnicodeScript.of(cp);
      switch (script) {
        case LATIN -> hasLatin = true;
        case CYRILLIC, ARMENIAN, CHEROKEE, DESERET, OSAGE, ADLAM, HEBREW, ARABIC -> {
          return true;
        }
        case COMMON, INHERITED, GREEK -> {}
        default -> hasNonMathScript = true;
      }
    }
    return hasLatin && hasNonMathScript;
  }

  static TrojanScan scanTrojanRisks(
      @Nullable String primarySource,
      @Nullable String secondarySource,
      List<LeanBlock> blocksA,
      List<LeanBlock> blocksB) {
    int hiddenCount = 0;
    boolean hasBidi = false;

    String[] sources = {primarySource, secondarySource};
    for (String source : sources) {
      if (source == null || source.isEmpty()) {
        continue;
      }
      int start = source.charAt(0) == '\uFEFF' ? 1 : 0;
      int len = source.length();
      for (int i = start; i < len; ) {
        char ch = source.charAt(i);
        if (Character.isHighSurrogate(ch)) {
          if (i + 1 < len && Character.isLowSurrogate(source.charAt(i + 1))) {
            int cp = Character.toCodePoint(ch, source.charAt(i + 1));
            if (isHiddenTrojanCodePoint(cp)) {
              hiddenCount++;
              if (isBidiControl(cp)) {
                hasBidi = true;
              }
            }
            i += 2;
          } else {
            hiddenCount++;
            i++;
          }
        } else if (Character.isLowSurrogate(ch)) {
          hiddenCount++;
          i++;
        } else {
          if (isHiddenTrojanCodePoint(ch)) {
            hiddenCount++;
            if (isBidiControl(ch)) {
              hasBidi = true;
            }
          }
          i++;
        }
      }
    }

    Set<String> homoglyphs = new LinkedHashSet<>();
    collectHomoglyphs(blocksA, homoglyphs);
    collectHomoglyphs(blocksB, homoglyphs);

    EntityCounts entityCounts = scanDocstringEntities(blocksA, blocksB);
    hiddenCount += entityCounts.hiddenCount;
    hasBidi |= entityCounts.hasBidi;

    return new TrojanScan(hiddenCount, hasBidi, ImmutableList.copyOf(homoglyphs));
  }

  private record EntityCounts(int hiddenCount, boolean hasBidi) {}

  private static EntityCounts scanDocstringEntities(
      List<LeanBlock> blocksA, List<LeanBlock> blocksB) {
    int count = 0;
    boolean hasBidi = false;
    for (List<LeanBlock> blocks : ImmutableList.of(blocksA, blocksB)) {
      for (LeanBlock block : blocks) {
        if (block.markdownContent() != null) {
          EntityCounts c = scanEntitiesInText(block.markdownContent());
          count += c.hiddenCount;
          hasBidi |= c.hasBidi;
        }
        if (block.decl() != null && block.decl().docComment() != null) {
          EntityCounts c = scanEntitiesInText(block.decl().docComment());
          count += c.hiddenCount;
          hasBidi |= c.hasBidi;
        }
      }
    }
    return new EntityCounts(count, hasBidi);
  }

  private static EntityCounts scanEntitiesInText(String text) {
    int count = 0;
    boolean hasBidi = false;
    int idx = 0;
    while ((idx = text.indexOf('&', idx)) >= 0) {
      int semi = text.indexOf(';', idx + 1);
      if (semi > idx + 1 && semi - idx <= 12) {
        String entity = text.substring(idx + 1, semi);
        int cp = decodeTrojanEntity(entity);
        if (cp >= 0) {
          count++;
          if (isBidiControl(cp)) {
            hasBidi = true;
          }
          idx = semi + 1;
          continue;
        }
      }
      idx++;
    }
    return new EntityCounts(count, hasBidi);
  }

  private static int decodeTrojanEntity(String entity) {
    if (entity.startsWith("#x") || entity.startsWith("#X")) {
      try {
        int cp = Integer.parseInt(entity.substring(2), 16);
        return Character.isValidCodePoint(cp) && isHiddenTrojanCodePoint(cp) ? cp : -1;
      } catch (NumberFormatException e) {
        return -1;
      }
    }
    if (entity.startsWith("#")) {
      try {
        int cp = Integer.parseInt(entity.substring(1), 10);
        return Character.isValidCodePoint(cp) && isHiddenTrojanCodePoint(cp) ? cp : -1;
      } catch (NumberFormatException e) {
        return -1;
      }
    }
    return switch (entity.toLowerCase(Locale.ROOT)) {
      case "lrm" -> 0x200E;
      case "rlm" -> 0x200F;
      case "zwj" -> 0x200D;
      case "zwnj" -> 0x200C;
      case "shy" -> 0x00AD;
      default -> -1;
    };
  }

  private static void collectHomoglyphs(List<LeanBlock> blocks, Set<String> out) {
    for (LeanBlock block : blocks) {
      if (block.type() == LeanBlockType.DECLARATION && block.decl() != null) {
        String name = block.decl().name();
        if (isHomoglyphDeclName(name)) {
          out.add(name);
        }
      }
    }
  }

  /**
   * Appends {@code text} into {@code html}, replacing any invisible, bidirectional control, C0/C1
   * control (other than {@code \t}, {@code \n}, {@code \r}), or lone surrogate character with a
   * visible {@code <span class="LeanTrojanChar">U+XXXX</span>} marker.
   */
  static void appendVisibleText(BoundedHtml html, String text, String trojanCharClass) {
    if (text.isEmpty()) {
      return;
    }
    if (!containsHiddenOrSurrogate(text)) {
      html.appendAndEscape(text);
      return;
    }
    int len = text.length();
    int plainStart = 0;
    for (int i = 0; i < len; ) {
      char ch = text.charAt(i);
      int cp;
      int step;
      if (Character.isHighSurrogate(ch)) {
        if (i + 1 < len && Character.isLowSurrogate(text.charAt(i + 1))) {
          cp = Character.toCodePoint(ch, text.charAt(i + 1));
          step = 2;
        } else {
          cp = ch;
          step = 1;
        }
      } else {
        cp = ch;
        step = 1;
      }

      if ((Character.isSurrogate(ch) && step == 1) || isHiddenTrojanCodePoint(cp)) {
        if (i > plainStart) {
          html.appendAndEscape(text.substring(plainStart, i));
        }
        html.open("span")
            .attribute("class", trojanCharClass)
            .appendAndEscape(formatCodePoint(cp))
            .close("span");
        i += step;
        plainStart = i;
      } else {
        i += step;
      }
    }
    if (plainStart < len) {
      html.appendAndEscape(text.substring(plainStart));
    }
  }

  static void renderTrojanBanner(
      BoundedHtml html,
      TrojanScan scan,
      String bannerClass,
      String badgeClass,
      String trojanCharClass) {
    if (!scan.hasAny()) {
      return;
    }
    html.open("div").attribute("class", bannerClass).attribute("role", "alert");
    html.open("span")
        .attribute("class", badgeClass)
        .appendAndEscape("Unicode warning")
        .close("span");
    StringBuilder msg = new StringBuilder();
    if (scan.hiddenChars() > 0) {
      msg.append("Detected ")
          .append(scan.hiddenChars())
          .append(
              scan.hiddenChars() == 1 ? " hidden/control character" : " hidden/control characters");
      if (scan.hasBidi()) {
        msg.append(" including bidirectional controls");
      }
      msg.append(" (shown as U+XXXX). ");
    }
    html.appendAndEscape(msg.toString());
    if (!scan.homoglyphDecls().isEmpty()) {
      html.appendAndEscape("Confusable homoglyph characters in declaration name(s): ");
      int shown = Math.min(scan.homoglyphDecls().size(), MAX_BANNER_DECL_NAMES);
      for (int i = 0; i < shown; i++) {
        if (i > 0) {
          html.appendAndEscape(", ");
        }
        appendVisibleText(html, scan.homoglyphDecls().get(i), trojanCharClass);
      }
      if (scan.homoglyphDecls().size() > MAX_BANNER_DECL_NAMES) {
        html.appendAndEscape(
            " (+" + (scan.homoglyphDecls().size() - MAX_BANNER_DECL_NAMES) + " more)");
      }
      html.appendAndEscape(".");
    }
    html.close("div");
  }

  static String normalizeLineEndings(String text) {
    if (text.indexOf('\r') < 0) {
      return text;
    }
    StringBuilder out = new StringBuilder(text.length());
    int len = text.length();
    for (int i = 0; i < len; i++) {
      char c = text.charAt(i);
      if (c == '\r') {
        if (i + 1 < len && text.charAt(i + 1) == '\n') {
          i++;
        }
        out.append('\n');
      } else {
        out.append(c);
      }
    }
    return out.toString();
  }

  /**
   * Returns whether {@code markdown} exceeds safe nesting depth ({@value
   * #MAX_MARKDOWN_NESTING_DEPTH}) for blockquotes, nested list prefixes, or bracketed/emphasis
   * constructs so CommonMark parsing is skipped before risking deep AST recursion.
   */
  static boolean exceedsMarkdownNestingLimit(String markdown) {
    int len = markdown.length();
    int lineQuoteOrListDepth = 0;
    int leadingSpaces = 0;
    int bracketDepth = 0;
    int parenDepth = 0;
    int consecutiveEmphasis = 0;
    boolean atLineStart = true;

    for (int i = 0; i < len; i++) {
      char c = markdown.charAt(i);
      if (c == '\n' || c == '\r') {
        lineQuoteOrListDepth = 0;
        leadingSpaces = 0;
        atLineStart = true;
        continue;
      }
      if (atLineStart) {
        if (c == ' ' || c == '\t') {
          leadingSpaces += (c == '\t' ? 4 : 1);
          continue;
        }
        if (c == '>' || c == '-' || c == '*' || c == '+') {
          if (i + 1 >= len
              || markdown.charAt(i + 1) == ' '
              || markdown.charAt(i + 1) == '\t'
              || markdown.charAt(i + 1) == '>') {
            if (leadingSpaces > MAX_MARKDOWN_NESTING_DEPTH * 2) {
              return true;
            }
            lineQuoteOrListDepth++;
            if (lineQuoteOrListDepth > MAX_MARKDOWN_NESTING_DEPTH) {
              return true;
            }
            continue;
          }
        } else if (c >= '0' && c <= '9') {
          int j = i;
          while (j < len && markdown.charAt(j) >= '0' && markdown.charAt(j) <= '9') {
            j++;
          }
          if (j + 1 < len
              && (markdown.charAt(j) == '.' || markdown.charAt(j) == ')')
              && (markdown.charAt(j + 1) == ' ' || markdown.charAt(j + 1) == '\t')) {
            if (leadingSpaces > MAX_MARKDOWN_NESTING_DEPTH * 2) {
              return true;
            }
            lineQuoteOrListDepth++;
            if (lineQuoteOrListDepth > MAX_MARKDOWN_NESTING_DEPTH) {
              return true;
            }
            i = j;
            continue;
          }
        }
        atLineStart = false;
      }
      if (c == '*' || c == '_' || c == '~') {
        consecutiveEmphasis++;
        if (consecutiveEmphasis > MAX_MARKDOWN_NESTING_DEPTH * 4) {
          return true;
        }
      } else {
        consecutiveEmphasis = 0;
      }
      if (c == '[') {
        bracketDepth++;
        if (bracketDepth > MAX_MARKDOWN_NESTING_DEPTH) {
          return true;
        }
      } else if (c == ']' && bracketDepth > 0) {
        bracketDepth--;
      } else if (c == '(') {
        parenDepth++;
        if (parenDepth > MAX_MARKDOWN_NESTING_DEPTH) {
          return true;
        }
      } else if (c == ')' && parenDepth > 0) {
        parenDepth--;
      }
    }
    return false;
  }

  private static String formatCodePoint(int cp) {
    return String.format(Locale.ROOT, "U+%04X", cp);
  }
}
