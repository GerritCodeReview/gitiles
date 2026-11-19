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
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.collect.ImmutableList;
import com.google.common.io.Resources;
import com.google.gitiles.GitilesView;
import com.google.gitiles.Renderer;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.lib.Config;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link LeanRendering}. */
@RunWith(JUnit4.class)
public class LeanRenderingTest {
  private static GitilesView.Builder newPathView() {
    return GitilesView.path()
        .setHostName("host")
        .setServletPath("/b")
        .setRepositoryName("repo")
        .setRevision("HEAD")
        .setPathPart("Main.lean");
  }

  @Test
  public void capsMatchExpectedLimits() {
    assertThat(
            ImmutableList.of(
                LeanRendering.MAX_BYTES, LeanRendering.MAX_LINES, LeanRendering.MAX_OUTPUT_CHARS))
        .containsExactly(2_097_152, 20_000, 16_777_216)
        .inOrder();
  }

  @Test
  public void configEnabledDefaultsToTrueAndRespectsFlag() {
    assertThat(LeanRendering.isEnabled((Config) null)).isTrue();
    Config empty = new Config();
    assertThat(LeanRendering.isEnabled(empty)).isTrue();

    Config enabled = new Config();
    enabled.setBoolean("gitiles", null, "leanRendering", true);
    assertThat(LeanRendering.isEnabled(enabled)).isTrue();

    Config disabled = new Config();
    disabled.setBoolean("gitiles", null, "leanRendering", false);
    assertThat(LeanRendering.isEnabled(disabled)).isFalse();

    Config subsectionOnly = new Config();
    subsectionOnly.setBoolean("gitiles", "other", "leanRendering", false);
    assertThat(LeanRendering.isEnabled(subsectionOnly)).isTrue();
  }

  @Test
  public void requestCheckFalseOnlyWhenLeanParamIsZero() {
    assertThat(LeanRendering.isRequested(null)).isTrue();
    assertThat(LeanRendering.isRequested(newPathView().build())).isTrue();
    assertThat(LeanRendering.isRequested(newPathView().putParam("lean", "1").build())).isTrue();
    assertThat(LeanRendering.isRequested(newPathView().putParam("lean", "true").build())).isTrue();
    assertThat(LeanRendering.isRequested(newPathView().putParam("lean", "false").build())).isTrue();
    assertThat(LeanRendering.isRequested(newPathView().putParam("lean", "").build())).isTrue();
    assertThat(LeanRendering.isRequested(newPathView().putParam("other", "0").build())).isTrue();

    assertThat(LeanRendering.isRequested(newPathView().putParam("lean", "0").build())).isFalse();
    assertThat(
            LeanRendering.isRequested(
                newPathView().putParam("lean", "1").putParam("lean", "0").build()))
        .isFalse();
    assertThat(
            LeanRendering.isRequested(
                newPathView().putParam("lean", "0").putParam("lean", "1").build()))
        .isFalse();
  }

  @Test
  public void combinedConfigAndRequestCheck() {
    Config enabled = new Config();
    Config disabled = new Config();
    disabled.setBoolean("gitiles", null, "leanRendering", false);

    GitilesView normal = newPathView().build();
    GitilesView optOut = newPathView().putParam("lean", "0").build();
    GitilesView optIn = newPathView().putParam("lean", "1").build();

    assertThat(LeanRendering.isEnabled(enabled, normal)).isTrue();
    assertThat(LeanRendering.isEnabled(enabled, optIn)).isTrue();
    assertThat(LeanRendering.isEnabled(enabled, optOut)).isFalse();
    assertThat(LeanRendering.isEnabled(disabled, normal)).isFalse();
    assertThat(LeanRendering.isEnabled(disabled, optIn)).isFalse();
    assertThat(LeanRendering.isEnabled(disabled, optOut)).isFalse();
  }

  @Test
  public void isLeanPathMatchesLeanExtensionOnly() {
    assertThat(LeanRendering.isLeanPath("Main.lean")).isTrue();
    assertThat(LeanRendering.isLeanPath("Mathlib/Algebra/Group/Basic.lean")).isTrue();
    assertThat(LeanRendering.isLeanPath(".lean")).isTrue();

    assertThat(LeanRendering.isLeanPath(null)).isFalse();
    assertThat(LeanRendering.isLeanPath("")).isFalse();
    assertThat(LeanRendering.isLeanPath("lean")).isFalse();
    assertThat(LeanRendering.isLeanPath("Main.lean/")).isFalse();
    assertThat(LeanRendering.isLeanPath("Main.lean.bak")).isFalse();
    assertThat(LeanRendering.isLeanPath("Main.LEAN")).isFalse();
    assertThat(LeanRendering.isLeanPath("README.md")).isFalse();
  }

