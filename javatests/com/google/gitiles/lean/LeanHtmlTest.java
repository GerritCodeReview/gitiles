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

import static com.google.common.truth.Truth.assertThat;

import com.google.common.html.types.SafeHtml;
import com.google.gitiles.GitilesView;
import com.google.gitiles.Revision;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.eclipse.jgit.lib.Config;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link LeanHtml}. */
@RunWith(JUnit4.class)
public class LeanHtmlTest {
  private static final Pattern ID_ATTR = Pattern.compile("\\bid=\"([^\"]*)\"");

  private static GitilesView newPathView(String path) {
    return GitilesView.path()
        .setHostName("host")
        .setServletPath("/b")
        .setRepositoryName("repo")
        .setRevision("HEAD")
        .setPathPart(path)
        .build();
  }

  private static void assertAllIdsUnique(String html) {
    Matcher m = ID_ATTR.matcher(html);
    Set<String> seen = new HashSet<>();
    while (m.find()) {
      String id = m.group(1);
      assertThat(seen.add(id)).isTrue();
    }
  }

  @Test
  public void renderBlobRichlyRendersAllBlockTypesAndLineAnchors() {
    String source =
        """
        /- Copyright 2026 Google Inc. -/

        /-!
        # Module Title

        Module **overview** with [link](https://example.com) and ![img](local.png).
        -/

        import Mathlib.Data.Nat.Basic

        -- ========================================
        -- Helper Lemmas
        -- ========================================

        namespace Foo

        /-- Adds one to `n`. -/
        @[simp]
        theorem add_one_pos (n : Nat) : n + 1 > 0 := by
          omega

        def compactConst : Nat := 42

        structure Point where
          x : Nat := 0 -- horizontal coord
          y : Nat
          deriving Repr, BEq

        inductive Color where
          | red | green -- primary colors
          | blue : Color
        """;

    GitilesView view = newPathView("Foo/Bar.lean");
    SafeHtml safe =
        LeanHtml.renderBlob(
            source, new Config(), view, "/b/repo/+/HEAD/Foo/Bar.lean", "Foo/Bar.lean");
    assertThat(safe).isNotNull();
    String html = safe.getSafeHtmlString();

    assertAllIdsUnique(html);

    // Summary bar, verified pill, stats, [source] link
    assertThat(html).contains("class=\"LeanSummary\"");
    assertThat(html).contains("Lean 4");
    assertThat(html).contains("LeanPill--neutral");
    assertThat(html).contains("no sorry found (lexical scan)");
    assertThat(html).doesNotContain("LeanPill--verified");
    assertThat(html).contains("1 theorem");
    assertThat(html).contains("2 types");
    assertThat(html).contains("1 def");

    // License comment, module doc (rendered markdown without image loading), section banner
    assertThat(html).contains("LeanStrip--license");
    assertThat(html).contains("LeanModuleDoc");
    assertThat(html).contains("<h1>");
    assertThat(html).contains("<strong>overview</strong>");
    assertThat(html).contains("href=\"https://example.com\"");
    assertThat(html).contains("data:image/gif;base64,zSoyz");
    assertThat(html).contains("LeanBanner-title");
    assertThat(html).contains("Helper Lemmas");

    // Declaration cards, attributes, strategy chips, tokens
    assertThat(html).contains("LeanDecl-name\">add_one_pos</span>");
    assertThat(html).contains("LeanDecl-chip--attr\">@[simp]</span>");
    assertThat(html).contains("by tactic");
    assertThat(html).contains("LeanTok--keyword");
    assertThat(html).contains("LeanTok--tactic");
    assertThat(html).contains("LeanTok--type");
    assertThat(html).contains("LeanDecl--compact");
    assertThat(html).contains("LeanTable");
    assertThat(html).contains("horizontal coord");
    assertThat(html).contains("LeanDecl-derivingChip\">Repr</span>");
    assertThat(html).contains("LeanDecl-derivingChip\">BEq</span>");
    assertThat(html).contains("| red");
    assertThat(html).contains("| green");

    // Source line id="<n>" and <a href="#n">n</a> anchors
    assertThat(html).contains("id=\"1\"");
    assertThat(html).contains("href=\"#1\"");
    assertThat(html).contains("id=\"9\"");
    assertThat(html).contains("href=\"#9\"");
    assertThat(html).contains("id=\"19\"");
    assertThat(html).contains("href=\"#19\"");
    assertThat(html).contains("id=\"20\"");
    assertThat(html).contains("href=\"#20\"");
    assertThat(html).contains("id=\"27\"");
    assertThat(html).contains("href=\"#27\"");
  }

