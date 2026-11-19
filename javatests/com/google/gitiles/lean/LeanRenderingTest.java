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
import static java.nio.charset.StandardCharsets.UTF_16BE;
import static java.nio.charset.StandardCharsets.UTF_16LE;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.collect.ImmutableList;
import com.google.common.io.Resources;
import com.google.gitiles.GitilesView;
import com.google.gitiles.Renderer;
import java.util.Arrays;
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
                LeanRendering.MAX_BYTES,
                LeanRendering.MAX_LINES,
                LeanRendering.MAX_LINE_CHARS,
                LeanRendering.MAX_OUTPUT_CHARS))
        .containsExactly(2_097_152, 20_000, 65_536, 16_777_216)
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
    Arrays.fill(maxBytes, (byte) 'a');
    for (int i = 32_767; i < maxBytes.length; i += 32_768) {
      maxBytes[i] = '\n';
    }
    assertThat(LeanRendering.withinInputCaps(new RawText(maxBytes))).isTrue();

    byte[] overMaxBytes = new byte[LeanRendering.MAX_BYTES + 1];
    Arrays.fill(overMaxBytes, (byte) 'a');
    for (int i = 32_767; i < overMaxBytes.length; i += 32_768) {
      overMaxBytes[i] = '\n';
    }
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

  @Test
  public void plainTextAndRenderedUrlsPreserveOtherParamsAndEscapeHostileInputs() {
    GitilesView richView = newPathView().putParam("foo", "a&b\"<c>").putParam("lean", "1").build();
    assertThat(LeanRendering.plainTextUrl(richView))
        .isEqualTo("/b/repo/+/HEAD/Main.lean?foo=a%26b%22%3Cc%3E&lean=0");
    assertThat(LeanRendering.plainTextUrl(null)).isEqualTo("?lean=0");

    GitilesView plainView =
        newPathView().putParam("foo", "1").putParam("lean", "0").putParam("bar", "2").build();
    assertThat(LeanRendering.renderedUrl(plainView))
        .isEqualTo("/b/repo/+/HEAD/Main.lean?foo=1&bar=2");
    assertThat(LeanRendering.renderedUrl(newPathView().putParam("lean", "0").build()))
        .isEqualTo("/b/repo/+/HEAD/Main.lean");
    assertThat(LeanRendering.renderedUrl(null)).isEqualTo("?");

    GitilesView hostileView =
        GitilesView.path()
            .setHostName("host")
            .setServletPath("/b")
            .setRepositoryName("repo")
            .setRevision("refs/heads/x\"'<>&y")
            .setPathPart("dir/\"onclick=alert(1)<script>.lean")
            .putParam("q", "\"onmouseover=alert(1)")
            .build();
    String plainHref = LeanRendering.plainTextUrl(hostileView);
    assertThat(plainHref)
        .isEqualTo(
            "/b/repo/+/refs/heads/x%22%27%3C%3E%26y/dir/%22onclick%3Dalert%281%29%3Cscript%3E.lean"
                + "?q=%22onmouseover%3Dalert%281%29&lean=0");
    String renderedHref =
        LeanRendering.renderedUrl(hostileView.toBuilder().putParam("lean", "0").build());
    assertThat(renderedHref)
        .isEqualTo(
            "/b/repo/+/refs/heads/x%22%27%3C%3E%26y/dir/%22onclick%3Dalert%281%29%3Cscript%3E.lean"
                + "?q=%22onmouseover%3Dalert%281%29");
  }

  @Test
  public void invalidBooleanConfigFailsClosedWithoutThrowing() {
    Config bad = new Config();
    bad.setString("gitiles", null, "leanRendering", "not_a_boolean");
    assertThat(LeanRendering.isEnabled(bad)).isFalse();
    assertThat(LeanRendering.isEnabled(bad, newPathView().putParam("lean", "1").build())).isFalse();
  }

  @Test
  public void withinInputCapsRejectsCrOnlyLinesAboveMaxLines() {
    String over = "a\r".repeat(LeanRendering.MAX_LINES + 1);
    RawText rawOver = new RawText(over.getBytes(UTF_8));
    assertThat(rawOver.size()).isEqualTo(1);
    assertThat(LeanRendering.withinInputCaps(rawOver)).isFalse();

    String exact = "a\r".repeat(LeanRendering.MAX_LINES);
    assertThat(LeanRendering.withinInputCaps(new RawText(exact.getBytes(UTF_8)))).isTrue();
  }

  @Test
  public void sanitizeForLogEscapesControlCharsCrLfAndBoundsLength() {
    String evil = "dir/evil\r\n[INFO] forged\t\u001b\u2028\u2029\u202E.lean";
    String clean = LeanRendering.sanitizeForLog(evil);
    assertThat(clean).doesNotContain("\r");
    assertThat(clean).doesNotContain("\n");
    assertThat(clean).doesNotContain("\u001b");
    assertThat(clean).doesNotContain("\u202E");
    assertThat(clean).contains("\\r\\n[INFO] forged\\t\\u001b\\u2028\\u2029\\u202e.lean");
    assertThat(LeanRendering.sanitizeForLog("a".repeat(500)).length()).isLessThan(300);
    assertThat(LeanRendering.sanitizeForLog(null)).isEqualTo("<null>");
  }

  @Test
  public void withinInputCapsEnforcesPerLineCharLimit() {
    String exactLine = "a".repeat(LeanRendering.MAX_LINE_CHARS);
    assertThat(LeanRendering.withinInputCaps(exactLine.length(), exactLine)).isTrue();
    assertThat(LeanRendering.withinInputCaps(exactLine.length() + 1, exactLine + "\n")).isTrue();
    assertThat(LeanRendering.withinInputCaps(exactLine.length() + 2, exactLine + "\r\n")).isTrue();
    assertThat(LeanRendering.withinInputCaps(exactLine.length() + 1, exactLine + "\r")).isTrue();

    String multiExact = (exactLine + "\n").repeat(4);
    assertThat(LeanRendering.withinInputCaps(multiExact.length(), multiExact)).isTrue();

    String overLine = "a".repeat(LeanRendering.MAX_LINE_CHARS + 1);
    assertThat(LeanRendering.withinInputCaps(overLine.length(), overLine)).isFalse();
    assertThat(LeanRendering.withinInputCaps(overLine.length() + 1, overLine + "\n")).isFalse();
    assertThat(LeanRendering.withinInputCaps(overLine.length() + 2, "x\r\n" + overLine)).isFalse();
    assertThat(LeanRendering.withinInputCaps(overLine.length() + 2, "x\r" + overLine)).isFalse();
    assertThat(LeanRendering.withinInputCaps(overLine.length() + 2, "x\n" + overLine)).isFalse();

    assertThat(LeanRendering.withinInputCaps(new RawText(exactLine.getBytes(UTF_8)))).isTrue();
    assertThat(LeanRendering.withinInputCaps(new RawText((exactLine + "\r\n").getBytes(UTF_8))))
        .isTrue();
    assertThat(LeanRendering.withinInputCaps(new RawText(multiExact.getBytes(UTF_8)))).isTrue();
    assertThat(LeanRendering.withinInputCaps(new RawText(overLine.getBytes(UTF_8)))).isFalse();
    assertThat(LeanRendering.withinInputCaps(new RawText(("x\r\n" + overLine).getBytes(UTF_8))))
        .isFalse();
    assertThat(LeanRendering.withinInputCaps(new RawText(("x\r" + overLine).getBytes(UTF_8))))
        .isFalse();
  }

  @Test
  public void isValidLeanTextBytesRejectsNulBareCrUtf16AndInvalidUtf8After8Kb() {
    assertThat(LeanRendering.isValidLeanTextBytes(null)).isFalse();
    assertThat(LeanRendering.isValidLeanTextBytes(new byte[0])).isTrue();
    assertThat(
            LeanRendering.isValidLeanTextBytes("def ok : Nat := 1\r\n-- α→β 𝔸\n".getBytes(UTF_8)))
        .isTrue();
    // Valid UTF-8 BOM and Trojan C0 controls (except NUL and bare CR) remain accepted so
    // LeanTrojanChar can expose them explicitly.
    assertThat(
            LeanRendering.isValidLeanTextBytes(
                "\uFEFF-- \u0001\u001B\u000B\u000C\u202E\n".getBytes(UTF_8)))
        .isTrue();

    String prefix8k = "-- " + "a".repeat(8_192) + "\n";
    assertThat(LeanRendering.isValidLeanTextBytes((prefix8k + "def bad\0 := 1\n").getBytes(UTF_8)))
        .isFalse();
    assertThat(
            LeanRendering.isValidLeanTextBytes(
                (prefix8k + "def x := 1\rdef y := 2\n").getBytes(UTF_8)))
        .isFalse();
    assertThat(LeanRendering.isValidLeanTextBytes("def eofCr := 1\r".getBytes(UTF_8))).isFalse();

    // UTF-16LE/BE BOMs (including CJK without any NUL bytes) and bare BOMs must fail.
    byte[] utf16LeCjk = "\uFEFF測試數學".getBytes(UTF_16LE);
    byte[] utf16BeCjk = "\uFEFF測試數學".getBytes(UTF_16BE);
    assertThat(LeanRendering.isValidLeanTextBytes(utf16LeCjk)).isFalse();
    assertThat(LeanRendering.isValidLeanTextBytes(utf16BeCjk)).isFalse();
    assertThat(LeanRendering.isValidLeanTextBytes(new byte[] {(byte) 0xFF, (byte) 0xFE})).isFalse();
    assertThat(LeanRendering.isValidLeanTextBytes(new byte[] {(byte) 0xFE, (byte) 0xFF})).isFalse();
    // Invalid / overlong / surrogate UTF-8 sequences.
    assertThat(LeanRendering.isValidLeanTextBytes(new byte[] {(byte) 0x80})).isFalse();
    assertThat(LeanRendering.isValidLeanTextBytes(new byte[] {(byte) 0xC0, (byte) 0xAF})).isFalse();
    assertThat(
            LeanRendering.isValidLeanTextBytes(new byte[] {(byte) 0xE0, (byte) 0x9F, (byte) 0x80}))
        .isFalse();
    assertThat(
            LeanRendering.isValidLeanTextBytes(new byte[] {(byte) 0xED, (byte) 0xA0, (byte) 0x80}))
        .isFalse();
    assertThat(
            LeanRendering.isValidLeanTextBytes(
                new byte[] {(byte) 0xF4, (byte) 0x90, (byte) 0x80, (byte) 0x80}))
        .isFalse();
  }

  @Test
  public void requestBudgetTracksScanCapAndToggleLinks() {
    long[] tick = {0L};
    LeanRequestBudget budget =
        LeanRequestBudget.forTesting(4_096, 16_384, 3, 1_000_000L, 2_048, () -> tick[0]);
    assertThat(budget.beginInputCheck(0, 1_024)).isTrue();
    tick[0] += 200_000L;
    budget.endInputCheck(0, 1_024);
    assertThat(budget.scanBytesUsed()).isEqualTo(1_024);
    assertThat(budget.cpuNanosUsed()).isEqualTo(200_000L);
    // Second scan fits within 2_048; third exceeds scan cap without permanently exhausting smaller
    // checks unless CPU hits deadline.
    assertThat(budget.beginInputCheck(0, 1_024)).isTrue();
    budget.endInputCheck(0, 1_024);
    assertThat(budget.beginInputCheck(0, 1)).isFalse();

    LeanRequestBudget toggleBudget =
        LeanRequestBudget.forTesting(2_000, 16_384, 2, 1_000_000L, 16_384, () -> 0L);
    assertThat(toggleBudget.canAttempt(600, 600)).isTrue();
    toggleBudget.recordToggleLink(600, 600);
    assertThat(toggleBudget.canAttempt(600, 600)).isFalse();
    assertThat(toggleBudget.canAttempt(400, 400)).isTrue();
    toggleBudget.recordToggleLink(400, 400);
    assertThat(toggleBudget.isExhausted()).isTrue();
    assertThat(toggleBudget.canAttempt(1, 1)).isFalse();
  }
}
