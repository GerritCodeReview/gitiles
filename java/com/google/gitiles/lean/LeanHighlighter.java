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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.collect.ImmutableList;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.List;
import javax.annotation.Nullable;

/**
 * Lossless line-by-line Lean 4 syntax highlighter tracking nested {@code /- ... -/} block comment
 * depth across lines.
 */
public final class LeanHighlighter {

  /** Result of tokenizing a single Lean line with exit block comment nesting depth. */
  public record LineResult(ImmutableList<LeanToken> tokens, int blockCommentDepth) {
    public LineResult {
      tokens = ImmutableList.copyOf(checkNotNull(tokens, "tokens"));
      checkArgument(
          blockCommentDepth >= 0, "blockCommentDepth must be non-negative: %s", blockCommentDepth);
    }
  }

  private static final class TokenAccumulator {
    private final ImmutableList.Builder<LeanToken> tokens = ImmutableList.builder();
    private @Nullable LeanTokenKind pendingKind;
    private final StringBuilder pendingText = new StringBuilder();

    void push(String text, LeanTokenKind kind) {
      if (text.isEmpty()) {
        return;
      }
      if (pendingKind == kind) {
        pendingText.append(text);
        return;
      }
      flush();
      pendingKind = kind;
      pendingText.append(text);
    }

    void pushSlice(String source, int start, int end, LeanTokenKind kind) {
      if (end <= start) {
        return;
      }
      if (pendingKind == kind) {
        pendingText.append(source, start, end);
        return;
      }
      flush();
      pendingKind = kind;
      pendingText.append(source, start, end);
    }

    ImmutableList<LeanToken> build() {
      flush();
      return tokens.build();
    }

    private void flush() {
      if (pendingKind != null && pendingText.length() > 0) {
        tokens.add(new LeanToken(pendingText.toString(), pendingKind));
        pendingText.setLength(0);
        pendingKind = null;
      }
    }
  }

  private LeanHighlighter() {}