  @Test
  public void renderBlobHygieneChipsLinkToEachDeclarationStartLine() {
    String source =
        """
        axiom badAx : False

        theorem firstSorry : True := by
          sorry

        theorem secondSorry : True := by
          admit

        theorem usesNative : 1 = 1 := by
          native_decide

        partial def loop : Nat := loop

        unsafe def rawPtr : Nat := 0
        """;

    SafeHtml safe = LeanHtml.renderBlob(source, new Config(), null, null, "Hygiene.lean");
    assertThat(safe).isNotNull();
    String html = safe.getSafeHtmlString();
    assertAllIdsUnique(html);

    assertThat(html).doesNotContain("no sorry found");
    assertThat(html).contains("2 sorry");
    assertThat(html).contains("href=\"#3\"");
    assertThat(html).contains("href=\"#6\"");
    assertThat(html).contains("href=\"#1\"");
    assertThat(html).contains(">1 axiom</a>");
    assertThat(html).contains("href=\"#9\"");
    assertThat(html).contains(">1 native_decide</a>");
    assertThat(html).contains("href=\"#12\"");
    assertThat(html).contains(">1 partial</a>");
    assertThat(html).contains("href=\"#14\"");
    assertThat(html).contains(">1 unsafe</a>");
    assertThat(html).contains("LeanTok--sorry\">sorry</span>");
  }

  @Test
  public void renderBlobMarkdownDisabledUsesEscapedText() {
    String source =
        """
        /-!
        # Not Heading

        **not bold** <script>alert(1)</script>
        -/

        /-- `code` [link](https://example.com) -/
        def x : Nat := 1
        """;

    Config config = new Config();
    config.setBoolean("markdown", null, "render", false);

    SafeHtml safe =
        LeanHtml.renderBlob(source, config, newPathView("DocOff.lean"), null, "DocOff.lean");
    assertThat(safe).isNotNull();
    String html = safe.getSafeHtmlString();

    assertThat(html).doesNotContain("<h1>");
    assertThat(html).doesNotContain("<strong>");
    assertThat(html).doesNotContain("href=\"https://example.com\"");
    assertThat(html).contains("# Not Heading");
    assertThat(html).contains("**not bold**");
    assertThat(html).contains("&lt;script&gt;alert(1)&lt;/script&gt;");
  }

  @Test
  public void renderBlobUnicodeOperatorsAndSurrogatePairs() {
    String source =
        """
        theorem unicodeOpTest (α : Type) : ∀ x : α, x = x → True := by
          intro 𝔸 h
          trivial
        """;

    SafeHtml safe = LeanHtml.renderBlob(source, new Config(), null, null, "Unicode.lean");
    assertThat(safe).isNotNull();
    String html = safe.getSafeHtmlString();
    assertAllIdsUnique(html);

    assertThat(html).contains("LeanTok--operator LeanTok--unicodeOp");
    assertThat(html).contains("∀");
    assertThat(html).contains("→");
    assertThat(html).contains("𝔸");
  }

  @Test
  public void renderBlobSingleLineProofDoesNotDuplicateLineId() {
    String source = "theorem inlineProof : 1 = 1 := by rfl\n";
    SafeHtml safe = LeanHtml.renderBlob(source, new Config(), null, null, "Inline.lean");
    assertThat(safe).isNotNull();
    String html = safe.getSafeHtmlString();
    assertAllIdsUnique(html);
    assertThat(html).contains("id=\"1\"");
    assertThat(html).contains("href=\"#1\"");
  }

  @Test
  public void renderBlobHandlesEmptyCrlfBomAndUnclosedConstructs() {
    SafeHtml empty = LeanHtml.renderBlob("", new Config(), null, null, "Empty.lean");
    assertThat(empty).isNotNull();
    assertThat(empty.getSafeHtmlString()).contains("Lean 4");

    String bomCrlf = "\uFEFF-- comment\r\ndef x := \"unclosed\r\n/- unclosed block";
    SafeHtml safe = LeanHtml.renderBlob(bomCrlf, new Config(), null, null, "Malformed.lean");
    assertThat(safe).isNotNull();
    assertAllIdsUnique(safe.getSafeHtmlString());
    assertThat(safe.getSafeHtmlString()).contains("LeanTok--comment");
  }

