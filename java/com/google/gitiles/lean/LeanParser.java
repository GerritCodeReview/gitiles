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
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import javax.annotation.Nullable;

/** Structural block and declaration parser for Lean 4 source files. */
public final class LeanParser {

  private static final Splitter NEWLINE_SPLITTER = Splitter.on('\n');

  private static final ImmutableSet<String> SINGLE_LINE_DIRECTIVES =
      ImmutableSet.of(
          "module",
          "namespace",
          "section",
          "end",
          "universe",
          "universes",
          "export",
          "#where",
          "#exit");

  private static final ImmutableSet<String> BODY_DIRECTIVES =
      ImmutableSet.of(
          "macro",
          "macro_rules",
          "syntax",
          "elab",
          "elab_rules",
          "initialize",
          "builtin_initialize",
          "notation",
          "infix",
          "infixl",
          "infixr",
          "prefix",
          "postfix");

  private static final ImmutableSet<String> IN_SCOPING_DIRECTIVES =
      ImmutableSet.of(
          "open",
          "set_option",
          "variable",
          "variables",
          "universe",
          "universes",
          "attribute",
          "local",
          "scoped",
          "export");

  private static final ImmutableList<String> TERMINATION_CLAUSES =
      ImmutableList.of(
          "termination_by",
          "decreasing_by",
          "partial_fixpoint",
          "coinductive_fixpoint",
          "inductive_fixpoint");

  private record DeclHeaderStart(
      ImmutableList<String> attributes,
      ImmutableList<String> modifiers,
      LeanDeclKind kind,
      String name) {}

  private record DelimDepth(int paren, int bracket, int brace) {}

  private record AssignResult(int index, DelimDepth depth) {}

  private record LineCommentSplit(String code, @Nullable String comment) {}

  private record DerivingSplit(
      ImmutableList<LeanCodeLine> remaining, @Nullable ImmutableList<String> derivingClause) {}

  private record ImportLineParse(int trailingCodeCol) {}

  private record PendingDoc(String text, String raw, int startLine, int endLine, int startCol) {}

  private record PendingAttr(String text, int startLine, int endLine, int startCol) {}

  private record PendingModifier(
      String text, ImmutableList<String> mods, int lineNum, int startCol) {}

  private record PendingComment(
      int startLine, int endLine, ImmutableList<LeanCodeLine> lines, String raw, int startCol) {}

  private enum PendingKind {
    DOC,
    ATTR,
    MODIFIER,
    COMMENT
  }

  private record PendingItem(
      PendingKind kind,
      int startLine,
      int endLine,
      @Nullable PendingDoc doc,
      @Nullable PendingAttr attr,
      @Nullable PendingModifier modifier,
      @Nullable PendingComment comment) {}

  private LeanParser() {}

  /**
   * Parses a Lean 4 source file into semantic top-level blocks with exact 1-based {@code
   * startLine}..{@code endLine} ranges.
   */
  public static ImmutableList<LeanBlock> parse(String source) {
    return parseLeanBlocks(source);
  }

