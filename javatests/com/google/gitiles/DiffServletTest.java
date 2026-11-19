// Copyright (C) 2014 The Android Open Source Project
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

package com.google.gitiles;

import static com.google.common.collect.Iterables.getOnlyElement;
import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static javax.servlet.http.HttpServletResponse.SC_OK;

import com.google.common.collect.ImmutableList;
import com.google.common.io.BaseEncoding;
import com.google.gitiles.lean.LeanRendering;
import com.google.gitiles.lean.LeanRequestBudget;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffEntry.ChangeType;
import org.eclipse.jgit.diff.DiffEntry.Side;
import org.eclipse.jgit.diff.Edit;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.dircache.DirCacheEditor.PathEdit;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.patch.FileHeader;
import org.eclipse.jgit.patch.Patch;
import org.eclipse.jgit.revwalk.RevBlob;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class DiffServletTest extends ServletTest {
  @Test
  public void diffFileOneParentHtml() throws Exception {
    String contents1 = "foo\n";
    String contents2 = "foo\ncontents\n";
    RevCommit c1 = repo.update("master", repo.commit().add("foo", contents1));
    RevCommit c2 = repo.update("master", repo.commit().parent(c1).add("foo", contents2));

    String actual = buildHtml("/repo/+diff/" + c2.name() + "^!/foo", false);

    String diffHeader =
        String.format(
            "diff --git <a href=\"/b/repo/+/%s/foo\">a/foo</a> <a"
                + " href=\"/b/repo/+/%s/foo\">b/foo</a>",
            c1.name(), c2.name());
    assertThat(actual).contains(diffHeader);
    assertThat(actual).contains("<span class=\"Diff-change\"> foo</span>");
    assertThat(actual).contains("<span class=\"Diff-insert\">+contents</span>");
  }

  @Test
  public void diffFileNoParentsText() throws Exception {
    String contents = "foo\ncontents\n";
    RevCommit c = repo.update("master", repo.commit().add("foo", contents));

    FakeHttpServletResponse res = buildText("/repo/+diff/" + c.name() + "^!/foo");

    Patch p = parsePatch(res.getActualBody());
    FileHeader f = getOnlyElement(p.getFiles());
    assertThat(f.getChangeType()).isEqualTo(ChangeType.ADD);
    assertThat(f.getPath(Side.OLD)).isEqualTo(DiffEntry.DEV_NULL);
    assertThat(f.getPath(Side.NEW)).isEqualTo("foo");

    RawText rt = new RawText(contents.getBytes(UTF_8));
    Edit e = getOnlyElement(getOnlyElement(f.getHunks()).toEditList());
    assertThat(e.getType()).isEqualTo(Edit.Type.INSERT);
    assertThat(rt.getString(e.getBeginB(), e.getEndB(), false)).isEqualTo(contents);
  }

  @Test
  public void diffFileOneParentText() throws Exception {
    String contents1 = "foo\n";
    String contents2 = "foo\ncontents\n";
    RevCommit c1 = repo.update("master", repo.commit().add("foo", contents1));
    RevCommit c2 = repo.update("master", repo.commit().parent(c1).add("foo", contents2));

    FakeHttpServletResponse res = buildText("/repo/+diff/" + c2.name() + "^!/foo");

    Patch p = parsePatch(res.getActualBody());
    FileHeader f = getOnlyElement(p.getFiles());
    assertThat(f.getChangeType()).isEqualTo(ChangeType.MODIFY);
    assertThat(f.getPath(Side.OLD)).isEqualTo("foo");
    assertThat(f.getPath(Side.NEW)).isEqualTo("foo");

    RawText rt2 = new RawText(contents2.getBytes(UTF_8));
    Edit e = getOnlyElement(getOnlyElement(f.getHunks()).toEditList());
    assertThat(e.getType()).isEqualTo(Edit.Type.INSERT);
    assertThat(rt2.getString(e.getBeginB(), e.getEndB(), false)).isEqualTo("contents\n");
  }

  @Test
  public void diffDirectoryText() throws Exception {
    String contents = "contents\n";
    RevCommit c =
        repo.update(
            "master",
            repo.commit().add("dir/foo", contents).add("dir/bar", contents).add("baz", contents));

    FakeHttpServletResponse res = buildText("/repo/+diff/" + c.name() + "^!/dir");

    Patch p = parsePatch(res.getActualBody());
    assertThat(p.getFiles().size()).isEqualTo(2);
    assertThat(p.getFiles().get(0).getPath(Side.NEW)).isEqualTo("dir/bar");
    assertThat(p.getFiles().get(1).getPath(Side.NEW)).isEqualTo("dir/foo");
  }

  private static Patch parsePatch(byte[] enc) {
    byte[] buf = BaseEncoding.base64().decode(new String(enc, UTF_8));
    Patch p = new Patch();
    p.parse(buf, 0, buf.length);
    assertThat(p.getErrors()).isEqualTo(ImmutableList.of());
    return p;
  }

  @Test
  public void diffLeanFileRendersRichHtmlAndIncludesCssInHead() throws Exception {
    String base =
        """
        theorem keep_me : 1 = 1 := by
          rfl

        theorem fix_me : True := by
          sorry
        """;
    String updated =
        """
        theorem keep_me : 1 = 1 := by
          rfl

        theorem fix_me : True := by
          trivial
        """;
    RevCommit c1 = repo.update("master", repo.commit().add("Math/Proof.lean", base));
    RevCommit c2 = repo.update("master", repo.commit().parent(c1).add("Math/Proof.lean", updated));

    String html = buildHtml("/repo/+diff/" + c2.name() + "^!/Math/Proof.lean", false);
    assertThat(html).contains("lean.css");
    assertThat(html).contains("diff --git <a href=\"/b/repo/+/" + c1.name() + "/Math/Proof.lean\"");
    assertThat(html).contains("class=\"LeanDiff\"");
    assertThat(html).contains("1 unchanged declaration");
    assertThat(html).doesNotContain("keep_me");
    assertThat(html).contains("LeanDecl-name\">fix_me</span>");
    assertThat(html).contains("LeanCode-line--deleted");
    assertThat(html).contains("LeanCode-line--added");
    assertThat(html)
        .contains(
            "[Rendered | <a href=\"/b/repo/+/"
                + c2.name()
                + "%5E%21/Math/Proof.lean?lean=0\">Plain text</a>]");
    assertThat(html).doesNotContain(">raw diff</a>");
    assertThat(html).doesNotContain("Diff-unified");
  }

  @Test
  public void diffLeanFileWithLeanZeroAndTextFormatUseRawDiff() throws Exception {
    RevCommit c1 =
        repo.update("master", repo.commit().add("Main.lean", "theorem t : True := by sorry\n"));
    RevCommit c2 =
        repo.update(
            "master",
            repo.commit().parent(c1).add("Main.lean", "theorem t : True := by trivial\n"));

    FakeHttpServletResponse res =
        buildResponse("/repo/+diff/" + c2.name() + "^!/Main.lean", "lean=0", SC_OK);
    String html = res.getActualBodyString();
    assertThat(html).contains("Diff-unified");
    assertThat(html).doesNotContain("class=\"LeanDiff\"");
    assertThat(html).doesNotContain("lean.css");
    assertThat(html)
        .contains(
            "[<a href=\"/b/repo/+/" + c2.name() + "%5E%21/Main.lean\">Rendered</a> | Plain text]");

    FakeHttpServletResponse textRes = buildText("/repo/+diff/" + c2.name() + "^!/Main.lean");
    Patch p = parsePatch(textRes.getActualBody());
    FileHeader f = getOnlyElement(p.getFiles());
    assertThat(f.getPath(Side.NEW)).isEqualTo("Main.lean");
  }

  @Test
  public void diffLeanRenderingDisabledByConfigFallsBackToRawDiff() throws Exception {
    Config config = new Config();
    config.setBoolean("gitiles", null, "leanRendering", false);
    servlet = TestGitilesServlet.create(repo, config);

    RevCommit c1 =
        repo.update("master", repo.commit().add("Main.lean", "theorem t : True := by sorry\n"));
    RevCommit c2 =
        repo.update(
            "master",
            repo.commit().parent(c1).add("Main.lean", "theorem t : True := by trivial\n"));

    String html = buildHtml("/repo/+diff/" + c2.name() + "^!/Main.lean", false);
    assertThat(html).contains("Diff-unified");
    assertThat(html).doesNotContain("class=\"LeanDiff\"");
    assertThat(html).doesNotContain("lean.css");
    assertThat(html).doesNotContain("Plain text");
    assertThat(html).doesNotContain("?lean=0");

    String plainParamHtml =
        buildResponse("/repo/+diff/" + c2.name() + "^!/Main.lean", "lean=0", SC_OK)
            .getActualBodyString();
    assertThat(plainParamHtml).doesNotContain("Plain text");
    assertThat(plainParamHtml).doesNotContain(">Rendered</a>");
  }

  @Test
  public void diffMixedCommitRendersLeanRichlyAndNonLeanNormally() throws Exception {
    RevCommit c1 =
        repo.update(
            "master",
            repo.commit().add("Main.lean", "def x : Nat := 1\n").add("notes.txt", "hello\n"));
    RevCommit c2 =
        repo.update(
            "master",
            repo.commit()
                .parent(c1)
                .add("Main.lean", "def x : Nat := 2\n")
                .add("notes.txt", "world\n"));

    String html = buildHtml("/repo/+diff/" + c2.name() + "^!/", false);
    assertThat(html).contains("lean.css");
    assertThat(html).contains("class=\"LeanDiff\"");
    assertThat(html).contains("Diff-unified");
    assertThat(html).contains("<span class=\"Diff-insert\">+world</span>");
    assertThat(
            countOccurrences(
                html,
                "[Rendered | <a href=\"/b/repo/+/"
                    + c2.name()
                    + "%5E%21/?lean=0\">Plain text</a>]"))
        .isEqualTo(1);

    String plainHtml =
        buildResponse("/repo/+diff/" + c2.name() + "^!/", "lean=0", SC_OK).getActualBodyString();
    assertThat(plainHtml).doesNotContain("class=\"LeanDiff\"");
    assertThat(
            countOccurrences(
                plainHtml,
                "[<a href=\"/b/repo/+/" + c2.name() + "%5E%21/\">Rendered</a> | Plain text]"))
        .isEqualTo(1);
  }

  @Test
  public void diffLeanAddAndDeleteFilesRenderRichDiff() throws Exception {
    RevCommit c1 =
        repo.update(
            "master",
            repo.commit()
                .add("Old.lean", "theorem deleted_thm : True := by\n  trivial\n")
                .add("Keep.txt", "stay\n"));
    RevCommit c2 =
        repo.update(
            "master",
            repo.commit()
                .parent(c1)
                .rm("Old.lean")
                .add("New.lean", "theorem added_thm : True := by\n  trivial\n")
                .add("Broken.lean", "theorem ok : True := by trivial\ndef s := \"unclosed\n"));

    String html = buildHtml("/repo/+diff/" + c2.name() + "^!/", false);
    assertThat(html).contains("LeanDiff-block--added");
    assertThat(html).contains("LeanDiff-block--deleted");
    assertThat(html).contains("added_thm");
    assertThat(html).contains("deleted_thm");
    assertThat(
            countOccurrences(
                html, "<span class=\"LeanPill LeanPill--neutral\">unterminated string/comment"))
        .isEqualTo(1);
    assertThat(countOccurrences(html, "counts may be incomplete</span>")).isEqualTo(1);
    assertThat(countOccurrences(html, "no sorry found (lexical scan)")).isEqualTo(2);
  }

  @Test
  public void diffLeanIneligibleFilesFallBackToStandardDiff() throws Exception {
    String smallLean = "def x : Nat := 1\n";
    String oversizeLean = "def x : Nat := 1\n".repeat(LeanRendering.MAX_LINES + 1);
    String gitlinkSha1 = "1111111111111111111111111111111111111111";
    String gitlinkSha2 = "2222222222222222222222222222222222222222";
    RevBlob modeBlob = repo.blob(smallLean);

    RevCommit c1 =
        repo.update(
            "master",
            repo.commit()
                .add("Rename.lean", smallLean)
                .add("Mode.lean", modeBlob)
                .add("Binary.lean", "def b := 1\0\n")
                .add("Big.lean", smallLean)
                .add("LongLine.lean", smallLean)
                .edit(
                    new PathEdit("Sub.lean") {
                      @Override
                      public void apply(DirCacheEntry ent) {
                        ent.setFileMode(FileMode.GITLINK);
                        ent.setObjectId(ObjectId.fromString(gitlinkSha1));
                      }
                    }));

    RevCommit c2 =
        repo.update(
            "master",
            repo.commit()
                .parent(c1)
                .rm("Rename.lean")
                .add("Renamed.lean", smallLean)
                .edit(
                    new PathEdit("Mode.lean") {
                      @Override
                      public void apply(DirCacheEntry ent) {
                        ent.setFileMode(FileMode.EXECUTABLE_FILE);
                        ent.setObjectId(modeBlob);
                      }
                    })
                .add("Binary.lean", "def b := 2\0\n")
                .add("Big.lean", oversizeLean)
                .add(
                    "LongLine.lean",
                    "def longLine : String := \""
                        + "a".repeat(LeanRendering.MAX_LINE_CHARS)
                        + "\"\n")
                .edit(
                    new PathEdit("Sub.lean") {
                      @Override
                      public void apply(DirCacheEntry ent) {
                        ent.setFileMode(FileMode.GITLINK);
                        ent.setObjectId(ObjectId.fromString(gitlinkSha2));
                      }
                    }));

    String html = buildHtml("/repo/+diff/" + c2.name() + "^!/", false);
    assertThat(html).doesNotContain("class=\"LeanDiff\"");
    assertThat(html).contains("Diff-unified");
    assertThat(html).contains("Binary files");
    assertThat(html).contains("Subproject commit");
    assertThat(html).doesNotContain("Plain text");

    String plainParamHtml =
        buildResponse("/repo/+diff/" + c2.name() + "^!/", "lean=0", SC_OK).getActualBodyString();
    assertThat(plainParamHtml).doesNotContain("Plain text");
    assertThat(plainParamHtml).doesNotContain(">Rendered</a>");

    String longLineHtml = buildHtml("/repo/+diff/" + c2.name() + "^!/LongLine.lean", false);
    assertThat(longLineHtml).doesNotContain("class=\"LeanDiff\"");
    assertThat(longLineHtml).contains("Diff-unified");
    assertThat(longLineHtml).doesNotContain(">Plain text</a>");
    String longLinePlain =
        buildResponse("/repo/+diff/" + c2.name() + "^!/LongLine.lean", "lean=0", SC_OK)
            .getActualBodyString();
    assertThat(longLinePlain).doesNotContain(">Rendered</a>");
  }

  @Test
  public void diffNonLeanSingleFileOmitsLeanCssFromHead() throws Exception {
    RevCommit c1 = repo.update("master", repo.commit().add("plain.txt", "a\n"));
    RevCommit c2 = repo.update("master", repo.commit().parent(c1).add("plain.txt", "b\n"));

    String html = buildHtml("/repo/+diff/" + c2.name() + "^!/plain.txt", false);
    assertThat(html).doesNotContain("lean.css");
    assertThat(html).contains("Diff-unified");
    assertThat(html).doesNotContain("Plain text");

    String plainParamHtml =
        buildResponse("/repo/+diff/" + c2.name() + "^!/plain.txt", "lean=0", SC_OK)
            .getActualBodyString();
    assertThat(plainParamHtml).doesNotContain("Plain text");
    assertThat(plainParamHtml).doesNotContain(">Rendered</a>");
  }

  @Test
  public void diffLeanMalformedAndLean3InputDegradesGracefully() throws Exception {
    String base =
        """
        lemma legacy_thm : true :=
        begin
          trivial,
        end
        """;
    String next =
        """
        lemma legacy_thm : true :=
        begin
          sorry,
        end
        def broken := "unclosed
        /- unclosed block comment
        """;
    RevCommit c1 = repo.update("master", repo.commit().add("Legacy.lean", base));
    RevCommit c2 = repo.update("master", repo.commit().parent(c1).add("Legacy.lean", next));

    String html = buildHtml("/repo/+diff/" + c2.name() + "^!/Legacy.lean", false);
    assertThat(html).contains("class=\"LeanDiff\"");
    assertThat(html).contains("legacy_thm");
  }

  @Test
  public void diffLeanFallsBackCleanlyOnRenderingRuntimeException() throws Exception {
    Config badMdConfig = new Config();
    badMdConfig.setString("markdown", null, "render", "not_a_boolean");
    servlet = TestGitilesServlet.create(repo, badMdConfig);

    RevCommit c1 =
        repo.update("master", repo.commit().add("Main.lean", "theorem t : True := by sorry\n"));
    RevCommit c2 =
        repo.update(
            "master",
            repo.commit().parent(c1).add("Main.lean", "theorem t : True := by trivial\n"));

    String html = buildHtml("/repo/+diff/" + c2.name() + "^!/Main.lean", false);
    assertThat(html).contains("Diff-unified");
    assertThat(html).doesNotContain("class=\"LeanDiff\"");
    assertThat(html).doesNotContain("Plain text");
  }

  @Test
  public void diffLeanTogglePreservesQueryParamsAndEscapesHostileInputs() throws Exception {
    String branch = "feat\"x";
    String hostilePath = "dir/Evil\"<Path>&1.lean";
    RevCommit c1 = repo.update(branch, repo.commit().add(hostilePath, "def a : Nat := 1\n"));
    repo.update(branch, repo.commit().parent(c1).add(hostilePath, "def a : Nat := 2\n"));

    String pathUrl = "/repo/+diff/" + branch + "^!/" + hostilePath;
    String encodedPrefix = "/b/repo/+/feat%22x%5E%21/dir/Evil%22%3CPath%3E%261.lean";

    String richHtml = buildResponse(pathUrl, "foo=a%22b&lean=1", SC_OK).getActualBodyString();
    assertThat(richHtml)
        .contains(
            "[Rendered | <a href=\"" + encodedPrefix + "?foo=a%22b&amp;lean=0\">Plain text</a>]");

    String plainHtml = buildResponse(pathUrl, "foo=a%22b&lean=0", SC_OK).getActualBodyString();
    assertThat(plainHtml)
        .contains("[<a href=\"" + encodedPrefix + "?foo=a%22b\">Rendered</a> | Plain text]");
  }

  @Test
  public void diffLeanInvalidConfigFailsClosedAndBlocksGateBypass() throws Exception {
    Config invalid = new Config();
    invalid.setString("gitiles", null, "leanRendering", "invalid_bool");
    servlet = TestGitilesServlet.create(repo, invalid);

    RevCommit c1 =
        repo.update("master", repo.commit().add("Main.lean", "theorem t : True := by sorry\n"));
    RevCommit c2 =
        repo.update(
            "master",
            repo.commit().parent(c1).add("Main.lean", "theorem t : True := by trivial\n"));

    String invalidHtml = buildHtml("/repo/+diff/" + c2.name() + "^!/Main.lean", false);
    assertThat(invalidHtml).contains("Diff-unified");
    assertThat(invalidHtml).doesNotContain("class=\"LeanDiff\"");
    assertThat(invalidHtml).doesNotContain("lean.css");

    Config disabled = new Config();
    disabled.setBoolean("gitiles", null, "leanRendering", false);
    servlet = TestGitilesServlet.create(repo, disabled);
    for (String query : new String[] {"lean=1", "lean=", "lean=1&lean=0", "LEAN=1"}) {
      String html =
          buildResponse("/repo/+diff/" + c2.name() + "^!/Main.lean", query, SC_OK)
              .getActualBodyString();
      assertThat(html).contains("Diff-unified");
      assertThat(html).doesNotContain("class=\"LeanDiff\"");
      assertThat(html).doesNotContain("lean.css");
      assertThat(html).doesNotContain("Plain text");
    }

    RevCommit c3 =
        repo.update(
            "master",
            repo.commit()
                .parent(c2)
                .add("Upper.LEAN", "def a := 1\n")
                .add("Semi.lean;x", "def b := 1\n"));
    RevCommit c4 =
        repo.update(
            "master",
            repo.commit()
                .parent(c3)
                .add("Upper.LEAN", "def a := 2\n")
                .add("Semi.lean;x", "def b := 2\n"));
    servlet = TestGitilesServlet.create(repo);
    String nonLeanHtml = buildHtml("/repo/+diff/" + c4.name() + "^!/", false);
    assertThat(nonLeanHtml).doesNotContain("class=\"LeanDiff\"");
    assertThat(nonLeanHtml).contains("Diff-unified");
  }

  @Test
  public void diffLeanCrOnlyOversizeAndDeepMarkdownCrlfPathDegradeSafely() throws Exception {
    // Prefix >8000 bytes of LF lines bypasses JGit RawText.isBinary sniffing so CR-only lines
    // past the 8KB mark still test LeanRendering.withinInputCaps(RawText) against MAX_LINES.
    String safePrefix = "-- header\n".repeat(1_000);
    String crOnlyOver = safePrefix + "def a : Nat := 1\r".repeat(LeanRendering.MAX_LINES + 1);
    RevCommit c1 =
        repo.update("master", repo.commit().add("Cr.lean", safePrefix + "def a : Nat := 1\n"));
    RevCommit c2 = repo.update("master", repo.commit().parent(c1).add("Cr.lean", crOnlyOver));
    String crHtml = buildHtml("/repo/+diff/" + c2.name() + "^!/Cr.lean", false);
    assertThat(crHtml).contains("Binary files differ");
    assertThat(crHtml).doesNotContain("class=\"LeanDiff\"");

    String deepMd = "/-!\n" + "> ".repeat(10_000) + "deep\n-/\ndef d : Nat := 1\n";
    RevCommit c3 =
        repo.update("master", repo.commit().parent(c2).add("Deep.lean", "def d : Nat := 0\n"));
    RevCommit c4 = repo.update("master", repo.commit().parent(c3).add("Deep.lean", deepMd));
    String deepHtml = buildHtml("/repo/+diff/" + c4.name() + "^!/Deep.lean", false);
    assertThat(deepHtml).contains("class=\"LeanDiff\"");
    assertThat(deepHtml).contains("<pre><code>");

    Config badMd = new Config();
    badMd.setString("markdown", null, "render", "not_bool");
    servlet = TestGitilesServlet.create(repo, badMd);
    String crlfPath = "evil\r\nFORGED.lean";
    RevCommit c5 = repo.update("master", repo.commit().parent(c4).add(crlfPath, "def e := 1\n"));
    RevCommit c6 = repo.update("master", repo.commit().parent(c5).add(crlfPath, "def e := 2\n"));
    String crlfHtml = buildHtml("/repo/+diff/" + c6.name() + "^!/", false);
    assertThat(crlfHtml).contains("Diff-unified");
    assertThat(crlfHtml).doesNotContain("class=\"LeanDiff\"");
  }

  @Test
  public void diffLeanPerRequestBudgetLimitsFileCountAndCumulativeInputBytes() throws Exception {
    // Part 1: Cumulative 4 MiB input byte cap (aBytes + bBytes across rendered Lean diffs).
    // A.lean ~ 1.6 MiB per side (~3.2 MiB total) fits; B.lean ~ 0.8 MiB per side (~1.6 MiB total)
    // pushes cumulative input bytes past 4 MiB and falls back to Diff-unified without a toggle.
    String bigPad = ("-- " + "x".repeat(990) + "\n").repeat(1_600);
    String halfPad = ("-- " + "y".repeat(990) + "\n").repeat(800);
    RevCommit c1 =
        repo.update(
            "master",
            repo.commit()
                .add("A.lean", "def a : Nat := 1\n" + bigPad)
                .add("B.lean", "def b : Nat := 1\n" + halfPad));
    RevCommit c2 =
        repo.update(
            "master",
            repo.commit()
                .parent(c1)
                .add("A.lean", "def a : Nat := 2\n" + bigPad)
                .add("B.lean", "def b : Nat := 2\n" + halfPad));
    String byteCapHtml = buildHtml("/repo/+diff/" + c2.name() + "^!/", false);
    assertThat(countOccurrences(byteCapHtml, "class=\"LeanDiff\"")).isEqualTo(1);
    assertThat(countOccurrences(byteCapHtml, "Diff-unified")).isEqualTo(1);
    assertThat(countOccurrences(byteCapHtml, ">Plain text</a>")).isEqualTo(1);

    FakeHttpServletResponse leanZeroRes =
        buildResponse("/repo/+diff/" + c2.name() + "^!/", "lean=0", SC_OK);
    String leanZeroHtml = leanZeroRes.getActualBodyString();
    assertThat(countOccurrences(leanZeroHtml, "Diff-unified")).isEqualTo(2);
    assertThat(countOccurrences(leanZeroHtml, ">Rendered</a>")).isEqualTo(2);

    // Part 2: Per-request max 50 rendered Lean files; files 51..55 degrade cleanly to plain diff.
    var addCommit = repo.commit().parent(c2);
    var modCommit = repo.commit();
    for (int i = 1; i <= 55; i++) {
      String name = String.format("F%02d.lean", i);
      addCommit.add(name, "def f" + i + " : Nat := 1\n");
    }
    RevCommit c3 = repo.update("many", addCommit);
    modCommit.parent(c3);
    for (int i = 1; i <= 55; i++) {
      String name = String.format("F%02d.lean", i);
      modCommit.add(name, "def f" + i + " : Nat := 2\n");
    }
    RevCommit c4 = repo.update("many", modCommit);
    String manyHtml = buildHtml("/repo/+diff/" + c4.name() + "^!/", false);
    assertThat(countOccurrences(manyHtml, "class=\"LeanDiff\""))
        .isEqualTo(LeanRequestBudget.MAX_REQUEST_LEAN_FILES);
    assertThat(countOccurrences(manyHtml, "Diff-unified")).isEqualTo(5);
    assertThat(countOccurrences(manyHtml, ">Plain text</a>"))
        .isEqualTo(LeanRequestBudget.MAX_REQUEST_LEAN_FILES);
  }

  private static int countOccurrences(String haystack, String needle) {
    int count = 0;
    int idx = 0;
    while ((idx = haystack.indexOf(needle, idx)) >= 0) {
      count++;
      idx += needle.length();
    }
    return count;
  }
}