  @Test
  public void renderBlobPreservesOpaqueMarkdownHtmlAndLinkReferenceDefs() {
    String source =
        """
        /-!
        See [docs][conv].

        <!-- module html comment -->

        <div>
        block line 1
        block line 2
        </div>

        Inline <span id="greeting">hello</span> world.

        [conv]: https://leanprover-community.github.io/ "Lean"
        [unused]: https://example.org/ref
        -/

        /-- Docstring <!-- inline --> and <T> value.

        [docref]: https://example.com/doc
        -/
        def greeting : Nat := 1
        """;
    SafeHtml safe = LeanHtml.renderBlob(source, new Config(), null, null, "Doc.lean");
    assertThat(safe).isNotNull();
    String html = safe.getSafeHtmlString();
    assertThat(html).contains("<a href=\"https://leanprover-community.github.io/\"");
    assertThat(html).contains("&lt;!-- module html comment --&gt;");
    assertThat(html).contains("&lt;div&gt;");
    assertThat(html).contains("block line 1");
    assertThat(html).contains("block line 2");
    assertThat(html).contains("&lt;span id=&quot;greeting&quot;&gt;hello&lt;/span&gt;");
    assertThat(html).contains("[conv]: https://leanprover-community.github.io/ &quot;Lean&quot;");
    assertThat(html).contains("[unused]: https://example.org/ref");
    assertThat(html).contains("&lt;!-- inline --&gt;");
    assertThat(html).contains("&lt;T&gt;");
    assertThat(html).contains("[docref]: https://example.com/doc");
    assertThat(html).doesNotContain("<!--");
    assertThat(html).doesNotContain("<span id=\"greeting\">");
  }

  @Test
  public void renderBlobDirectiveHygieneChipLinksInterpolatedHazardLine() {
    String source =
        """
        -- line 1
        -- line 2
        #eval dbg_trace "{sorry}"
        theorem ok : True := by trivial
        """;
    SafeHtml safe = LeanHtml.renderBlob(source, new Config(), null, null, "Hazard.lean");
    assertThat(safe).isNotNull();
    String html = safe.getSafeHtmlString();
    assertThat(html)
        .contains(
            "<a class=\"LeanPill LeanPill--error\" href=\"#3\" title=\"Jump to line 3\">1"
                + " sorry</a>");

    String twoDirectives =
        """
        -- line 1
        -- line 2
        #eval dbg_trace "{sorry}"
        #eval println! "{admit}"
        theorem ok : True := by trivial
        """;
    SafeHtml multi = LeanHtml.renderBlob(twoDirectives, new Config(), null, null, "Hazard2.lean");
    assertThat(multi).isNotNull();
    String multiHtml = multi.getSafeHtmlString();
    assertThat(multiHtml).contains("2 sorry");
    assertThat(multiHtml).contains("href=\"#3\"");
    assertThat(multiHtml).contains(">L3</a>");
    assertThat(multiHtml).contains("href=\"#4\"");
    assertThat(multiHtml).contains(">L4</a>");
  }

  @Test
  public void renderBlobHighlightsMultilineAndInterpolatedStringsWithState() {
    String source =
        """
        def msg : String := "line1
          /- not a comment
          sorry
        "

        def s : String := s!"prefix
        {sorry}
        suffix"

        def raw : String := r#"first
        /- still raw
        "#

        def «multi
        /- keyword def» : Nat := 1

        theorem afterStrings : True := by
          sorry
        """;
    SafeHtml safe = LeanHtml.renderBlob(source, new Config(), null, null, "Strings.lean");
    assertThat(safe).isNotNull();
    String html = safe.getSafeHtmlString();
    assertThat(html).contains("<span class=\"LeanTok--string\">  /- not a comment</span>");
    assertThat(html).contains("<span class=\"LeanTok--string\">  sorry</span>");
    assertThat(html).contains("<span class=\"LeanTok--sorry\">sorry</span>");
    assertThat(html).contains("<span class=\"LeanTok--string\">/- still raw</span>");
    assertThat(html).doesNotContain("<span class=\"LeanTok--comment\">  /- not a comment</span>");
    assertThat(html).doesNotContain("<span class=\"LeanTok--comment\">/- still raw</span>");
    assertThat(html).doesNotContain("<span class=\"LeanTok--comment\">/- keyword def»</span>");
  }

