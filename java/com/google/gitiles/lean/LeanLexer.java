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

import static java.util.Objects.requireNonNull;

import com.google.common.base.Splitter;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.Arrays;
import java.util.List;
import javax.annotation.Nullable;

/**
 * Stateless Lean 4 lexical helpers: source normalization, literal matchers, bracket/attribute
 * scanning, comment/string stripping, block-comment helpers, and per-parse incremental lexical
 * state.
 */
public final class LeanLexer {

  private static final Splitter NEWLINE_SPLITTER = Splitter.on('\n');

  /** Raw string opener metadata ({@code r"..."}/{@code r#"..."#}). */
  public record RawStringOpen(int hashes, int contentStart) {}

  /** Result of scanning a multi-line {@code @[...]} attribute across lines. */
  public record MultiLineAttrScan(
      boolean closed, int endRow, int closeColExclusive, String text, int scannedLines) {}

  /** Result of scanning for the closing {@code -/} of a nested block comment. */
  public record BlockCommentEnd(int endRow, int endCol, boolean closed) {}

  /** Mutable incremental lexical and delimiter state across consecutive Lean source lines. */
  public static final class LeanLexState {
    private int blockDepth;
    private boolean inString;
    private boolean inInterpString;
    @Nullable private int[] interpBraceDepths;
    private int interpBraceTop;
    private int rawStringHashes = -1;
    private boolean inGuillemet;
    private int paren;
    private int bracket;
    private int brace;

    private LeanLexState() {}

    public int blockDepth() {
      return blockDepth;
    }

    public boolean inString() {
      return inString;
    }

    public boolean inInterpString() {
      return inInterpString;
    }

    public int rawStringHashes() {
      return rawStringHashes;
    }

    public boolean inGuillemet() {
      return inGuillemet;
    }

    public int paren() {
      return paren;
    }

    public int bracket() {
      return bracket;
    }

    public int brace() {
      return brace;
    }

    public boolean hasOpenLiteralOrComment() {
      return blockDepth > 0 || inString || inInterpString || rawStringHashes >= 0 || inGuillemet;
    }

    public boolean hasOpenInterpBrace() {
      return interpBraceTop > 0;
    }

    public boolean hasOpenDelimiters() {
      return paren > 0 || bracket > 0 || brace > 0;
    }

    private void pushInterpBrace(int depth) {
      if (interpBraceDepths == null) {
        interpBraceDepths = new int[8];
      } else if (interpBraceTop == interpBraceDepths.length) {
        interpBraceDepths = Arrays.copyOf(interpBraceDepths, interpBraceDepths.length * 2);
      }
      interpBraceDepths[interpBraceTop++] = depth;
    }
  }

  private LeanLexer() {}

  public static boolean isLeanContentToken(@Nullable String tok) {
    return LeanSyntax.isLeanContentToken(tok);
  }

  /** Strips a single leading UTF-8 BOM and normalizes CRLF/CR line endings to LF. */
  public static String normalizeLeanSource(String source) {
    requireNonNull(source, "source");
    int start = (!source.isEmpty() && source.charAt(0) == '\uFEFF') ? 1 : 0;
    int firstCr = source.indexOf('\r', start);
    if (firstCr < 0) {
      return start == 0 ? source : source.substring(start);
    }
    int len = source.length();
    StringBuilder out = new StringBuilder(len - start);
    for (int i = start; i < len; i++) {
      char c = source.charAt(i);
      if (c == '\r') {
        out.append('\n');
        if (i + 1 < len && source.charAt(i + 1) == '\n') {
          i++;
        }
      } else {
        out.append(c);
      }
    }
    return out.toString();
  }

  /**
   * Returns whether the code point immediately preceding UTF-16 index {@code i} is a Lean
   * identifier continuation character, without splitting surrogate pairs.
   */
  public static boolean hasIdentPartBefore(String text, int i) {
    requireNonNull(text, "text");
    if (i <= 0 || i > text.length()) {
      return false;
    }
    return LeanSyntax.isIdentPart(text.codePointBefore(i));
  }

