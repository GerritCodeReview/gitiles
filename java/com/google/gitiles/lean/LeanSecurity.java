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
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.html.types.LegacyConversions;
import com.google.common.html.types.SafeHtml;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.gitiles.GitilesView;
import com.google.gitiles.ThreadSafePrettifyParser;
import com.google.gitiles.doc.MarkdownConfig;
import com.google.gitiles.doc.MarkdownToHtml;
import com.google.gitiles.doc.html.HtmlBuilder;
import com.google.gitiles.doc.html.SoyHtmlBuilder;
import com.google.gitiles.doc.html.StreamHtmlBuilder;
import java.io.Writer;
import java.lang.Character.UnicodeScript;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import javax.annotation.Nullable;
import org.commonmark.node.Code;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.Heading;
import org.commonmark.node.Image;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.Link;
import org.commonmark.node.Node;
import org.commonmark.node.Text;
import org.eclipse.jgit.lib.Config;
import prettify.parser.Prettify;
import syntaxhighlight.ParseResult;

/**
 * Security helpers for {@link LeanHtml}: bounded {@link SoyHtmlBuilder} output, hardened Markdown
 * configuration, Trojan Source / invisible-character neutralization, homoglyph identifier
 * detection, and normalized line-ending checks.
 */
final class LeanSecurity {
  private static final int MAX_BANNER_DECL_NAMES = 5;
  static final int MAX_MARKDOWN_BLOCK_CHARS = 128 << 10;
  private static final int MAX_MARKDOWN_NESTING_DEPTH = 64;
  private static final int MAX_MARKDOWN_PARAGRAPH_DELIMS = 256;
  private static final int MAX_MARKDOWN_TOTAL_DELIMS = 2_048;
  private static final int MAX_MARKDOWN_HEADINGS = 128;
  private static final int MAX_MARKDOWN_HEADING_CHARS = 512;
  private static final int MAX_MARKDOWN_TOTAL_HEADING_CHARS = 4_096;
  private static final int MAX_MARKDOWN_TABLE_PIPES = 2_048;
  private static final int MAX_ENTITY_LENGTH = 36;
  private static final String CLASS_BIDI_ISOLATE = "LeanBidiIsolate";
  private static final String INNOCUOUS_LINK_URI = "about:invalid#zSoyz";
  private static final String INNOCUOUS_IMAGE_URI = "data:image/gif;base64,zSoyz";

  private static final ImmutableSet<Integer> GREEK_CONFUSABLES =
      ImmutableSet.of(
          0x037B, // ͻ
          0x037C, // ͼ
          0x037D, // ͽ
          0x037F, // Ϳ
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
          0x03BD, // ν
          0x03BF, // ο
          0x03D2, // ϒ
          0x03F2, // ϲ
          0x03F3, // ϳ
          0x03F9, // Ϲ
          0x03FA, // Ϻ
          0x03FB // ϻ
          );

  // Visually confusable COMMON-script Letterlike symbols valid in Lean identifiers (excluding
  // standard Mathlib blackboard-bold constants such as ℂ, ℍ, ℕ, ℙ, ℚ, ℝ, ℤ).
  private static final ImmutableSet<Integer> LETTERLIKE_CONFUSABLES =
      ImmutableSet.of(
          0x2107, // ℇ
          0x210A, // ℊ
          0x210B, // ℋ
          0x210C, // ℌ
          0x210E, // ℎ
          0x210F, // ℏ
          0x2110, // ℐ
          0x2111, // ℑ
          0x2112, // ℒ
          0x2113, // ℓ
          0x2118, // ℘
          0x211B, // ℛ
          0x211C, // ℜ
          0x2126, // Ω
          0x2128, // ℨ
          0x212A, // K
          0x212B, // Å
          0x212C, // ℬ
          0x212D, // ℭ
          0x212E, // ℮
          0x212F, // ℯ
          0x2130, // ℰ
          0x2131, // ℱ
          0x2132, // Ⅎ
          0x2133, // ℳ
          0x2134, // ℴ
          0x2139, // ℹ
          0x2145, // ⅅ
          0x2146, // ⅆ
          0x2147, // ⅇ
          0x2148, // ⅈ
          0x2149, // ⅉ
          0x214E // ⅎ
          );

  private static final ImmutableSet<Integer> EXTRA_INVISIBLE_CODEPOINTS =
      ImmutableSet.of(
          0x034F, // COMBINING GRAPHEME JOINER
          0x115F, // HANGUL CHOSEONG FILLER
          0x1160, // HANGUL JUNGSEONG FILLER
          0x17B4, // KHMER VOWEL INHERENT AQ
          0x17B5, // KHMER VOWEL INHERENT AA
          0x180E, // MONGOLIAN VOWEL SEPARATOR
          0x2028, // LINE SEPARATOR
          0x2029, // PARAGRAPH SEPARATOR
          0x2800, // BRAILLE PATTERN BLANK
          0x3164, // HANGUL FILLER
          0xFFA0 // HALFWIDTH HANGUL FILLER
          );

  // HTML5 named character references that CommonMark decodes into hidden/Trojan code points
  // (plus math variant entities whose expansion appends variation selector U+FE00).
  private static final ImmutableMap<String, Integer> HTML5_HIDDEN_NAMED_ENTITIES =
      ImmutableMap.<String, Integer>builder()
          .put("lrm", 0x200E)
          .put("rlm", 0x200F)
          .put("zwj", 0x200D)
          .put("zwnj", 0x200C)
          .put("shy", 0x00AD)
          .put("ZeroWidthSpace", 0x200B)
          .put("NegativeVeryThinSpace", 0x200B)
          .put("NegativeThinSpace", 0x200B)
          .put("NegativeMediumSpace", 0x200B)
          .put("NegativeThickSpace", 0x200B)
          .put("NoBreak", 0x2060)
          .put("ApplyFunction", 0x2061)
          .put("af", 0x2061)
          .put("InvisibleTimes", 0x2062)
          .put("it", 0x2062)
          .put("InvisibleComma", 0x2063)
          .put("ic", 0x2063)
          .put("caps", 0xFE00)
          .put("cups", 0xFE00)
          .put("gesl", 0xFE00)
          .put("gvertneqq", 0xFE00)
          .put("gvnE", 0xFE00)
          .put("lates", 0xFE00)
          .put("lesg", 0xFE00)
          .put("lvertneqq", 0xFE00)
          .put("lvnE", 0xFE00)
          .put("smtes", 0xFE00)
          .put("sqcaps", 0xFE00)
          .put("sqcups", 0xFE00)
          .put("varsubsetneq", 0xFE00)
          .put("varsubsetneqq", 0xFE00)
          .put("varsupsetneq", 0xFE00)
          .put("varsupsetneqq", 0xFE00)
          .put("vsubnE", 0xFE00)
          .put("vsubne", 0xFE00)
          .put("vsupnE", 0xFE00)
          .put("vsupne", 0xFE00)
          .buildOrThrow();

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
    private final @Nullable TrojanScan trojanScan;
    private int approxChars;
    private int opCount;