  @Test
  public void renderBlobRespectsNullAndInputOutputCaps() {
    assertThat(LeanHtml.renderBlob(null, new Config(), null, null, "Null.lean")).isNull();
    assertThat(
            LeanHtml.renderBlob(
                LeanRendering.MAX_BYTES + 1, "def x := 1", new Config(), null, null, "Big.lean"))
        .isNull();

    String tooManyLines = "def x := 1\n".repeat(LeanRendering.MAX_LINES + 1);
    assertThat(LeanHtml.renderBlob(tooManyLines, new Config(), null, null, "ManyLines.lean"))
        .isNull();

    // Under byte, line, and per-line caps (~600 KB, 20 lines), but dense tokens expand past
    // MAX_OUTPUT_CHARS.
    String denseTokens = ("·1".repeat(15_000) + "\n").repeat(20);
    assertThat(LeanHtml.renderBlob(denseTokens, new Config(), null, null, "Dense.lean")).isNull();

    String exactLineChars = "a".repeat(LeanRendering.MAX_LINE_CHARS);
    assertThat(LeanHtml.renderBlob(exactLineChars, new Config(), null, null, "ExactLine.lean"))
        .isNotNull();
    String overLineChars = "a".repeat(LeanRendering.MAX_LINE_CHARS + 1);
    assertThat(LeanHtml.renderBlob(overLineChars, new Config(), null, null, "OverLine.lean"))
        .isNull();
  }

  private static GitilesView newDiffView(String path) {
    return GitilesView.diff()
        .setHostName("host")
        .setServletPath("/b")
        .setRepositoryName("repo")
        .setOldRevision(Revision.named("HEAD~1"))
        .setRevision(Revision.named("HEAD"))
        .setPathPart(path)
        .build();
  }

  @Test
  public void renderDiffCollapsesUnchangedDeclarationsAndHighlightsEdits() {
    String left =
        """
        /-! Module A -/
        import Mathlib.Init

        theorem first_unchanged : 1 = 1 := by
          rfl

        theorem second_unchanged : 2 = 2 := by
          rfl

        /-- Old doc with *markdown*. -/
        theorem target_proof (n : Nat) : n + 0 = n := by
          exact Nat.add_zero n

        structure Config where
          timeout : Nat
          retries : Nat := 3 -- max retries
        deriving Repr

        theorem tail_unchanged : True := by
          trivial
        """;

    String right =
        """
        /-! Module A -/
        import Mathlib.Init

        theorem first_unchanged : 1 = 1 := by
          rfl

        theorem second_unchanged : 2 = 2 := by
          rfl

        /-- New doc with **bold**. -/
        theorem target_proof (n : Nat) : n + 0 = n := by
          sorry

        structure Config where
          timeout : Int
          retries : Nat := 3 -- retry limit
        deriving Repr, DecidableEq

        theorem added_helper : 3 = 3 := by
          rfl

        theorem tail_unchanged : True := by
          trivial
        """;

    SafeHtml safe =
        LeanHtml.renderDiff(
            left,
            right,
            new Config(),
            newDiffView("Main.lean"),
            "/b/repo/+diff/HEAD^!/Main.lean",
            "Main.lean");
    assertThat(safe).isNotNull();
    String html = safe.getSafeHtmlString();

    assertThat(html).contains("class=\"LeanDiff\"");
    assertThat(html).contains("class=\"LeanSummary\"");
    assertThat(html).contains("1 sorry");
    assertThat(html).contains("LeanSummary-deltas");
    assertThat(html).contains("+1");
    assertThat(html).contains("~2");
    assertThat(html).doesNotContain(">raw diff</a>");
    assertThat(html).doesNotContain("?lean=0");

    // Consecutive unchanged blocks collapse cleanly and do not leak unchanged theorem bodies.
    assertThat(html).contains("2 unchanged declarations");
    assertThat(html).contains("1 unchanged declaration");
    assertThat(html).doesNotContain("first_unchanged");
    assertThat(html).doesNotContain("second_unchanged");
    assertThat(html).doesNotContain("tail_unchanged");

    // Modified declaration preserves inline deltas, docstring diff, field diff, and deriving diff.
    assertThat(html).contains("LeanDecl-name\">target_proof</span>");
    assertThat(html).contains("LeanCode-line--deleted");
    assertThat(html).contains("LeanCode-line--added");
    assertThat(html).contains("LeanDiff-subrow--deleted");
    assertThat(html).contains("LeanDiff-subrow--added");
    assertThat(html).contains("<em>markdown</em>");
    assertThat(html).contains("<strong>bold</strong>");
    assertThat(html).contains("LeanDiff-inlineDel");
    assertThat(html).contains("LeanDiff-inlineAdd");
    assertThat(html).contains("LeanTable-row--deleted");
    assertThat(html).contains("LeanTable-row--added");
    assertThat(html).contains("DecidableEq");
    assertThat(html).contains("LeanDiff-block--added");
    assertThat(html).contains("added_helper");

    // Diff output emits no line anchor ids so multiple files on one page never collide.
    assertThat(ID_ATTR.matcher(html).find()).isFalse();
  }