  /**
   * Matches a Lean 4 character literal ({@code 'a'}, {@code '"'}, {@code '\''}, {@code '\n'},
   * {@code '\x41'}, {@code '\u0041'}, {@code '😀'}) at index {@code i}, ensuring prime suffixes on
   * identifiers ({@code hlt'}, {@code x'}) are never misread. Returns matched UTF-16 length or 0.
   */
  public static int matchCharLiteral(String text, int i) {
    requireNonNull(text, "text");
    int len = text.length();
    if (i < 0 || i >= len || text.charAt(i) != '\'' || hasIdentPartBefore(text, i)) {
      return 0;
    }
    int pos = i + 1;
    if (pos >= len) {
      return 0;
    }
    char c = text.charAt(pos);
    if (c == '\\') {
      if (pos + 1 >= len) {
        return 0;
      }
      int escCp = text.codePointAt(pos + 1);
      if (escCp == '\n') {
        return 0;
      }
      if (escCp == 'x'
          && pos + 4 < len
          && isHexDigit(text.charAt(pos + 2))
          && isHexDigit(text.charAt(pos + 3))
          && text.charAt(pos + 4) == '\'') {
        return 6;
      }
      if (escCp == 'u'
          && pos + 6 < len
          && isHexDigit(text.charAt(pos + 2))
          && isHexDigit(text.charAt(pos + 3))
          && isHexDigit(text.charAt(pos + 4))
          && isHexDigit(text.charAt(pos + 5))
          && text.charAt(pos + 6) == '\'') {
        return 8;
      }
      int afterEsc = pos + 1 + Character.charCount(escCp);
      if (afterEsc < len && text.charAt(afterEsc) == '\'') {
        return afterEsc + 1 - i;
      }
      return 0;
    }
    if (c == '\'' || c == '\n') {
      return 0;
    }
    int cp = text.codePointAt(pos);
    int afterChar = pos + Character.charCount(cp);
    if (afterChar < len && text.charAt(afterChar) == '\'') {
      return afterChar + 1 - i;
    }
    return 0;
  }

  /**
   * Matches a Lean 4 raw string opener ({@code r"..."}, {@code r#"..."#}, {@code r##"..."##}) at
   * index {@code i}, or returns {@code null}.
   */
  @Nullable
  public static RawStringOpen matchRawStringOpen(String text, int i) {
    requireNonNull(text, "text");
    int len = text.length();
    if (i < 0 || i >= len || text.charAt(i) != 'r' || hasIdentPartBefore(text, i)) {
      return null;
    }
    int hashes = 0;
    while (i + 1 + hashes < len && text.charAt(i + 1 + hashes) == '#') {
      hashes++;
    }
    if (i + 1 + hashes < len && text.charAt(i + 1 + hashes) == '"') {
      return new RawStringOpen(hashes, i + 2 + hashes);
    }
    return null;
  }

  /**
   * Checks whether {@code text} at index {@code i} closes a raw string with {@code hashes} trailing
   * {@code #} characters.
   */
  public static boolean matchRawStringCloseAt(String text, int i, int hashes) {
    requireNonNull(text, "text");
    int len = text.length();
    if (hashes < 0 || i < 0 || hashes >= len - i || text.charAt(i) != '"') {
      return false;
    }
    for (int k = 1; k <= hashes; k++) {
      if (text.charAt(i + k) != '#') {
        return false;
      }
    }
    return true;
  }

  /**
   * Matches a Lean 4 number literal (hex {@code 0x...}, binary {@code 0b...}, octal {@code 0o...},
   * decimal/float) at index {@code i}. Returns matched length or 0.
   */
  public static int matchNumberLiteral(String text, int i) {
    requireNonNull(text, "text");
    int len = text.length();
    if (i < 0 || i >= len) {
      return 0;
    }
    char ch = text.charAt(i);
    if (ch < '0' || ch > '9' || hasIdentPartBefore(text, i)) {
      return 0;
    }
    if (ch == '0' && i + 2 < len) {
      char prefix = text.charAt(i + 1);
      if ((prefix == 'x' || prefix == 'X') && isHexDigitOrUnderscore(text.charAt(i + 2))) {
        int j = i + 3;
        while (j < len && isHexDigitOrUnderscore(text.charAt(j))) {
          j++;
        }
        return j - i;
      }
      if ((prefix == 'b' || prefix == 'B') && isBinaryDigitOrUnderscore(text.charAt(i + 2))) {
        int j = i + 3;
        while (j < len && isBinaryDigitOrUnderscore(text.charAt(j))) {
          j++;
        }
        return j - i;
      }
      if ((prefix == 'o' || prefix == 'O') && isOctalDigitOrUnderscore(text.charAt(i + 2))) {
        int j = i + 3;
        while (j < len && isOctalDigitOrUnderscore(text.charAt(j))) {
          j++;
        }
        return j - i;
      }
    }
    int j = i + 1;
    while (j < len && isDecimalDigitOrUnderscore(text.charAt(j))) {
      j++;
    }
    if (j + 1 < len && text.charAt(j) == '.' && isDecimalDigitOrUnderscore(text.charAt(j + 1))) {
      j += 2;
      while (j < len && isDecimalDigitOrUnderscore(text.charAt(j))) {
        j++;
      }
    }
    return j - i;
  }