  /**
   * Tokenizes a single line of Lean 4 code for syntax highlighting while tracking nested multi-line
   * block comment ({@code /- ... -/}) depth across lines. Preserves exact UTF-16 character offsets
   * so intra-line diff ranges map 1:1.
   */
  public static LineResult tokenizeLineWithState(
      String line, boolean inTacticContext, int initialBlockCommentDepth) {
    checkNotNull(line, "line");
    int blockCommentDepth = Math.max(0, initialBlockCommentDepth);
    if (line.isEmpty()) {
      return new LineResult(ImmutableList.of(), blockCommentDepth);
    }

    TokenAccumulator acc = new TokenAccumulator();
    int i = 0;
    int len = line.length();

    while (i < len) {
      if (blockCommentDepth > 0) {
        int start = i;
        while (i < len && blockCommentDepth > 0) {
          if (i + 1 < len && line.charAt(i) == '/' && line.charAt(i + 1) == '-') {
            if (blockCommentDepth < Integer.MAX_VALUE) {
              blockCommentDepth++;
            }
            i += 2;
          } else if (i + 1 < len && line.charAt(i) == '-' && line.charAt(i + 1) == '/') {
            blockCommentDepth--;
            i += 2;
          } else {
            i++;
          }
        }
        acc.pushSlice(line, start, i, LeanTokenKind.COMMENT);
        continue;
      }

      char ch = line.charAt(i);
      char next = i + 1 < len ? line.charAt(i + 1) : '\0';

      // Whitespace
      int cp = line.codePointAt(i);
      int cpLen = Character.charCount(cp);
      if (isWhitespaceCodePoint(cp)) {
        int j = i + cpLen;
        while (j < len) {
          int nextCp = line.codePointAt(j);
          if (!isWhitespaceCodePoint(nextCp)) {
            break;
          }
          j += Character.charCount(nextCp);
        }
        acc.pushSlice(line, i, j, LeanTokenKind.PLAIN);
        i = j;
        continue;
      }

      // Line comment `-- ...`
      if (ch == '-' && next == '-') {
        acc.pushSlice(line, i, len, LeanTokenKind.COMMENT);
        break;
      }

      // Inline or multi-line block comment `/- ... -/`
      if (ch == '/' && next == '-') {
        blockCommentDepth = 1;
        int j = i + 2;
        while (j < len && blockCommentDepth > 0) {
          if (j + 1 < len && line.charAt(j) == '/' && line.charAt(j + 1) == '-') {
            if (blockCommentDepth < Integer.MAX_VALUE) {
              blockCommentDepth++;
            }
            j += 2;
          } else if (j + 1 < len && line.charAt(j) == '-' && line.charAt(j + 1) == '/') {
            blockCommentDepth--;
            j += 2;
          } else {
            j++;
          }
        }
        acc.pushSlice(line, i, j, LeanTokenKind.COMMENT);
        i = j;
        continue;
      }

      // Raw string literal `r"..."`, `r#"..."#`, `r##"..."##`
      LeanLexer.RawStringOpen rawOpen = LeanLexer.matchRawStringOpen(line, i);
      if (rawOpen != null) {
        int j = scanRawStringEnd(line, rawOpen);
        acc.pushSlice(line, i, j, LeanTokenKind.STRING);
        i = j;
        continue;
      }

      // Interpolated string `s!"..."`, `m!"..."`, `f!"..."`, brace-aware
      // so `s!"{'"'}"` or `s!"{r#"""#}"` cannot flip quote parity.
      if ((ch == 's' || ch == 'm' || ch == 'f')
          && next == '!'
          && i + 2 < len
          && line.charAt(i + 2) == '"'
          && !LeanLexer.hasIdentPartBefore(line, i)) {
        int j = scanInterpolatedStringEnd(line, i + 3);
        acc.pushSlice(line, i, j, LeanTokenKind.STRING);
        i = j;
        continue;
      }

      // String literal `"..."`
      if (ch == '"') {
        int j = scanStringLiteralEnd(line, i + 1);
        acc.pushSlice(line, i, j, LeanTokenKind.STRING);
        i = j;
        continue;
      }

      // Character literal `'a'`, `'"'`, `'\n'`, `'\x41'`, `'\u0041'`, `'😀'`
      int charLen = LeanLexer.matchCharLiteral(line, i);
      if (charLen > 0) {
        acc.pushSlice(line, i, i + charLen, LeanTokenKind.STRING);
        i += charLen;
        continue;
      }

      // Attribute `@[...]` (supports nested brackets like `@[simp [foo]]`)
      if (ch == '@' && next == '[') {
        int closeIdx = LeanLexer.findMatchingBracket(line, i + 1);
        int end = closeIdx >= 0 ? closeIdx + 1 : len;
        acc.pushSlice(line, i, end, LeanTokenKind.ATTRIBUTE);
        i = end;
        continue;
      }

      // Goal-focusing bullet `·`
      if (ch == '·') {
        acc.push("·", LeanTokenKind.BULLET);
        i++;
        continue;
      }

      // Number literal (hex `0x...`, binary `0b...`, octal `0o...`, decimal/float)
      int numLen = LeanLexer.matchNumberLiteral(line, i);
      if (numLen > 0) {
        acc.pushSlice(line, i, i + numLen, LeanTokenKind.NUMBER);
        i += numLen;
        continue;
      }

      // Escaped identifier `«...»`
      if (ch == '«') {
        int closeIdx = line.indexOf('»', i + 1);
        int end = closeIdx >= 0 ? closeIdx + 1 : len;
        String inner = closeIdx >= 0 ? line.substring(i + 1, closeIdx) : "";
        acc.pushSlice(
            line,
            i,
            end,
            LeanSyntax.SOUNDNESS_GUILLEMET_NAMES.contains(inner)
                ? LeanTokenKind.SORRY
                : LeanTokenKind.PLAIN);
        i = end;
        continue;
      }

      // Identifiers & keywords (`#` is excluded so `#s!"..."`, `#r"..."`, and `#sorry` cannot
      // swallow the following literal prefix or keyword).
      if (cp != '#' && LeanSyntax.isIdentStart(cp)) {
        int j = i + cpLen;
        while (j < len) {
          int partCp = line.codePointAt(j);
          if (!LeanSyntax.isIdentPart(partCp)) {
            break;
          }
          j += Character.charCount(partCp);
        }
        String word = line.substring(i, j);
        acc.push(word, classifyIdentifier(word, inTacticContext));
        i = j;
        continue;
      }

      // Unicode math / logic operators
      if (cp >= 128) {
        String chStr = line.substring(i, i + cpLen);
        if (LeanSyntax.isUnicodeOperator(chStr)) {
          acc.push(chStr, LeanTokenKind.OPERATOR);
          i += cpLen;
          continue;
        }
      }

      // Multi-char ASCII operators
      String matchedOp = matchMultiCharOperator(line, i);
      if (matchedOp != null) {
        acc.push(matchedOp, LeanTokenKind.OPERATOR);
        i += matchedOp.length();
        continue;
      }

      acc.pushSlice(line, i, i + cpLen, LeanTokenKind.PLAIN);
      i += cpLen;
    }

    return new LineResult(acc.build(), blockCommentDepth);
  }

  /**
   * Tokenizes a single line of Lean 4 code for syntax highlighting. Preserves exact UTF-16
   * character offsets so intra-line diff ranges map 1:1.
   */
  public static ImmutableList<LeanToken> tokenizeLine(
      String line, boolean inTacticContext, int initialBlockCommentDepth) {
    return tokenizeLineWithState(line, inTacticContext, initialBlockCommentDepth).tokens();
  }