  @Test
  public void renderDiffHandlesModifiedModuleDocBannerStripsAndMarkdownDisabled() {
    String left =
        """
        /-!
        # Old Header
        -/
        -- ========================================
        -- Old Section
        -- ========================================
        import Std.Data
        """;
    String right =
        """
        /-!
        # New Header
        -/
        -- ========================================
        -- New Section
        -- ========================================
        import Std.Data
        import Mathlib.Init
        """;

    Config mdDisabled = new Config();
    mdDisabled.setBoolean("markdown", null, "render", false);

    SafeHtml safe = LeanHtml.renderDiff(left, right, mdDisabled, null, null, "Doc.lean");
    assertThat(safe).isNotNull();
    String html = safe.getSafeHtmlString();
    assertThat(html).contains("LeanModuleDoc");
    assertThat(html).contains("<pre><code># Old Header</code></pre>");
    assertThat(html).contains("<pre><code># New Header</code></pre>");
    assertThat(html).contains("LeanBanner-title\">Old Section</h3>");
    assertThat(html).contains("LeanBanner-title\">New Section</h3>");
    assertThat(html).contains("LeanCode-line--added");
    assertThat(ID_ATTR.matcher(html).find()).isFalse();
  }

  @Test
  public void renderDiffHighlightsInlineTokenEditsOnModifiedCodeLines() {
    String left =
        """
        theorem helper (a b : Nat) : a + b = b + a := by
          exact Nat.add_comm a b
        """;
    String right =
        """
        theorem helper (a b : Nat) : a + b = b + a := by
          exact Nat.mul_comm a b
        """;
    SafeHtml safe = LeanHtml.renderDiff(left, right, new Config(), null, null, "Inline.lean");
    assertThat(safe).isNotNull();
    String html = safe.getSafeHtmlString();
    assertThat(html).contains("<span class=\"LeanDiff-inlineDel\">add_comm</span>");
    assertThat(html).contains("<span class=\"LeanDiff-inlineAdd\">mul_comm</span>");
  }

  @Test
  public void renderDiffHandlesEmptyWhitespaceNullOversizeCrlfAndSurrogates() {
    assertThat(LeanHtml.renderDiff(null, "def x := 1", new Config(), null, null, "A.lean"))
        .isNull();
    assertThat(LeanHtml.renderDiff("def x := 1", null, new Config(), null, null, "A.lean"))
        .isNull();
    assertThat(LeanHtml.renderDiff("   \n", "\n\n", new Config(), null, null, "Blank.lean"))
        .isNull();

    assertThat(
            LeanHtml.renderDiff(
                LeanRendering.MAX_BYTES + 1,
                "def x := 1",
                10,
                "def x := 2",
                new Config(),
                null,
                null,
                "BigA.lean"))
        .isNull();
    assertThat(
            LeanHtml.renderDiff(
                10,
                "def x := 1",
                LeanRendering.MAX_BYTES + 1,
                "def x := 2",
                new Config(),
                null,
                null,
                "BigB.lean"))
        .isNull();

    String tooManyLines = "def x := 1\n".repeat(LeanRendering.MAX_LINES + 1);
    assertThat(
            LeanHtml.renderDiff("def x := 1", tooManyLines, new Config(), null, null, "Lines.lean"))
        .isNull();

    String dense = "1+".repeat(500) + "\n";
    LeanRequestBudget denseBudget =
        LeanRequestBudget.forTesting(
            LeanRequestBudget.MAX_REQUEST_INPUT_BYTES, 4_000, 50, 750_000_000L, () -> 0L);
    assertThat(
            LeanHtml.renderDiff(
                0, "", dense.length(), dense, new Config(), null, null, "Dense.lean", denseBudget))
        .isNull();

    String exactLineChars = "a".repeat(LeanRendering.MAX_LINE_CHARS);
    assertThat(
            LeanHtml.renderDiff(
                "def x := 1", exactLineChars, new Config(), null, null, "ExactLine.lean"))
        .isNotNull();
    String overLineChars = "a".repeat(LeanRendering.MAX_LINE_CHARS + 1);
    assertThat(
            LeanHtml.renderDiff(
                "def x := 1", overLineChars, new Config(), null, null, "OverLine.lean"))
        .isNull();
    assertThat(
            LeanHtml.renderDiff(
                overLineChars, "def x := 1", new Config(), null, null, "OverLeftLine.lean"))
        .isNull();

    String left = "\uFEFF-- old 𝔸\r\ntheorem t (α : Type) : α → α := id\r\n";
    String right = "\uFEFF-- new 𝔹\r\ntheorem t (α : Type) : α → α := fun x => x\r\n";
    SafeHtml safe = LeanHtml.renderDiff(left, right, new Config(), null, null, "Unicode.lean");
    assertThat(safe).isNotNull();
    String html = safe.getSafeHtmlString();
    assertThat(html).contains("𝔸");
    assertThat(html).contains("𝔹");
    assertThat(html).contains("LeanTok--operator LeanTok--unicodeOp");
  }