  /**
   * Finds the matching {@code ]} for {@code [} at {@code openIdx}, respecting nested brackets,
   * comments ({@code --} and nested {@code /- ... -/}), strings, raw strings, char literals, and
   * guillemet identifiers.
   */
  public static int findMatchingBracket(String text, int openIdx) {
    requireNonNull(text, "text");
    int len = text.length();
    if (openIdx < 0 || openIdx >= len || text.charAt(openIdx) != '[') {
      return -1;
    }
    LeanLexState state = createLeanLexState();
    state.bracket = 1;
    return advanceLeanLexStateCore(state, text, openIdx + 1, null, true);
  }

  /**
   * Incrementally scans a multi-line {@code @[...]} attribute in a single forward pass without
   * re-concatenating or rescanning prior lines.
   */
  public static MultiLineAttrScan findMultiLineAttributeEnd(
      List<String> lines, int startRow, int attrStartCol, int openBracketCol, int maxEndRow) {
    requireNonNull(lines, "lines");
    int limitRow = Math.min(lines.size() - 1, maxEndRow);
    if (startRow < 0 || startRow > limitRow) {
      return new MultiLineAttrScan(false, -1, -1, "", Math.max(1, limitRow - startRow + 1));
    }
    LeanLexState state = createLeanLexState();
    state.bracket = 1;

    for (int row = startRow; row <= limitRow; row++) {
      String line = lines.get(row);
      int startCol = row == startRow ? Math.max(0, openBracketCol + 1) : 0;
      int closeCol = advanceLeanLexStateCore(state, line, startCol, null, true);
      if (closeCol >= 0) {
        int clampedAttrStart = Math.max(0, Math.min(attrStartCol, lines.get(startRow).length()));
        String text;
        if (row == startRow) {
          text = line.substring(Math.min(clampedAttrStart, closeCol + 1), closeCol + 1);
        } else {
          StringBuilder sb = new StringBuilder();
          sb.append(lines.get(startRow), clampedAttrStart, lines.get(startRow).length());
          for (int r = startRow + 1; r < row; r++) {
            sb.append('\n').append(lines.get(r));
          }
          sb.append('\n').append(line, 0, closeCol + 1);
          text = sb.toString();
        }
        return new MultiLineAttrScan(true, row, closeCol + 1, text, row - startRow + 1);
      }
    }

    return new MultiLineAttrScan(false, -1, -1, "", Math.max(1, limitRow - startRow + 1));
  }

  /**
   * Strips Lean 4 comments (including nested block comments), string literals (including raw {@code
   * r#"..."#}, multi-line strings, and stack-aware interpolated strings {@code s!"...{expr}..."}),
   * char literals, and non-soundness guillemet-escaped identifiers ({@code «...»}) while preserving
   * exact 1:1 UTF-16 character offsets and newlines.
   */
  public static String stripLeanCommentsAndStrings(String rawCode) {
    return stripLeanCommentsAndStrings(rawCode, false);
  }

  /**
   * Like {@link #stripLeanCommentsAndStrings(String)}, but for proof-hygiene counting only also
   * exposes code inside balanced {@code {...}} interpolations of unprefixed {@code "..."} string
   * literals (such as {@code dbg_trace} or {@code throwError} messages).
   */
  public static String stripLeanCommentsAndStringsForHygiene(String rawCode) {
    return stripLeanCommentsAndStrings(rawCode, true);
  }

