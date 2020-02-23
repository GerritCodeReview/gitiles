// Copyright (C) 2009 The Android Open Source Project
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

package com.google.gitiles.diff;

import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.eclipse.jgit.diff.Edit;
import org.eclipse.jgit.diff.EditList;
import org.eclipse.jgit.diff.MyersDiff;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.diff.Sequence;
import org.eclipse.jgit.diff.SequenceComparator;

/**
 * Computes intraline (within-line) diffs for a pair of texts.
 *
 * <p>Given the line-level {@link Edit}s between two {@link RawText}s, {@link #compute} refines each
 * {@link Edit.Type#REPLACE} edit into a {@link ReplaceEdit} that carries character-level edits for
 * the replaced region, so callers can highlight exactly which characters changed within a line.
 *
 * <p>The algorithm is ported from Gerrit's {@code IntraLineLoader}: it runs JGit's {@link
 * MyersDiff} over the characters of each replaced region and applies the same coalescing and
 * boundary heuristics. It is a pure, stateless function; because the character diff is O(N*D),
 * callers that need to bound its runtime should do so at the call site (Gerrit and Gitiles both
 * wrap it in a timeout).
 */
public final class IntraLineDiff {
  private static final Pattern BLANK_LINE_RE =
      Pattern.compile("^[ \\t]*(|[{}]|/\\*\\*?|\\*)[ \\t]*$");
  private static final Pattern CONTROL_BLOCK_START_RE = Pattern.compile("[{:][ \\t]*$");

  /**
   * Refines the line-level {@code edits} between {@code aText} and {@code bText} with
   * character-level intraline edits.
   *
   * @param aText the pre-image text
   * @param bText the post-image text
   * @param edits the line-level edits between {@code aText} and {@code bText}
   * @return a copy of {@code edits} in which every {@link Edit.Type#REPLACE} edit is a {@link
   *     ReplaceEdit} carrying its internal character-level edits; other edits are unchanged
   */
  public static ImmutableList<Edit> compute(RawText aText, RawText bText, List<Edit> edits) {
    return compute(aText, bText, edits, Set.of());
  }

  /**
   * Like {@link #compute(RawText, RawText, List)}, but never merges the edits in {@code
   * editsToKeepSeparate} with their neighbours.
   *
   * @param aText the pre-image text
   * @param bText the post-image text
   * @param edits the line-level edits between {@code aText} and {@code bText}
   * @param editsToKeepSeparate edits (for example, edits due to rebase) that must not be coalesced
   *     with adjacent edits
   * @return a copy of {@code edits} in which every {@link Edit.Type#REPLACE} edit is a {@link
   *     ReplaceEdit} carrying its internal character-level edits; other edits are unchanged
   */
  public static ImmutableList<Edit> compute(
      RawText aText, RawText bText, List<Edit> edits, Set<Edit> editsToKeepSeparate) {
    List<Edit> result = new ArrayList<>(edits);
    combineLineEdits(result, editsToKeepSeparate, aText, bText);

    for (int i = 0; i < result.size(); i++) {
      Edit e = result.get(i);
      if (e.getType() == Edit.Type.REPLACE) {
        CharText a = new CharText(aText, e.getBeginA(), e.getEndA());
        CharText b = new CharText(bText, e.getBeginB(), e.getEndB());
        result.set(i, new ReplaceEdit(e, computeInternalEdits(a, b)));
      }
    }

    return ImmutableList.copyOf(result);
  }