  /**
   * Computes the entry block-comment ({@code /- ... -/}) nesting depth for each line in {@code
   * lines}.
   */
  public static int[] computeBlockCommentDepths(List<LeanCodeLine> lines, int initialDepth) {
    checkNotNull(lines, "lines");
    int[] depths = new int[lines.size()];
    computeBlockCommentDepthsInternal(lines, initialDepth, depths);
    return depths;
  }

  /**
   * Computes the exit block-comment ({@code /- ... -/}) nesting depth after the last line in {@code
   * lines}.
   */
  public static int computeFinalBlockCommentDepth(List<LeanCodeLine> lines, int initialDepth) {
    checkNotNull(lines, "lines");
    return computeBlockCommentDepthsInternal(lines, initialDepth, null);
  }

  /** Returns whether {@code tokenText} is a Lean Unicode math/logic operator. */
  public static boolean isUnicodeOperator(@Nullable String tokenText) {
    return LeanSyntax.isUnicodeOperator(tokenText);
  }

  @CanIgnoreReturnValue
  private static int computeBlockCommentDepthsInternal(
      List<LeanCodeLine> lines, int initialDepth, @Nullable int[] depthsOut) {
    int depth = Math.max(0, initialDepth);
    for (int idx = 0; idx < lines.size(); idx++) {
      LeanCodeLine codeLine = checkNotNull(lines.get(idx), "line");
      if (depthsOut != null) {
        depthsOut[idx] = depth;
      }
      depth = tokenizeLineWithState(codeLine.text(), false, depth).blockCommentDepth();
    }
    return depth;
  }

  private static LeanTokenKind classifyIdentifier(String word, boolean inTacticContext) {
    if (LeanSyntax.LEAN_SORRY_TOKENS.contains(word)) {
      return LeanTokenKind.SORRY;
    }
    if (LeanSyntax.LEAN_KEYWORDS.contains(word)) {
      return LeanTokenKind.KEYWORD;
    }
    if (LeanSyntax.LEAN_TACTICS.contains(word)
        && (inTacticContext || LeanSyntax.UNAMBIGUOUS_TACTICS.contains(word))) {
      return LeanTokenKind.TACTIC;
    }
    if (LeanSyntax.LEAN_BUILTIN_TYPES.contains(word)) {
      return LeanTokenKind.TYPE;
    }
    if (LeanSyntax.LEAN_LITERALS.contains(word)) {
      return LeanTokenKind.LITERAL;
    }
    return LeanTokenKind.PLAIN;
  }

  private static @Nullable String matchMultiCharOperator(String line, int index) {
    for (String op : LeanSyntax.MULTI_CHAR_OPERATORS) {
      if (line.startsWith(op, index)) {
        return op;
      }
    }
    return null;
  }

  private static int scanRawStringEnd(String line, LeanLexer.RawStringOpen rawOpen) {
    int j = rawOpen.contentStart();
    int len = line.length();
    while (j < len) {
      if (LeanLexer.matchRawStringCloseAt(line, j, rawOpen.hashes())) {
        return j + 1 + rawOpen.hashes();
      }
      j++;
    }
    return len;
  }

  private static int scanStringLiteralEnd(String line, int contentStart) {
    int j = contentStart;
    int len = line.length();
    while (j < len) {
      char ch = line.charAt(j);
      if (ch == '\\' && j + 1 < len) {
        j += 1 + Character.charCount(line.codePointAt(j + 1));
      } else if (ch == '"') {
        return j + 1;
      } else {
        j += Character.charCount(line.codePointAt(j));
      }
    }
    return len;
  }

  private static int scanInterpolatedStringEnd(String line, int contentStart) {
    int j = contentStart;
    int len = line.length();
    int braceDepth = 0;
    while (j < len) {
      if (braceDepth == 0) {
        char ch = line.charAt(j);
        if (ch == '\\' && j + 1 < len) {
          j += 1 + Character.charCount(line.codePointAt(j + 1));
        } else if (ch == '{') {
          braceDepth = 1;
          j++;
        } else if (ch == '"') {
          return j + 1;
        } else {
          j += Character.charCount(line.codePointAt(j));
        }
      } else {
        LeanLexer.RawStringOpen innerRaw = LeanLexer.matchRawStringOpen(line, j);
        if (innerRaw != null) {
          j = scanRawStringEnd(line, innerRaw);
          continue;
        }
        int innerChar = LeanLexer.matchCharLiteral(line, j);
        if (innerChar > 0) {
          j += innerChar;
          continue;
        }
        char ch = line.charAt(j);
        if (ch == '"') {
          j = scanStringLiteralEnd(line, j + 1);
          continue;
        }
        if (ch == '{') {
          braceDepth++;
        } else if (ch == '}') {
          braceDepth--;
        }
        j += Character.charCount(line.codePointAt(j));
      }
    }
    return len;
  }

  private static boolean isWhitespaceCodePoint(int cp) {
    return Character.isWhitespace(cp) || Character.isSpaceChar(cp) || cp == '\uFEFF';
  }
}