  private static String stripLeanCommentsAndStrings(
      String rawCode, boolean exposeNormalStringBraces) {
    requireNonNull(rawCode, "rawCode");
    String code = normalizeLeanSource(rawCode);
    int len = code.length();
    StringBuilder out = new StringBuilder(len);
    int i = 0;
    int blockDepth = 0;
    boolean inLineComment = false;
    boolean inString = false;
    int strBraceDepth = 0;
    int strOpenBraceIdx = -1;
    int strBlockDepth = 0;
    boolean strInLineComment = false;
    boolean inInterpString = false;
    int[] interpBraceDepths = new int[8];
    int interpBraceTop = 0;
    int rawStringHashes = -1;

    while (i < len) {
      char ch = code.charAt(i);
      char next = i + 1 < len ? code.charAt(i + 1) : '\0';

      if (inLineComment) {
        if (ch == '\n') {
          inLineComment = false;
          out.append('\n');
        } else {
          out.append(' ');
        }
        i++;
        continue;
      }

      if (blockDepth > 0) {
        if (ch == '/' && next == '-') {
          blockDepth++;
          out.append("  ");
          i += 2;
        } else if (ch == '-' && next == '/') {
          blockDepth--;
          out.append("  ");
          i += 2;
        } else {
          out.append(ch == '\n' ? '\n' : ' ');
          i++;
        }
        continue;
      }

      if (rawStringHashes >= 0) {
        if (matchRawStringCloseAt(code, i, rawStringHashes)) {
          int closeLen = 1 + rawStringHashes;
          appendSpaces(out, closeLen);
          i += closeLen;
          rawStringHashes = -1;
        } else {
          out.append(ch == '\n' ? '\n' : ' ');
          i++;
        }
        continue;
      }

      if (inInterpString) {
        if (ch == '\\' && i + 1 < len) {
          out.append(' ').append(next == '\n' ? '\n' : ' ');
          i += 2;
        } else if (ch == '"') {
          inInterpString = false;
          out.append(' ');
          i++;
        } else if (ch == '{') {
          inInterpString = false;
          if (interpBraceTop == interpBraceDepths.length) {
            interpBraceDepths = Arrays.copyOf(interpBraceDepths, interpBraceDepths.length * 2);
          }
          interpBraceDepths[interpBraceTop++] = 1;
          out.append('{');
          i++;
        } else {
          out.append(ch == '\n' ? '\n' : ' ');
          i++;
        }
        continue;
      }

      if (inString) {
        if (ch == '\\' && i + 1 < len) {
          out.append(' ').append(next == '\n' ? '\n' : ' ');
          i += 2;
        } else if (ch == '"') {
          inString = false;
          strBraceDepth = 0;
          strOpenBraceIdx = -1;
          strBlockDepth = 0;
          strInLineComment = false;
          out.append(' ');
          i++;
        } else if (exposeNormalStringBraces && strInLineComment) {
          if (ch == '\n') {
            strInLineComment = false;
            out.append('\n');
          } else {
            out.append(' ');
          }
          i++;
        } else if (exposeNormalStringBraces && strBlockDepth > 0) {
          if (ch == '/' && next == '-') {
            strBlockDepth++;
            out.append("  ");
            i += 2;
          } else if (ch == '-' && next == '/') {
            strBlockDepth--;
            out.append("  ");
            i += 2;
          } else {
            out.append(ch == '\n' ? '\n' : ' ');
            i++;
          }
        } else if (exposeNormalStringBraces && strBraceDepth > 0) {
          if (ch == '/' && next == '-') {
            strBlockDepth = 1;
            out.append("  ");
            i += 2;
          } else if (ch == '-' && next == '-') {
            strInLineComment = true;
            out.append("  ");
            i += 2;
          } else {
            int charLen = matchCharLiteral(code, i);
            if (charLen > 0 && noQuoteInSlice(code, i, charLen)) {
              appendSpaces(out, charLen);
              i += charLen;
            } else {
              out.append(ch == '\n' ? '\n' : ' ');
              if (ch == '{') {
                strBraceDepth++;
              } else if (ch == '}') {
                strBraceDepth--;
                if (strBraceDepth == 0 && strOpenBraceIdx >= 0) {
                  revealBalancedInterpSpan(code, out, strOpenBraceIdx, i);
                  strOpenBraceIdx = -1;
                }
              }
              i++;
            }
          }
        } else {
          out.append(ch == '\n' ? '\n' : ' ');
          if (exposeNormalStringBraces && ch == '{') {
            strOpenBraceIdx = i;
            strBraceDepth = 1;
          }
          i++;
        }
        continue;
      }

      if (ch == '/' && next == '-') {
        blockDepth = 1;
        out.append("  ");
        i += 2;
        continue;
      }

      if (ch == '-' && next == '-') {
        inLineComment = true;
        out.append("  ");
        i += 2;
        continue;
      }

      if (interpBraceTop > 0) {
        if (ch == '{') {
          interpBraceDepths[interpBraceTop - 1]++;
          out.append('{');
          i++;
          continue;
        }
        if (ch == '}') {
          int nextDepth = interpBraceDepths[interpBraceTop - 1] - 1;
          if (nextDepth <= 0) {
            interpBraceTop--;
            inInterpString = true;
          } else {
            interpBraceDepths[interpBraceTop - 1] = nextDepth;
          }
          out.append('}');
          i++;
          continue;
        }
      }

      RawStringOpen rawOpen = matchRawStringOpen(code, i);
      if (rawOpen != null) {
        appendSpaces(out, rawOpen.contentStart() - i);
        rawStringHashes = rawOpen.hashes();
        i = rawOpen.contentStart();
        continue;
      }

      int charLen = matchCharLiteral(code, i);
      if (charLen > 0) {
        appendSpaces(out, charLen);
        i += charLen;
        continue;
      }

      // Multi-line guillemet identifier `«...»`.
      if (ch == '«') {
        int j = i + 1;
        while (j < len && code.charAt(j) != '»') {
          j++;
        }
        boolean hasClose = j < len && code.charAt(j) == '»';
        String inner = code.substring(i + 1, j);
        int end = hasClose ? j + 1 : j;
        if (hasClose && LeanSyntax.SOUNDNESS_GUILLEMET_NAMES.contains(inner)) {
          out.append(' ').append(inner).append(' ');
        } else {
          for (int k = i; k < end; k++) {
            out.append(code.charAt(k) == '\n' ? '\n' : ' ');
          }
        }
        i = end;
        continue;
      }

      int startCp = code.codePointAt(i);
      int startLen = Character.charCount(startCp);
      if (startCp == '#') {
        out.append('#');
        i++;
        continue;
      }
      if (LeanSyntax.isIdentStart(startCp)) {
        int j = i + startLen;
        while (j < len) {
          int partCp = code.codePointAt(j);
          if (!LeanSyntax.isIdentPart(partCp)) {
            break;
          }
          j += Character.charCount(partCp);
        }
        String ident = code.substring(i, j);
        if (LeanSyntax.LEAN_INTERP_STRING_PREFIXES.contains(ident)
            && j < len
            && code.charAt(j) == '"') {
          appendSpaces(out, j - i + 1);
          inInterpString = true;
          i = j + 1;
          continue;
        }
        out.append(code, i, j);
        i = j;
        continue;
      }

      if (ch == '"') {
        inString = true;
        strBraceDepth = 0;
        strOpenBraceIdx = -1;
        out.append(' ');
        i++;
        continue;
      }

      out.appendCodePoint(startCp);
      i += startLen;
    }

    return out.toString();
  }