  /** Port of {@code parseLeanBlocks} from {@code lean-diff-util.ts}. */
  public static ImmutableList<LeanBlock> parseLeanBlocks(String source) {
    requireNonNull(source, "source");
    if (source.isEmpty()) {
      return ImmutableList.of();
    }
    List<String> lines = NEWLINE_SPLITTER.splitToList(LeanLexer.normalizeLeanSource(source));
    List<LeanBlock> blocks = new ArrayList<>();

    int i = 0;
    int colOffset = 0;
    int blockCounter = 0;
    boolean inMutual = false;

    // Lazy watermark and linear scan budget for multi-line @[...] attributes so files with many
    // unclosed @[ lines stay strictly O(N).
    int lastClosingBracketRow = Integer.MIN_VALUE;
    int remainingUnclosedAttrScanBudget = Math.max(256, lines.size() * 2);

    PendingDoc pendingDoc = null;
    List<PendingAttr> pendingAttrs = new ArrayList<>();
    List<PendingModifier> pendingModifiers = new ArrayList<>();
    List<PendingComment> pendingComments = new ArrayList<>();
    int cachedStrippedRow = -1;
    String cachedStrippedFullLine = "";

    while (i < lines.size()) {
      int loopStartI = i;
      int loopStartCol = colOffset;
      String fullLine = lines.get(i);
      int startCol = skipLeanWhitespace(fullLine, colOffset);

      if (startCol >= fullLine.length()) {
        i++;
        colOffset = 0;
        continue;
      }

      int lineIndent = getLineIndent(fullLine);

      // Exit `mutual ... end` scope on column-0 `end`.
      if (inMutual
          && colOffset == 0
          && lineIndent == 0
          && startsWithKeywordTokenOrComment(fullLine, startCol, "end")) {
        inMutual = false;
      }

      // 1. Module doc comment `/-! ... -/`
      if (fullLine.startsWith("/-!", startCol)) {
        blockCounter =
            flushPendingAsStandalone(
                blocks, blockCounter, pendingDoc, pendingAttrs, pendingModifiers, pendingComments);
        pendingDoc = null;
        pendingAttrs.clear();
        pendingModifiers.clear();
        pendingComments.clear();

        LeanLexer.BlockCommentEnd endPos = LeanLexer.findBlockCommentEnd(lines, i, startCol);
        String raw = extractBlockCommentRaw(lines, i, startCol, endPos.endRow(), endPos.endCol());
        blocks.add(
            new LeanBlock(
                "moddoc-" + blockCounter++,
                LeanBlockType.MODULE_DOC,
                raw,
                i + 1,
                endPos.endRow() + 1,
                LeanLexer.unwrapBlockComment(raw, 3),
                ImmutableList.of(),
                null,
                ImmutableList.of(),
                ImmutableList.of(),
                false,
                null));
        if (endPos.closed() && hasNonWhitespaceFrom(lines.get(endPos.endRow()), endPos.endCol())) {
          i = endPos.endRow();
          colOffset = endPos.endCol();
        } else {
          i = endPos.endRow() + 1;
          colOffset = 0;
        }
        continue;
      }

      // 2. Declaration doc comment `/-- ... -/` (not `/---`)
      if (fullLine.startsWith("/--", startCol) && !fullLine.startsWith("/---", startCol)) {
        blockCounter =
            flushPendingAsStandalone(
                blocks, blockCounter, pendingDoc, pendingAttrs, pendingModifiers, pendingComments);
        pendingDoc = null;
        pendingAttrs.clear();
        pendingModifiers.clear();
        pendingComments.clear();

        LeanLexer.BlockCommentEnd endPos = LeanLexer.findBlockCommentEnd(lines, i, startCol);
        String raw = extractBlockCommentRaw(lines, i, startCol, endPos.endRow(), endPos.endCol());
        pendingDoc =
            new PendingDoc(
                LeanLexer.unwrapBlockComment(raw, 3),
                raw,
                i + 1,
                endPos.endRow() + 1,
                loopStartCol);
        if (endPos.closed() && hasNonWhitespaceFrom(lines.get(endPos.endRow()), endPos.endCol())) {
          i = endPos.endRow();
          colOffset = endPos.endCol();
        } else {
          i = endPos.endRow() + 1;
          colOffset = 0;
        }
        continue;
      }

      // 3. Regular block comment `/- ... -/`
      if (fullLine.startsWith("/-", startCol)) {
        blockCounter =
            flushPendingAsStandalone(
                blocks, blockCounter, pendingDoc, pendingAttrs, pendingModifiers, pendingComments);
        pendingDoc = null;
        pendingAttrs.clear();
        pendingModifiers.clear();
        pendingComments.clear();

        LeanLexer.BlockCommentEnd endPos = LeanLexer.findBlockCommentEnd(lines, i, startCol);
        String raw = extractBlockCommentRaw(lines, i, startCol, endPos.endRow(), endPos.endCol());
        String inner = LeanLexer.unwrapBlockComment(raw, 2);
        List<String> rawLines = NEWLINE_SPLITTER.splitToList(raw);
        ImmutableList.Builder<LeanCodeLine> commentLines = ImmutableList.builder();
        for (int idx = 0; idx < rawLines.size(); idx++) {
          commentLines.add(new LeanCodeLine(i + 1 + idx, rawLines.get(idx)));
        }
        boolean isLicenseHeader = isLicenseHeaderText(inner);
        blocks.add(
            new LeanBlock(
                "comment-" + blockCounter++,
                LeanBlockType.COMMENT,
                raw,
                i + 1,
                endPos.endRow() + 1,
                inner,
                ImmutableList.of(),
                null,
                ImmutableList.of(),
                commentLines.build(),
                isLicenseHeader,
                null));
        if (endPos.closed() && hasNonWhitespaceFrom(lines.get(endPos.endRow()), endPos.endCol())) {
          i = endPos.endRow();
          colOffset = endPos.endCol();
        } else {
          i = endPos.endRow() + 1;
          colOffset = 0;
        }
        continue;
      }

      // 4. Section divider banners (`-- ===... / -- Title / -- ===...`)
      if (colOffset == 0 && LeanLexer.isDividerCommentLine(fullLine)) {
        blockCounter =
            flushPendingAsStandalone(
                blocks, blockCounter, pendingDoc, pendingAttrs, pendingModifiers, pendingComments);
        pendingDoc = null;
        pendingAttrs.clear();
        pendingModifiers.clear();
        pendingComments.clear();

        int startIdx = i;
        int j = i + 1;
        List<String> titleLines = new ArrayList<>();
        while (j < lines.size() && stripLeanWhitespace(lines.get(j)).startsWith("--")) {
          if (LeanLexer.isDividerCommentLine(lines.get(j))) {
            j++;
            break;
          }
          titleLines.add(stripLineCommentPrefix(stripLeanWhitespace(lines.get(j))));
          j++;
        }
        if (!titleLines.isEmpty()) {
          int endIdx = j - 1;
          blocks.add(
              new LeanBlock(
                  "section-" + blockCounter++,
                  LeanBlockType.SECTION_BANNER,
                  joinLines(lines, startIdx, endIdx + 1),
                  startIdx + 1,
                  endIdx + 1,
                  stripLeanWhitespace(String.join(" ", titleLines)),
                  ImmutableList.of(),
                  null,
                  ImmutableList.of(),
                  ImmutableList.of(),
                  false,
                  null));
          i = endIdx + 1;
          colOffset = 0;
          continue;
        }
        // Standalone divider comment(s) with no title lines: emit as regular comment and advance.
        int dividerEnd = j;
        ImmutableList.Builder<LeanCodeLine> dividerLines = ImmutableList.builder();
        for (int idx = startIdx; idx < dividerEnd; idx++) {
          dividerLines.add(new LeanCodeLine(idx + 1, lines.get(idx)));
        }
        blocks.add(
            new LeanBlock(
                "comment-" + blockCounter++,
                LeanBlockType.COMMENT,
                joinLines(lines, startIdx, dividerEnd),
                startIdx + 1,
                dividerEnd,
                null,
                ImmutableList.of(),
                null,
                ImmutableList.of(),
                dividerLines.build(),
                false,
                null));
        i = dividerEnd;
        colOffset = 0;
        continue;
      }

      // 5. Standalone line comments `-- ...`
      if (fullLine.startsWith("--", startCol)) {
        int startIdx = i;
        ImmutableList<LeanCodeLine> commentLines;
        String raw;
        int endLine;
        if (colOffset > 0) {
          raw = fullLine.substring(colOffset);
          endLine = startIdx + 1;
          commentLines = ImmutableList.of(new LeanCodeLine(startIdx + 1, raw));
          i = startIdx + 1;
          colOffset = 0;
        } else {
          ImmutableList.Builder<LeanCodeLine> commentBuilder = ImmutableList.builder();
          while (i < lines.size()
              && stripLeanWhitespace(lines.get(i)).startsWith("--")
              && (i == startIdx || !LeanLexer.isDividerCommentLine(lines.get(i)))) {
            commentBuilder.add(new LeanCodeLine(i + 1, lines.get(i)));
            i++;
          }
          commentLines = commentBuilder.build();
          raw = joinLines(lines, startIdx, i);
          endLine = i;
          colOffset = 0;
        }
        if (pendingDoc != null || !pendingAttrs.isEmpty() || !pendingModifiers.isEmpty()) {
          pendingComments.add(
              new PendingComment(startIdx + 1, endLine, commentLines, raw, loopStartCol));
        } else {
          blocks.add(
              new LeanBlock(
                  "comment-" + blockCounter++,
                  LeanBlockType.COMMENT,
                  raw,
                  startIdx + 1,
                  endLine,
                  null,
                  ImmutableList.of(),
                  null,
                  ImmutableList.of(),
                  commentLines,
                  false,
                  null));
        }
        continue;
      }

      // 6. Contiguous `import` / `public import` / `private import` / `meta import` / `import all`
      ImportLineParse firstImport = colOffset == 0 ? parseImportLine(fullLine) : null;
      if (firstImport != null) {
        blockCounter =
            flushPendingAsStandalone(
                blocks, blockCounter, pendingDoc, pendingAttrs, pendingModifiers, pendingComments);
        pendingDoc = null;
        pendingAttrs.clear();
        pendingModifiers.clear();
        pendingComments.clear();

        int startIdx = i;
        ImmutableList.Builder<LeanCodeLine> importLines = ImmutableList.builder();
        boolean splitTrailing = false;
        while (i < lines.size()) {
          ImportLineParse imp = (i == startIdx) ? firstImport : parseImportLine(lines.get(i));
          if (imp == null) {
            break;
          }
          if (imp.trailingCodeCol >= 0) {
            String prefix =
                stripTrailingLeanWhitespace(lines.get(i).substring(0, imp.trailingCodeCol));
            importLines.add(new LeanCodeLine(i + 1, prefix));
            colOffset = imp.trailingCodeCol;
            splitTrailing = true;
            break;
          }
          importLines.add(new LeanCodeLine(i + 1, lines.get(i)));
          i++;
        }
        ImmutableList<LeanCodeLine> builtImportLines = importLines.build();
        int importEndLine = splitTrailing ? i + 1 : i;
        String importRaw =
            splitTrailing ? joinCodeLineTexts(builtImportLines) : joinLines(lines, startIdx, i);
        blocks.add(
            new LeanBlock(
                "imports-" + blockCounter++,
                LeanBlockType.IMPORTS,
                importRaw,
                startIdx + 1,
                importEndLine,
                null,
                builtImportLines,
                null,
                ImmutableList.of(),
                ImmutableList.of(),
                false,
                null));
        if (!splitTrailing) {
          colOffset = 0;
        }
        continue;
      }

      // 6b. `mutual` opener: emit directive and allow nested declarations until `end`
      if (colOffset == 0
          && lineIndent == 0
          && startsWithKeywordTokenOrComment(fullLine, startCol, "mutual")) {
        blockCounter =
            flushPendingAsStandalone(
                blocks, blockCounter, pendingDoc, pendingAttrs, pendingModifiers, pendingComments);
        pendingDoc = null;
        pendingAttrs.clear();
        pendingModifiers.clear();
        pendingComments.clear();

        int startIdx = i;
        List<LeanCodeLine> mutualLines = new ArrayList<>();
        mutualLines.add(new LeanCodeLine(i + 1, fullLine));
        LeanLexer.LeanLexState mutualLex = LeanLexer.createLeanLexState();
        LeanLexer.advanceLeanLexState(mutualLex, fullLine);
        i =
            collectDirectiveOrFallbackContinuation(
                lines, i + 1, lineIndent, true, false, mutualLex, mutualLines);
        blocks.add(
            new LeanBlock(
                "dir-mutual-" + blockCounter++,
                LeanBlockType.DIRECTIVE,
                joinLines(lines, startIdx, i),
                startIdx + 1,
                i,
                null,
                ImmutableList.of(),
                "mutual",
                ImmutableList.copyOf(mutualLines),
                ImmutableList.of(),
                false,
                null));
        inMutual = true;
        colOffset = 0;
        continue;
      }

      // 7. Standalone or multi-line attribute `@[...]` preceding a declaration
      if (fullLine.startsWith("@[", startCol)) {
        if (isStandaloneAttributeLine(fullLine, startCol)) {
          String attrLine =
              colOffset > 0 ? stripTrailingLeanWhitespace(fullLine.substring(startCol)) : fullLine;
          pendingAttrs.add(new PendingAttr(attrLine, i + 1, i + 1, loopStartCol));
          i++;
          colOffset = 0;
          continue;
        }
        if (LeanLexer.findMatchingBracket(fullLine, startCol + 1) < 0) {
          if (lastClosingBracketRow == Integer.MIN_VALUE) {
            lastClosingBracketRow = findLastClosingBracketRow(lines);
          }
          if (lastClosingBracketRow > i) {
            int maxAdditionalLines =
                remainingUnclosedAttrScanBudget > 0
                    ? Math.max(8, remainingUnclosedAttrScanBudget)
                    : 8;
            int maxEndRow = Math.min(lastClosingBracketRow, i + maxAdditionalLines);
            LeanLexer.MultiLineAttrScan attrScan =
                LeanLexer.findMultiLineAttributeEnd(lines, i, startCol, startCol + 1, maxEndRow);
            if (attrScan.closed()) {
              pendingAttrs.add(
                  new PendingAttr(attrScan.text(), i + 1, attrScan.endRow() + 1, loopStartCol));
              if (hasNonWhitespaceFrom(
                  lines.get(attrScan.endRow()), attrScan.closeColExclusive())) {
                i = attrScan.endRow();
                colOffset = attrScan.closeColExclusive();
              } else {
                i = attrScan.endRow() + 1;
                colOffset = 0;
              }
              continue;
            }
            remainingUnclosedAttrScanBudget =
                Math.max(0, remainingUnclosedAttrScanBudget - attrScan.scannedLines());
          }
        }
      }

      // 7b. Standalone modifier line (`unsafe`, `partial`, `private`, ...) preceding a declaration
      ImmutableList<String> standaloneMods = parseStandaloneModifiersLine(fullLine, startCol);
      if (standaloneMods != null) {
        String modLine = colOffset > 0 ? fullLine.substring(colOffset) : fullLine;
        pendingModifiers.add(new PendingModifier(modLine, standaloneMods, i + 1, loopStartCol));
        i++;
        colOffset = 0;
        continue;
      }

      // 8. Declaration (`theorem`, `lemma`, `def`, `structure`, `inductive`, `axiom`, ...)
      DeclHeaderStart declHeader = parseDeclarationHeaderStart(fullLine, startCol, true);
      if (declHeader != null) {
        int declIndent = lineIndent;
        int declStartIdx = i;
        String firstCodeText = colOffset > 0 ? fullLine.substring(colOffset) : fullLine;
        List<LeanCodeLine> prefixLines = new ArrayList<>();
        for (PendingModifier m : pendingModifiers) {
          prefixLines.add(new LeanCodeLine(m.lineNum, m.text));
        }
        for (PendingComment c : pendingComments) {
          prefixLines.addAll(c.lines);
        }
        prefixLines.sort(Comparator.comparingInt(LeanCodeLine::lineNum));
        List<LeanCodeLine> codeLines = new ArrayList<>(prefixLines.size() + 1);
        codeLines.addAll(prefixLines);
        codeLines.add(new LeanCodeLine(i + 1, firstCodeText));

        ImmutableList.Builder<String> combinedModifiers = ImmutableList.builder();
        for (PendingModifier m : pendingModifiers) {
          combinedModifiers.addAll(m.mods);
        }
        combinedModifiers.addAll(declHeader.modifiers);
        DeclHeaderStart combinedHeader =
            new DeclHeaderStart(
                declHeader.attributes, combinedModifiers.build(), declHeader.kind, declHeader.name);

        boolean isTypeDecl =
            combinedHeader.kind == LeanDeclKind.STRUCTURE
                || combinedHeader.kind == LeanDeclKind.CLASS
                || combinedHeader.kind == LeanDeclKind.INDUCTIVE
                || combinedHeader.kind == LeanDeclKind.COINDUCTIVE;

        LeanLexer.LeanLexState lexState = LeanLexer.createLeanLexState();
        String lastNonBlankStripped = "";
        boolean inWhereBlock = false;
        for (LeanCodeLine cl : codeLines) {
          String s = LeanLexer.advanceLeanLexState(lexState, cl.text());
          if (!isBlankLeanLine(s)) {
            lastNonBlankStripped = s;
            if (!LeanLexer.hasOpenDelimiters(lexState) && containsWhereToken(s)) {
              inWhereBlock = true;
            }
          }
        }

        i++;
        colOffset = 0;

        int cachedCommentLookaheadEnd = -1;
        boolean cachedCommentLookaheadHasOpenDelim = false;
        boolean cachedCommentLookaheadInWhere = false;
        boolean cachedCommentLookaheadAccepts = false;

        while (i < lines.size()) {
          String cur = lines.get(i);
          String curTrimmed = stripLeanWhitespace(cur);

          if (LeanLexer.hasOpenLiteralOrComment(lexState) || lexState.hasOpenInterpBrace()) {
            codeLines.add(new LeanCodeLine(i + 1, cur));
            String s = LeanLexer.advanceLeanLexState(lexState, cur);
            if (!isBlankLeanLine(s)) {
              lastNonBlankStripped = s;
              if (!LeanLexer.hasOpenDelimiters(lexState) && containsWhereToken(s)) {
                inWhereBlock = true;
              }
            }
            i++;
            continue;
          }

          if (curTrimmed.isEmpty()) {
            int lookahead = i + 1;
            while (lookahead < lines.size() && isBlankLeanLine(lines.get(lookahead))) {
              lookahead++;
            }
            if (lookahead < lines.size()) {
              String nextLine = lines.get(lookahead);
              int nextIndent = getLineIndent(nextLine);
              String nextTrimmed = stripLeanWhitespace(nextLine);
              boolean curHasOpenDelim = LeanLexer.hasOpenDelimiters(lexState);
              if ((nextIndent > declIndent
                      && !shouldBreakDeclOnCommand(
                          nextLine, curHasOpenDelim, isTypeDecl, inWhereBlock))
                  || (curHasOpenDelim
                      && nextIndent == declIndent
                      && !startsNewTopLevelCommandInDeclBody(nextLine, isTypeDecl, inWhereBlock)
                      && !LeanLexer.isDividerCommentLine(nextLine))
                  || (nextIndent == declIndent
                      && (nextTrimmed.startsWith("|")
                          || startsWithAnyKeywordToken(nextTrimmed, TERMINATION_CLAUSES)))) {
                while (i < lookahead) {
                  codeLines.add(new LeanCodeLine(i + 1, lines.get(i)));
                  i++;
                }
                continue;
              }
            }
            break;
          }

          int curIndent = getLineIndent(cur);

          if (curIndent > declIndent) {
            if (shouldBreakDeclOnCommand(
                cur, LeanLexer.hasOpenDelimiters(lexState), isTypeDecl, inWhereBlock)) {
              break;
            }
            codeLines.add(new LeanCodeLine(i + 1, cur));
            String s = LeanLexer.advanceLeanLexState(lexState, cur);
            if (!isBlankLeanLine(s)) {
              lastNonBlankStripped = s;
              if (!LeanLexer.hasOpenDelimiters(lexState) && containsWhereToken(s)) {
                inWhereBlock = true;
              }
            }
            i++;
            continue;
          }

          if (curIndent == declIndent
              && curTrimmed.startsWith("--")
              && !LeanLexer.isDividerCommentLine(cur)) {
            boolean curHasOpenDelim = LeanLexer.hasOpenDelimiters(lexState);
            if (i >= cachedCommentLookaheadEnd) {
              int lookahead = i + 1;
              while (lookahead < lines.size()
                  && (isBlankLeanLine(lines.get(lookahead))
                      || (stripLeanWhitespace(lines.get(lookahead)).startsWith("--")
                          && !LeanLexer.isDividerCommentLine(lines.get(lookahead))))) {
                lookahead++;
              }
              cachedCommentLookaheadEnd = lookahead;
              cachedCommentLookaheadHasOpenDelim = curHasOpenDelim;
              cachedCommentLookaheadInWhere = inWhereBlock;
              cachedCommentLookaheadAccepts =
                  computeCommentLookaheadAccepts(
                      lines, lookahead, declIndent, isTypeDecl, curHasOpenDelim, inWhereBlock);
            } else if (cachedCommentLookaheadHasOpenDelim != curHasOpenDelim
                || cachedCommentLookaheadInWhere != inWhereBlock) {
              cachedCommentLookaheadHasOpenDelim = curHasOpenDelim;
              cachedCommentLookaheadInWhere = inWhereBlock;
              cachedCommentLookaheadAccepts =
                  computeCommentLookaheadAccepts(
                      lines,
                      cachedCommentLookaheadEnd,
                      declIndent,
                      isTypeDecl,
                      curHasOpenDelim,
                      inWhereBlock);
            }
            if (cachedCommentLookaheadAccepts) {
              codeLines.add(new LeanCodeLine(i + 1, cur));
              i++;
              continue;
            }
          }

          if (curIndent == declIndent
              && (LeanLexer.hasOpenDelimiters(lexState)
                  || lineExpectsDeclContinuation(lastNonBlankStripped))
              && !startsNewTopLevelCommandInDeclBody(cur, isTypeDecl, inWhereBlock)
              && !LeanLexer.isDividerCommentLine(cur)) {
            codeLines.add(new LeanCodeLine(i + 1, cur));
            String s = LeanLexer.advanceLeanLexState(lexState, cur);
            if (!isBlankLeanLine(s)) {
              lastNonBlankStripped = s;
              if (!LeanLexer.hasOpenDelimiters(lexState) && containsWhereToken(s)) {
                inWhereBlock = true;
              }
            }
            i++;
            continue;
          }

          if (curIndent == declIndent
              && (curTrimmed.startsWith("|")
                  || (isTypeDecl && startsWithKeywordToken(curTrimmed, "extends"))
                  || (startsWithKeywordToken(curTrimmed, "deriving")
                      && !startsNewTopLevelCommandInDeclBody(cur, isTypeDecl, inWhereBlock))
                  || startsWithKeywordToken(curTrimmed, "where")
                  || startsWithAnyKeywordToken(curTrimmed, TERMINATION_CLAUSES))) {
            codeLines.add(new LeanCodeLine(i + 1, cur));
            String s = LeanLexer.advanceLeanLexState(lexState, cur);
            if (!isBlankLeanLine(s)) {
              lastNonBlankStripped = s;
              if (!LeanLexer.hasOpenDelimiters(lexState) && containsWhereToken(s)) {
                inWhereBlock = true;
              }
            }
            i++;
            continue;
          }

          break;
        }

        int blockStartLine = declStartIdx + 1;
        int blockStartCol = loopStartCol;
        if (pendingDoc != null) {
          if (pendingDoc.startLine < blockStartLine) {
            blockStartLine = pendingDoc.startLine;
            blockStartCol = pendingDoc.startCol;
          } else if (pendingDoc.startLine == blockStartLine) {
            blockStartCol = Math.min(blockStartCol, pendingDoc.startCol);
          }
        }
        if (!pendingAttrs.isEmpty()) {
          PendingAttr firstAttr = pendingAttrs.get(0);
          if (firstAttr.startLine < blockStartLine) {
            blockStartLine = firstAttr.startLine;
            blockStartCol = firstAttr.startCol;
          } else if (firstAttr.startLine == blockStartLine) {
            blockStartCol = Math.min(blockStartCol, firstAttr.startCol);
          }
        }
        if (!pendingModifiers.isEmpty()) {
          PendingModifier firstMod = pendingModifiers.get(0);
          if (firstMod.lineNum < blockStartLine) {
            blockStartLine = firstMod.lineNum;
            blockStartCol = firstMod.startCol;
          } else if (firstMod.lineNum == blockStartLine) {
            blockStartCol = Math.min(blockStartCol, firstMod.startCol);
          }
        }
        if (!pendingComments.isEmpty()) {
          PendingComment firstComment = pendingComments.get(0);
          if (firstComment.startLine < blockStartLine) {
            blockStartLine = firstComment.startLine;
            blockStartCol = firstComment.startCol;
          } else if (firstComment.startLine == blockStartLine) {
            blockStartCol = Math.min(blockStartCol, firstComment.startCol);
          }
        }
        int blockEndLine = codeLines.get(codeLines.size() - 1).lineNum();
        ImmutableList.Builder<String> pendingAttrTexts = ImmutableList.builder();
        for (PendingAttr attr : pendingAttrs) {
          pendingAttrTexts.add(attr.text);
        }
        LeanDeclaration details =
            buildDeclarationDetails(
                combinedHeader,
                pendingAttrTexts.build(),
                pendingDoc != null ? pendingDoc.text : null,
                pendingDoc != null ? pendingDoc.startLine : null,
                pendingDoc != null ? pendingDoc.endLine : null,
                ImmutableList.copyOf(codeLines));
        String rawText =
            blockStartCol > 0
                ? joinLinesFromCol(lines, blockStartLine - 1, blockEndLine, blockStartCol)
                : joinLines(lines, blockStartLine - 1, blockEndLine);
        String idName =
            combinedHeader.name.isEmpty() ? String.valueOf(blockCounter) : combinedHeader.name;
        blocks.add(
            new LeanBlock(
                "decl-" + combinedHeader.kind.keyword() + "-" + idName + "-" + blockCounter++,
                LeanBlockType.DECLARATION,
                rawText,
                blockStartLine,
                blockEndLine,
                null,
                ImmutableList.of(),
                null,
                ImmutableList.of(),
                ImmutableList.of(),
                false,
                details));
        pendingDoc = null;
        pendingAttrs.clear();
        pendingModifiers.clear();
        pendingComments.clear();
        continue;
      }

      // Any column-0 non-declaration exits an unclosed `mutual` scope cleanly.
      if (inMutual && colOffset == 0 && lineIndent == 0) {
        inMutual = false;
      }

      // 9. Scope / file directives (`module`, `namespace`, `section`, `end`, `open`, `variable`)
      String dirKeyword = getDirectiveKeyword(fullLine, startCol, true);
      if (dirKeyword != null) {
        blockCounter =
            flushPendingAsStandalone(
                blocks, blockCounter, pendingDoc, pendingAttrs, pendingModifiers, pendingComments);
        pendingDoc = null;
        pendingAttrs.clear();
        pendingModifiers.clear();
        pendingComments.clear();

        int startIdx = i;

        if (IN_SCOPING_DIRECTIVES.contains(dirKeyword)) {
          if (cachedStrippedRow != i) {
            cachedStrippedFullLine = LeanLexer.stripLeanCommentsAndStrings(fullLine);
            cachedStrippedRow = i;
          }
          int bomShift = (!fullLine.isEmpty() && fullLine.charAt(0) == '\uFEFF') ? 1 : 0;
          int inEnd = findStandaloneInEnd(cachedStrippedFullLine, Math.max(0, startCol - bomShift));
          if (inEnd >= 0) {
            int safeInEnd = Math.min(inEnd + bomShift, fullLine.length());
            String prefixText =
                stripTrailingLeanWhitespace(fullLine.substring(colOffset, safeInEnd));
            if (!LeanLexer.hasUnterminatedLiteralOrComment(prefixText)) {
              blocks.add(
                  new LeanBlock(
                      "dir-" + dirKeyword + "-" + blockCounter++,
                      LeanBlockType.DIRECTIVE,
                      prefixText,
                      startIdx + 1,
                      startIdx + 1,
                      null,
                      ImmutableList.of(),
                      dirKeyword,
                      ImmutableList.of(new LeanCodeLine(startIdx + 1, prefixText)),
                      ImmutableList.of(),
                      false,
                      null));
              if (hasNonWhitespaceFrom(fullLine, safeInEnd)) {
                colOffset = safeInEnd;
              } else {
                i = startIdx + 1;
                colOffset = 0;
              }
              continue;
            }
          }
        }

        String firstDirText = colOffset > 0 ? fullLine.substring(colOffset) : fullLine;
        List<LeanCodeLine> dirLines = new ArrayList<>();
        dirLines.add(new LeanCodeLine(i + 1, firstDirText));
        LeanLexer.LeanLexState dirLex = LeanLexer.createLeanLexState();
        LeanLexer.advanceLeanLexState(dirLex, firstDirText);
        i =
            collectDirectiveOrFallbackContinuation(
                lines,
                i + 1,
                lineIndent,
                SINGLE_LINE_DIRECTIVES.contains(dirKeyword),
                BODY_DIRECTIVES.contains(dirKeyword),
                dirLex,
                dirLines);
        colOffset = 0;

        blocks.add(
            new LeanBlock(
                "dir-" + dirKeyword + "-" + blockCounter++,
                LeanBlockType.DIRECTIVE,
                loopStartCol > 0 ? joinCodeLineTexts(dirLines) : joinLines(lines, startIdx, i),
                startIdx + 1,
                i,
                null,
                ImmutableList.of(),
                dirKeyword,
                ImmutableList.copyOf(dirLines),
                ImmutableList.of(),
                false,
                null));
        continue;
      }

      // 10. Fallback: any unrecognized top-level Lean snippet stays intact as a directive block.
      blockCounter =
          flushPendingAsStandalone(
              blocks, blockCounter, pendingDoc, pendingAttrs, pendingModifiers, pendingComments);
      pendingDoc = null;
      pendingAttrs.clear();
      pendingModifiers.clear();
      pendingComments.clear();

      int startIdx = i;
      String firstFallbackText = colOffset > 0 ? fullLine.substring(colOffset) : fullLine;
      List<LeanCodeLine> fallbackLines = new ArrayList<>();
      fallbackLines.add(new LeanCodeLine(i + 1, firstFallbackText));
      LeanLexer.LeanLexState fallbackLex = LeanLexer.createLeanLexState();
      LeanLexer.advanceLeanLexState(fallbackLex, firstFallbackText);
      i =
          collectDirectiveOrFallbackContinuation(
              lines, i + 1, lineIndent, false, false, fallbackLex, fallbackLines);
      colOffset = 0;
      blocks.add(
          new LeanBlock(
              "code-" + blockCounter++,
              LeanBlockType.DIRECTIVE,
              loopStartCol > 0 ? joinCodeLineTexts(fallbackLines) : joinLines(lines, startIdx, i),
              startIdx + 1,
              i,
              null,
              ImmutableList.of(),
              "code",
              ImmutableList.copyOf(fallbackLines),
              ImmutableList.of(),
              false,
              null));

      // Mandatory forward-progress invariant.
      if (i < loopStartI || (i == loopStartI && colOffset <= loopStartCol)) {
        i = loopStartI + 1;
        colOffset = 0;
      }
    }

    flushPendingAsStandalone(
        blocks, blockCounter, pendingDoc, pendingAttrs, pendingModifiers, pendingComments);
    return ImmutableList.copyOf(blocks);
  }