  @Test
  public void renderBlobShowsUnterminatedWarningAndSuppressesNoSorryPill() {
    String unclosedString =
        """
        theorem ok : True := by trivial
        def s : String := "never closed
        theorem hidden : True := by sorry
        """;
    SafeHtml strSafe = LeanHtml.renderBlob(unclosedString, new Config(), null, null, "Str.lean");
    assertThat(strSafe).isNotNull();
    String strHtml = strSafe.getSafeHtmlString();
    assertThat(strHtml).contains("LeanPill--neutral");
    assertThat(strHtml).contains("unterminated string/comment \u2014 counts may be incomplete");
    assertThat(strHtml).doesNotContain("no sorry found");

    String unclosedComment = "theorem ok : True := by trivial\n/- unclosed block comment\n";
    SafeHtml cmtSafe = LeanHtml.renderBlob(unclosedComment, new Config(), null, null, "Cmt.lean");
    assertThat(cmtSafe).isNotNull();
    assertThat(cmtSafe.getSafeHtmlString())
        .contains("unterminated string/comment \u2014 counts may be incomplete");
    assertThat(cmtSafe.getSafeHtmlString()).doesNotContain("no sorry found");

    String closedSource = "def s : String := \"closed\"\ntheorem ok : True := by trivial\n";
    SafeHtml closedSafe = LeanHtml.renderBlob(closedSource, new Config(), null, null, "Ok.lean");
    assertThat(closedSafe).isNotNull();
    assertThat(closedSafe.getSafeHtmlString()).contains("no sorry found (lexical scan)");
    assertThat(closedSafe.getSafeHtmlString()).doesNotContain("unterminated string/comment");
  }

  @Test
  public void renderDiffShowsUnterminatedWarningOnActiveRevisionAndDelete() {
    String clean = "theorem ok : True := by trivial\n";
    String broken =
        """
        theorem ok : True := by trivial
        def bad : String := "unclosed
        theorem hidden : True := by sorry
        """;

    SafeHtml introduced =
        LeanHtml.renderDiff(clean, broken, new Config(), null, null, "Broken.lean");
    assertThat(introduced).isNotNull();
    String introducedHtml = introduced.getSafeHtmlString();
    assertThat(introducedHtml).contains("LeanPill--neutral");
    assertThat(introducedHtml)
        .contains("unterminated string/comment \u2014 counts may be incomplete");
    assertThat(introducedHtml).doesNotContain("no sorry found");

    SafeHtml deleted =
        LeanHtml.renderDiff(
            "theorem ok : True := by trivial\n/- unclosed\n",
            "",
            new Config(),
            null,
            null,
            "Deleted.lean");
    assertThat(deleted).isNotNull();
    assertThat(deleted.getSafeHtmlString())
        .contains("unterminated string/comment \u2014 counts may be incomplete");
    assertThat(deleted.getSafeHtmlString()).doesNotContain("no sorry found");

    SafeHtml fixed = LeanHtml.renderDiff(broken, clean, new Config(), null, null, "Fixed.lean");
    assertThat(fixed).isNotNull();
    assertThat(fixed.getSafeHtmlString()).contains("no sorry found (lexical scan)");
    assertThat(fixed.getSafeHtmlString()).doesNotContain("unterminated string/comment");
  }