  /** Scans for the closing {@code -/} starting from column 0 of {@code startIdx}. */
  public static BlockCommentEnd findBlockCommentEnd(List<String> lines, int startIdx) {
    return findBlockCommentEnd(lines, startIdx, 0);
  }

  /**
   * Scans the end position (0-based {@code endRow}, exclusive {@code endCol} after {@code -/}) for
   * nested Lean 4 block comments ({@code /-}, {@code /--}, {@code /-!}) starting at {@code
   * (startIdx, startCol)}.
   */
  public static BlockCommentEnd findBlockCommentEnd(
      List<String> lines, int startIdx, int startCol) {
    requireNonNull(lines, "lines");
    if (lines.isEmpty() || startIdx < 0 || startIdx >= lines.size()) {
      return new BlockCommentEnd(0, 0, false);
    }
    int depth = 0;
    for (int row = startIdx; row < lines.size(); row++) {
      String line = lines.get(row);
      int lineLen = line.length();
      int i = row == startIdx ? Math.max(0, startCol) : 0;
      while (i < lineLen) {
        if (i + 1 < lineLen && line.charAt(i) == '/' && line.charAt(i + 1) == '-') {
          depth++;
          i += 2;
        } else if (i + 1 < lineLen && line.charAt(i) == '-' && line.charAt(i + 1) == '/') {
          depth--;
          i += 2;
          if (depth <= 0) {
            return new BlockCommentEnd(row, i, true);
          }
        } else {
          i++;
        }
      }
    }
    int endRow = lines.size() - 1;
    // Avoid attributing a phantom trailing empty line from a final newline to an unclosed EOF block
    // comment.
    if (endRow > startIdx && lines.get(endRow).isEmpty()) {
      endRow--;
    }
    return new BlockCommentEnd(endRow, lines.get(endRow).length(), false);
  }

