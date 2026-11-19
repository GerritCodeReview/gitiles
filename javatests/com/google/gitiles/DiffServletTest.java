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
    assertThat(html).contains("href=\"/b/repo/+/" + c2.name() + "%5E%21/Math/Proof.lean?lean=0\"");
    assertThat(html).contains(">raw diff</a>");
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
                .add("New.lean", "theorem added_thm : True := by\n  trivial\n"));

    String html = buildHtml("/repo/+diff/" + c2.name() + "^!/", false);
    assertThat(html).contains("LeanDiff-block--added");
    assertThat(html).contains("LeanDiff-block--deleted");
    assertThat(html).contains("added_thm");
    assertThat(html).contains("deleted_thm");
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
  }

  @Test
  public void diffNonLeanSingleFileOmitsLeanCssFromHead() throws Exception {
    RevCommit c1 = repo.update("master", repo.commit().add("plain.txt", "a\n"));
    RevCommit c2 = repo.update("master", repo.commit().parent(c1).add("plain.txt", "b\n"));

    String html = buildHtml("/repo/+diff/" + c2.name() + "^!/plain.txt", false);
    assertThat(html).doesNotContain("lean.css");
    assertThat(html).contains("Diff-unified");
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
  }
}