  private static int collectDirectiveOrFallbackContinuation(
      List<String> lines,
      int nextIndex,
      int baseIndent,
      boolean isSingleLine,
      boolean isBodyDir,
      LeanLexer.LeanLexState lexState,
      List<LeanCodeLine> outLines) {
    int i = nextIndex;
    while (i < lines.size()) {
      String nextLine = lines.get(i);
      boolean keepForOpenLiteral =
          LeanLexer.hasOpenLiteralOrComment(lexState) || lexState.hasOpenInterpBrace();
      if (!keepForOpenLiteral) {
        if (isSingleLine || isBlankLeanLine(nextLine) || getLineIndent(nextLine) <= baseIndent) {
          break;
        }
        String nextTrimmed = stripLeanWhitespace(nextLine);
        if (!isBodyDir
            && (parseDeclarationHeaderStart(nextLine, true) != null
                || parseStandaloneModifiersLine(nextLine) != null
                || LeanLexer.isDividerCommentLine(nextLine)
                || nextTrimmed.startsWith("/-!")
                || (nextTrimmed.startsWith("/--") && !nextTrimmed.startsWith("/---"))
                || nextTrimmed.startsWith("@["))) {
          break;
        }
      }
      outLines.add(new LeanCodeLine(i + 1, nextLine));
      LeanLexer.advanceLeanLexState(lexState, nextLine);
      i++;
    }
    return i;
  }

