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
import com.google.common.collect.ImmutableSet;
import java.util.List;

/** Proof hygiene and declaration summary analysis for parsed Lean 4 source files. */
public final class LeanHygiene {

  private static final Splitter NEWLINE_SPLITTER = Splitter.on('\n');

  private static final int HAZARD_SORRY = 1;
  private static final int HAZARD_ADMIT = 1 << 1;
  private static final int HAZARD_AXIOM = 1 << 2;
  private static final int HAZARD_PARTIAL = 1 << 3;
  private static final int HAZARD_UNSAFE = 1 << 4;
  private static final int HAZARD_NATIVE_DECIDE = 1 << 5;

  private LeanHygiene() {}

  /**
   * Extracts all Lean identifier tokens from comment/string-stripped code using exact Lean
   * identifier boundaries (so {@code sorry'}, {@code sorry?}, {@code sorry₀}, and {@code αsorry} do
   * not match {@code sorry}).
   */
  public static ImmutableSet<String> extractLeanIdentifiers(String cleanCode) {
    requireNonNull(cleanCode, "cleanCode");
    ImmutableSet.Builder<String> ids = ImmutableSet.builder();
    int len = cleanCode.length();
    int i = 0;
    while (i < len) {
      int cp = cleanCode.codePointAt(i);
      int chLen = Character.charCount(cp);
      if (cp != '#' && LeanSyntax.isIdentStart(cp)) {
        int j = i + chLen;
        while (j < len) {
          int partCp = cleanCode.codePointAt(j);
          if (!LeanSyntax.isIdentPart(partCp)) {
            break;
          }
          j += Character.charCount(partCp);
        }
        ids.add(cleanCode.substring(i, j));
        i = j;
      } else {
        i += Math.max(1, chLen);
      }
    }
    return ids.build();
  }

  private static int scanHazardMask(String cleanCode) {
    int mask = 0;
    int len = cleanCode.length();
    int i = 0;
    while (i < len) {
      int cp = cleanCode.codePointAt(i);
      int chLen = Character.charCount(cp);
      if (cp != '#' && LeanSyntax.isIdentStart(cp)) {
        int j = i + chLen;
        while (j < len) {
          int partCp = cleanCode.codePointAt(j);
          if (!LeanSyntax.isIdentPart(partCp)) {
            break;
          }
          j += Character.charCount(partCp);
        }
        int tokLen = j - i;
        switch (tokLen) {
          case 5 -> {
            char c0 = cleanCode.charAt(i);
            if (c0 == 's' && cleanCode.regionMatches(i, "sorry", 0, 5)) {
              mask |= HAZARD_SORRY;
            } else if (c0 == 'a') {
              if (cleanCode.regionMatches(i, "admit", 0, 5)) {
                mask |= HAZARD_ADMIT;
              } else if (cleanCode.regionMatches(i, "axiom", 0, 5)) {
                mask |= HAZARD_AXIOM;
              }
            }
          }
          case 6 -> {
            if (cleanCode.charAt(i) == 'u' && cleanCode.regionMatches(i, "unsafe", 0, 6)) {
              mask |= HAZARD_UNSAFE;
            }
          }
          case 7 -> {
            char c0 = cleanCode.charAt(i);
            if (c0 == 's' && cleanCode.regionMatches(i, "sorryAx", 0, 7)) {
              mask |= HAZARD_SORRY;
            } else if (c0 == 'p' && cleanCode.regionMatches(i, "partial", 0, 7)) {
              mask |= HAZARD_PARTIAL;
            }
          }
          case 11 -> {
            if (cleanCode.charAt(i) == 'o' && cleanCode.regionMatches(i, "ofReduceNat", 0, 11)) {
              mask |= HAZARD_NATIVE_DECIDE;
            }
          }
          case 12 -> {
            if (cleanCode.charAt(i) == 'o' && cleanCode.regionMatches(i, "ofReduceBool", 0, 12)) {
              mask |= HAZARD_NATIVE_DECIDE;
            }
          }
          case 13 -> {
            if (cleanCode.charAt(i) == 'n' && cleanCode.regionMatches(i, "native_decide", 0, 13)) {
              mask |= HAZARD_NATIVE_DECIDE;
            }
          }
          default -> {}
        }
        i = j;
      } else {
        i += Math.max(1, chLen);
      }
    }
    return mask;
  }