  /** Cleans the outer delimiters ({@code /-! ... -/}, {@code /-- ... -/}, {@code /- ... -/}). */
  public static String unwrapBlockComment(String raw, int openerLen) {
    requireNonNull(raw, "raw");
    String inner = raw.trim();
    int safeOpenerLen = Math.max(0, openerLen);
    if (inner.length() >= safeOpenerLen) {
      inner = inner.substring(safeOpenerLen);
    }
    if (inner.endsWith("-/")) {
      inner = inner.substring(0, inner.length() - 2);
    }
    List<String> innerLines = NEWLINE_SPLITTER.splitToList(inner);
    int start = 0;
    int end = innerLines.size();
    while (start < end && innerLines.get(start).trim().isEmpty()) {
      start++;
    }
    while (end > start && innerLines.get(end - 1).trim().isEmpty()) {
      end--;
    }
    if (end - start == 1) {
      return innerLines.get(start).trim();
    }
    return String.join("\n", innerLines.subList(start, end));
  }

  /** Returns whether {@code line} is a section divider comment ({@code -- ====...}). */
  public static boolean isDividerCommentLine(String line) {
    requireNonNull(line, "line");
    int len = line.length();
    int i = skipLeadingWhitespace(line, 0, len);
    int end = len;
    while (end > i && isLeanWhitespace(line.charAt(end - 1))) {
      end--;
    }
    if (end - i < 2 || line.charAt(i) != '-' || line.charAt(i + 1) != '-') {
      return false;
    }
    i += 2;
    while (i < end && isLeanWhitespace(line.charAt(i))) {
      i++;
    }
    int dividerCount = 0;
    while (i < end) {
      char c = line.charAt(i);
      if (c == '=' || c == '-' || c == '~' || c == '*' || c == '─' || c == '━') {
        dividerCount++;
        i++;
      } else {
        break;
      }
    }
    if (dividerCount < 4) {
      return false;
    }
    while (i < end) {
      if (!isLeanWhitespace(line.charAt(i))) {
        return false;
      }
      i++;
    }
    return true;
  }

  /**
   * Returns the UTF-16 index in {@code text} at or after {@code start} past any leading Unicode
   * whitespace and closed Lean block comments ({@code /- ... -/}) in a single linear pass.
   */
  public static int skipLeadingWsAndBlockCommentsIndex(String text, int start) {
    requireNonNull(text, "text");
    int len = text.length();
    int pos = skipLeadingWhitespace(text, Math.min(len, Math.max(0, start)), len);
    while (pos + 1 < len && text.charAt(pos) == '/' && text.charAt(pos + 1) == '-') {
      int depth = 1;
      int i = pos + 2;
      while (i < len && depth > 0) {
        if (i + 1 < len && text.charAt(i) == '/' && text.charAt(i + 1) == '-') {
          depth++;
          i += 2;
        } else if (i + 1 < len && text.charAt(i) == '-' && text.charAt(i + 1) == '/') {
          depth--;
          i += 2;
        } else {
          i++;
        }
      }
      if (depth != 0) {
        break;
      }
      pos = skipLeadingWhitespace(text, i, len);
    }
    return pos;
  }

  /** Strips leading whitespace and any leading closed Lean block comments ({@code /- ... -/}). */
  public static String skipLeadingWsAndBlockComments(String text) {
    requireNonNull(text, "text");
    return text.substring(skipLeadingWsAndBlockCommentsIndex(text, 0));
  }

  private static int skipLeadingWhitespace(String text, int pos, int len) {
    while (pos < len && isLeanWhitespace(text.charAt(pos))) {
      pos++;
    }
    return pos;
  }

  private static boolean isLeanWhitespace(int cp) {
    return cp == ' ' || cp == '\t' || cp == '\r' || cp == '\n';
  }

  /** Creates a fresh {@link LeanLexState} for multi-line declaration scanning. */
  public static LeanLexState createLeanLexState() {
    return new LeanLexState();
  }

  /** Returns whether {@code state} currently has an unclosed literal or comment. */
  public static boolean hasOpenLiteralOrComment(LeanLexState state) {
    requireNonNull(state, "state");
    return state.hasOpenLiteralOrComment();
  }

  /** Returns whether {@code state} currently has unclosed {@code ()}, {@code []}, or {@code {}}. */
  public static boolean hasOpenDelimiters(LeanLexState state) {
    requireNonNull(state, "state");
    return state.hasOpenDelimiters();
  }

  /**
   * Returns whether {@code source} ends inside an unterminated string, raw/interpolated string,
   * guillemet identifier, or block comment.
   */
  public static boolean hasUnterminatedLiteralOrComment(String source) {
    requireNonNull(source, "source");
    if (source.isEmpty()) {
      return false;
    }
    LeanLexState state = createLeanLexState();
    for (String line : NEWLINE_SPLITTER.split(normalizeLeanSource(source))) {
      advanceLeanLexState(state, line);
    }
    return state.hasOpenLiteralOrComment() || state.hasOpenInterpBrace();
  }