  @Test
  public void withinInputCapsEnforcesBytesAndLines() {
    assertThat(LeanRendering.withinInputCaps(0, "")).isTrue();
    assertThat(LeanRendering.withinInputCaps(1, "x")).isTrue();
    assertThat(LeanRendering.withinInputCaps(LeanRendering.MAX_BYTES, "x")).isTrue();
    assertThat(LeanRendering.withinInputCaps(LeanRendering.MAX_BYTES + 1, "x")).isFalse();
    assertThat(LeanRendering.withinInputCaps(-1, "x")).isFalse();
    assertThat(LeanRendering.withinInputCaps(0, null)).isFalse();

    String exactLinesTrailingNewline = "a\n".repeat(LeanRendering.MAX_LINES);
    assertThat(
            LeanRendering.withinInputCaps(
                exactLinesTrailingNewline.length(), exactLinesTrailingNewline))
        .isTrue();

    String exactLinesNoTrailingNewline = "a\n".repeat(LeanRendering.MAX_LINES - 1) + "a";
    assertThat(
            LeanRendering.withinInputCaps(
                exactLinesNoTrailingNewline.length(), exactLinesNoTrailingNewline))
        .isTrue();

    String oneTooManyTrailingNewline = "a\n".repeat(LeanRendering.MAX_LINES + 1);
    assertThat(
            LeanRendering.withinInputCaps(
                oneTooManyTrailingNewline.length(), oneTooManyTrailingNewline))
        .isFalse();

    String oneTooManyNoTrailingNewline = "a\n".repeat(LeanRendering.MAX_LINES) + "a";
    assertThat(
            LeanRendering.withinInputCaps(
                oneTooManyNoTrailingNewline.length(), oneTooManyNoTrailingNewline))
        .isFalse();

    String exactCrlf = "a\r\n".repeat(LeanRendering.MAX_LINES);
    assertThat(LeanRendering.withinInputCaps(exactCrlf.length(), exactCrlf)).isTrue();
    String tooManyCrlf = exactCrlf + "a";
    assertThat(LeanRendering.withinInputCaps(tooManyCrlf.length(), tooManyCrlf)).isFalse();

    String exactCr = "a\r".repeat(LeanRendering.MAX_LINES);
    assertThat(LeanRendering.withinInputCaps(exactCr.length(), exactCr)).isTrue();
    String tooManyCr = exactCr + "a";
    assertThat(LeanRendering.withinInputCaps(tooManyCr.length(), tooManyCr)).isFalse();
  }

  @Test
  public void withinInputCapsRawTextEnforcesBytesAndLines() {
    assertThat(LeanRendering.withinInputCaps((RawText) null)).isFalse();
    assertThat(LeanRendering.withinInputCaps(RawText.EMPTY_TEXT)).isTrue();

    byte[] exactLinesBytes = "a\n".repeat(LeanRendering.MAX_LINES).getBytes(UTF_8);
    assertThat(LeanRendering.withinInputCaps(new RawText(exactLinesBytes))).isTrue();

    byte[] tooManyLinesBytes = "a\n".repeat(LeanRendering.MAX_LINES + 1).getBytes(UTF_8);
    assertThat(LeanRendering.withinInputCaps(new RawText(tooManyLinesBytes))).isFalse();

    byte[] maxBytes = new byte[LeanRendering.MAX_BYTES];
    maxBytes[0] = 'a';
    assertThat(LeanRendering.withinInputCaps(new RawText(maxBytes))).isTrue();

    byte[] overMaxBytes = new byte[LeanRendering.MAX_BYTES + 1];
    overMaxBytes[0] = 'a';
    assertThat(LeanRendering.withinInputCaps(new RawText(overMaxBytes))).isFalse();
  }

  @Test
  public void withinOutputCapChecksBounds() {
    assertThat(LeanRendering.withinOutputCap(-1)).isFalse();
    assertThat(LeanRendering.withinOutputCap(0)).isTrue();
    assertThat(LeanRendering.withinOutputCap(LeanRendering.MAX_OUTPUT_CHARS)).isTrue();
    assertThat(LeanRendering.withinOutputCap(LeanRendering.MAX_OUTPUT_CHARS + 1)).isFalse();
  }

  @Test
  public void rendererStaticUrlGlobalsContainsLeanCss() {
    assertThat(Renderer.STATIC_URL_GLOBALS).containsEntry("gitiles.LEAN_CSS_URL", "lean.css");
  }

  @Test
  public void leanCssDefinesThemesAndAllTokenClasses() throws Exception {
    String css =
        Resources.toString(Resources.getResource(Renderer.class, "static/lean.css"), UTF_8);
    assertThat(css).contains(":root,\nhtml[data-theme=\"light\"]");
    assertThat(css).contains("@media (prefers-color-scheme: dark)");
    assertThat(css).contains("html[data-theme=\"dark\"]");

    ImmutableList<String> expectedTokenClasses =
        ImmutableList.of(
            ".LeanTok--plain",
            ".LeanTok--comment",
            ".LeanTok--string",
            ".LeanTok--keyword",
            ".LeanTok--tactic",
            ".LeanTok--sorry",
            ".LeanTok--type",
            ".LeanTok--operator",
            ".LeanTok--bullet",
            ".LeanTok--attribute",
            ".LeanTok--number",
            ".LeanTok--literal",
            ".LeanTok--unicodeOp");
    for (String tokenClass : expectedTokenClasses) {
      assertThat(css).contains(tokenClass);
    }
  }
}