  @CanIgnoreReturnValue
  private static int flushPendingAsStandalone(
      List<LeanBlock> blocks,
      int blockCounter,
      @Nullable PendingDoc pendingDoc,
      List<PendingAttr> pendingAttrs,
      List<PendingModifier> pendingModifiers,
      List<PendingComment> pendingComments) {
    List<PendingItem> items = new ArrayList<>();
    if (pendingDoc != null) {
      items.add(
          new PendingItem(
              PendingKind.DOC,
              pendingDoc.startLine,
              pendingDoc.endLine,
              pendingDoc,
              null,
              null,
              null));
    }
    for (PendingAttr attr : pendingAttrs) {
      items.add(
          new PendingItem(PendingKind.ATTR, attr.startLine, attr.endLine, null, attr, null, null));
    }
    for (PendingModifier mod : pendingModifiers) {
      items.add(
          new PendingItem(PendingKind.MODIFIER, mod.lineNum, mod.lineNum, null, null, mod, null));
    }
    for (PendingComment c : pendingComments) {
      items.add(new PendingItem(PendingKind.COMMENT, c.startLine, c.endLine, null, null, null, c));
    }
    items.sort(
        Comparator.comparingInt(PendingItem::startLine)
            .thenComparingInt(PendingItem::endLine)
            .thenComparingInt(item -> item.kind().ordinal()));

    int idx = 0;
    while (idx < items.size()) {
      PendingItem item = items.get(idx);
      switch (item.kind()) {
        case DOC -> {
          PendingDoc doc = item.doc();
          blocks.add(
              new LeanBlock(
                  "doc-" + blockCounter++,
                  LeanBlockType.MODULE_DOC,
                  doc.raw,
                  doc.startLine,
                  doc.endLine,
                  doc.text,
                  ImmutableList.of(),
                  null,
                  ImmutableList.of(),
                  ImmutableList.of(),
                  false,
                  null));
          idx++;
        }
        case COMMENT -> {
          PendingComment c = item.comment();
          blocks.add(
              new LeanBlock(
                  "comment-" + blockCounter++,
                  LeanBlockType.COMMENT,
                  c.raw,
                  c.startLine,
                  c.endLine,
                  null,
                  ImmutableList.of(),
                  null,
                  ImmutableList.of(),
                  c.lines,
                  false,
                  null));
          idx++;
        }
        case ATTR -> {
          int endIdx = idx + 1;
          while (endIdx < items.size()
              && items.get(endIdx).kind() == PendingKind.ATTR
              && items.get(endIdx).startLine() == items.get(endIdx - 1).endLine() + 1) {
            endIdx++;
          }
          int startLine = item.startLine();
          int endLine = items.get(endIdx - 1).endLine();
          StringBuilder rawAttrs = new StringBuilder();
          ImmutableList.Builder<LeanCodeLine> dirLines = ImmutableList.builder();
          for (int k = idx; k < endIdx; k++) {
            PendingAttr attr = items.get(k).attr();
            if (k > idx) {
              rawAttrs.append('\n');
            }
            rawAttrs.append(attr.text);
            List<String> attrParts = NEWLINE_SPLITTER.splitToList(attr.text);
            for (int partIdx = 0; partIdx < attrParts.size(); partIdx++) {
              int lineNum = Math.min(attr.endLine, attr.startLine + partIdx);
              dirLines.add(new LeanCodeLine(lineNum, attrParts.get(partIdx)));
            }
          }
          blocks.add(
              new LeanBlock(
                  "dir-" + blockCounter++,
                  LeanBlockType.DIRECTIVE,
                  rawAttrs.toString(),
                  startLine,
                  endLine,
                  null,
                  ImmutableList.of(),
                  "attribute",
                  dirLines.build(),
                  ImmutableList.of(),
                  false,
                  null));
          idx = endIdx;
        }
        case MODIFIER -> {
          int endIdx = idx + 1;
          while (endIdx < items.size()
              && items.get(endIdx).kind() == PendingKind.MODIFIER
              && items.get(endIdx).startLine() == items.get(endIdx - 1).endLine() + 1) {
            endIdx++;
          }
          int startLine = item.startLine();
          int endLine = items.get(endIdx - 1).endLine();
          StringBuilder rawMods = new StringBuilder();
          ImmutableList.Builder<LeanCodeLine> dirLines = ImmutableList.builder();
          for (int k = idx; k < endIdx; k++) {
            PendingModifier mod = items.get(k).modifier();
            if (k > idx) {
              rawMods.append('\n');
            }
            rawMods.append(mod.text);
            dirLines.add(new LeanCodeLine(mod.lineNum, mod.text));
          }
          blocks.add(
              new LeanBlock(
                  "dir-mod-" + blockCounter++,
                  LeanBlockType.DIRECTIVE,
                  rawMods.toString(),
                  startLine,
                  endLine,
                  null,
                  ImmutableList.of(),
                  "modifier",
                  dirLines.build(),
                  ImmutableList.of(),
                  false,
                  null));
          idx = endIdx;
        }
      }
    }
    return blockCounter;
  }

  private static boolean isStandaloneAttributeLine(String line, int startPos) {
    int len = line.length();
    int pos = startPos;
    int count = 0;
    while (pos < len && line.startsWith("@[", pos)) {
      int closeIdx = LeanLexer.findMatchingBracket(line, pos + 1);
      if (closeIdx < 0) {
        return false;
      }
      count++;
      pos = LeanLexer.skipLeadingWsAndBlockCommentsIndex(line, closeIdx + 1);
    }
    return count > 0 && (pos >= len || line.startsWith("--", pos));
  }

  @Nullable
  private static ImmutableList<String> parseStandaloneModifiersLine(String line) {
    return parseStandaloneModifiersLine(line, 0);
  }

  @Nullable
  private static ImmutableList<String> parseStandaloneModifiersLine(String line, int startPos) {
    int len = line.length();
    int pos = LeanLexer.skipLeadingWsAndBlockCommentsIndex(line, startPos);
    if (pos >= len || line.startsWith("--", pos)) {
      return null;
    }
    ImmutableList.Builder<String> mods = ImmutableList.builder();
    int count = 0;
    while (pos < len && !line.startsWith("--", pos)) {
      String word = matchLeadingAsciiWord(line, pos);
      if (word == null || !LeanSyntax.LEAN_MODIFIER_KEYWORDS.contains(word)) {
        return null;
      }
      mods.add(word);
      count++;
      pos = LeanLexer.skipLeadingWsAndBlockCommentsIndex(line, pos + word.length());
    }
    return count > 0 ? mods.build() : null;
  }

  @Nullable
  private static DeclHeaderStart parseDeclarationHeaderStart(String line, boolean allowIndent) {
    return parseDeclarationHeaderStart(line, 0, allowIndent);
  }

  @Nullable
  private static DeclHeaderStart parseDeclarationHeaderStart(
      String line, int startPos, boolean allowIndent) {
    if (startPos >= line.length()
        || (!allowIndent && isLeanWhitespace(line.codePointAt(startPos)))) {
      return null;
    }
    int len = line.length();
    int pos = LeanLexer.skipLeadingWsAndBlockCommentsIndex(line, startPos);
    return parseDeclarationHeaderFromLeadingPos(line, pos, len);
  }

