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
}