  @Test
  public void renderDiffReflowedMarkdownAndHtmlCommentsNeverEmitIdenticalSubrows() {
    String leftReflow =
        """
        /-!
        First
        second
        -/
        /-- Doc
        wrap. -/
        def f : Nat := 1
        """;
    String rightReflow =
        """
        /-!
        First second
        -/
        /-- Doc wrap. -/
        def f : Nat := 1
        """;
    SafeHtml reflowSafe =
        LeanHtml.renderDiff(leftReflow, rightReflow, new Config(), null, null, "Reflow.lean");
    assertThat(reflowSafe).isNotNull();
    String reflowHtml = reflowSafe.getSafeHtmlString();
    assertThat(reflowHtml)
        .doesNotContain("<div class=\"LeanDiff-subrow LeanDiff-subrow--deleted\"><p>");
    assertThat(reflowHtml).contains("First");
    assertThat(reflowHtml).contains("second");
    assertThat(reflowHtml).contains("<pre><code> Doc\nwrap. </code></pre>");
    assertThat(reflowHtml).contains("<pre><code>Doc wrap.</code></pre>");

    String leftOpaque =
        """
        /-!
        <!-- old comment -->
        Inline <span id="a">x</span>

        [ref]: https://old.example
        -/
        /-- <!-- decl old --> -/
        def g : Nat := 1
        """;
    String rightOpaque =
        """
        /-!
        <!-- new comment -->
        Inline <span id="b">x</span>

        [ref]: https://new.example
        -/
        /-- <!-- decl new --> -/
        def g : Nat := 1
        """;
    SafeHtml opaqueSafe =
        LeanHtml.renderDiff(leftOpaque, rightOpaque, new Config(), null, null, "Opaque.lean");
    assertThat(opaqueSafe).isNotNull();
    String opaqueHtml = opaqueSafe.getSafeHtmlString();
    assertThat(opaqueHtml).contains("&lt;!-- old comment --&gt;");
    assertThat(opaqueHtml).contains("&lt;!-- new comment --&gt;");
    assertThat(opaqueHtml).contains("&lt;span id=&quot;a&quot;&gt;x&lt;/span&gt;");
    assertThat(opaqueHtml).contains("&lt;span id=&quot;b&quot;&gt;x&lt;/span&gt;");
    assertThat(opaqueHtml).contains("[ref]: https://old.example");
    assertThat(opaqueHtml).contains("[ref]: https://new.example");
    assertThat(opaqueHtml).contains("&lt;!-- decl old --&gt;");
    assertThat(opaqueHtml).contains("&lt;!-- decl new --&gt;");
  }

  @Test
  public void renderDiffHighlightsMultilineAndInterpolatedStringsAcrossAlignedAndRawBlocks() {
    String left =
        """
        def f : IO Unit := do
          let msg := "start
          /- old inside string
          sorry
        "
          trivial

        #eval IO.println "d1
          /- dir old
        "

        #eval IO.println "raw1
          /- raw line
        "
        """;
    String right =
        """
        def f : IO Unit := do
          let msg := "start
          /- new inside string
          sorry
        "
          sorry

        #eval IO.println "d2
          /- dir new
        "

        #eval  IO.println "raw1
          /- raw line
        "
        """;
    SafeHtml safe = LeanHtml.renderDiff(left, right, new Config(), null, null, "StrDiff.lean");
    assertThat(safe).isNotNull();
    String html = safe.getSafeHtmlString();
    assertThat(html).contains("<span class=\"LeanTok--string\">  /- </span>");
    assertThat(html)
        .contains(
            "<span class=\"LeanDiff-inlineDel\"><span class=\"LeanTok--string\">old</span></span>");
    assertThat(html)
        .contains(
            "<span class=\"LeanDiff-inlineAdd\"><span class=\"LeanTok--string\">new</span></span>");
    assertThat(html).contains("<span class=\"LeanTok--string\">  sorry</span>");
    assertThat(html).contains("<span class=\"LeanTok--sorry\">sorry</span>");
    assertThat(html).contains("<span class=\"LeanTok--string\">  /- dir </span>");
    assertThat(html).contains("<span class=\"LeanTok--string\">  /- raw line</span>");
    assertThat(html).doesNotContain("LeanTok--comment");
  }
}