  @Nullable
  private static DeclHeaderStart parseDeclarationHeaderFromLeadingPos(
      String line, int pos, int len) {
    ImmutableList.Builder<String> attributes = ImmutableList.builder();
    ImmutableList.Builder<String> modifiers = ImmutableList.builder();

    while (pos < len && line.startsWith("@[", pos)) {
      int closeIdx = LeanLexer.findMatchingBracket(line, pos + 1);
      if (closeIdx < 0) {
        break;
      }
      attributes.add(line.substring(pos, closeIdx + 1));
      pos = LeanLexer.skipLeadingWsAndBlockCommentsIndex(line, closeIdx + 1);
    }

    while (pos < len) {
      String word = matchLeadingAsciiWord(line, pos);
      if (word != null && LeanSyntax.LEAN_MODIFIER_KEYWORDS.contains(word)) {
        modifiers.add(word);
        pos = LeanLexer.skipLeadingWsAndBlockCommentsIndex(line, pos + word.length());
      } else {
        break;
      }
    }

    String kindWord = matchLeadingAsciiWord(line, pos);
    if (kindWord == null) {
      return null;
    }
    Optional<LeanDeclKind> parsedKind = LeanDeclKind.fromKeyword(kindWord);
    if (parsedKind.isEmpty()) {
      return null;
    }
    LeanDeclKind candidate = parsedKind.get();
    int afterKindPos = LeanLexer.skipLeadingWsAndBlockCommentsIndex(line, pos + kindWord.length());

    if (candidate == LeanDeclKind.CLASS) {
      if (startsWithKeywordToken(line, afterKindPos, "inductive")) {
        candidate = LeanDeclKind.INDUCTIVE;
        afterKindPos =
            LeanLexer.skipLeadingWsAndBlockCommentsIndex(line, afterKindPos + "inductive".length());
      } else if (startsWithKeywordToken(line, afterKindPos, "abbrev")) {
        candidate = LeanDeclKind.ABBREV;
        afterKindPos =
            LeanLexer.skipLeadingWsAndBlockCommentsIndex(line, afterKindPos + "abbrev".length());
      }
    }

    String name = "";
    if (candidate != LeanDeclKind.EXAMPLE) {
      String matched = matchQualifiedName(line, afterKindPos);
      if (!matched.isEmpty()
          && !"where".equals(matched)
          && !"extends".equals(matched)
          && !matched.startsWith(":")) {
        name = matched;
      }
    }

    return new DeclHeaderStart(attributes.build(), modifiers.build(), candidate, name);
  }

  @Nullable
  private static String getDirectiveKeyword(String line, int startPos, boolean allowIndent) {
    if (startPos >= line.length()
        || (!allowIndent && isLeanWhitespace(line.codePointAt(startPos)))) {
      return null;
    }
    int len = line.length();
    int pos = LeanLexer.skipLeadingWsAndBlockCommentsIndex(line, startPos);
    return getDirectiveKeywordFromLeadingPos(line, pos, len);
  }

  @Nullable
  private static String getDirectiveKeywordFromLeadingPos(String line, int pos, int len) {
    while (pos < len) {
      String modWord = matchLeadingAsciiWord(line, pos);
      if (modWord != null && LeanSyntax.LEAN_MODIFIER_KEYWORDS.contains(modWord)) {
        int nextPos = LeanLexer.skipLeadingWsAndBlockCommentsIndex(line, pos + modWord.length());
        if (nextPos >= len) {
          break;
        }
        pos = nextPos;
      } else {
        break;
      }
    }
    String word = matchLeadingDirectiveWord(line, pos);
    if (word == null) {
      return null;
    }
    return LeanSyntax.LEAN_DIRECTIVE_KEYWORDS.contains(word) ? word : null;
  }

  private static AssignResult findTopLevelAssign(String line, DelimDepth initialDepth) {
    int paren = initialDepth.paren;
    int bracket = initialDepth.bracket;
    int brace = initialDepth.brace;
    boolean inString = false;
    int len = line.length();
    for (int i = 0; i < len; i++) {
      char ch = line.charAt(i);
      char next = i + 1 < len ? line.charAt(i + 1) : '\0';
      if (inString) {
        if (ch == '\\') {
          i++;
        } else if (ch == '"') {
          inString = false;
        }
        continue;
      }
      if (ch == '-' && next == '-') {
        break;
      }
      if (ch == '/' && next == '-') {
        int depth = 1;
        int j = i + 2;
        while (j < len && depth > 0) {
          if (j + 1 < len && line.charAt(j) == '/' && line.charAt(j + 1) == '-') {
            depth++;
            j += 2;
          } else if (j + 1 < len && line.charAt(j) == '-' && line.charAt(j + 1) == '/') {
            depth--;
            j += 2;
          } else {
            j++;
          }
        }
        i = j - 1;
        continue;
      }
      LeanLexer.RawStringOpen rawOpen = LeanLexer.matchRawStringOpen(line, i);
      if (rawOpen != null) {
        int j = rawOpen.contentStart();
        while (j < len) {
          if (LeanLexer.matchRawStringCloseAt(line, j, rawOpen.hashes())) {
            j += rawOpen.hashes();
            break;
          }
          j++;
        }
        i = j;
        continue;
      }
      int charLen = LeanLexer.matchCharLiteral(line, i);
      if (charLen > 0) {
        i += charLen - 1;
        continue;
      }
      if (ch == '«') {
        int closeGuillemet = line.indexOf('»', i + 1);
        i = closeGuillemet >= 0 ? closeGuillemet : len - 1;
        continue;
      }
      if (ch == '"') {
        inString = true;
        continue;
      }
      if (ch == '(') {
        paren++;
      } else if (ch == ')' && paren > 0) {
        paren--;
      } else if (ch == '[') {
        bracket++;
      } else if (ch == ']' && bracket > 0) {
        bracket--;
      } else if (ch == '{') {
        brace++;
      } else if (ch == '}' && brace > 0) {
        brace--;
      } else if (ch == ':' && next == '=' && paren == 0 && bracket == 0 && brace == 0) {
        return new AssignResult(i, new DelimDepth(paren, bracket, brace));
      }
    }
    return new AssignResult(-1, new DelimDepth(paren, bracket, brace));
  }

  private static LineCommentSplit splitLineComment(String line) {
    boolean inString = false;
    int len = line.length();
    for (int i = 0; i < len; i++) {
      char ch = line.charAt(i);
      if (inString) {
        if (ch == '\\') {
          i++;
        } else if (ch == '"') {
          inString = false;
        }
        continue;
      }
      if (ch == '/' && i + 1 < len && line.charAt(i + 1) == '-') {
        int depth = 1;
        int j = i + 2;
        while (j < len && depth > 0) {
          if (j + 1 < len && line.charAt(j) == '/' && line.charAt(j + 1) == '-') {
            depth++;
            j += 2;
          } else if (j + 1 < len && line.charAt(j) == '-' && line.charAt(j + 1) == '/') {
            depth--;
            j += 2;
          } else {
            j++;
          }
        }
        i = j - 1;
        continue;
      }
      LeanLexer.RawStringOpen rawOpen = LeanLexer.matchRawStringOpen(line, i);
      if (rawOpen != null) {
        int j = rawOpen.contentStart();
        while (j < len) {
          if (LeanLexer.matchRawStringCloseAt(line, j, rawOpen.hashes())) {
            j += rawOpen.hashes();
            break;
          }
          j++;
        }
        i = j;
        continue;
      }
      int charLen = LeanLexer.matchCharLiteral(line, i);
      if (charLen > 0) {
        i += charLen - 1;
        continue;
      }
      if (ch == '«') {
        int closeGuillemet = line.indexOf('»', i + 1);
        i = closeGuillemet >= 0 ? closeGuillemet : len - 1;
        continue;
      }
      if (ch == '"') {
        inString = true;
        continue;
      }
      if (ch == '-' && i + 1 < len && line.charAt(i + 1) == '-') {
        return new LineCommentSplit(
            line.substring(0, i).stripTrailing(), line.substring(i + 2).trim());
      }
    }
    return new LineCommentSplit(line.stripTrailing(), null);
  }

  private static DerivingSplit parseDerivingClause(ImmutableList<LeanCodeLine> lines) {
    if (lines.isEmpty()) {
      return new DerivingSplit(lines, null);
    }
    LeanCodeLine last = lines.get(lines.size() - 1);
    LineCommentSplit split = splitLineComment(last.text());
    String code = split.code.trim();
    if (!code.startsWith("deriving") || code.length() <= "deriving".length()) {
      return new DerivingSplit(lines, null);
    }
    int pos = "deriving".length();
    if (!Character.isWhitespace(code.codePointAt(pos))) {
      return new DerivingSplit(lines, null);
    }
    while (pos < code.length() && Character.isWhitespace(code.codePointAt(pos))) {
      pos += Character.charCount(code.codePointAt(pos));
    }
    if (pos >= code.length()) {
      return new DerivingSplit(lines, null);
    }
    String rest = code.substring(pos);
    ImmutableList.Builder<String> clauses = ImmutableList.builder();
    int count = 0;
    for (String part : Splitter.on(',').split(rest)) {
      String trimmed = part.trim();
      if (!trimmed.isEmpty()) {
        clauses.add(trimmed);
        count++;
      }
    }
    if (count > 0 && split.comment != null) {
      clauses.add("-- " + split.comment);
    }
    return new DerivingSplit(
        lines.subList(0, lines.size() - 1), count > 0 ? clauses.build() : null);
  }

  @Nullable
  private static ImmutableList<LeanStructureField> parseStructureFields(
      ImmutableList<LeanCodeLine> bodyLines) {
    if (bodyLines.isEmpty()) {
      return null;
    }
    ImmutableList.Builder<LeanStructureField> fields = ImmutableList.builder();
    int count = 0;
    for (LeanCodeLine line : bodyLines) {
      String trimmed = line.text().trim();
      if (trimmed.isEmpty()) {
        continue;
      }
      if (trimmed.startsWith("--") || trimmed.startsWith("/-")) {
        return null;
      }
      LineCommentSplit split = splitLineComment(line.text());
      String code = stripLeanWhitespace(split.code);
      int nameEnd = matchSimpleFieldOrCtorName(code);
      if (nameEnd <= 0) {
        return null;
      }
      String fieldName = code.substring(0, nameEnd);
      int pos = skipLeanWhitespace(code, nameEnd);
      if (pos >= code.length() || code.charAt(pos) != ':') {
        return null;
      }
      if (pos + 1 < code.length() && code.charAt(pos + 1) == ':') {
        return null;
      }
      pos = skipLeanWhitespace(code, pos + 1);
      if (pos >= code.length()) {
        return null;
      }
      String rhs = stripLeanWhitespace(code.substring(pos));
      if (rhs.isEmpty()) {
        return null;
      }
      AssignResult assign = findTopLevelAssign(rhs, new DelimDepth(0, 0, 0));
      String typeText = rhs;
      String defaultValue = null;
      if (assign.index >= 0) {
        typeText = stripLeanWhitespace(rhs.substring(0, assign.index));
        defaultValue = stripLeanWhitespace(rhs.substring(assign.index + 2));
      }
      fields.add(
          new LeanStructureField(
              fieldName, typeText, defaultValue, split.comment, line.lineNum(), line.text()));
      count++;
    }
    return count > 0 ? fields.build() : null;
  }