    BoundedHtml(int maxChars) {
      this(maxChars, null, null);
    }

    BoundedHtml(int maxChars, @Nullable LeanRequestBudget budget) {
      this(maxChars, budget, null);
    }

    BoundedHtml(int maxChars, @Nullable LeanRequestBudget budget, @Nullable TrojanScan trojanScan) {
      this.maxChars = Math.min(LeanRendering.MAX_OUTPUT_CHARS, Math.max(0, maxChars));
      this.budget = budget;
      this.trojanScan = trojanScan;
    }

    @Nullable
    LeanRequestBudget budget() {
      return budget;
    }

    boolean hasDeclarationHomoglyph(@Nullable LeanDeclaration decl) {
      return decl != null && trojanScan != null && trojanScan.hasDeclarationHomoglyph(decl);
    }

    int remainingChars() {
      return Math.max(0, maxChars - approxChars);
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
  static final class TrojanScan {
    private final int hiddenChars;
    private final boolean hasBidi;
    private final ImmutableList<String> homoglyphDecls;
    private final @Nullable IdentityHashMap<LeanDeclaration, Boolean> homoglyphDeclarations;

    private TrojanScan(
        int hiddenChars,
        boolean hasBidi,
        ImmutableList<String> homoglyphDecls,
        @Nullable IdentityHashMap<LeanDeclaration, Boolean> homoglyphDeclarations) {
      this.hiddenChars = hiddenChars;
      this.hasBidi = hasBidi;
      this.homoglyphDecls = homoglyphDecls;
      this.homoglyphDeclarations = homoglyphDeclarations;
    }

    int hiddenChars() {
      return hiddenChars;
    }

    boolean hasBidi() {
      return hasBidi;
    }

    ImmutableList<String> homoglyphDecls() {
      return homoglyphDecls;
    }

    boolean hasAny() {
      return hiddenChars > 0 || !homoglyphDecls.isEmpty();
    }

    boolean hasDeclarationHomoglyph(@Nullable LeanDeclaration decl) {
      return decl != null
          && homoglyphDeclarations != null
          && homoglyphDeclarations.containsKey(decl);
    }
  }

  /**
   * Builds a {@link MarkdownConfig} for Lean docstrings with {@code allowiframe}, {@code mermaid},
   * {@code safehtml}, {@code namedanchor}, and {@code toc} forced off so host configs cannot inject
   * {@code <iframe>} or raw {@code <svg>} blocks and docstrings cannot hijack page anchors.
   */
  static MarkdownConfig leanMarkdownConfig(@Nullable Config config) {
    Config base = config != null ? config : new Config();
    Config hardened = new Config(base);
    hardened.setBoolean("markdown", null, "mermaid", false);
    hardened.setBoolean("markdown", null, "safehtml", false);
    hardened.setBoolean("markdown", null, "namedanchor", false);
    hardened.setBoolean("markdown", null, "toc", false);
    hardened.setStringList("markdown", null, "allowiframe", ImmutableList.of());
    MarkdownConfig parsed = MarkdownConfig.get(hardened);
    if (parsed.inputLimit > MAX_MARKDOWN_BLOCK_CHARS) {
      hardened.setInt("markdown", null, "inputLimit", MAX_MARKDOWN_BLOCK_CHARS);
      return MarkdownConfig.get(hardened);
    }
    return parsed;
  }

  /**
   * Renders a pre-parsed Lean docstring Markdown AST using {@link MarkdownToHtml} into a bounded
   * {@link StreamHtmlBuilder} while stripping heading anchors (preventing {@code #F1} / line anchor
   * collisions), capping Prettify on fenced code blocks, and blocking protocol-relative or
   * backslash-authority URLs ({@code //}, {@code ///}, {@code /\}, {@code \/}, {@code \\}).
   */
  static @Nullable SafeHtml renderHardenedMarkdown(
      MarkdownConfig markdownConfig,
      @Nullable GitilesView view,
      @Nullable String requestUri,
      @Nullable String filePath,
      Node root,
      int maxOutputChars,
      @Nullable LeanRequestBudget budget) {
    if (budget != null && budget.isCpuDeadlineExceeded()) {
      return null;
    }
    int cappedMaxChars = Math.min(LeanRendering.MAX_OUTPUT_CHARS, Math.max(0, maxOutputChars));
    BoundedMarkdownWriter writer = new BoundedMarkdownWriter(cappedMaxChars, budget);
    MarkdownToHtml.Builder builder =
        MarkdownToHtml.builder()
            .setConfig(markdownConfig)
            .setGitilesView(view)
            .setRequestUri(requestUri)
            .setFilePath(filePath);
    new LeanMarkdownToHtml(builder, budget).renderToHtml(new StreamHtmlBuilder(writer), root);
    if (budget != null && budget.isCpuDeadlineExceeded()) {
      return null;
    }
    return LegacyConversions.riskilyAssumeSafeHtml(writer.finish());
  }

  private static final class BoundedMarkdownWriter extends Writer {
    private final StringBuilder buf = new StringBuilder();
    private final int maxChars;
    private final @Nullable LeanRequestBudget budget;
    private int writeOps;

    private BoundedMarkdownWriter(int maxChars, @Nullable LeanRequestBudget budget) {
      this.maxChars = maxChars;
      this.budget = budget;
    }

    @Override
    public void write(char[] cbuf, int off, int len) {
      if (len <= 0) {
        return;
      }
      if ((long) buf.length() + len > maxChars) {
        throw OutputLimitExceeded.INSTANCE;
      }
      if ((++writeOps & 0x3F) == 0 && budget != null && budget.isCpuDeadlineExceeded()) {
        throw OutputLimitExceeded.INSTANCE;
      }
      buf.append(cbuf, off, len);
    }

    @Override
    public void flush() {}

    @Override
    public void close() {}

    String finish() {
      if (buf.length() > maxChars || (budget != null && budget.isCpuDeadlineExceeded())) {
        throw OutputLimitExceeded.INSTANCE;
      }
      return buf.toString();
    }
  }

  static boolean isDisallowedMarkdownUrl(@Nullable String target) {
    if (Strings.isNullOrEmpty(target)) {
      return false;
    }
    StringBuilder norm = new StringBuilder(target.length());
    for (int i = 0; i < target.length(); ) {
      int cp = target.codePointAt(i);
      i += Character.charCount(cp);
      if (cp <= 0x20 || cp == 0x7F || isHiddenTrojanCodePoint(cp)) {
        continue;
      }
      norm.appendCodePoint(cp);
      if (norm.length() >= 2) {
        break;
      }
    }
    if (norm.length() < 2) {
      return false;
    }
    char c0 = norm.charAt(0);
    char c1 = norm.charAt(1);
    return (c0 == '/' || c0 == '\\') && (c1 == '/' || c1 == '\\');
  }

  private static final class LeanMarkdownToHtml extends MarkdownToHtml {
    private final @Nullable LeanRequestBudget budget;
    private int prettifyCharsUsed;

    private LeanMarkdownToHtml(MarkdownToHtml.Builder builder, @Nullable LeanRequestBudget budget) {
      super(builder);
      this.budget = budget;
    }

    @Override
    public void visit(Text node) {
      String lit = Strings.nullToEmpty(node.getLiteral());
      if (!containsStrongRtl(lit)) {
        super.visit(node);
        return;
      }
      appendRtlIsolatedText(html(), lit);
    }

    @Override
    public void visit(Code node) {
      String lit = Strings.nullToEmpty(node.getLiteral());
      if (!containsStrongRtl(lit)) {
        super.visit(node);
        return;
      }
      html().open("code").attribute("class", "code");
      appendRtlIsolatedText(html(), lit);
      html().close("code");
    }

    @Override
    public void visit(IndentedCodeBlock node) {
      String lit = Strings.nullToEmpty(node.getLiteral());
      if (!containsStrongRtl(lit)) {
        super.visit(node);
        return;
      }
      leanCodeInPre(null, lit);
    }

    @Override
    public void visit(FencedCodeBlock node) {
      String info = node.getInfo();
      String lit = Strings.nullToEmpty(node.getLiteral());
      if (!Strings.isNullOrEmpty(info) && !canPrettifyFence(lit.length())) {
        node.setInfo(null);
        info = null;
      }
      if (!containsStrongRtl(lit)) {
        super.visit(node);
        return;
      }
      leanCodeInPre(info, lit);
    }

    private void leanCodeInPre(@Nullable String lang, String rawText) {
      html().open("pre").attribute("class", "code");
      int leading = 0;
      while (leading < rawText.length() && rawText.charAt(leading) == '\n') {
        html().open("br");
        leading++;
      }
      String text = rawText.substring(leading);
      List<ParseResult> parsed = ImmutableList.of();
      if (!Strings.isNullOrEmpty(lang)) {
        try {
          parsed = ThreadSafePrettifyParser.INSTANCE.parse(lang, text);
        } catch (StackOverflowError ignored) {
          parsed = ImmutableList.of();
        }
      }
      if (!parsed.isEmpty()) {
        int last = 0;
        for (ParseResult r : parsed) {
          appendCodeSpan(null, text, last, r.getOffset());
          last = r.getOffset() + r.getLength();
          appendCodeSpan(r.getStyleKeysString(), text, r.getOffset(), last);
        }
        if (last < text.length()) {
          appendCodeSpan(null, text, last, text.length());
        }
      } else {
        appendRtlIsolatedText(html(), text);
      }
      html().close("pre");
    }

    private void appendCodeSpan(@Nullable String classes, String s, int start, int end) {
      if (end - start <= 0) {
        return;
      }
      String cls = Strings.isNullOrEmpty(classes) ? Prettify.PR_PLAIN : classes;
      html().open("span").attribute("class", cls);
      appendRtlIsolatedText(html(), s.substring(start, end));
      html().close("span");
    }

    private boolean canPrettifyFence(int length) {
      if (budget != null) {
        return budget.tryConsumePrettify(length);
      }
      if (length > LeanRequestBudget.MAX_SINGLE_FENCE_PRETTIFY_CHARS
          || length > LeanRequestBudget.MAX_REQUEST_PRETTIFY_CHARS - prettifyCharsUsed) {
        return false;
      }
      prettifyCharsUsed += length;
      return true;
    }

    @Override
    public void visit(Heading node) {
      int level = Math.max(1, Math.min(6, node.getLevel()));
      String tag = "h" + level;
      html().open(tag);
      for (Node c = node.getFirstChild(); c != null; c = c.getNext()) {
        c.accept(this);
      }
      html().close(tag);
    }

    @Override
    public void visit(Link node) {
      if (isDisallowedMarkdownUrl(node.getDestination())) {
        html().open("a").attribute("href", INNOCUOUS_LINK_URI).attribute("title", node.getTitle());
        for (Node c = node.getFirstChild(); c != null; c = c.getNext()) {
          c.accept(this);
        }
        html().close("a");
        return;
      }
      super.visit(node);
    }

    @Override
    public void visit(Image node) {
      if (isDisallowedMarkdownUrl(node.getDestination())) {
        html()
            .open("img")
            .attribute("src", INNOCUOUS_IMAGE_URI)
            .attribute("title", node.getTitle())
            .attribute("alt", extractMarkdownInnerText(node));
        return;
      }
      super.visit(node);
    }

    private static @Nullable String extractMarkdownInnerText(Node node) {
      if (node.getFirstChild() == null) {
        return null;
      }
      StringBuilder b = new StringBuilder();
      appendTextChildren(b, node);
      return Strings.emptyToNull(b.toString().trim());
    }

    private static void appendTextChildren(StringBuilder b, Node node) {
      for (Node c = node.getFirstChild(); c != null; c = c.getNext()) {
        if (c instanceof Text text) {
          b.append(text.getLiteral());
        } else {
          appendTextChildren(b, c);
        }
      }
    }
  }

  static boolean isBidiControl(int cp) {
    return (cp >= 0x202A && cp <= 0x202E)
        || (cp >= 0x2066 && cp <= 0x2069)
        || cp == 0x200E
        || cp == 0x200F
        || cp == 0x061C;
  }

  static boolean isStrongRtlCodePoint(int cp) {
    byte dir = Character.getDirectionality(cp);
    return dir == Character.DIRECTIONALITY_RIGHT_TO_LEFT
        || dir == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC;
  }

  static boolean isHiddenTrojanCodePoint(int cp) {
    return isBidiControl(cp)
        || Character.getType(cp) == Character.FORMAT
        || EXTRA_INVISIBLE_CODEPOINTS.contains(cp)
        || (cp >= 0x180B && cp <= 0x180F)
        || (cp >= 0x2060 && cp <= 0x206F)
        || (cp >= 0xFE00 && cp <= 0xFE0F)
        || (cp >= 0xE0100 && cp <= 0xE01EF)
        || (cp >= 0x1BCA0 && cp <= 0x1BCA3)
        || (cp >= 0x1D173 && cp <= 0x1D17A)
        || (cp >= 0xFFF9 && cp <= 0xFFFB)
        || (cp >= 0xE0000 && cp <= 0xE007F)
        || cp == 0
        || (Character.isISOControl(cp) && cp != '\t' && cp != '\n' && cp != '\r');
  }

  static boolean containsHiddenOrSurrogate(String text) {
    return scanTextFlags(text, /* checkHidden= */ true, /* checkRtl= */ false);
  }

  private static boolean containsStrongRtl(String text) {
    return scanTextFlags(text, /* checkHidden= */ false, /* checkRtl= */ true);
  }

  private static boolean containsHiddenOrStrongRtl(String text) {
    return scanTextFlags(text, /* checkHidden= */ true, /* checkRtl= */ true);
  }

  private static boolean scanTextFlags(String text, boolean checkHidden, boolean checkRtl) {
    int len = text.length();
    for (int i = 0; i < len; ) {
      char ch = text.charAt(i);
      if (Character.isHighSurrogate(ch)) {
        if (i + 1 < len && Character.isLowSurrogate(text.charAt(i + 1))) {
          int cp = Character.toCodePoint(ch, text.charAt(i + 1));
          if ((checkHidden && isHiddenTrojanCodePoint(cp))
              || (checkRtl && isStrongRtlCodePoint(cp))) {
            return true;
          }
          i += 2;
        } else {
          if (checkHidden) {
            return true;
          }
          i++;
        }
      } else if (Character.isLowSurrogate(ch)) {
        if (checkHidden) {
          return true;
        }
        i++;
      } else {
        if ((checkHidden && isHiddenTrojanCodePoint(ch))
            || (checkRtl && isStrongRtlCodePoint(ch))) {
          return true;
        }
        i++;
      }
    }
    return false;
  }

  private static boolean isConfusableLatinOrSymbol(int cp) {
    return GREEK_CONFUSABLES.contains(cp)
        || LETTERLIKE_CONFUSABLES.contains(cp)
        || (cp >= 0x0250 && cp <= 0x02AF) // IPA extensions (e.g. ɑ, ɡ)
        || (cp >= 0x1D00 && cp <= 0x1DBF && !(cp >= 0x1D62 && cp <= 0x1D6A))
        || (cp >= 0x2160 && cp <= 0x2188) // Roman numerals (e.g. Ⅰ, ⅴ, ⅹ)
        || (cp >= 0x24B6 && cp <= 0x24E9) // Enclosed Latin
        || (cp >= 0xA720 && cp <= 0xA7FF) // Latin Extended-D
        || (cp >= 0xAB30 && cp <= 0xAB6F) // Latin Extended-E
        || (cp >= 0xFF01 && cp <= 0xFF5E) // Fullwidth ASCII
        || (cp >= 0x1D400 && cp <= 0x1D7FF) // Math alphanumeric
        || (cp >= 0x1F130 && cp <= 0x1F18F) // Enclosed alphanumeric supplement
        || cp == 0x0131 // ı
        || cp == 0x017F // ſ
        || cp == 0x0196 // Ɩ
        || cp == 0x019F // Ɵ
        || cp == 0x01A9 // Ʃ
        || cp == 0x01B7 // Ʒ
        || (cp >= 0x01C0 && cp <= 0x01C3) // ǀ ǁ ǂ ǃ
        || cp == 0x0237 // ȷ
        || cp == 0x0241; // Ɂ
  }

  static boolean isHomoglyphDeclName(@Nullable String name) {
    if (Strings.isNullOrEmpty(name) || !containsNonAscii(name)) {
      return false;
    }
    boolean hasLatin = false;
    UnicodeScript firstOtherScript = null;
    int len = name.length();
    for (int i = 0; i < len; ) {
      int cp = name.codePointAt(i);
      i += Character.charCount(cp);
      if (isConfusableLatinOrSymbol(cp)) {
        return true;
      }
      UnicodeScript script = UnicodeScript.of(cp);
      switch (script) {
        case LATIN -> hasLatin = true;
        case CYRILLIC,
            ARMENIAN,
            CHEROKEE,
            DESERET,
            OSAGE,
            ADLAM,
            HEBREW,
            ARABIC,
            LISU,
            COPTIC,
            CANADIAN_ABORIGINAL,
            TIFINAGH,
            GEORGIAN,
            OLD_ITALIC,
            GOTHIC,
            GLAGOLITIC,
            SHAVIAN,
            BAMUM,
            VAI,
            OL_CHIKI,
            WARANG_CITI,
            OSMANYA,
            RUNIC,
            SYRIAC,
            THAANA,
            NKO,
            SAMARITAN,
            MANDAIC -> {
          return true;
        }
        case COMMON, INHERITED, GREEK -> {}
        default -> {
          if (firstOtherScript == null) {
            firstOtherScript = script;
          } else if (firstOtherScript != script) {
            return true;
          }
        }
      }
    }
    return hasLatin && firstOtherScript != null;
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
    IdentityHashMap<LeanDeclaration, Boolean> homoglyphDeclarations = null;
    homoglyphDeclarations = collectHomoglyphs(blocksA, homoglyphs, homoglyphDeclarations);
    homoglyphDeclarations = collectHomoglyphs(blocksB, homoglyphs, homoglyphDeclarations);

    EntityCounts entityCounts = scanDocstringEntities(blocksA, blocksB);
    hiddenCount += entityCounts.hiddenCount;
    hasBidi |= entityCounts.hasBidi;

    return new TrojanScan(
        hiddenCount, hasBidi, ImmutableList.copyOf(homoglyphs), homoglyphDeclarations);
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
    int len = text.length();
    int idx = 0;
    while ((idx = text.indexOf('&', idx)) >= 0) {
      int limit = Math.min(len, idx + MAX_ENTITY_LENGTH + 1);
      int semi = -1;
      for (int k = idx + 1; k < limit; k++) {
        if (text.charAt(k) == ';') {
          semi = k;
          break;
        }
      }
      if (semi > idx + 1) {
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
    Integer exact = HTML5_HIDDEN_NAMED_ENTITIES.get(entity);
    if (exact != null) {
      return exact;
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

  private static @Nullable IdentityHashMap<LeanDeclaration, Boolean> collectHomoglyphs(
      List<LeanBlock> blocks,
      Set<String> out,
      @Nullable IdentityHashMap<LeanDeclaration, Boolean> declOut) {
    for (LeanBlock block : blocks) {
      if (!containsNonAscii(block.raw())) {
        continue;
      }
      if (block.type() == LeanBlockType.DECLARATION && block.decl() != null) {
        LeanDeclaration decl = block.decl();
        if (scanDeclarationHomoglyphs(decl, out)) {
          if (declOut == null) {
            declOut = new IdentityHashMap<>();
          }
          declOut.put(decl, Boolean.TRUE);
        }
      } else if (block.type() == LeanBlockType.IMPORTS) {
        scanCodeLinesHomoglyphs(block.importLines(), null, out);
      } else if (block.type() == LeanBlockType.DIRECTIVE) {
        scanCodeLinesHomoglyphs(block.directiveLines(), null, out);
      }
    }
    return declOut;
  }

  private static boolean scanDeclarationHomoglyphs(
      LeanDeclaration decl, @Nullable Set<String> out) {
    boolean found = false;
    if (isHomoglyphDeclName(decl.name())) {
      if (out == null) {
        return true;
      }
      out.add(decl.name());
      found = true;
    }
    for (String mod : decl.modifiers()) {
      if (scanCodeHomoglyphs(mod, out)) {
        if (out == null) {
          return true;
        }
        found = true;
      }
    }
    for (String attr : decl.attributes()) {
      if (scanCodeHomoglyphs(attr, out)) {
        if (out == null) {
          return true;
        }
        found = true;
      }
    }
    if (decl.derivingClause() != null) {
      for (String drv : decl.derivingClause()) {
        if (scanCodeHomoglyphs(drv, out)) {
          if (out == null) {
            return true;
          }
          found = true;
        }
      }
    }
    if (decl.fields() != null) {
      for (LeanStructureField field : decl.fields()) {
        if (isHomoglyphDeclName(field.name())) {
          if (out == null) {
            return true;
          }
          out.add(field.name());
          found = true;
        }
        if (scanCodeHomoglyphs(field.typeText(), out)
            || scanCodeHomoglyphs(field.defaultValue(), out)) {
          if (out == null) {
            return true;
          }
          found = true;
        }
      }
    }
    if (decl.constructors() != null) {
      for (LeanInductiveCtor ctor : decl.constructors()) {
        if (isHomoglyphDeclName(ctor.name())) {
          if (out == null) {
            return true;
          }
          out.add(ctor.name());
          found = true;
        }
        if (scanCodeHomoglyphs(ctor.signatureText(), out)) {
          if (out == null) {
            return true;
          }
          found = true;
        }
      }
    }
    if (scanCodeLinesHomoglyphs(decl.signatureLines(), decl.bodyLines(), out)) {
      if (out == null) {
        return true;
      }
      found = true;
    }
    return found;
  }

  @CanIgnoreReturnValue
  private static boolean scanCodeLinesHomoglyphs(
      List<LeanCodeLine> primary,
      @Nullable List<LeanCodeLine> secondary,
      @Nullable Set<String> out) {
    boolean hasNonAscii = false;
    for (LeanCodeLine line : primary) {
      if (containsNonAscii(line.text())) {
        hasNonAscii = true;
        break;
      }
    }
    if (!hasNonAscii && secondary != null) {
      for (LeanCodeLine line : secondary) {
        if (containsNonAscii(line.text())) {
          hasNonAscii = true;
          break;
        }
      }
    }
    if (!hasNonAscii) {
      return false;
    }
    StringBuilder joined = new StringBuilder();
    for (LeanCodeLine line : primary) {
      if (joined.length() > 0) {
        joined.append('\n');
      }
      joined.append(line.text());
    }
    if (secondary != null) {
      for (LeanCodeLine line : secondary) {
        if (joined.length() > 0) {
          joined.append('\n');
        }
        joined.append(line.text());
      }
    }
    return scanCodeHomoglyphs(joined.toString(), out);
  }

  private static boolean scanCodeHomoglyphs(@Nullable String code, @Nullable Set<String> out) {
    if (Strings.isNullOrEmpty(code) || !containsNonAscii(code)) {
      return false;
    }
    boolean found = false;
    String stripped = LeanLexer.stripLeanCommentsAndStringsForHygiene(code);
    if (containsNonAscii(stripped)) {
      int len = stripped.length();
      int tokenStart = -1;
      for (int i = 0; i < len; ) {
        int cp = stripped.codePointAt(i);
        int step = Character.charCount(cp);
        boolean isDelimiter = isIdentifierScanDelimiter(cp, stripped, i, step);
        if (isDelimiter) {
          if (tokenStart >= 0) {
            String tok = stripped.substring(tokenStart, i);
            if (isHomoglyphDeclName(tok)) {
              if (out == null) {
                return true;
              }
              out.add(tok);
              found = true;
            }
            tokenStart = -1;
          }
        } else if (tokenStart < 0) {
          tokenStart = i;
        }
        i += step;
      }
      if (tokenStart >= 0) {
        String tok = stripped.substring(tokenStart);
        if (isHomoglyphDeclName(tok)) {
          if (out == null) {
            return true;
          }
          out.add(tok);
          found = true;
        }
      }
    }
    if (scanGuillemetHomoglyphs(code, out)) {
      if (out == null) {
        return true;
      }
      found = true;
    }
    return found;
  }

  private static boolean isIdentifierScanDelimiter(int cp, String source, int offset, int cpLen) {
    if (Character.isWhitespace(cp)) {
      return true;
    }
    if (cp < 128) {
      return switch (cp) {
        case '(',
            ')',
            '[',
            ']',
            '{',
            '}',
            ',',
            ';',
            '.',
            ':',
            '@',
            '#',
            '$',
            '%',
            '^',
            '&',
            '*',
            '=',
            '+',
            '-',
            '/',
            '<',
            '>',
            '|',
            '\\',
            '~',
            '"',
            '`' ->
            true;
        default -> false;
      };
    }
    return LeanSyntax.isUnicodeOperator(source.substring(offset, offset + cpLen));
  }

  private static boolean scanGuillemetHomoglyphs(String code, @Nullable Set<String> out) {
    if (code.indexOf('«') < 0) {
      return false;
    }
    boolean found = false;
    int len = code.length();
    int blockDepth = 0;
    boolean inString = false;
    int rawHashes = -1;
    int[] interpDepths = null;
    int interpTop = 0;
    int i = 0;
    while (i < len) {
      char ch = code.charAt(i);
      char next = i + 1 < len ? code.charAt(i + 1) : '\0';
      if (blockDepth > 0) {
        if (ch == '/' && next == '-') {
          if (blockDepth < Integer.MAX_VALUE) {
            blockDepth++;
          }
          i += 2;
        } else if (ch == '-' && next == '/') {
          blockDepth--;
          i += 2;
        } else {
          i++;
        }
        continue;
      }
      if (rawHashes >= 0) {
        int closeEnd = matchRawClose(code, i, rawHashes);
        if (closeEnd >= 0) {
          rawHashes = -1;
          i = closeEnd;
        } else {
          i++;
        }
        continue;
      }
      if (inString) {
        if (ch == '\\' && i + 1 < len) {
          i += 2;
        } else if (ch == '"') {
          inString = false;
          i++;
        } else {
          i++;
        }
        continue;
      }
      if (interpTop > 0 && interpDepths[interpTop - 1] == 0) {
        if (ch == '\\' && i + 1 < len) {
          i += 2;
        } else if (ch == '{') {
          interpDepths[interpTop - 1] = 1;
          i++;
        } else if (ch == '"') {
          interpTop--;
          i++;
        } else {
          i++;
        }
        continue;
      }
      if (ch == '-' && next == '-') {
        int nl = code.indexOf('\n', i + 2);
        if (nl < 0) {
          break;
        }
        i = nl + 1;
        continue;
      }
      if (ch == '/' && next == '-') {
        blockDepth = 1;
        i += 2;
        continue;
      }
      LeanLexer.RawStringOpen rawOpen = LeanLexer.matchRawStringOpen(code, i);
      if (rawOpen != null) {
        rawHashes = rawOpen.hashes();
        i = rawOpen.contentStart();
        continue;
      }
      if (isInterpPrefixAt(code, i) && interpTop < 64) {
        if (interpDepths == null) {
          interpDepths = new int[64];
        }
        interpDepths[interpTop++] = 0;
        i += 3;
        continue;
      }
      if (ch == '"') {
        inString = true;
        i++;
        continue;
      }
      int charLen = LeanLexer.matchCharLiteral(code, i);
      if (charLen > 0) {
        i += charLen;
        continue;
      }
      if (interpTop > 0) {
        if (ch == '{') {
          if (interpDepths[interpTop - 1] < Integer.MAX_VALUE) {
            interpDepths[interpTop - 1]++;
          }
          i++;
          continue;
        }
        if (ch == '}') {
          interpDepths[interpTop - 1]--;
          i++;
          continue;
        }
      }
      if (ch == '«') {
        int closeIdx = code.indexOf('»', i + 1);
        int innerEnd = closeIdx >= 0 ? closeIdx : len;
        String inner = code.substring(i + 1, innerEnd);
        if (isHomoglyphDeclName(inner)) {
          if (out == null) {
            return true;
          }
          out.add(inner);
          found = true;
        }
        i = closeIdx >= 0 ? closeIdx + 1 : len;
        continue;
      }
      i++;
    }
    return found;
  }

  private static int matchRawClose(String code, int pos, int hashes) {
    if (pos >= code.length() || code.charAt(pos) != '"') {
      return -1;
    }
    int end = pos + 1 + hashes;
    if (end > code.length()) {
      return -1;
    }
    for (int k = pos + 1; k < end; k++) {
      if (code.charAt(k) != '#') {
        return -1;
      }
    }
    return end;
  }

  private static boolean isInterpPrefixAt(String code, int pos) {
    if (pos + 2 >= code.length()) {
      return false;
    }
    char c0 = code.charAt(pos);
    return (c0 == 's' || c0 == 'm' || c0 == 'f' || c0 == 'v')
        && code.charAt(pos + 1) == '!'
        && code.charAt(pos + 2) == '"';
  }

  private static boolean containsNonAscii(String text) {
    int len = text.length();
    for (int i = 0; i < len; i++) {
      if (text.charAt(i) >= 128) {
        return true;
      }
    }
    return false;
  }

  private interface VisibleTextSink {
    void openSpan(String cls);

    void closeSpan();

    void appendAndEscape(String text);
  }

  private static void appendRtlIsolatedText(HtmlBuilder html, String text) {
    if (text.isEmpty()) {
      return;
    }
    if (!containsStrongRtl(text)) {
      html.appendAndEscape(text);
      return;
    }
    emitVisibleText(
        new VisibleTextSink() {
          @Override
          public void openSpan(String cls) {
            html.open("span").attribute("class", cls);
          }

          @Override
          public void closeSpan() {
            html.close("span");
          }

          @Override
          public void appendAndEscape(String part) {
            html.appendAndEscape(part);
          }
        },
        text,
        null);
  }

  /**
   * Appends {@code text} into {@code html}, replacing any invisible, bidirectional control, C0/C1
   * control (other than {@code \t}, {@code \n}, {@code \r}), or lone surrogate character with a
   * visible {@code <span class="LeanTrojanChar">U+XXXX</span>} marker, and wrapping contiguous
   * strong RTL runs in {@code <span class="LeanBidiIsolate">} so implicit UAX #9 bidi reordering
   * cannot swap adjacent operators or tokens.
   */
  static void appendVisibleText(BoundedHtml html, String text, String trojanCharClass) {
    if (text.isEmpty()) {
      return;
    }
    if (!containsHiddenOrStrongRtl(text)) {
      html.appendAndEscape(text);
      return;
    }
    emitVisibleText(
        new VisibleTextSink() {
          @Override
          public void openSpan(String cls) {
            html.open("span").attribute("class", cls);
          }

          @Override
          public void closeSpan() {
            html.close("span");
          }

          @Override
          public void appendAndEscape(String part) {
            html.appendAndEscape(part);
          }
        },
        text,
        trojanCharClass);
  }

  private static void emitVisibleText(
      VisibleTextSink sink, String text, @Nullable String trojanCharClass) {
    int len = text.length();
    int plainStart = 0;
    boolean inBidiIsolate = false;
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

      if (trojanCharClass != null
          && ((Character.isSurrogate(ch) && step == 1) || isHiddenTrojanCodePoint(cp))) {
        if (i > plainStart) {
          sink.appendAndEscape(text.substring(plainStart, i));
        }
        if (inBidiIsolate) {
          sink.closeSpan();
          inBidiIsolate = false;
        }
        sink.openSpan(trojanCharClass);
        sink.appendAndEscape(formatCodePoint(cp));
        sink.closeSpan();
        i += step;
        plainStart = i;
      } else if (isStrongRtlCodePoint(cp)) {
        if (!inBidiIsolate) {
          if (i > plainStart) {
            sink.appendAndEscape(text.substring(plainStart, i));
          }
          sink.openSpan(CLASS_BIDI_ISOLATE);
          inBidiIsolate = true;
          plainStart = i;
        }
        i += step;
      } else if (inBidiIsolate
          && Character.getDirectionality(cp) == Character.DIRECTIONALITY_NONSPACING_MARK) {
        i += step;
      } else {
        if (inBidiIsolate) {
          if (i > plainStart) {
            sink.appendAndEscape(text.substring(plainStart, i));
          }
          sink.closeSpan();
          inBidiIsolate = false;
          plainStart = i;
        }
        i += step;
      }
    }
    if (plainStart < len) {
      sink.appendAndEscape(text.substring(plainStart));
    }
    if (inBidiIsolate) {
      sink.closeSpan();
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
      html.appendAndEscape("Confusable homoglyph characters in identifier(s): ");
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
   * Returns whether {@code markdown} exceeds safe size, nesting depth, heading count/length, GFM
   * table pipe count, or inline delimiter count bounds so CommonMark parsing is skipped before
   * risking deep AST recursion, quadratic backtracking, or large allocations.
   */
  static boolean exceedsMarkdownNestingLimit(String markdown) {
    int len = markdown.length();
    if (len > MAX_MARKDOWN_BLOCK_CHARS) {
      return true;
    }
    int lineQuoteOrListDepth = 0;
    int leadingSpaces = 0;
    int bracketDepth = 0;
    int parenDepth = 0;
    int consecutiveEmphasis = 0;
    int paragraphDelims = 0;
    int totalDelims = 0;
    int headingCount = 0;
    int currentHeadingChars = 0;
    int totalHeadingChars = 0;
    int tablePipes = 0;
    int lineStart = 0;
    int currentLineLen = 0;
    int prevLineLen = 0;
    boolean prevLineBlank = true;
    boolean onAtxHeadingLine = false;
    boolean seenNonWsOnLine = false;
    boolean atLineStart = true;
    boolean inFence = false;
    char fenceChar = '\0';
    int fenceLen = 0;

    for (int i = 0; i < len; i++) {
      char c = markdown.charAt(i);
      if (c == '\r' || c == '\n') {
        int lineEnd = i;
        if (c == '\r' && i + 1 < len && markdown.charAt(i + 1) == '\n') {
          i++;
        }
        if (inFence) {
          // Fenced blocks stay inert for inline delimiters; only closing fence ends them.
        } else if (!seenNonWsOnLine) {
          paragraphDelims = 0;
          prevLineBlank = true;
          prevLineLen = 0;
        } else {
          if (!prevLineBlank
              && isSetextHeadingUnderline(markdown, lineStart, lineEnd, leadingSpaces)) {
            if (++headingCount > MAX_MARKDOWN_HEADINGS
                || prevLineLen > MAX_MARKDOWN_HEADING_CHARS
                || (totalHeadingChars += prevLineLen) > MAX_MARKDOWN_TOTAL_HEADING_CHARS) {
              return true;
            }
          }
          prevLineBlank = false;
          prevLineLen = currentLineLen;
        }
        lineStart = i + 1;
        currentLineLen = 0;
        lineQuoteOrListDepth = 0;
        leadingSpaces = 0;
        atLineStart = true;
        seenNonWsOnLine = false;
        onAtxHeadingLine = false;
        currentHeadingChars = 0;
        continue;
      }

      currentLineLen++;
      if (atLineStart) {
        if (c == ' ' || c == '\t') {
          leadingSpaces += (c == '\t' ? 4 : 1);
          continue;
        }
        seenNonWsOnLine = true;
        if (leadingSpaces <= 3 && (c == '`' || c == '~')) {
          int run = countLeadingFenceRun(markdown, i, c);
          if (!inFence && run >= 3) {
            if (c != '`' || !hasRestOfLineBacktick(markdown, i + run)) {
              inFence = true;
              fenceChar = c;
              fenceLen = run;
              atLineStart = false;
              i += run - 1;
              continue;
            }
          } else if (inFence
              && c == fenceChar
              && run >= fenceLen
              && isOnlyTrailingSpaceToEol(markdown, i + run)) {
            inFence = false;
            atLineStart = false;
            i += run - 1;
            continue;
          }
        }
        if (inFence) {
          atLineStart = false;
          continue;
        }
        if (leadingSpaces <= 3 && c == '#') {
          int hashes = 0;
          int j = i;
          while (j < len && hashes < 7 && markdown.charAt(j) == '#') {
            hashes++;
            j++;
          }
          if (hashes >= 1
              && hashes <= 6
              && (j >= len
                  || markdown.charAt(j) == ' '
                  || markdown.charAt(j) == '\t'
                  || markdown.charAt(j) == '\n'
                  || markdown.charAt(j) == '\r')) {
            if (++headingCount > MAX_MARKDOWN_HEADINGS) {
              return true;
            }
            onAtxHeadingLine = true;
            atLineStart = false;
            i = j - 1;
            continue;
          }
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
      } else {
        if (c != ' ' && c != '\t') {
          seenNonWsOnLine = true;
        }
        if (inFence) {
          continue;
        }
      }

      if (onAtxHeadingLine) {
        if (++currentHeadingChars > MAX_MARKDOWN_HEADING_CHARS
            || ++totalHeadingChars > MAX_MARKDOWN_TOTAL_HEADING_CHARS) {
          return true;
        }
      }
      if (c == '|' && ++tablePipes > MAX_MARKDOWN_TABLE_PIPES) {
        return true;
      }
      if (c == '*' || c == '_' || c == '~') {
        consecutiveEmphasis++;
        if (consecutiveEmphasis > MAX_MARKDOWN_NESTING_DEPTH * 4) {
          return true;
        }
      } else {
        consecutiveEmphasis = 0;
      }
      if (c == '*'
          || c == '~'
          || c == '['
          || c == ']'
          || (c == '_' && !isFlankedByAsciiAlphaNum(markdown, i))) {
        if (++paragraphDelims > MAX_MARKDOWN_PARAGRAPH_DELIMS
            || ++totalDelims > MAX_MARKDOWN_TOTAL_DELIMS) {
          return true;
        }
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
    if (!inFence
        && seenNonWsOnLine
        && !prevLineBlank
        && isSetextHeadingUnderline(markdown, lineStart, len, leadingSpaces)) {
      if (++headingCount > MAX_MARKDOWN_HEADINGS
          || prevLineLen > MAX_MARKDOWN_HEADING_CHARS
          || (totalHeadingChars + prevLineLen) > MAX_MARKDOWN_TOTAL_HEADING_CHARS) {
        return true;
      }
    }
    return false;
  }

  private static boolean isFlankedByAsciiAlphaNum(String s, int idx) {
    if (idx <= 0 || idx + 1 >= s.length()) {
      return false;
    }
    return isAsciiAlphaNum(s.charAt(idx - 1)) && isAsciiAlphaNum(s.charAt(idx + 1));
  }

  private static boolean isAsciiAlphaNum(char c) {
    return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
  }

  private static int countLeadingFenceRun(String s, int pos, char fenceChar) {
    int count = 0;
    int len = s.length();
    while (pos + count < len && s.charAt(pos + count) == fenceChar) {
      count++;
    }
    return count;
  }

  private static boolean hasRestOfLineBacktick(String s, int pos) {
    int len = s.length();
    for (int k = pos; k < len; k++) {
      char c = s.charAt(k);
      if (c == '\n' || c == '\r') {
        return false;
      }
      if (c == '`') {
        return true;
      }
    }
    return false;
  }

  private static boolean isOnlyTrailingSpaceToEol(String s, int pos) {
    int len = s.length();
    for (int k = pos; k < len; k++) {
      char c = s.charAt(k);
      if (c == '\n' || c == '\r') {
        return true;
      }
      if (c != ' ' && c != '\t') {
        return false;
      }
    }
    return true;
  }

  private static boolean isSetextHeadingUnderline(
      String s, int lineStart, int lineEnd, int leadingSpaces) {
    if (leadingSpaces > 3) {
      return false;
    }
    int i = lineStart;
    while (i < lineEnd && (s.charAt(i) == ' ' || s.charAt(i) == '\t')) {
      i++;
    }
    if (i >= lineEnd) {
      return false;
    }
    char mark = s.charAt(i);
    if (mark != '=' && mark != '-') {
      return false;
    }
    while (i < lineEnd && s.charAt(i) == mark) {
      i++;
    }
    while (i < lineEnd) {
      char c = s.charAt(i);
      if (c != ' ' && c != '\t') {
        return false;
      }
      i++;
    }
    return true;
  }

  private static String formatCodePoint(int cp) {
    return String.format(Locale.ROOT, "U+%04X", cp);
  }
}
