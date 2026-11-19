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
import java.util.Objects;
import javax.annotation.Nullable;

/**
 * Lossless line-by-line Lean 4 syntax highlighter tracking cross-line lexical state (nested {@code
 * /- ... -/} block comments, multi-line plain/raw/interpolated strings, and {@code «...»} escaped
 * identifiers).
 */
public final class LeanHighlighter {

  /** Maximum tracked nesting depth of {@code s!"{...}"} interpolation frames across lines. */
  private static final int MAX_INTERP_NESTING = 256;

  /** Result of tokenizing a single Lean line with exit block comment nesting depth. */
  public record LineResult(ImmutableList<LeanToken> tokens, int blockCommentDepth) {
    public LineResult {
      tokens = ImmutableList.copyOf(checkNotNull(tokens, "tokens"));
      checkArgument(
          blockCommentDepth >= 0, "blockCommentDepth must be non-negative: %s", blockCommentDepth);
    }
  }

  /** Cross-line lexical state carried across consecutive lines of Lean 4 source. */
  public static final class HighlightState {
    public static final HighlightState INITIAL =
        new HighlightState(
            0, false, false, -1, ImmutableList.of(), LeanLexer.InterpCmdState.INITIAL);

    private final int blockCommentDepth;
    private final boolean inString;
    private final boolean inGuillemet;
    private final int rawStringHashes;
    private final ImmutableList<Integer> interpBraceDepths;
    private final LeanLexer.InterpCmdState interpCmd;

    public HighlightState(
        int blockCommentDepth,
        boolean inString,
        boolean inGuillemet,
        int rawStringHashes,
        ImmutableList<Integer> interpBraceDepths) {
      this(
          blockCommentDepth,
          inString,
          inGuillemet,
          rawStringHashes,
          interpBraceDepths,
          LeanLexer.InterpCmdState.INITIAL);
    }

    HighlightState(
        int blockCommentDepth,
        boolean inString,
        boolean inGuillemet,
        int rawStringHashes,
        ImmutableList<Integer> interpBraceDepths,
        LeanLexer.InterpCmdState interpCmd) {
      checkArgument(
          blockCommentDepth >= 0, "blockCommentDepth must be non-negative: %s", blockCommentDepth);
      checkArgument(rawStringHashes >= -1, "rawStringHashes must be >= -1: %s", rawStringHashes);
      checkArgument(!(inString && inGuillemet), "inString and inGuillemet cannot both be true");
      checkArgument(
          rawStringHashes < 0 || (!inString && !inGuillemet),
          "rawStringHashes cannot overlap plain string or guillemet");
      ImmutableList<Integer> validatedDepths =
          ImmutableList.copyOf(checkNotNull(interpBraceDepths, "interpBraceDepths"));
      for (Integer depth : validatedDepths) {
        checkNotNull(depth, "interpBraceDepths element");
        checkArgument(depth >= 0, "interpBraceDepth must be non-negative: %s", depth);
      }
      this.blockCommentDepth = blockCommentDepth;
      this.inString = inString;
      this.inGuillemet = inGuillemet;
      this.rawStringHashes = rawStringHashes;
      this.interpBraceDepths = validatedDepths;
      this.interpCmd = checkNotNull(interpCmd, "interpCmd");
    }

    public int blockCommentDepth() {
      return blockCommentDepth;
    }

    public boolean inString() {
      return inString;
    }

    public boolean inGuillemet() {
      return inGuillemet;
    }

    public int rawStringHashes() {
      return rawStringHashes;
    }

    public ImmutableList<Integer> interpBraceDepths() {
      return interpBraceDepths;
    }

    LeanLexer.InterpCmdState interpCmd() {
      return interpCmd;
    }

    HighlightState withNewline() {
      LeanLexer.InterpCmdState nextCmd = interpCmd.onNewline();
      return nextCmd.equals(interpCmd)
          ? this
          : new HighlightState(
              blockCommentDepth,
              inString,
              inGuillemet,
              rawStringHashes,
              interpBraceDepths,
              nextCmd);
    }