  @Nullable
  private static ImmutableList<String> splitTopLevelPipes(String trimmedCode) {
    if (!trimmedCode.startsWith("|")) {
      return null;
    }
    ImmutableList.Builder<String> segments = ImmutableList.builder();
    int count = 0;
    int segStart = 1;
    int paren = 0;
    int bracket = 0;
    int brace = 0;
    boolean inString = false;
    int len = trimmedCode.length();

    for (int i = 1; i < len; i++) {
      char ch = trimmedCode.charAt(i);
      if (inString) {
        if (ch == '\\') {
          i++;
        } else if (ch == '"') {
          inString = false;
        }
        continue;
      }
      LeanLexer.RawStringOpen rawOpen = LeanLexer.matchRawStringOpen(trimmedCode, i);
      if (rawOpen != null) {
        int j = rawOpen.contentStart();
        while (j < len) {
          if (LeanLexer.matchRawStringCloseAt(trimmedCode, j, rawOpen.hashes())) {
            j += rawOpen.hashes();
            break;
          }
          j++;
        }
        i = j;
        continue;
      }
      int charLen = LeanLexer.matchCharLiteral(trimmedCode, i);
      if (charLen > 0) {
        i += charLen - 1;
        continue;
      }
      if (ch == '«') {
        int closeGuillemet = trimmedCode.indexOf('»', i + 1);
        i = closeGuillemet >= 0 ? closeGuillemet : len - 1;
        continue;
      }
      if (ch == '"') {
        inString = true;
        continue;
      }
      if (ch == '(') {
        paren++;
      } else if (ch == ')' && paren > 0) {
        paren--;
      } else if (ch == '[') {
        bracket++;
      } else if (ch == ']' && bracket > 0) {
        bracket--;
      } else if (ch == '{') {
        brace++;
      } else if (ch == '}' && brace > 0) {
        brace--;
      } else if (ch == '|'
          && paren == 0
          && bracket == 0
          && brace == 0
          && trimmedCode.charAt(i - 1) != '|'
          && trimmedCode.charAt(i - 1) != '<'
          && (i + 1 >= len
              || (trimmedCode.charAt(i + 1) != '|' && trimmedCode.charAt(i + 1) != '>'))) {
        String seg = trimmedCode.substring(segStart, i).trim();
        if (seg.isEmpty()) {
          return null;
        }
        segments.add(seg);
        count++;
        segStart = i + 1;
      }
    }
    String lastSeg = trimmedCode.substring(segStart).trim();
    if (lastSeg.isEmpty()) {
      return null;
    }
    segments.add(lastSeg);
    count++;
    return count > 0 ? segments.build() : null;
  }

  @Nullable
  private static ImmutableList<LeanInductiveCtor> parseInductiveConstructors(
      ImmutableList<LeanCodeLine> bodyLines) {
    if (bodyLines.isEmpty()) {
      return null;
    }
    ImmutableList.Builder<LeanInductiveCtor> ctors = ImmutableList.builder();
    int count = 0;
    for (LeanCodeLine line : bodyLines) {
      String trimmed = line.text().trim();
      if (trimmed.isEmpty()) {
        continue;
      }
      if (!trimmed.startsWith("|")) {
        return null;
      }
      LineCommentSplit split = splitLineComment(line.text());
      ImmutableList<String> segments = splitTopLevelPipes(split.code.trim());
      if (segments == null) {
        return null;
      }
      for (int s = 0; s < segments.size(); s++) {
        String segment = segments.get(s);
        int nameEnd = matchSimpleFieldOrCtorName(segment);
        if (nameEnd <= 0) {
          return null;
        }
        String name = segment.substring(0, nameEnd);
        String signatureText = segment.substring(nameEnd).trim();
        String comment = (s == segments.size() - 1) ? split.comment : null;
        ctors.add(new LeanInductiveCtor(name, signatureText, comment, line.lineNum(), line.text()));
        count++;
      }
    }
    return count > 0 ? ctors.build() : null;
  }

  @Nullable
  private static LeanProofStrategy detectProofStrategy(
      LeanDeclKind kind,
      boolean isTacticProof,
      ImmutableList<LeanCodeLine> bodyLines,
      LeanDeclHygiene hygiene) {
    if (kind != LeanDeclKind.THEOREM
        && kind != LeanDeclKind.LEMMA
        && kind != LeanDeclKind.EXAMPLE) {
      return null;
    }
    String cleanBody = LeanLexer.stripLeanCommentsAndStrings(joinCodeLineTexts(bodyLines)).trim();
    if (cleanBody.isEmpty()) {
      return null;
    }
    if (!hygiene.hasSorry()
        && !hygiene.hasAdmit()
        && !hygiene.hasNativeDecide()
        && isPureDecideBody(cleanBody)) {
      return LeanProofStrategy.DECIDE;
    }
    if (isTacticProof) {
      return LeanProofStrategy.TACTIC;
    }
    return LeanProofStrategy.TERM;
  }

  private static AssignResult findTopLevelAssignInStrippedLine(
      String strippedLine, DelimDepth initialDepth) {
    int paren = initialDepth.paren;
    int bracket = initialDepth.bracket;
    int brace = initialDepth.brace;
    int len = strippedLine.length();
    for (int i = 0; i < len; i++) {
      char ch = strippedLine.charAt(i);
      if (ch == '(') {
        paren++;
      } else if (ch == ')' && paren > 0) {
        paren--;
      } else if (ch == '[') {
        bracket++;
      } else if (ch == ']' && bracket > 0) {
        bracket--;
      } else if (ch == '{') {
        brace++;
      } else if (ch == '}' && brace > 0) {
        brace--;
      } else if (ch == ':'
          && i + 1 < len
          && strippedLine.charAt(i + 1) == '='
          && paren == 0
          && bracket == 0
          && brace == 0) {
        return new AssignResult(i, new DelimDepth(paren, bracket, brace));
      }
    }
    return new AssignResult(-1, new DelimDepth(paren, bracket, brace));
  }

  private static LeanDeclaration buildDeclarationDetails(
      DeclHeaderStart header,
      ImmutableList<String> pendingAttrs,
      @Nullable String docComment,
      @Nullable Integer docStartLine,
      @Nullable Integer docEndLine,
      ImmutableList<LeanCodeLine> codeLines) {
    ImmutableList<String> attributes =
        ImmutableList.<String>builder().addAll(pendingAttrs).addAll(header.attributes).build();
    ImmutableList<String> modifiers = header.modifiers;
    LeanDeclHygiene hygiene =
        LeanHygiene.analyzeDeclarationHygiene(header.kind, modifiers, pendingAttrs, codeLines);

    List<String> strippedLines =
        NEWLINE_SPLITTER.splitToList(
            LeanLexer.stripLeanCommentsAndStrings(joinCodeLineTexts(codeLines)));

    ImmutableList<LeanCodeLine> signatureLines;
    ImmutableList<LeanCodeLine> bodyLines = ImmutableList.of();
    boolean isTacticProof = false;
    ImmutableList<LeanStructureField> fields = null;
    ImmutableList<LeanInductiveCtor> constructors = null;
    ImmutableList<String> derivingClause = null;

    if (header.kind == LeanDeclKind.STRUCTURE
        || header.kind == LeanDeclKind.CLASS
        || header.kind == LeanDeclKind.INDUCTIVE
        || header.kind == LeanDeclKind.COINDUCTIVE) {
      int whereIdx = -1;
      for (int idx = 0; idx < codeLines.size(); idx++) {
        String strippedTrimmed = idx < strippedLines.size() ? strippedLines.get(idx).trim() : "";
        if (endsWithWord(strippedTrimmed, "where")) {
          whereIdx = idx;
          break;
        }
        if (idx > 0 && strippedTrimmed.startsWith("|")) {
          whereIdx = idx - 1;
          break;
        }
      }
      if (whereIdx >= 0) {
        signatureLines = codeLines.subList(0, whereIdx + 1);
        ImmutableList<LeanCodeLine> rawBody = codeLines.subList(whereIdx + 1, codeLines.size());
        DerivingSplit parsedDeriving = parseDerivingClause(rawBody);
        derivingClause = parsedDeriving.derivingClause;
        bodyLines = parsedDeriving.remaining;
        if (header.kind == LeanDeclKind.STRUCTURE || header.kind == LeanDeclKind.CLASS) {
          fields = parseStructureFields(bodyLines);
        } else {
          constructors = parseInductiveConstructors(bodyLines);
        }
      } else {
        signatureLines = codeLines;
      }
    } else {
      int instanceWhereIdx = -1;
      if (header.kind == LeanDeclKind.INSTANCE) {
        for (int idx = 0; idx < codeLines.size(); idx++) {
          String strippedTrimmed = idx < strippedLines.size() ? strippedLines.get(idx).trim() : "";
          if (endsWithWord(strippedTrimmed, "where")) {
            instanceWhereIdx = idx;
            break;
          }
        }
      }

      if (instanceWhereIdx >= 0) {
        signatureLines = codeLines.subList(0, instanceWhereIdx + 1);
        bodyLines = codeLines.subList(instanceWhereIdx + 1, codeLines.size());
      } else {
        DelimDepth depth = new DelimDepth(0, 0, 0);
        int splitLineIdx = -1;
        int assignCharIdx = -1;
        boolean isMatchArmSplit = false;

        for (int idx = 0; idx < codeLines.size(); idx++) {
          String strippedLine = idx < strippedLines.size() ? strippedLines.get(idx) : "";
          if (idx > 0
              && depth.paren == 0
              && depth.bracket == 0
              && depth.brace == 0
              && stripLeadingLeanWhitespace(strippedLine).startsWith("|")) {
            splitLineIdx = idx;
            isMatchArmSplit = true;
            break;
          }
          AssignResult res = findTopLevelAssignInStrippedLine(strippedLine, depth);
          depth = res.depth;
          if (res.index >= 0) {
            splitLineIdx = idx;
            assignCharIdx = res.index;
            break;
          }
        }

        if (splitLineIdx >= 0 && isMatchArmSplit) {
          signatureLines = codeLines.subList(0, splitLineIdx);
          bodyLines = codeLines.subList(splitLineIdx, codeLines.size());
          String cleanBody =
              splitLineIdx < strippedLines.size()
                  ? String.join("\n", strippedLines.subList(splitLineIdx, strippedLines.size()))
                  : "";
          isTacticProof = LeanHygiene.extractLeanIdentifiers(cleanBody).contains("by");
        } else if (splitLineIdx >= 0 && assignCharIdx >= 0) {
          LeanCodeLine splitLine = codeLines.get(splitLineIdx);
          String beforeAssign =
              stripTrailingLeanWhitespace(splitLine.text().substring(0, assignCharIdx));
          String afterAssign = stripLeanWhitespace(splitLine.text().substring(assignCharIdx + 2));
          String rawStrippedLine =
              splitLineIdx < strippedLines.size() ? strippedLines.get(splitLineIdx) : "";
          String afterAssignStripped =
              assignCharIdx + 2 <= rawStrippedLine.length()
                  ? stripLeanWhitespace(rawStrippedLine.substring(assignCharIdx + 2))
                  : "";

          signatureLines =
              ImmutableList.<LeanCodeLine>builder()
                  .addAll(codeLines.subList(0, splitLineIdx))
                  .add(new LeanCodeLine(splitLine.lineNum(), beforeAssign))
                  .build();

          if (startsWithWordOrEnd(afterAssignStripped, "by")) {
            isTacticProof = true;
            int afterCommentsIdx =
                LeanLexer.skipLeadingWsAndBlockCommentsIndex(splitLine.text(), assignCharIdx + 2);
            String leadingComments =
                stripLeanWhitespace(
                    splitLine.text().substring(assignCharIdx + 2, afterCommentsIdx));
            String remainder =
                splitLine.text().startsWith("by", afterCommentsIdx)
                    ? stripLeanWhitespace(splitLine.text().substring(afterCommentsIdx + 2))
                    : afterAssign;
            String firstBodyLine =
                leadingComments.isEmpty()
                    ? remainder
                    : (remainder.isEmpty() ? leadingComments : leadingComments + " " + remainder);
            if (!firstBodyLine.isEmpty()) {
              bodyLines =
                  ImmutableList.<LeanCodeLine>builder()
                      .add(new LeanCodeLine(splitLine.lineNum(), firstBodyLine))
                      .addAll(codeLines.subList(splitLineIdx + 1, codeLines.size()))
                      .build();
            } else {
              bodyLines = codeLines.subList(splitLineIdx + 1, codeLines.size());
            }
          } else if (startsWithWordOrEnd(afterAssignStripped, "do") || !afterAssign.isEmpty()) {
            bodyLines =
                ImmutableList.<LeanCodeLine>builder()
                    .add(new LeanCodeLine(splitLine.lineNum(), afterAssign))
                    .addAll(codeLines.subList(splitLineIdx + 1, codeLines.size()))
                    .build();
          } else {
            bodyLines = codeLines.subList(splitLineIdx + 1, codeLines.size());
            for (int offset = 0; offset < bodyLines.size(); offset++) {
              int sIdx = splitLineIdx + 1 + offset;
              String strippedBodyLine =
                  sIdx < strippedLines.size() ? stripLeanWhitespace(strippedLines.get(sIdx)) : "";
              if (!strippedBodyLine.isEmpty()) {
                if (startsWithWordOrEnd(strippedBodyLine, "by")) {
                  isTacticProof = true;
                }
                break;
              }
            }
          }
        } else {
          signatureLines = codeLines;
        }
      }
    }

    boolean isCompactDef =
        (header.kind == LeanDeclKind.DEF || header.kind == LeanDeclKind.ABBREV)
            && docComment == null
            && attributes.isEmpty()
            && codeLines.size() == 1
            && !hygiene.hasSorry()
            && !hygiene.hasAdmit()
            && !hygiene.hasNativeDecide()
            && !hygiene.isAxiom()
            && !hygiene.isPartial()
            && !hygiene.isUnsafe();

    LeanProofStrategy proofStrategy =
        detectProofStrategy(header.kind, isTacticProof, bodyLines, hygiene);

    return new LeanDeclaration(
        header.kind,
        header.name,
        attributes,
        modifiers,
        docComment,
        docStartLine,
        docEndLine,
        signatureLines,
        bodyLines,
        isTacticProof,
        proofStrategy,
        fields,
        constructors,
        derivingClause,
        isCompactDef,
        hygiene);
  }