  /**
   * Incrementally advances multi-line lexical and delimiter state across a single line and returns
   * the comment/string-stripped view of that line.
   */
  @CanIgnoreReturnValue
  public static String advanceLeanLexState(LeanLexState state, String line) {
    requireNonNull(state, "state");
    requireNonNull(line, "line");
    StringBuilder out = new StringBuilder(line.length());
    advanceLeanLexStateCore(state, line, 0, out, false);
    return out.toString();
  }

  @CanIgnoreReturnValue
  private static int advanceLeanLexStateCore(
      LeanLexState state,
      String line,
      int startIdx,
      @Nullable StringBuilder out,
      boolean stopOnBracketZero) {
    int len = line.length();
    int i = startIdx;
    while (i < len) {
      char ch = line.charAt(i);
      char next = i + 1 < len ? line.charAt(i + 1) : '\0';

      if (state.blockDepth > 0) {
        if (ch == '/' && next == '-') {
          if (state.blockDepth < Integer.MAX_VALUE) {
            state.blockDepth++;
          }
          if (out != null) {
            out.append("  ");
          }
          i += 2;
        } else if (ch == '-' && next == '/') {
          state.blockDepth--;
          if (out != null) {
            out.append("  ");
          }
          i += 2;
        } else {
          if (out != null) {
            out.append(' ');
          }
          i++;
        }
        continue;
      }

      if (state.inGuillemet) {
        if (out != null) {
          out.append(' ');
        }
        if (ch == '»') {
          state.inGuillemet = false;
        }
        i++;
        continue;
      }

      if (state.rawStringHashes >= 0) {
        if (matchRawStringCloseAt(line, i, state.rawStringHashes)) {
          int closeLen = 1 + state.rawStringHashes;
          if (out != null) {
            appendSpaces(out, closeLen);
          }
          i += closeLen;
          state.rawStringHashes = -1;
        } else {
          if (out != null) {
            out.append(' ');
          }
          i++;
        }
        continue;
      }

      if (state.inInterpString) {
        if (ch == '\\' && i + 1 < len) {
          if (out != null) {
            out.append("  ");
          }
          i += 2;
        } else if (ch == '"') {
          state.inInterpString = false;
          if (out != null) {
            out.append(' ');
          }
          i++;
        } else if (ch == '{') {
          state.inInterpString = false;
          state.pushInterpBrace(1);
          if (out != null) {
            out.append('{');
          }
          i++;
        } else {
          if (out != null) {
            out.append(' ');
          }
          i++;
        }
        continue;
      }

      if (state.inString) {
        if (ch == '\\' && i + 1 < len) {
          if (out != null) {
            out.append("  ");
          }
          i += 2;
        } else if (ch == '"') {
          state.inString = false;
          if (out != null) {
            out.append(' ');
          }
          i++;
        } else {
          if (out != null) {
            out.append(' ');
          }
          i++;
        }
        continue;
      }

      if (ch == '/' && next == '-') {
        state.blockDepth = 1;
        if (out != null) {
          out.append("  ");
        }
        i += 2;
        continue;
      }

      if (ch == '-' && next == '-') {
        int nl = line.indexOf('\n', i + 2);
        if (nl < 0) {
          break;
        }
        if (out != null) {
          appendSpaces(out, nl - i);
          out.append('\n');
        }
        i = nl + 1;
        continue;
      }

      if (state.interpBraceTop > 0) {
        if (ch == '{') {
          state.interpBraceDepths[state.interpBraceTop - 1]++;
          if (out != null) {
            out.append('{');
          }
          i++;
          continue;
        }
        if (ch == '}') {
          int nextDepth = state.interpBraceDepths[state.interpBraceTop - 1] - 1;
          if (nextDepth <= 0) {
            state.interpBraceTop--;
            state.inInterpString = true;
          } else {
            state.interpBraceDepths[state.interpBraceTop - 1] = nextDepth;
          }
          if (out != null) {
            out.append('}');
          }
          i++;
          continue;
        }
      }

      RawStringOpen rawOpen = matchRawStringOpen(line, i);
      if (rawOpen != null) {
        if (out != null) {
          appendSpaces(out, rawOpen.contentStart() - i);
        }
        state.rawStringHashes = rawOpen.hashes();
        i = rawOpen.contentStart();
        continue;
      }

      int charLen = matchCharLiteral(line, i);
      if (charLen > 0) {
        if (out != null) {
          appendSpaces(out, charLen);
        }
        i += charLen;
        continue;
      }

      if (ch == '«') {
        state.inGuillemet = true;
        if (out != null) {
          out.append(' ');
        }
        i++;
        continue;
      }

      int startCp = line.codePointAt(i);
      int startLen = Character.charCount(startCp);
      if (startCp == '#') {
        if (out != null) {
          out.append('#');
        }
        i++;
        continue;
      }
      if (LeanSyntax.isIdentStart(startCp)) {
        int j = i + startLen;
        while (j < len) {
          int partCp = line.codePointAt(j);
          if (!LeanSyntax.isIdentPart(partCp)) {
            break;
          }
          j += Character.charCount(partCp);
        }
        String ident = line.substring(i, j);
        if (LeanSyntax.LEAN_INTERP_STRING_PREFIXES.contains(ident)
            && j < len
            && line.charAt(j) == '"') {
          if (out != null) {
            appendSpaces(out, j - i + 1);
          }
          state.inInterpString = true;
          i = j + 1;
          continue;
        }
        if (out != null) {
          out.append(line, i, j);
        }
        i = j;
        continue;
      }

      if (ch == '"') {
        state.inString = true;
        if (out != null) {
          out.append(' ');
        }
        i++;
        continue;
      }

      if (ch == '(') {
        state.paren++;
      } else if (ch == ')' && state.paren > 0) {
        state.paren--;
      } else if (ch == '[') {
        state.bracket++;
      } else if (ch == ']') {
        if (stopOnBracketZero && state.interpBraceTop > 0 && state.bracket <= 1) {
          // Outer attribute `[` opened before `s!"...{` cannot close inside an interp brace.
        } else if (state.bracket > 0) {
          state.bracket--;
          if (stopOnBracketZero && state.bracket == 0) {
            return i;
          }
        }
      } else if (ch == '{') {
        state.brace++;
      } else if (ch == '}' && state.brace > 0) {
        state.brace--;
      }

      if (out != null) {
        out.appendCodePoint(startCp);
      }
      i += startLen;
    }
    return -1;
  }