    /** Returns a clean {@link HighlightState} starting at the given block-comment depth. */
    public static HighlightState forBlockCommentDepth(int depth) {
      int clamped = Math.max(0, depth);
      return clamped == 0
          ? INITIAL
          : new HighlightState(
              clamped, false, false, -1, ImmutableList.of(), LeanLexer.InterpCmdState.INITIAL);
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof HighlightState other)) {
        return false;
      }
      return blockCommentDepth == other.blockCommentDepth
          && inString == other.inString
          && inGuillemet == other.inGuillemet
          && rawStringHashes == other.rawStringHashes
          && interpBraceDepths.equals(other.interpBraceDepths)
          && interpCmd.equals(other.interpCmd);
    }

    @Override
    public int hashCode() {
      return Objects.hash(
          blockCommentDepth, inString, inGuillemet, rawStringHashes, interpBraceDepths, interpCmd);
    }

    @Override
    public String toString() {
      return "HighlightState[blockCommentDepth="
          + blockCommentDepth
          + ", inString="
          + inString
          + ", inGuillemet="
          + inGuillemet
          + ", rawStringHashes="
          + rawStringHashes
          + ", interpBraceDepths="
          + interpBraceDepths
          + ", interpCmd="
          + interpCmd
          + "]";
    }
  }

  /**
   * Result of tokenizing a single Lean line with its full cross-line exit {@link HighlightState}.
   */
  public record StateLineResult(ImmutableList<LeanToken> tokens, HighlightState state) {
    public StateLineResult {
      tokens = ImmutableList.copyOf(checkNotNull(tokens, "tokens"));
      checkNotNull(state, "state");
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
    StateLineResult res =
        tokenizeLineWithHighlightState(
            line, inTacticContext, HighlightState.forBlockCommentDepth(initialBlockCommentDepth));
    return new LineResult(res.tokens(), res.state().blockCommentDepth());
  }

  /**
   * Tokenizes a single line of Lean 4 code for syntax highlighting while carrying full cross-line
   * lexical state (block comments, plain/raw/interpolated strings, and guillemets). Preserves exact
   * UTF-16 character offsets so intra-line diff ranges map 1:1.
   */
  public static StateLineResult tokenizeLineWithHighlightState(
      String line, boolean inTacticContext, HighlightState initialState) {
    checkNotNull(line, "line");
    checkNotNull(initialState, "initialState");
    if (line.isEmpty()) {
      return new StateLineResult(ImmutableList.of(), initialState.withNewline());
    }

    int blockCommentDepth = initialState.blockCommentDepth();
    boolean inString = initialState.inString();
    boolean inGuillemet = initialState.inGuillemet();
    int rawStringHashes = initialState.rawStringHashes();
    LeanLexer.InterpCmdState interpCmd = initialState.interpCmd();
    boolean prevWasNumOrChar = false;
    int[] interpStack = null;
    int interpTop = 0;
    if (!initialState.interpBraceDepths().isEmpty()) {
      interpStack = new int[MAX_INTERP_NESTING];
      interpTop = Math.min(MAX_INTERP_NESTING, initialState.interpBraceDepths().size());
      for (int k = 0; k < interpTop; k++) {
        interpStack[k] = initialState.interpBraceDepths().get(k);
      }
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

      if (rawStringHashes >= 0) {
        int closeEnd = scanRawStringClose(line, i, rawStringHashes);
        if (closeEnd >= 0) {
          acc.pushSlice(line, i, closeEnd, LeanTokenKind.STRING);
          rawStringHashes = -1;
          i = closeEnd;
        } else {
          acc.pushSlice(line, i, len, LeanTokenKind.STRING);
          i = len;
        }
        continue;
      }

      if (inGuillemet) {
        LeanTokenKind guilKind = interpTop > 0 ? LeanTokenKind.STRING : LeanTokenKind.PLAIN;
        int closeEnd = scanGuillemetClose(line, i);
        if (closeEnd >= 0) {
          acc.pushSlice(line, i, closeEnd, guilKind);
          inGuillemet = false;
          i = closeEnd;
        } else {
          acc.pushSlice(line, i, len, guilKind);
          i = len;
        }
        continue;
      }

      if (inString) {
        int closeEnd = scanPlainStringClose(line, i);
        if (closeEnd >= 0) {
          acc.pushSlice(line, i, closeEnd, LeanTokenKind.STRING);
          inString = false;
          i = closeEnd;
        } else {
          acc.pushSlice(line, i, len, LeanTokenKind.STRING);
          i = len;
        }
        continue;
      }

      if (interpTop > 0 && interpStack[interpTop - 1] == 0) {
        int j = i;
        while (j < len) {
          char c = line.charAt(j);
          if (c == '\\' && j + 1 < len) {
            j += 1 + Character.charCount(line.codePointAt(j + 1));
          } else if (c == '{') {
            interpStack[interpTop - 1] = 1;
            j++;
            break;
          } else if (c == '"') {
            interpTop--;
            j++;
            break;
          } else {
            j += Character.charCount(line.codePointAt(j));
          }
        }
        acc.pushSlice(line, i, j, LeanTokenKind.STRING);
        i = j;
        continue;
      }

      boolean inInterpExpr = interpTop > 0;
      boolean afterNumOrChar = prevWasNumOrChar;
      prevWasNumOrChar = false;
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
        for (int k = i; k < j; k = line.offsetByCodePoints(k, 1)) {
          int wspCp = line.codePointAt(k);
          if (wspCp == '\n' || wspCp == '\r') {
            interpCmd = interpCmd.onNewline();
          } else if (wspCp == ' ' || wspCp == '\t') {
            interpCmd = interpCmd.onPunct((char) wspCp);
          } else {
            interpCmd = interpCmd.onPunct('\0');
          }
        }
        acc.pushSlice(line, i, j, inInterpExpr ? LeanTokenKind.STRING : LeanTokenKind.PLAIN);
        i = j;
        continue;
      }

      // Line comment `-- ...` (EOL applies interpCmd.onNewline()).
      if (ch == '-' && next == '-') {
        acc.pushSlice(line, i, len, LeanTokenKind.COMMENT);
        break;
      }

      // Inline or multi-line block comment `/- ... -/` (at depth 0, `/--` and `/-!` are 3-char
      // doc-comment openers before block-comment nesting per Lean 4 Basic.lean).
      if (ch == '/' && next == '-') {
        blockCommentDepth = 1;
        int j = i + 2;
        if (j < len && (line.charAt(j) == '-' || line.charAt(j) == '!')) {
          j++;
        }
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
      LeanLexer.RawStringOpen rawOpen = matchRawStringAt(line, i, afterNumOrChar);
      if (rawOpen != null) {
        interpCmd = interpCmd.onNonInterpLiteral();
        int closeEnd = scanRawStringClose(line, rawOpen.contentStart(), rawOpen.hashes());
        if (closeEnd >= 0) {
          acc.pushSlice(line, i, closeEnd, LeanTokenKind.STRING);
          i = closeEnd;
        } else {
          acc.pushSlice(line, i, len, LeanTokenKind.STRING);
          rawStringHashes = rawOpen.hashes();
          i = len;
        }
        continue;
      }

      // Interpolated string `s!"..."`, `m!"..."`, `f!"..."`, `v!"..."`, brace-aware
      // so `s!"{'"'}"` or multi-line `s!"{\n \"/-\"\n}"` cannot flip quote parity or leak comments.
      int interpPrefixLen = matchInterpStringPrefix(line, i, afterNumOrChar);
      if (interpPrefixLen > 0) {
        interpCmd = interpCmd.onNonInterpLiteral();
        int afterQuote = i + interpPrefixLen + 1;
        acc.pushSlice(line, i, afterQuote, LeanTokenKind.STRING);
        if (interpTop < MAX_INTERP_NESTING) {
          if (interpStack == null) {
            interpStack = new int[MAX_INTERP_NESTING];
          }
          interpStack[interpTop++] = 0;
        } else {
          inString = true;
        }
        i = afterQuote;
        continue;
      }

      // String literal `"..."` (or interpolated command string after `dbg_trace`, `println!`,
      // `throwError`, `trace[...]`, etc.)
      if (ch == '"') {
        boolean cmdInterp = interpCmd.expectsQuote();
        interpCmd = interpCmd.onQuote();
        if (cmdInterp) {
          acc.pushSlice(line, i, i + 1, LeanTokenKind.STRING);
          if (interpTop < MAX_INTERP_NESTING) {
            if (interpStack == null) {
              interpStack = new int[MAX_INTERP_NESTING];
            }
            interpStack[interpTop++] = 0;
          } else {
            inString = true;
          }
          i++;
          continue;
        }
        int closeEnd = scanPlainStringClose(line, i + 1);
        if (closeEnd >= 0) {
          acc.pushSlice(line, i, closeEnd, LeanTokenKind.STRING);
          i = closeEnd;
        } else {
          acc.pushSlice(line, i, len, LeanTokenKind.STRING);
          inString = true;
          i = len;
        }
        continue;
      }

      // Character literal `'a'`, `'"'`, `'\n'`, `'\x41'`, `'\u0041'`, `'😀'`
      int charLen = matchCharLiteralAt(line, i, afterNumOrChar);
      if (charLen > 0) {
        interpCmd = interpCmd.onNonInterpLiteral();
        prevWasNumOrChar = true;
        acc.pushSlice(line, i, i + charLen, LeanTokenKind.STRING);
        i += charLen;
        continue;
      }

      if (inInterpExpr) {
        if (ch == '{') {
          interpCmd = LeanLexer.InterpCmdState.INITIAL;
          if (interpStack[interpTop - 1] < Integer.MAX_VALUE) {
            interpStack[interpTop - 1]++;
          }
          acc.pushSlice(line, i, i + 1, LeanTokenKind.STRING);
          i++;
          continue;
        }
        if (ch == '}') {
          interpCmd = LeanLexer.InterpCmdState.INITIAL;
          interpStack[interpTop - 1]--;
          acc.pushSlice(line, i, i + 1, LeanTokenKind.STRING);
          i++;
          continue;
        }
      }

      // Escaped identifier `«...»` (tracked here too inside interpolation `{...}` so `«}»` does not
      // prematurely close an interpolation expression or leak `/-`).
      if (ch == '«') {
        interpCmd = interpCmd.onGuillemet();
        int closeEnd = scanGuillemetClose(line, i + 1);
        if (closeEnd >= 0) {
          if (inInterpExpr) {
            acc.pushSlice(line, i, closeEnd, LeanTokenKind.STRING);
          } else {
            String inner = line.substring(i + 1, closeEnd - 1);
            acc.pushSlice(
                line,
                i,
                closeEnd,
                LeanSyntax.SOUNDNESS_GUILLEMET_NAMES.contains(inner)
                    ? LeanTokenKind.SORRY
                    : LeanTokenKind.PLAIN);
          }
          i = closeEnd;
        } else {
          acc.pushSlice(line, i, len, inInterpExpr ? LeanTokenKind.STRING : LeanTokenKind.PLAIN);
          inGuillemet = true;
          i = len;
        }
        continue;
      }

      // Attribute `@[...]` (supports nested brackets like `@[simp [foo]]`)
      if (ch == '@' && next == '[') {
        interpCmd = interpCmd.inTermDelimiter() ? interpCmd : LeanLexer.InterpCmdState.INITIAL;
        int closeIdx = LeanLexer.findMatchingBracket(line, i + 1);
        int end = closeIdx >= 0 ? closeIdx + 1 : len;
        acc.pushSlice(line, i, end, inInterpExpr ? LeanTokenKind.STRING : LeanTokenKind.ATTRIBUTE);
        i = end;
        continue;
      }

      // Goal-focusing bullet `·`
      if (ch == '·') {
        interpCmd = interpCmd.onPunct('·');
        acc.push("·", inInterpExpr ? LeanTokenKind.STRING : LeanTokenKind.BULLET);
        i++;
        continue;
      }

      // Number literal (hex `0x...`, binary `0b...`, octal `0o...`, decimal/float)
      int numLen = LeanLexer.matchNumberLiteral(line, i);
      if (numLen > 0) {
        interpCmd = interpCmd.onNumber();
        prevWasNumOrChar = true;
        acc.pushSlice(
            line, i, i + numLen, inInterpExpr ? LeanTokenKind.STRING : LeanTokenKind.NUMBER);
        i += numLen;
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
        interpCmd = interpCmd.onIdent(word);
        if (inInterpExpr) {
          acc.pushSlice(line, i, j, LeanTokenKind.STRING);
        } else {
          acc.push(word, classifyIdentifier(word, inTacticContext));
        }
        i = j;
        continue;
      }

      // Unicode math / logic operators
      if (cp >= 128) {
        String chStr = line.substring(i, i + cpLen);
        if (LeanSyntax.isUnicodeOperator(chStr)) {
          interpCmd = interpCmd.onPunct(cpLen == 1 ? ch : '\0');
          acc.push(chStr, inInterpExpr ? LeanTokenKind.STRING : LeanTokenKind.OPERATOR);
          i += cpLen;
          continue;
        }
      }

      // Multi-char ASCII operators
      String matchedOp = matchMultiCharOperator(line, i);
      if (matchedOp != null) {
        for (int k = 0; k < matchedOp.length(); k++) {
          interpCmd = interpCmd.onPunct(matchedOp.charAt(k));
        }
        acc.push(matchedOp, inInterpExpr ? LeanTokenKind.STRING : LeanTokenKind.OPERATOR);
        i += matchedOp.length();
        continue;
      }

      interpCmd = interpCmd.onPunct(cpLen == 1 ? ch : '\0');
      acc.pushSlice(line, i, i + cpLen, inInterpExpr ? LeanTokenKind.STRING : LeanTokenKind.PLAIN);
      i += cpLen;
    }

    interpCmd = interpCmd.onNewline();
    HighlightState exitState =
        buildHighlightState(
            blockCommentDepth,
            inString,
            inGuillemet,
            rawStringHashes,
            interpStack,
            interpTop,
            interpCmd);
    return new StateLineResult(acc.build(), exitState);
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
   * Tokenizes a single line of Lean 4 code using the full entry {@link HighlightState}. Preserves
   * exact UTF-16 character offsets so intra-line diff ranges map 1:1.
   */
  public static ImmutableList<LeanToken> tokenizeLine(
      String line, boolean inTacticContext, HighlightState initialState) {
    return tokenizeLineWithHighlightState(line, inTacticContext, initialState).tokens();
  }

  /**
   * Computes the entry block-comment ({@code /- ... -/}) nesting depth for each line in {@code
   * lines}.
   */
  public static int[] computeBlockCommentDepths(List<LeanCodeLine> lines, int initialDepth) {
    checkNotNull(lines, "lines");
    int[] depths = new int[lines.size()];
    computeHighlightStatesInternal(
        lines, HighlightState.forBlockCommentDepth(initialDepth), depths, null);
    return depths;
  }

  /**
   * Computes the exit block-comment ({@code /- ... -/}) nesting depth after the last line in {@code
   * lines}.
   */
  public static int computeFinalBlockCommentDepth(List<LeanCodeLine> lines, int initialDepth) {
    checkNotNull(lines, "lines");
    return computeHighlightStatesInternal(
            lines, HighlightState.forBlockCommentDepth(initialDepth), null, null)
        .blockCommentDepth();
  }

  /** Computes the entry {@link HighlightState} for each line in {@code lines}. */
  public static ImmutableList<HighlightState> computeHighlightStates(
      List<LeanCodeLine> lines, HighlightState initialState) {
    checkNotNull(lines, "lines");
    checkNotNull(initialState, "initialState");
    ImmutableList.Builder<HighlightState> statesOut = ImmutableList.builder();
    computeHighlightStatesInternal(lines, initialState, null, statesOut);
    return statesOut.build();
  }

  /** Computes the exit {@link HighlightState} after the last line in {@code lines}. */
  public static HighlightState computeFinalHighlightState(
      List<LeanCodeLine> lines, HighlightState initialState) {
    checkNotNull(lines, "lines");
    checkNotNull(initialState, "initialState");
    return computeHighlightStatesInternal(lines, initialState, null, null);
  }

  /** Returns whether {@code tokenText} is a Lean Unicode math/logic operator. */
  public static boolean isUnicodeOperator(@Nullable String tokenText) {
    return LeanSyntax.isUnicodeOperator(tokenText);
  }

  @CanIgnoreReturnValue
  private static HighlightState computeHighlightStatesInternal(
      List<LeanCodeLine> lines,
      HighlightState initialState,
      @Nullable int[] depthsOut,
      @Nullable ImmutableList.Builder<HighlightState> statesOut) {
    HighlightState state = initialState;
    for (int idx = 0; idx < lines.size(); idx++) {
      LeanCodeLine codeLine = checkNotNull(lines.get(idx), "line");
      if (depthsOut != null) {
        depthsOut[idx] = state.blockCommentDepth();
      }
      if (statesOut != null) {
        statesOut.add(state);
      }
      state = tokenizeLineWithHighlightState(codeLine.text(), false, state).state();
    }
    return state;
  }

  private static HighlightState buildHighlightState(
      int blockCommentDepth,
      boolean inString,
      boolean inGuillemet,
      int rawStringHashes,
      @Nullable int[] interpStack,
      int interpTop,
      LeanLexer.InterpCmdState interpCmd) {
    if (blockCommentDepth == 0
        && !inString
        && !inGuillemet
        && rawStringHashes < 0
        && interpTop == 0
        && interpCmd.equals(LeanLexer.InterpCmdState.INITIAL)) {
      return HighlightState.INITIAL;
    }
    ImmutableList<Integer> interpDepths = ImmutableList.of();
    if (interpTop > 0 && interpStack != null) {
      ImmutableList.Builder<Integer> builder = ImmutableList.builderWithExpectedSize(interpTop);
      for (int k = 0; k < interpTop; k++) {
        builder.add(interpStack[k]);
      }
      interpDepths = builder.build();
    }
    return new HighlightState(
        blockCommentDepth, inString, inGuillemet, rawStringHashes, interpDepths, interpCmd);
  }

  @Nullable
  private static LeanLexer.RawStringOpen matchRawStringAt(
      String line, int index, boolean afterNumOrChar) {
    if (!afterNumOrChar) {
      return LeanLexer.matchRawStringOpen(line, index);
    }
    int len = line.length();
    if (index < 0 || index >= len || line.charAt(index) != 'r') {
      return null;
    }
    int hashes = 0;
    while (index + 1 + hashes < len && line.charAt(index + 1 + hashes) == '#') {
      hashes++;
    }
    if (index + 1 + hashes < len && line.charAt(index + 1 + hashes) == '"') {
      return new LeanLexer.RawStringOpen(hashes, index + 2 + hashes);
    }
    return null;
  }

  private static int matchCharLiteralAt(String line, int index, boolean afterNumOrChar) {
    if (!afterNumOrChar) {
      return LeanLexer.matchCharLiteral(line, index);
    }
    if (index < 0 || index >= line.length() || line.charAt(index) != '\'') {
      return 0;
    }
    // Slice at most 8 UTF-16 chars (max escaped hex char literal) so lookback stops at index 0.
    return LeanLexer.matchCharLiteral(line.substring(index, Math.min(line.length(), index + 8)), 0);
  }

  private static int matchInterpStringPrefix(String line, int index, boolean afterNumOrChar) {
    if (!afterNumOrChar && LeanLexer.hasIdentPartBefore(line, index)) {
      return 0;
    }
    int len = line.length();
    for (String prefix : LeanSyntax.LEAN_INTERP_STRING_PREFIXES) {
      int pLen = prefix.length();
      if (index + pLen < len
          && line.startsWith(prefix, index)
          && line.charAt(index + pLen) == '"') {
        return pLen;
      }
    }
    return 0;
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

  private static int scanRawStringClose(String line, int contentStart, int hashes) {
    int j = contentStart;
    int len = line.length();
    while (j < len) {
      if (LeanLexer.matchRawStringCloseAt(line, j, hashes)) {
        return j + 1 + hashes;
      }
      j++;
    }
    return -1;
  }

  private static int scanPlainStringClose(String line, int contentStart) {
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
    return -1;
  }

  private static int scanGuillemetClose(String line, int contentStart) {
    int closeIdx = line.indexOf('»', contentStart);
    return closeIdx >= 0 ? closeIdx + 1 : -1;
  }

  private static boolean isWhitespaceCodePoint(int cp) {
    return Character.isWhitespace(cp) || Character.isSpaceChar(cp) || cp == '\uFEFF';
  }
}