  /** Analyzes declaration lines and attributes for proof-hygiene hazards. */
  public static LeanDeclHygiene analyzeDeclarationHygiene(
      LeanDeclKind kind,
      List<String> modifiers,
      List<String> pendingAttrs,
      List<LeanCodeLine> codeLines) {
    requireNonNull(kind, "kind");
    requireNonNull(modifiers, "modifiers");
    requireNonNull(pendingAttrs, "pendingAttrs");
    requireNonNull(codeLines, "codeLines");
    int hazardMask = 0;
    if (!pendingAttrs.isEmpty() || !codeLines.isEmpty()) {
      String rawText;
      if (pendingAttrs.isEmpty() && codeLines.size() == 1) {
        rawText = codeLines.get(0).text();
      } else if (pendingAttrs.size() == 1 && codeLines.isEmpty()) {
        rawText = pendingAttrs.get(0);
      } else {
        StringBuilder fullText = new StringBuilder();
        for (String attr : pendingAttrs) {
          if (fullText.length() > 0) {
            fullText.append('\n');
          }
          fullText.append(attr);
        }
        for (LeanCodeLine cl : codeLines) {
          if (fullText.length() > 0) {
            fullText.append('\n');
          }
          fullText.append(cl.text());
        }
        rawText = fullText.toString();
      }
      hazardMask = scanHazardMask(LeanLexer.stripLeanCommentsAndStringsForHygiene(rawText));
    }
    boolean isPartial =
        (!modifiers.isEmpty() && modifiers.contains("partial"))
            || (hazardMask & HAZARD_PARTIAL) != 0;
    boolean isUnsafe =
        (!modifiers.isEmpty() && modifiers.contains("unsafe")) || (hazardMask & HAZARD_UNSAFE) != 0;
    return new LeanDeclHygiene(
        (hazardMask & HAZARD_SORRY) != 0,
        (hazardMask & HAZARD_ADMIT) != 0,
        kind == LeanDeclKind.AXIOM || (hazardMask & HAZARD_AXIOM) != 0,
        isPartial,
        isUnsafe,
        (hazardMask & HAZARD_NATIVE_DECIDE) != 0);
  }

  /** Counts declarations and proof-hygiene hazards across parsed blocks ({@code countBlocks}). */
  public static LeanFileCounts count(List<LeanBlock> blocks) {
    requireNonNull(blocks, "blocks");
    int theorems = 0;
    int definitions = 0;
    int types = 0;
    int instances = 0;
    int axioms = 0;
    int sorryCount = 0;
    int admitCount = 0;
    int partialCount = 0;
    int unsafeCount = 0;
    int nativeDecideCount = 0;

    for (LeanBlock block : blocks) {
      if (block.type() == LeanBlockType.DECLARATION && block.decl() != null) {
        LeanDeclaration decl = block.decl();
        switch (decl.kind()) {
          case THEOREM, LEMMA, EXAMPLE -> theorems++;
          case DEF, ABBREV, OPAQUE -> definitions++;
          case STRUCTURE, CLASS, INDUCTIVE, COINDUCTIVE -> types++;
          case INSTANCE -> instances++;
          case AXIOM -> {
            // Counted via hygiene.isAxiom() below.
          }
        }
        LeanDeclHygiene hygiene = decl.hygiene();
        if (hygiene.isAxiom()) {
          axioms++;
        }
        if (hygiene.hasSorry()) {
          sorryCount++;
        }
        if (hygiene.hasAdmit()) {
          admitCount++;
        }
        if (hygiene.isPartial()) {
          partialCount++;
        }
        if (hygiene.isUnsafe()) {
          unsafeCount++;
        }
        if (hygiene.hasNativeDecide()) {
          nativeDecideCount++;
        }
      } else if (block.type() == LeanBlockType.DIRECTIVE) {
        // Fail-closed defense-in-depth: scan all non-comment/non-declaration code blocks so
        // soundness hazards cannot hide outside recognized declarations.
        String clean = LeanLexer.stripLeanCommentsAndStringsForHygiene(block.raw());
        int hazardMask = scanHazardMask(clean);
        if ((hazardMask & HAZARD_SORRY) != 0) {
          sorryCount++;
        }
        if ((hazardMask & HAZARD_ADMIT) != 0) {
          admitCount++;
        }
        if ((hazardMask & HAZARD_NATIVE_DECIDE) != 0) {
          nativeDecideCount++;
        }
        if ((hazardMask & HAZARD_AXIOM) != 0) {
          axioms++;
        }
        if ((hazardMask & HAZARD_UNSAFE) != 0) {
          unsafeCount++;
        }
        if ((hazardMask & HAZARD_PARTIAL) != 0) {
          partialCount++;
        }
      }
    }

    return new LeanFileCounts(
        theorems,
        definitions,
        types,
        instances,
        axioms,
        sorryCount,
        admitCount,
        partialCount,
        unsafeCount,
        nativeDecideCount);
  }

  /**
   * Returns whether the parsed blocks end inside an unterminated string, raw/interpolated string,
   * guillemet identifier, or block comment.
   */
  public static boolean hasUnterminatedLiteralOrComment(List<LeanBlock> blocks) {
    requireNonNull(blocks, "blocks");
    LeanLexer.LeanLexState state = LeanLexer.createLeanLexState();
    for (LeanBlock block : blocks) {
      if (block.type() == LeanBlockType.SECTION_BANNER) {
        continue;
      }
      for (String line : NEWLINE_SPLITTER.split(LeanLexer.normalizeLeanSource(block.raw()))) {
        LeanLexer.advanceLeanLexState(state, line);
      }
    }
    return state.hasOpenLiteralOrComment() || state.hasOpenInterpBrace();
  }
}