  private static List<Edit> computeInternalEdits(CharText a, CharText b) {
    CharTextComparator cmp = new CharTextComparator();
    List<Edit> wordEdits = MyersDiff.INSTANCE.diff(cmp, a, b);

    // Combine edits that are really close together. If they are
    // just a few characters apart we tend to get better results
    // by joining them together and taking the whole span.
    //
    for (int j = 0; j < wordEdits.size() - 1; ) {
      Edit c = wordEdits.get(j);
      Edit n = wordEdits.get(j + 1);

      if (n.getBeginA() - c.getEndA() <= 5 || n.getBeginB() - c.getEndB() <= 5) {
        int ab = c.getBeginA();
        int ae = n.getEndA();

        int bb = c.getBeginB();
        int be = n.getEndB();

        if (canCoalesce(a, c.getEndA(), n.getBeginA())
            && canCoalesce(b, c.getEndB(), n.getBeginB())) {
          wordEdits.set(j, new Edit(ab, ae, bb, be));
          wordEdits.remove(j + 1);
          continue;
        }
      }

      j++;
    }

    // Apply some simple rules to fix up some of the edits. Our
    // logic above, along with our per-character difference tends
    // to produce some crazy stuff.
    //
    for (int j = 0; j < wordEdits.size(); j++) {
      Edit c = wordEdits.get(j);
      int ab = c.getBeginA();
      int ae = c.getEndA();

      int bb = c.getBeginB();
      int be = c.getEndB();

      // Sometimes the diff generator produces an INSERT or DELETE
      // right up against a REPLACE, but we only find this after
      // we've also played some shifting games on the prior edit.
      // If that happened to us, coalesce them together so we can
      // correct this mess for the user. If we don't we wind up
      // with silly stuff like "es" -> "es = Addresses".
      //
      if (1 < j) {
        Edit p = wordEdits.get(j - 1);
        if (p.getEndA() == ab || p.getEndB() == bb) {
          if (p.getEndA() == ab && p.getBeginA() < p.getEndA()) {
            ab = p.getBeginA();
          }
          if (p.getEndB() == bb && p.getBeginB() < p.getEndB()) {
            bb = p.getBeginB();
          }
          wordEdits.remove(--j);
        }
      }

      // We sometimes collapsed an edit together in a strange way,
      // such that the edges of each text is identical. Fix by
      // by dropping out that incorrectly replaced region.
      //
      while (ab < ae && bb < be && cmp.equals(a, ab, b, bb)) {
        ab++;
        bb++;
      }
      while (ab < ae && bb < be && cmp.equals(a, ae - 1, b, be - 1)) {
        ae--;
        be--;
      }

      // The leading part of an edit and its trailing part in the same
      // text might be identical. Slide down that edit and use the tail
      // rather than the leading bit.
      //
      while (0 < ab && ab < ae && a.charAt(ab - 1) != '\n' && cmp.equals(a, ab - 1, a, ae - 1)) {
        ab--;
        ae--;
      }
      if (!a.isLineStart(ab) || !a.contains(ab, ae, '\n')) {
        while (ab < ae && ae < a.size() && cmp.equals(a, ab, a, ae)) {
          ab++;
          ae++;
          if (a.charAt(ae - 1) == '\n') {
            break;
          }
        }
      }

      while (0 < bb && bb < be && b.charAt(bb - 1) != '\n' && cmp.equals(b, bb - 1, b, be - 1)) {
        bb--;
        be--;
      }
      if (!b.isLineStart(bb) || !b.contains(bb, be, '\n')) {
        while (bb < be && be < b.size() && cmp.equals(b, bb, b, be)) {
          bb++;
          be++;
          if (b.charAt(be - 1) == '\n') {
            break;
          }
        }
      }

      // If most of a line was modified except the LF was common, make
      // the LF part of the modification region. This is easier to read.
      //
      if (ab < ae
          && (ab == 0 || a.charAt(ab - 1) == '\n')
          && ae < a.size()
          && a.charAt(ae - 1) != '\n'
          && a.charAt(ae) == '\n') {
        ae++;
      }
      if (bb < be
          && (bb == 0 || b.charAt(bb - 1) == '\n')
          && be < b.size()
          && b.charAt(be - 1) != '\n'
          && b.charAt(be) == '\n') {
        be++;
      }

      wordEdits.set(j, new Edit(ab, ae, bb, be));
    }

    // Validate that the intra-line edits applied to the "a" text produces the "b" text. If this
    // check fails, fallback to a single replace edit that covers the whole area.
    if (isValidTransformation(a, b, wordEdits)) {
      return wordEdits;
    }
    return EditList.singleton(new Edit(0, a.size(), 0, b.size()));
  }

  /**
   * Validate that the application of the list of {@code edits} to the {@code lText} text produces
   * the {@code rText} text.
   *
   * @return true if {@code lText} + {@code edits} results in the {@code rText} text, and false
   *     otherwise.
   */
  private static boolean isValidTransformation(CharText lText, CharText rText, List<Edit> edits) {
    // Apply replace and delete edits to the left text
    Optional<String> left =
        applyEditsToString(
            new StringBuilder(lText.content),
            rText.content,
            edits.stream()
                .filter(e -> e.getType() == Edit.Type.REPLACE || e.getType() == Edit.Type.DELETE)
                .collect(Collectors.toList()));
    // Remove insert edits from the right text
    Optional<String> right =
        applyEditsToString(
            new StringBuilder(rText.content),
            null,
            edits.stream()
                .filter(e -> e.getType() == Edit.Type.INSERT)
                .collect(Collectors.toList()));

    return left.isPresent() && right.isPresent() && left.get().contentEquals(right.get());
  }