  private static void revealBalancedInterpSpan(
      String code, StringBuilder out, int openBraceIdx, int closeBraceIdx) {
    int p = openBraceIdx + 1;
    int blockDepth = 0;
    boolean inLineComment = false;
    while (p < closeBraceIdx) {
      char ch = code.charAt(p);
      char next = p + 1 < closeBraceIdx ? code.charAt(p + 1) : '\0';
      if (inLineComment) {
        if (ch == '\n') {
          inLineComment = false;
        }
        p++;
        continue;
      }
      if (blockDepth > 0) {
        if (ch == '/' && next == '-') {
          blockDepth++;
          p += 2;
        } else if (ch == '-' && next == '/') {
          blockDepth--;
          p += 2;
        } else {
          p++;
        }
        continue;
      }
      if (ch == '/' && next == '-') {
        blockDepth = 1;
        p += 2;
        continue;
      }
      if (ch == '-' && next == '-') {
        inLineComment = true;
        p += 2;
        continue;
      }
      int charLen = matchCharLiteral(code, p);
      if (charLen > 0 && p + charLen <= closeBraceIdx && noQuoteInSlice(code, p, charLen)) {
        p += charLen;
        continue;
      }
      out.setCharAt(p, ch);
      p++;
    }
  }

  private static boolean noQuoteInSlice(String s, int start, int sliceLen) {
    for (int k = 0; k < sliceLen; k++) {
      if (s.charAt(start + k) == '"') {
        return false;
      }
    }
    return true;
  }

  private static void appendSpaces(StringBuilder out, int count) {
    for (int k = 0; k < count; k++) {
      out.append(' ');
    }
  }

  private static boolean isHexDigit(char c) {
    return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
  }

  private static boolean isHexDigitOrUnderscore(char c) {
    return isHexDigit(c) || c == '_';
  }

  private static boolean isBinaryDigitOrUnderscore(char c) {
    return c == '0' || c == '1' || c == '_';
  }

  private static boolean isOctalDigitOrUnderscore(char c) {
    return (c >= '0' && c <= '7') || c == '_';
  }

  private static boolean isDecimalDigitOrUnderscore(char c) {
    return (c >= '0' && c <= '9') || c == '_';
  }
}
