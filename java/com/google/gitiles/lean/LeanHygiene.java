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

import com.google.common.collect.ImmutableSet;
import java.util.List;

/** Proof hygiene and declaration summary analysis for parsed Lean 4 source files. */
public final class LeanHygiene {

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
    String cleanCode = LeanLexer.stripLeanCommentsAndStrings(fullText.toString());
    ImmutableSet<String> ids = extractLeanIdentifiers(cleanCode);
    return new LeanDeclHygiene(
        ids.contains("sorry") || ids.contains("sorryAx"),
        ids.contains("admit"),
        kind == LeanDeclKind.AXIOM || ids.contains("axiom"),
        modifiers.contains("partial") || ids.contains("partial"),
        modifiers.contains("unsafe") || ids.contains("unsafe"),
        ids.contains("native_decide")
            || ids.contains("ofReduceBool")
            || ids.contains("ofReduceNat"));
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
        String clean = LeanLexer.stripLeanCommentsAndStrings(block.raw());
        ImmutableSet<String> ids = extractLeanIdentifiers(clean);
        if (ids.contains("sorry") || ids.contains("sorryAx")) {
          sorryCount++;
        }
        if (ids.contains("admit")) {
          admitCount++;
        }
        if (ids.contains("native_decide")
            || ids.contains("ofReduceBool")
            || ids.contains("ofReduceNat")) {
          nativeDecideCount++;
        }
        if (ids.contains("axiom")) {
          axioms++;
        }
        if (ids.contains("unsafe")) {
          unsafeCount++;
        }
        if (ids.contains("partial")) {
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
}
