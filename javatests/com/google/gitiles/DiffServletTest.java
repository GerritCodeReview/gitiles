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
import static java.nio.charset.StandardCharsets.UTF_16LE;
import static java.nio.charset.StandardCharsets.UTF_8;
import static javax.servlet.http.HttpServletResponse.SC_OK;

import com.google.common.collect.ImmutableList;
import com.google.common.io.BaseEncoding;
import com.google.gitiles.lean.LeanRendering;
import com.google.gitiles.lean.LeanRequestBudget;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.util.List;
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
  public void diffLeanRichToggleMixedAddDeleteMalformedAndReflow() throws Exception {
    String baseProof =
        """
        theorem keep_me : 1 = 1 := by
          rfl

        theorem fix_me : True := by
          sorry
        """;
    String updatedProof =
        """
        theorem keep_me : 1 = 1 := by
          rfl

        theorem fix_me : True := by
          trivial
        """;
    String legacyBase =
        """
        lemma legacy_thm : true :=
        begin
          trivial,
        end
        """;
    String legacyNext =
        """
        lemma legacy_thm : true :=
        begin
          sorry,
        end
        def broken := "unclosed
        /- unclosed block comment
        """;
    String oldDoc =
        """
        /-!
        Alpha
        beta
        -/
        /-- <!-- old --> -/
        def f : Nat := 1
        """;
    String newDoc =
        """
        /-!
        Alpha beta
        -/
        /-- <!-- new --> -/
        def f : Nat := 1
        """;
    RevCommit c1 =
        repo.update(
            "master",
            repo.commit()
                .add("Math/Proof.lean", baseProof)
                .add("Main.lean", "theorem t : True := by sorry\n")
                .add("Legacy.lean", legacyBase)
                .add("Doc.lean", oldDoc));
    RevCommit c2 =
        repo.update(
            "master",
            repo.commit()
                .parent(c1)
                .add("Math/Proof.lean", updatedProof)
                .add("Main.lean", "theorem t : True := by trivial\n")
                .add("Legacy.lean", legacyNext)
                .add("Doc.lean", newDoc));

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

    FakeHttpServletResponse leanZeroRes =
        buildResponse("/repo/+diff/" + c2.name() + "^!/Main.lean", "lean=0", SC_OK);
    String leanZeroHtml = leanZeroRes.getActualBodyString();
    assertThat(leanZeroHtml).contains("Diff-unified");
    assertThat(leanZeroHtml).doesNotContain("class=\"LeanDiff\"");
    assertThat(leanZeroHtml).doesNotContain("lean.css");
    assertThat(leanZeroHtml)
        .contains(
            "[<a href=\"/b/repo/+/" + c2.name() + "%5E%21/Main.lean\">Rendered</a> | Plain text]");

    FakeHttpServletResponse textRes = buildText("/repo/+diff/" + c2.name() + "^!/Main.lean");
    Patch p = parsePatch(textRes.getActualBody());
    FileHeader f = getOnlyElement(p.getFiles());
    assertThat(f.getPath(Side.NEW)).isEqualTo("Main.lean");

    String legacyHtml = buildHtml("/repo/+diff/" + c2.name() + "^!/Legacy.lean", false);
    assertThat(legacyHtml).contains("class=\"LeanDiff\"");
    assertThat(legacyHtml).contains("legacy_thm");

    String docHtml = buildHtml("/repo/+diff/" + c2.name() + "^!/Doc.lean", false);
    assertThat(docHtml).contains("class=\"LeanDiff\"");
    assertThat(docHtml).doesNotContain("<p>Alpha beta</p>");
    assertThat(docHtml).contains("Alpha");
    assertThat(docHtml).contains("beta");
    assertThat(docHtml).contains("&lt;!-- old --&gt;");
    assertThat(docHtml).contains("&lt;!-- new --&gt;");

    RevCommit m1 =
        repo.update(
            "mix",
            repo.commit().add("Main.lean", "def x : Nat := 1\n").add("notes.txt", "hello\n"));
    RevCommit m2 =
        repo.update(
            "mix",
            repo.commit()
                .parent(m1)
                .add("Main.lean", "/-!\n# F1\n-/\ndef x : Nat := 2\n")
                .add("notes.txt", "world\n"));
    String mixHtml = buildHtml("/repo/+diff/" + m2.name() + "^!/", false);
    assertThat(mixHtml).contains("lean.css");
    assertThat(mixHtml).contains("class=\"LeanDiff\"");
    assertThat(mixHtml).contains("Diff-unified");
    assertThat(mixHtml).contains("<span class=\"Diff-insert\">+world</span>");
    assertThat(mixHtml).contains("<h1>F1</h1>");
    assertThat(mixHtml).doesNotContain("class=\"h\" name=\"F1\"");
    assertThat(countOccurrences(mixHtml, "name=\"F1\"")).isEqualTo(1);
    assertThat(
            countOccurrences(
                mixHtml,
                "[Rendered | <a href=\"/b/repo/+/"
                    + m2.name()
                    + "%5E%21/?lean=0\">Plain text</a>]"))
        .isEqualTo(1);
    String mixPlainHtml =
        buildResponse("/repo/+diff/" + m2.name() + "^!/", "lean=0", SC_OK).getActualBodyString();
    assertThat(mixPlainHtml).doesNotContain("class=\"LeanDiff\"");
    assertThat(
            countOccurrences(
                mixPlainHtml,
                "[<a href=\"/b/repo/+/" + m2.name() + "%5E%21/\">Rendered</a> | Plain text]"))
        .isEqualTo(1);

    RevCommit a1 =
        repo.update(
            "adddel",
            repo.commit()
                .add("Old.lean", "theorem deleted_thm : True := by\n  trivial\n")
                .add("Keep.txt", "stay\n"));
    RevCommit a2 =
        repo.update(
            "adddel",
            repo.commit()
                .parent(a1)
                .rm("Old.lean")
                .add("New.lean", "theorem added_thm : True := by\n  trivial\n")
                .add("Broken.lean", "theorem ok : True := by trivial\ndef s := \"unclosed\n"));
    String addDelHtml = buildHtml("/repo/+diff/" + a2.name() + "^!/", false);
    assertThat(addDelHtml).contains("LeanDiff-block--added");
    assertThat(addDelHtml).contains("LeanDiff-block--deleted");
    assertThat(addDelHtml).contains("added_thm");
    assertThat(addDelHtml).contains("deleted_thm");
    assertThat(
            countOccurrences(
                addDelHtml,
                "<span class=\"LeanPill LeanPill--neutral\">unterminated string/comment"))
        .isEqualTo(1);
    assertThat(countOccurrences(addDelHtml, "counts may be incomplete</span>")).isEqualTo(1);
    assertThat(countOccurrences(addDelHtml, "no sorry found (lexical scan)")).isEqualTo(2);

    String branch = "feat\"x";
    String hostilePath = "dir/Evil\"<Path>&1.lean";
    RevCommit h1 = repo.update(branch, repo.commit().add(hostilePath, "def a : Nat := 1\n"));
    repo.update(branch, repo.commit().parent(h1).add(hostilePath, "def a : Nat := 2\n"));
    String pathUrl = "/repo/+diff/" + branch + "^!/" + hostilePath;
    String encodedPrefix = "/b/repo/+/feat%22x%5E%21/dir/Evil%22%3CPath%3E%261.lean";
    String richHostile = buildResponse(pathUrl, "foo=a%22b&lean=1", SC_OK).getActualBodyString();
    assertThat(richHostile)
        .contains(
            "[Rendered | <a href=\"" + encodedPrefix + "?foo=a%22b&amp;lean=0\">Plain text</a>]");
    String plainHostile = buildResponse(pathUrl, "foo=a%22b&lean=0", SC_OK).getActualBodyString();
    assertThat(plainHostile)
        .contains("[<a href=\"" + encodedPrefix + "?foo=a%22b\">Rendered</a> | Plain text]");
  }

  @Test
  public void diffLeanIneligibleSymlinksBadUtf8AndNonLeanPathsFallBack() throws Exception {
    String smallLean = "def x : Nat := 1\n";
    String oversizeLean = ("-- " + " ".repeat(64_996) + "\n").repeat(33);
    RevBlob oversizeBlob = repo.blob(oversizeLean);
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
                .add("Big.lean", oversizeBlob)
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
                .add("Big.lean", "def head := 1\n" + oversizeLean)
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
        buildResponse("/repo/+diff/" + c2.name() + "^!/Binary.lean", "lean=0", SC_OK)
            .getActualBodyString();
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

    RevCommit n1 =
        repo.update(
            "nonlean",
            repo.commit()
                .add("plain.txt", "a\n")
                .add("Upper.LEAN", "def a := 1\n")
                .add("Semi.lean;x", "def b := 1\n"));
    RevCommit n2 =
        repo.update(
            "nonlean",
            repo.commit()
                .parent(n1)
                .add("plain.txt", "b\n")
                .add("Upper.LEAN", "def a := 2\n")
                .add("Semi.lean;x", "def b := 2\n"));
    String txtHtml = buildHtml("/repo/+diff/" + n2.name() + "^!/plain.txt", false);
    assertThat(txtHtml).doesNotContain("lean.css");
    assertThat(txtHtml).contains("Diff-unified");
    assertThat(txtHtml).doesNotContain("Plain text");
    String txtPlain =
        buildResponse("/repo/+diff/" + n2.name() + "^!/plain.txt", "lean=0", SC_OK)
            .getActualBodyString();
    assertThat(txtPlain).doesNotContain("Plain text");
    assertThat(txtPlain).doesNotContain(">Rendered</a>");
    String nonLeanHtml = buildHtml("/repo/+diff/" + n2.name() + "^!/", false);
    assertThat(nonLeanHtml).doesNotContain("class=\"LeanDiff\"");
    assertThat(nonLeanHtml).contains("Diff-unified");

    RevBlob symOld = repo.blob("theorem sym_fake : True := sorry\n");
    RevBlob symNew = repo.blob("theorem sym_fake2 : True := trivial\n");
    RevBlob symAdd = repo.blob("theorem sym_add : True := sorry\n");
    RevBlob symTarget = repo.blob("Main.lean");
    RevCommit s1 =
        repo.update(
            "sym",
            repo.commit()
                .edit(modeEdit("SymEdit.lean", FileMode.SYMLINK, symOld))
                .add("FileToSym.lean", "theorem old_file : True := by trivial\n"));
    RevCommit s2 =
        repo.update(
            "sym",
            repo.commit()
                .parent(s1)
                .edit(modeEdit("SymEdit.lean", FileMode.SYMLINK, symNew))
                .edit(modeEdit("SymAdd.lean", FileMode.SYMLINK, symAdd))
                .edit(modeEdit("FileToSym.lean", FileMode.SYMLINK, symTarget)));
    String symEditHtml = buildHtml("/repo/+diff/" + s2.name() + "^!/SymEdit.lean", false);
    assertThat(symEditHtml).doesNotContain("class=\"LeanDiff\"");
    assertThat(symEditHtml).contains("Diff-unified");
    assertThat(symEditHtml).doesNotContain(">Plain text</a>");
    String symEditPlain =
        buildResponse("/repo/+diff/" + s2.name() + "^!/SymEdit.lean", "lean=0", SC_OK)
            .getActualBodyString();
    assertThat(symEditPlain).doesNotContain(">Rendered</a>");
    String symAddHtml = buildHtml("/repo/+diff/" + s2.name() + "^!/SymAdd.lean", false);
    assertThat(symAddHtml).doesNotContain("class=\"LeanDiff\"");
    assertThat(symAddHtml).contains("Diff-unified");
    assertThat(symAddHtml).doesNotContain(">Plain text</a>");
    String fileToSymHtml = buildHtml("/repo/+diff/" + s2.name() + "^!/FileToSym.lean", false);
    assertThat(countOccurrences(fileToSymHtml, "class=\"LeanDiff\"")).isEqualTo(1);
    assertThat(fileToSymHtml).contains("Diff-unified");
    assertThat(countOccurrences(fileToSymHtml, ">Plain text</a>")).isEqualTo(1);

    RevBlob u16Old = repo.blob("\uFEFF測試甲".getBytes(UTF_16LE));
    RevBlob u16New = repo.blob("\uFEFF測試乙".getBytes(UTF_16LE));
    RevBlob badOld = repo.blob(new byte[] {'d', 'e', 'f', ' ', '1', '\n', (byte) 0x80});
    RevBlob badNew = repo.blob(new byte[] {'d', 'e', 'f', ' ', '2', '\n', (byte) 0x80});
    RevCommit u1 =
        repo.update(
            "badutf8",
            repo.commit()
                .edit(modeEdit("U16.lean", FileMode.REGULAR_FILE, u16Old))
                .edit(modeEdit("BadUtf8.lean", FileMode.REGULAR_FILE, badOld)));
    RevCommit u2 =
        repo.update(
            "badutf8",
            repo.commit()
                .parent(u1)
                .edit(modeEdit("U16.lean", FileMode.REGULAR_FILE, u16New))
                .edit(modeEdit("BadUtf8.lean", FileMode.REGULAR_FILE, badNew)));
    for (String file : new String[] {"U16.lean", "BadUtf8.lean"}) {
      String uHtml = buildHtml("/repo/+diff/" + u2.name() + "^!/" + file, false);
      assertThat(uHtml).doesNotContain("class=\"LeanDiff\"");
      assertThat(uHtml).contains("Diff-unified");
      assertThat(uHtml).doesNotContain(">Plain text</a>");
      String uPlain =
          buildResponse("/repo/+diff/" + u2.name() + "^!/" + file, "lean=0", SC_OK)
              .getActualBodyString();
      assertThat(uPlain).doesNotContain(">Rendered</a>");
    }
  }

  @Test
  public void diffLeanDisabledAndInvalidConfigFailClosed() throws Exception {
    RevCommit c1 =
        repo.update(
            "master",
            repo.commit()
                .add("Main.lean", "theorem t : True := by sorry\n")
                .add("evil\r\nFORGED.lean", "def e := 1\n"));
    RevCommit c2 =
        repo.update(
            "master",
            repo.commit()
                .parent(c1)
                .add("Main.lean", "theorem t : True := by trivial\n")
                .add("evil\r\nFORGED.lean", "def e := 2\n"));

    Config disabled = new Config();
    disabled.setBoolean("gitiles", null, "leanRendering", false);
    servlet = TestGitilesServlet.create(repo, disabled);
    String disabledHtml = buildHtml("/repo/+diff/" + c2.name() + "^!/Main.lean", false);
    assertThat(disabledHtml).contains("Diff-unified");
    assertThat(disabledHtml).doesNotContain("class=\"LeanDiff\"");
    assertThat(disabledHtml).doesNotContain("lean.css");
    assertThat(disabledHtml).doesNotContain("Plain text");
    assertThat(disabledHtml).doesNotContain("?lean=0");
    for (String query : new String[] {"lean=0", "lean=1", "lean=1&lean=0"}) {
      String qHtml =
          buildResponse("/repo/+diff/" + c2.name() + "^!/Main.lean", query, SC_OK)
              .getActualBodyString();
      assertThat(qHtml).contains("Diff-unified");
      assertThat(qHtml).doesNotContain("class=\"LeanDiff\"");
      assertThat(qHtml).doesNotContain("lean.css");
      assertThat(qHtml).doesNotContain("Plain text");
      assertThat(qHtml).doesNotContain(">Rendered</a>");
    }

    Config invalid = new Config();
    invalid.setString("gitiles", null, "leanRendering", "invalid_bool");
    servlet = TestGitilesServlet.create(repo, invalid);
    String invalidHtml = buildHtml("/repo/+diff/" + c2.name() + "^!/Main.lean", false);
    assertThat(invalidHtml).contains("Diff-unified");
    assertThat(invalidHtml).doesNotContain("class=\"LeanDiff\"");
    assertThat(invalidHtml).doesNotContain("lean.css");
    assertThat(invalidHtml).doesNotContain("Plain text");

    Config badMdConfig = new Config();
    badMdConfig.setString("markdown", null, "render", "not_a_boolean");
    servlet = TestGitilesServlet.create(repo, badMdConfig);
    String badMdHtml = buildHtml("/repo/+diff/" + c2.name() + "^!/Main.lean", false);
    assertThat(badMdHtml).contains("Diff-unified");
    assertThat(badMdHtml).doesNotContain("class=\"LeanDiff\"");
    assertThat(badMdHtml).doesNotContain("Plain text");

    String crlfPathHtml = buildHtml("/repo/+diff/" + c2.name() + "^!/", false);
    assertThat(crlfPathHtml).contains("Diff-unified");
    assertThat(crlfPathHtml).doesNotContain("class=\"LeanDiff\"");
  }

  @Test
  public void diffLeanCrOnlyOversizeAndDeepMarkdownDegradeSafely() throws Exception {
    // Prefix >8000 bytes of LF lines bypasses JGit RawText.isBinary sniffing so CR-only lines
    // past the 8KB mark still test LeanRendering.withinInputCaps(RawText) against MAX_LINES.
    String safePrefix = ("-- " + "a".repeat(97) + "\n").repeat(82);
    String crOnlyOver = safePrefix + "a\r".repeat(LeanRendering.MAX_LINES + 1);
    RevCommit c1 =
        repo.update(
            "master",
            repo.commit()
                .add("Cr.lean", safePrefix + "def a : Nat := 1\n")
                .add("Deep.lean", "def d : Nat := 0\n"));
    String deepMd = "/-!\n" + "> ".repeat(70) + "deep\n-/\ndef d : Nat := 1\n";
    RevCommit c2 =
        repo.update(
            "master", repo.commit().parent(c1).add("Cr.lean", crOnlyOver).add("Deep.lean", deepMd));
    String crHtml = buildHtml("/repo/+diff/" + c2.name() + "^!/Cr.lean", false);
    assertThat(crHtml).contains("Binary files differ");
    assertThat(crHtml).doesNotContain("class=\"LeanDiff\"");

    String deepHtml = buildHtml("/repo/+diff/" + c2.name() + "^!/Deep.lean", false);
    assertThat(deepHtml).contains("class=\"LeanDiff\"");
    assertThat(deepHtml).contains("<pre><code>");
  }

  @Test
  public void diffLeanPerRequestBudgetAndScanCapLimitRendering() throws Exception {
    // Part 1: Cumulative 4 MiB input byte cap (aBytes + bBytes across rendered Lean diffs).
    String bigPad = ("-- " + "x".repeat(63_996) + "\n").repeat(25);
    String halfPad = ("-- " + "y".repeat(63_996) + "\n").repeat(13);
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
    assertThat(countOccurrences(leanZeroHtml, ">Rendered</a>")).isEqualTo(1);

    // Part 2: Per-request max 50 rendered Lean files; file 51 degrades cleanly to plain diff.
    RevBlob f1Old = repo.blob("def f : Nat := 1\n");
    RevBlob f1New = repo.blob("def f : Nat := 2\n");
    var addCommit = repo.commit().parent(c2);
    var modCommit = repo.commit();
    for (int i = 1; i <= 51; i++) {
      addCommit.add(String.format("F%02d.lean", i), f1Old);
    }
    RevCommit c3 = repo.update("many", addCommit);
    modCommit.parent(c3);
    for (int i = 1; i <= 51; i++) {
      modCommit.add(String.format("F%02d.lean", i), f1New);
    }
    RevCommit c4 = repo.update("many", modCommit);

    Renderer renderer =
        new DefaultRenderer(GitilesServlet.STATIC_PREFIX, ImmutableList.of(), "repo test site");
    ByteArrayOutputStream manyOut = new ByteArrayOutputStream();
    GitilesView manyView =
        GitilesView.diff()
            .setHostName("host")
            .setServletPath("/b")
            .setRepositoryName("repo")
            .setOldRevision("c3", c3)
            .setRevision("c4", c4)
            .setPathPart("")
            .build();
    try (HtmlDiffFormatter formatter =
        new HtmlDiffFormatter(renderer, manyView, manyOut, new Config(), "/b/repo/+diff/c3..c4/")) {
      formatter.setRepository(repo.getRepository());
      formatter.format(formatter.scan(c3.getTree(), c4.getTree()));
    }
    String manyHtml = manyOut.toString(UTF_8);
    assertThat(countOccurrences(manyHtml, "class=\"LeanDiff\""))
        .isEqualTo(LeanRequestBudget.MAX_REQUEST_LEAN_FILES);
    assertThat(countOccurrences(manyHtml, "Diff-unified")).isEqualTo(1);
    assertThat(countOccurrences(manyHtml, ">Plain text</a>"))
        .isEqualTo(LeanRequestBudget.MAX_REQUEST_LEAN_FILES);

    ByteArrayOutputStream manyZeroOut = new ByteArrayOutputStream();
    GitilesView manyZeroView =
        GitilesView.diff()
            .copyFrom(manyView)
            .putParam(LeanRendering.LEAN_PARAM, LeanRendering.NO_LEAN_VALUE)
            .build();
    try (HtmlDiffFormatter formatter =
        new HtmlDiffFormatter(
            renderer, manyZeroView, manyZeroOut, new Config(), "/b/repo/+diff/c3..c4/")) {
      formatter.setRepository(repo.getRepository());
      formatter.format(formatter.scan(c3.getTree(), c4.getTree()));
    }
    String manyZeroHtml = manyZeroOut.toString(UTF_8);
    assertThat(countOccurrences(manyZeroHtml, "Diff-unified")).isEqualTo(51);
    assertThat(countOccurrences(manyZeroHtml, ">Rendered</a>"))
        .isEqualTo(LeanRequestBudget.MAX_REQUEST_LEAN_FILES);

    // Part 3: Late-failing oversize-line files charge scanBytesUsed and stop under tight scan cap.
    String lateFailSource =
        "-- "
            + "x".repeat(200)
            + "\ndef tail := \""
            + "a".repeat(LeanRendering.MAX_LINE_CHARS)
            + "\"\n";
    byte[] lateFailBytes = lateFailSource.getBytes(UTF_8);
    RevBlob lateFailBlob = repo.blob(lateFailBytes);
    RevCommit l1 = repo.update("late", repo.commit().add("base.txt", "ok\n"));
    var l2Builder = repo.commit().parent(l1);
    for (int i = 1; i <= 6; i++) {
      l2Builder.edit(modeEdit(String.format("L%02d.lean", i), FileMode.REGULAR_FILE, lateFailBlob));
    }
    RevCommit l2 = repo.update("late", l2Builder);

    String lateHttp = buildHtml("/repo/+diff/" + l2.name() + "^!/L01.lean", false);
    assertThat(lateHttp).doesNotContain("class=\"LeanDiff\"");
    assertThat(lateHttp).contains("Diff-unified");
    assertThat(lateHttp).doesNotContain(">Plain text</a>");

    long tightScanCapBytes = 200_000L;
    for (boolean leanZero : new boolean[] {false, true}) {
      LeanRequestBudget budget =
          LeanRequestBudget.forTesting(
              LeanRequestBudget.MAX_REQUEST_INPUT_BYTES,
              LeanRequestBudget.MAX_REQUEST_OUTPUT_CHARS,
              LeanRequestBudget.MAX_REQUEST_LEAN_FILES,
              LeanRequestBudget.MAX_REQUEST_CPU_NANOS,
              tightScanCapBytes,
              () -> 0L);
      GitilesView.Builder viewBuilder =
          GitilesView.diff()
              .setHostName("host")
              .setServletPath("/b")
              .setRepositoryName("repo")
              .setOldRevision("l1", l1)
              .setRevision("l2", l2)
              .setPathPart("");
      if (leanZero) {
        viewBuilder = viewBuilder.putParam(LeanRendering.LEAN_PARAM, LeanRendering.NO_LEAN_VALUE);
      }
      try (HtmlDiffFormatter formatter =
          new HtmlDiffFormatter(
              renderer,
              viewBuilder.build(),
              OutputStream.nullOutputStream(),
              new Config(),
              "/b/repo/+diff/l1..l2/",
              budget)) {
        formatter.setRepository(repo.getRepository());
        List<DiffEntry> entries = formatter.scan(l1.getTree(), l2.getTree());
        formatter.format(entries);
      }
      long expectedScanned = (tightScanCapBytes / lateFailBytes.length) * lateFailBytes.length;
      assertThat(budget.scanBytesUsed()).isEqualTo(expectedScanned);
      assertThat(budget.scanBytesUsed()).isAtMost(tightScanCapBytes);
      assertThat(budget.filesRendered()).isEqualTo(0);
    }
  }

  private static PathEdit modeEdit(String name, FileMode mode, RevBlob blob) {
    return new PathEdit(name) {
      @Override
      public void apply(DirCacheEntry ent) {
        ent.setFileMode(mode);
        ent.setObjectId(blob);
      }
    };
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