  /**
   * Apply edits to the {@code target} string. Replace edits are applied to target and replaced with
   * a substring from {@code from}. Delete edits are applied to target. Insert edits are removed
   * from target.
   *
   * @return Optional containing the transformed string, or empty if the transformation fails (due
   *     to index out of bounds).
   */
  private static Optional<String> applyEditsToString(
      StringBuilder target, String from, List<Edit> edits) {
    // Process edits right to left to avoid re-computation of indices when characters are removed.
    try {
      for (int i = edits.size() - 1; i >= 0; i--) {
        Edit edit = edits.get(i);
        if (edit.getType() == Edit.Type.REPLACE) {
          boundaryCheck(target, edit.getBeginA(), edit.getEndA() - 1);
          boundaryCheck(from, edit.getBeginB(), edit.getEndB() - 1);
          target.replace(
              edit.getBeginA(), edit.getEndA(), from.substring(edit.getBeginB(), edit.getEndB()));
        } else if (edit.getType() == Edit.Type.DELETE) {
          boundaryCheck(target, edit.getBeginA(), edit.getEndA() - 1);
          target.delete(edit.getBeginA(), edit.getEndA());
        } else if (edit.getType() == Edit.Type.INSERT) {
          boundaryCheck(target, edit.getBeginB(), edit.getEndB() - 1);
          target.delete(edit.getBeginB(), edit.getEndB());
        }
      }
      return Optional.of(target.toString());
    } catch (StringIndexOutOfBoundsException unused) {
      return Optional.empty();
    }
  }

  private static void boundaryCheck(StringBuilder s, int i1, int i2) {
    if (i1 >= 0 && i2 >= 0 && i1 < s.length() && i2 < s.length()) {
      return;
    }
    throw new StringIndexOutOfBoundsException();
  }

  private static void boundaryCheck(String s, int i1, int i2) {
    if (i1 >= 0 && i2 >= 0 && i1 < s.length() && i2 < s.length()) {
      return;
    }
    throw new StringIndexOutOfBoundsException();
  }

  private static void combineLineEdits(
      List<Edit> edits, Set<Edit> editsToKeepSeparate, RawText a, RawText b) {
    for (int j = 0; j < edits.size() - 1; ) {
      Edit c = edits.get(j);
      Edit n = edits.get(j + 1);

      if (editsToKeepSeparate.contains(c) || editsToKeepSeparate.contains(n)) {
        // Don't combine any edits which were identified as being introduced by a rebase as we would
        // lose that information because of the combination.
        j++;
        continue;
      }

      // Combine edits that are really close together. Right now our rule
      // is, coalesce two line edits which are only one line apart if that
      // common context line is either a "pointless line", or is identical
      // on both sides and starts a new block of code. These are mostly
      // block reindents to add or remove control flow operators.
      //
      final int ad = n.getBeginA() - c.getEndA();
      final int bd = n.getBeginB() - c.getEndB();
      if ((1 <= ad && isBlankLineGap(a, c.getEndA(), n.getBeginA()))
          || (1 <= bd && isBlankLineGap(b, c.getEndB(), n.getBeginB()))
          || (ad == 1 && bd == 1 && isControlBlockStart(a, c.getEndA()))) {
        int ab = c.getBeginA();
        int ae = n.getEndA();

        int bb = c.getBeginB();
        int be = n.getEndB();

        edits.set(j, new Edit(ab, ae, bb, be));
        edits.remove(j + 1);
        continue;
      }

      j++;
    }
  }

  private static boolean isBlankLineGap(RawText a, int b, int e) {
    for (; b < e; b++) {
      if (!BLANK_LINE_RE.matcher(a.getString(b)).matches()) {
        return false;
      }
    }
    return true;
  }

  private static boolean isControlBlockStart(RawText a, int idx) {
    return CONTROL_BLOCK_START_RE.matcher(a.getString(idx)).find();
  }

  private static boolean canCoalesce(CharText a, int b, int e) {
    while (b < e) {
      if (a.charAt(b++) == '\n') {
        return false;
      }
    }
    return true;
  }

  private IntraLineDiff() {}

  /** A {@link Sequence} over the characters of a line range of a {@link RawText}. */
  private static final class CharText extends Sequence {
    private final String content;

    CharText(RawText text, int s, int e) {
      content = text.getString(s, e, false);
    }

    char charAt(int idx) {
      return content.charAt(idx);
    }

    boolean isLineStart(int b) {
      return b == 0 || charAt(b - 1) == '\n';
    }

    boolean contains(int b, int e, char c) {
      for (; b < e; b++) {
        if (charAt(b) == c) {
          return true;
        }
      }
      return false;
    }

    @Override
    public int size() {
      return content.length();
    }
  }

  /** Compares two {@link CharText}s a single character at a time. */
  private static final class CharTextComparator extends SequenceComparator<CharText> {
    @Override
    public boolean equals(CharText a, int ai, CharText b, int bi) {
      return a.charAt(ai) == b.charAt(bi);
    }

    @Override
    public int hash(CharText seq, int ptr) {
      return seq.charAt(ptr);
    }
  }
}