  private static boolean lineExpectsDeclContinuation(String strippedLine) {
    String trimmed = stripTrailingLeanWhitespace(strippedLine);
    if (trimmed.isEmpty()) {
      return false;
    }
    if (trimmed.endsWith(":=") || trimmed.endsWith("=>")) {
      return true;
    }
    return endsWithDeclKeywordBoundary(trimmed, "by")
        || endsWithDeclKeywordBoundary(trimmed, "where")
        || endsWithDeclKeywordBoundary(trimmed, "extends");
  }

  private static boolean endsWithDeclKeywordBoundary(String s, String kw) {
    if (!s.endsWith(kw)) {
      return false;
    }
    if (s.length() == kw.length()) {
      return true;
    }
    char before = s.charAt(s.length() - kw.length() - 1);
    return isLeanWhitespace(before)
        || before == '('
        || before == ')'
        || before == '['
        || before == ']'
        || before == '{'
        || before == '}';
  }

  private static String extractBlockCommentRaw(
      List<String> lines, int startRow, int startCol, int endRow, int endCol) {
    if (startRow == endRow) {
      return lines.get(startRow).substring(startCol, endCol);
    }
    StringBuilder sb = new StringBuilder();
    sb.append(lines.get(startRow).substring(startCol));
    for (int r = startRow + 1; r < endRow; r++) {
      sb.append('\n').append(lines.get(r));
    }
    sb.append('\n').append(lines.get(endRow), 0, endCol);
    return sb.toString();
  }

  private static int getLineIndent(String line) {
    int idx = 0;
    int len = line.length();
    while (idx < len && (line.charAt(idx) == ' ' || line.charAt(idx) == '\t')) {
      idx++;
    }
    return idx;
  }

  private static boolean hasMemberSignatureAfterKeyword(String rawAfterKw) {
    int i = 0;
    int len = rawAfterKw.length();
    boolean sawParam = false;
    while (i < len) {
      while (i < len && isLeanWhitespace(rawAfterKw.charAt(i))) {
        i++;
      }
      if (i >= len) {
        return false;
      }
      char ch = rawAfterKw.charAt(i);
      char next = i + 1 < len ? rawAfterKw.charAt(i + 1) : '\0';
      if (ch == '-' && next == '-') {
        return false;
      }
      if (ch == '/' && next == '-') {
        int depth = 1;
        int j = i + 2;
        while (j < len && depth > 0) {
          if (j + 1 < len && rawAfterKw.charAt(j) == '/' && rawAfterKw.charAt(j + 1) == '-') {
            depth++;
            j += 2;
          } else if (j + 1 < len
              && rawAfterKw.charAt(j) == '-'
              && rawAfterKw.charAt(j + 1) == '/') {
            depth--;
            j += 2;
          } else {
            j++;
          }
        }
        if (depth > 0) {
          return false;
        }
        i = j;
        continue;
      }
      if (ch == ':' && next == '=') {
        return true;
      }
      if (ch == ':' && !(next >= '0' && next <= '9')) {
        return true;
      }
      if (ch == '|' && sawParam) {
        return true;
      }
      if (ch == '(' || ch == '{' || ch == '[') {
        char closeCh = ch == '(' ? ')' : (ch == '{' ? '}' : ']');
        int depth = 1;
        int j = i + 1;
        boolean inStr = false;
        while (j < len && depth > 0) {
          char c = rawAfterKw.charAt(j);
          if (inStr) {
            if (c == '\\') {
              j++;
            } else if (c == '"') {
              inStr = false;
            }
          } else if (c == '"') {
            inStr = true;
          } else if (c == ch) {
            depth++;
          } else if (c == closeCh) {
            depth--;
          }
          j++;
        }
        if (depth > 0) {
          return false;
        }
        sawParam = true;
        i = j;
        continue;
      }
      int startCp = rawAfterKw.codePointAt(i);
      int startLen = Character.charCount(startCp);
      if (LeanSyntax.isIdentStart(startCp) || ch == '«') {
        if (ch == '«') {
          int closeG = rawAfterKw.indexOf('»', i + 1);
          if (closeG < 0) {
            return false;
          }
          i = closeG + 1;
        } else {
          int j = i + startLen;
          while (j < len) {
            int partCp = rawAfterKw.codePointAt(j);
            if (!LeanSyntax.isIdentPart(partCp)) {
              break;
            }
            j += Character.charCount(partCp);
          }
          i = j;
        }
        sawParam = true;
        continue;
      }
      return false;
    }
    return false;
  }

  private static boolean computeCommentLookaheadAccepts(
      List<String> lines,
      int lookahead,
      int declIndent,
      boolean isTypeDecl,
      boolean hasOpenDelims,
      boolean inWhereBlock) {
    return lookahead < lines.size()
        && getLineIndent(lines.get(lookahead)) > declIndent
        && !shouldBreakDeclOnCommand(lines.get(lookahead), hasOpenDelims, isTypeDecl, inWhereBlock);
  }

  private static boolean shouldBreakDeclOnCommand(
      String line, boolean hasOpenDelims, boolean isTypeDecl, boolean inWhereBlock) {
    if (!startsNewTopLevelCommandInDeclBody(line, isTypeDecl, inWhereBlock)) {
      return false;
    }
    if (!hasOpenDelims) {
      return true;
    }
    String stripped = stripLeanWhitespace(line);
    int wsOrCommentPos = LeanLexer.skipLeadingWsAndBlockCommentsIndex(line, 0);
    return parseDeclarationHeaderStart(line, true) != null
        || parseStandaloneModifiersLine(line) != null
        || parseImportLine(line, wsOrCommentPos) != null
        || (wsOrCommentPos < line.length() && line.startsWith("@[", wsOrCommentPos))
        || stripped.startsWith("/-!")
        || LeanLexer.isDividerCommentLine(line);
  }

  private static boolean startsNewTopLevelCommandInDeclBody(
      String line, boolean isTypeDecl, boolean inWhereBlock) {
    int len = line.length();
    int wsStart = skipLeanWhitespace(line, 0);
    if (line.startsWith("/-!", wsStart)) {
      return true;
    }
    if (!isTypeDecl && line.startsWith("/--", wsStart) && !line.startsWith("/---", wsStart)) {
      return true;
    }
    int leadingPos = LeanLexer.skipLeadingWsAndBlockCommentsIndex(line, wsStart);
    if (leadingPos < len && line.startsWith("@[", leadingPos)) {
      return true;
    }
    if (parseDeclarationHeaderFromLeadingPos(line, leadingPos, len) != null) {
      return true;
    }
    if (parseStandaloneModifiersLine(line, leadingPos) != null) {
      return true;
    }
    if (parseImportLine(line, leadingPos) != null) {
      return true;
    }
    String dirKw = getDirectiveKeywordFromLeadingPos(line, leadingPos, len);
    if (dirKw == null) {
      return false;
    }
    int kwIdx = line.indexOf(dirKw, leadingPos);
    String rawAfterKw = kwIdx >= 0 ? line.substring(kwIdx + dirKw.length()) : "";
    String afterKw = LeanLexer.skipLeadingWsAndBlockComments(rawAfterKw);
    if ("deriving".equals(dirKw)) {
      return startsWithKeywordTokenOrComment(afterKw, "instance");
    }
    if ((afterKw.startsWith(":")
            && !(afterKw.length() > 1 && afterKw.charAt(1) >= '0' && afterKw.charAt(1) <= '9'))
        || afterKw.startsWith(":=")) {
      return false;
    }
    if (inWhereBlock && hasMemberSignatureAfterKeyword(rawAfterKw)) {
      return false;
    }
    if (IN_SCOPING_DIRECTIVES.contains(dirKw)) {
      String stripped = LeanLexer.stripLeanCommentsAndStrings(line);
      if (findStandaloneInEnd(stripped, 0) >= 0) {
        return false;
      }
    }
    return true;
  }

  private static int findLastClosingBracketRow(List<String> lines) {
    for (int r = lines.size() - 1; r >= 0; r--) {
      if (lines.get(r).indexOf(']') >= 0) {
        return r;
      }
    }
    return -1;
  }

  @Nullable
  private static ImportLineParse parseImportLine(String line) {
    return parseImportLine(line, 0);
  }

  @Nullable
  private static ImportLineParse parseImportLine(String line, int startPos) {
    int i = startPos;
    int len = line.length();
    while (true) {
      String kw = null;
      if (line.startsWith("public", i)) {
        kw = "public";
      } else if (line.startsWith("private", i)) {
        kw = "private";
      } else if (line.startsWith("meta", i)) {
        kw = "meta";
      }
      if (kw == null) {
        break;
      }
      int next = i + kw.length();
      if (next >= len || !isLeanWhitespace(line.charAt(next))) {
        break;
      }
      i = skipLeanWhitespace(line, next);
    }
    if (!line.startsWith("import", i)) {
      return null;
    }
    i += "import".length();
    if (i >= len || !isLeanWhitespace(line.charAt(i))) {
      return null;
    }
    i = skipLeanWhitespace(line, i);
    if (line.startsWith("all", i) && (i + 3 == len || isLeanWhitespace(line.charAt(i + 3)))) {
      if (i + 3 == len) {
        return null;
      }
      i = skipLeanWhitespace(line, i + 3);
    }
    int modEnd = matchQualifiedNameEnd(line, i);
    if (modEnd <= i) {
      return null;
    }
    if (modEnd < len
        && !isLeanWhitespace(line.charAt(modEnd))
        && !line.startsWith("--", modEnd)
        && !line.startsWith("/-", modEnd)) {
      return null;
    }
    int restPos = LeanLexer.skipLeadingWsAndBlockCommentsIndex(line, modEnd);
    if (restPos >= len || line.startsWith("--", restPos)) {
      return new ImportLineParse(-1);
    }
    return new ImportLineParse(restPos);
  }

  private static boolean isLicenseHeaderText(String inner) {
    String lower = inner.toLowerCase(Locale.ROOT);
    return lower.contains("copyright")
        || lower.contains("apache license")
        || lower.contains("spdx-license-identifier")
        || lower.contains("licensed under");
  }

  private static String stripLineCommentPrefix(String trimmed) {
    if (!trimmed.startsWith("--")) {
      return trimmed;
    }
    if (trimmed.length() > 2 && isLeanWhitespace(trimmed.charAt(2))) {
      return trimmed.substring(3);
    }
    return trimmed.substring(2);
  }

  private static boolean isAsciiWordChar(char c) {
    return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_';
  }

  private static boolean isAsciiAlphaOrUnderscore(char c) {
    return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || c == '_';
  }

  @Nullable
  private static String matchLeadingAsciiWord(String s, int start) {
    int len = s.length();
    int i = start;
    while (i < len && isAsciiAlphaOrUnderscore(s.charAt(i))) {
      i++;
    }
    if (i == start) {
      return null;
    }
    if (i < len && isAsciiWordChar(s.charAt(i))) {
      return null;
    }
    return s.substring(start, i);
  }

  @Nullable
  private static String matchLeadingDirectiveWord(String s, int pos) {
    int len = s.length();
    if (pos >= len) {
      return null;
    }
    int start = s.charAt(pos) == '#' ? pos + 1 : pos;
    int i = start;
    while (i < len && isAsciiAlphaOrUnderscore(s.charAt(i))) {
      i++;
    }
    if (i == start) {
      return null;
    }
    if (i < len && isAsciiWordChar(s.charAt(i))) {
      return null;
    }
    return s.substring(pos, i);
  }

  private static String matchQualifiedName(String s, int startPos) {
    int end = matchQualifiedNameEnd(s, startPos);
    return end > startPos ? s.substring(startPos, end) : "";
  }

  private static int matchQualifiedNameEnd(String s, int startPos) {
    int end = matchNameSegment(s, startPos);
    if (end < 0) {
      return -1;
    }
    int len = s.length();
    while (end < len && s.charAt(end) == '.') {
      int nextEnd = matchNameSegment(s, end + 1);
      if (nextEnd < 0) {
        break;
      }
      end = nextEnd;
    }
    return end;
  }

  private static int matchNameSegment(String s, int start) {
    int len = s.length();
    if (start >= len) {
      return -1;
    }
    if (s.charAt(start) == '«') {
      int close = s.indexOf('»', start + 1);
      return close > start + 1 ? close + 1 : -1;
    }
    int firstCp = s.codePointAt(start);
    if (!Character.isLetter(firstCp) && firstCp != '_') {
      return -1;
    }
    int pos = start + Character.charCount(firstCp);
    while (pos < len) {
      int cp = s.codePointAt(pos);
      if (!isQualifiedNamePart(cp)) {
        break;
      }
      pos += Character.charCount(cp);
    }
    return pos;
  }

  private static boolean isQualifiedNamePart(int cp) {
    int type = Character.getType(cp);
    boolean isNum =
        type == Character.DECIMAL_DIGIT_NUMBER
            || type == Character.LETTER_NUMBER
            || type == Character.OTHER_NUMBER;
    return Character.isLetter(cp)
        || isNum
        || cp == '_'
        || cp == '\''
        || cp == '?'
        || cp == '!'
        || (cp >= 0x2080 && cp <= 0x2089)
        || (cp >= 0x2070 && cp <= 0x2079);
  }

  private static int matchSimpleFieldOrCtorName(String s) {
    int len = s.length();
    if (len == 0) {
      return -1;
    }
    if (s.charAt(0) == '«') {
      int close = s.indexOf('»', 1);
      return close > 1 ? close + 1 : -1;
    }
    int firstCp = s.codePointAt(0);
    if (!Character.isLetter(firstCp) && firstCp != '_') {
      return -1;
    }
    int pos = Character.charCount(firstCp);
    while (pos < len) {
      int cp = s.codePointAt(pos);
      int type = Character.getType(cp);
      boolean isNum =
          type == Character.DECIMAL_DIGIT_NUMBER
              || type == Character.LETTER_NUMBER
              || type == Character.OTHER_NUMBER;
      if (!Character.isLetter(cp)
          && !isNum
          && cp != '_'
          && cp != '\''
          && !(cp >= 0x2080 && cp <= 0x2089)) {
        break;
      }
      pos += Character.charCount(cp);
    }
    return pos;
  }

  private static boolean isLeanWhitespace(int cp) {
    return cp == ' ' || cp == '\t' || cp == '\r' || cp == '\n';
  }

  private static boolean isBlankLeanLine(String s) {
    for (int idx = 0; idx < s.length(); idx++) {
      if (!isLeanWhitespace(s.charAt(idx))) {
        return false;
      }
    }
    return true;
  }

  private static String stripLeanWhitespace(String s) {
    int len = s.length();
    int start = 0;
    while (start < len && isLeanWhitespace(s.charAt(start))) {
      start++;
    }
    int end = len;
    while (end > start && isLeanWhitespace(s.charAt(end - 1))) {
      end--;
    }
    return (start == 0 && end == len) ? s : s.substring(start, end);
  }

  private static String stripTrailingLeanWhitespace(String s) {
    int end = s.length();
    while (end > 0 && isLeanWhitespace(s.charAt(end - 1))) {
      end--;
    }
    return end == s.length() ? s : s.substring(0, end);
  }

  private static String stripLeadingLeanWhitespace(String s) {
    int len = s.length();
    int start = 0;
    while (start < len && isLeanWhitespace(s.charAt(start))) {
      start++;
    }
    return start == 0 ? s : s.substring(start);
  }

  private static int skipLeanWhitespace(String s, int pos) {
    int len = s.length();
    while (pos < len && isLeanWhitespace(s.charAt(pos))) {
      pos++;
    }
    return pos;
  }

  private static boolean startsWithKeywordToken(String s, String kw) {
    return startsWithKeywordToken(s, 0, kw);
  }

  private static boolean startsWithKeywordToken(String s, int pos, String kw) {
    return s.startsWith(kw, pos)
        && (s.length() == pos + kw.length() || !isAsciiWordChar(s.charAt(pos + kw.length())));
  }

  private static boolean startsWithAnyKeywordToken(String s, List<String> keywords) {
    for (String kw : keywords) {
      if (startsWithKeywordToken(s, kw)) {
        return true;
      }
    }
    return false;
  }

  private static boolean startsWithKeywordTokenOrComment(String s, String kw) {
    return startsWithKeywordTokenOrComment(s, 0, kw);
  }

  private static boolean startsWithKeywordTokenOrComment(String s, int pos, String kw) {
    if (!s.startsWith(kw, pos)) {
      return false;
    }
    int end = pos + kw.length();
    if (s.length() == end) {
      return true;
    }
    char next = s.charAt(end);
    return isLeanWhitespace(next) || s.startsWith("--", end);
  }

  private static boolean hasNonWhitespaceFrom(String s, int pos) {
    int len = s.length();
    for (int p = pos; p < len; p++) {
      if (!isLeanWhitespace(s.charAt(p))) {
        return true;
      }
    }
    return false;
  }

  private static boolean startsWithWordOrEnd(String s, String word) {
    if (!s.startsWith(word)) {
      return false;
    }
    return s.length() == word.length() || isLeanWhitespace(s.charAt(word.length()));
  }

  private static boolean endsWithWord(String s, String word) {
    if (!s.endsWith(word)) {
      return false;
    }
    return s.length() == word.length()
        || !isAsciiWordChar(s.charAt(s.length() - word.length() - 1));
  }

  private static boolean containsWhereToken(String s) {
    int idx = 0;
    int len = s.length();
    while (idx <= len - "where".length()) {
      int found = s.indexOf("where", idx);
      if (found < 0) {
        return false;
      }
      boolean beforeOk = found == 0 || isWhereBoundaryBefore(s.charAt(found - 1));
      int after = found + "where".length();
      boolean afterOk = after == len || isLeanWhitespace(s.charAt(after));
      if (beforeOk && afterOk) {
        return true;
      }
      idx = found + 1;
    }
    return false;
  }

  private static boolean isWhereBoundaryBefore(char c) {
    return isLeanWhitespace(c)
        || c == '('
        || c == ')'
        || c == '['
        || c == ']'
        || c == '{'
        || c == '}';
  }

  private static int findStandaloneInEnd(String s, int fromIdx) {
    int idx = Math.max(0, fromIdx);
    int len = s.length();
    while (idx <= len - 2) {
      int found = s.indexOf("in", idx);
      if (found < 0) {
        return -1;
      }
      boolean beforeOk = found == 0 || isLeanWhitespace(s.charAt(found - 1));
      int after = found + 2;
      boolean afterOk = after == len || isLeanWhitespace(s.charAt(after));
      if (beforeOk && afterOk) {
        return after;
      }
      idx = found + 1;
    }
    return -1;
  }

  private static boolean isPureDecideBody(String cleanBody) {
    if ("decide".equals(cleanBody)) {
      return true;
    }
    if (!cleanBody.startsWith("by") || !cleanBody.endsWith("decide")) {
      return false;
    }
    int len = cleanBody.length();
    int decideStart = len - "decide".length();
    if (decideStart <= 2) {
      return false;
    }
    for (int i = 2; i < decideStart; i++) {
      if (!isLeanWhitespace(cleanBody.charAt(i))) {
        return false;
      }
    }
    return true;
  }

  private static String joinLines(List<String> lines, int fromInclusive, int toExclusive) {
    StringBuilder sb = new StringBuilder();
    for (int idx = fromInclusive; idx < toExclusive; idx++) {
      if (idx > fromInclusive) {
        sb.append('\n');
      }
      sb.append(lines.get(idx));
    }
    return sb.toString();
  }

  private static String joinLinesFromCol(
      List<String> lines, int fromInclusive, int toExclusive, int startCol) {
    StringBuilder sb = new StringBuilder();
    String first = lines.get(fromInclusive);
    sb.append(first, Math.min(startCol, first.length()), first.length());
    for (int idx = fromInclusive + 1; idx < toExclusive; idx++) {
      sb.append('\n').append(lines.get(idx));
    }
    return sb.toString();
  }

  private static String joinCodeLineTexts(List<LeanCodeLine> lines) {
    StringBuilder sb = new StringBuilder();
    for (int idx = 0; idx < lines.size(); idx++) {
      if (idx > 0) {
        sb.append('\n');
      }
      sb.append(lines.get(idx).text());
    }
    return sb.toString();
  }
}
