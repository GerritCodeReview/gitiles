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

import com.google.common.collect.ImmutableList;
import com.google.common.io.BaseEncoding;
import com.google.gitiles.diff.IntraLineDiffResult;
import com.google.gitiles.diff.ReplaceEdit;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffEntry.ChangeType;
import org.eclipse.jgit.diff.DiffEntry.Side;
import org.eclipse.jgit.diff.Edit;
import org.eclipse.jgit.diff.EditList;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.patch.FileHeader;
import org.eclipse.jgit.patch.Patch;
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
  }

  @Test
  public void intraLineComputeFallsBackToLineLevelOnTimeout() throws Exception {
    RawText a = new RawText("The quick brown fox\n".getBytes(UTF_8));
    RawText b = new RawText("The quick red fox\n".getBytes(UTF_8));
    EditList edits = new EditList();
    edits.add(new Edit(0, 1, 0, 1));

    CountDownLatch block = new CountDownLatch(1);
    ExecutorService occupied = Executors.newSingleThreadExecutor();
    var unused =
        occupied.submit(
            () -> {
              block.await();
              return null;
            });
    try {
      IntraLineDiffResult result =
          HtmlDiffFormatter.computeIntraLineEdits(occupied, 50, edits, a, b);
      assertThat(result.status()).isEqualTo(IntraLineDiffResult.Status.TIMEOUT);
      assertThat(result.isCacheable()).isFalse();
      assertThat(result.edits()).containsExactlyElementsIn(edits).inOrder();
      assertThat(result.edits().get(0)).isNotInstanceOf(ReplaceEdit.class);
    } finally {
      block.countDown();
      occupied.shutdownNow();
    }
  }

  @Test
  public void intraLineComputeWrapsReplaceWhenItCompletes() throws Exception {
    RawText a = new RawText("The quick brown fox\n".getBytes(UTF_8));
    RawText b = new RawText("The quick red fox\n".getBytes(UTF_8));
    EditList edits = new EditList();
    edits.add(new Edit(0, 1, 0, 1));

    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      IntraLineDiffResult result =
          HtmlDiffFormatter.computeIntraLineEdits(executor, 5000, edits, a, b);
      assertThat(result.status()).isEqualTo(IntraLineDiffResult.Status.EDIT_LIST);
      assertThat(result.isCacheable()).isTrue();
      assertThat(result.edits()).hasSize(1);
      assertThat(result.edits().get(0)).isInstanceOf(ReplaceEdit.class);
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  public void intraLineComputeFallsBackToLineLevelOnFailure() throws Exception {
    RawText a = new RawText("The quick brown fox\n".getBytes(UTF_8));
    RawText b = new RawText("The quick red fox\n".getBytes(UTF_8));
    EditList edits = new EditList();
    edits.add(new Edit(0, 1, 0, 1));

    IntraLineDiffResult result =
        HtmlDiffFormatter.computeIntraLineEdits(failingExecutor(), 5000, edits, a, b);

    assertThat(result.status()).isEqualTo(IntraLineDiffResult.Status.ERROR);
    assertThat(result.isCacheable()).isFalse();
    assertThat(result.edits()).containsExactlyElementsIn(edits).inOrder();
    assertThat(result.edits().get(0)).isNotInstanceOf(ReplaceEdit.class);
  }

  /** An executor whose submitted tasks always fail, so {@code Future.get} throws. */
  private static ExecutorService failingExecutor() {
    return new AbstractExecutorService() {
      @Override
      public <T> Future<T> submit(Callable<T> task) {
        return CompletableFuture.failedFuture(
            new IllegalStateException("intraline compute failed"));
      }

      @Override
      public void execute(Runnable command) {}

      @Override
      public void shutdown() {}

      @Override
      public ImmutableList<Runnable> shutdownNow() {
        return ImmutableList.of();
      }

      @Override
      public boolean isShutdown() {
        return true;
      }

      @Override
      public boolean isTerminated() {
        return true;
      }

      @Override
      public boolean awaitTermination(long timeout, TimeUnit unit) {
        return true;
      }
    };
  }

  @Test
  public void directCacheAlwaysComputes() throws Exception {
    IntraLineDiffResult canned = IntraLineDiffResult.editList(ImmutableList.of());
    int[] loaderCalls = {0};
    IntraLineDiffResult out =
        IntraLineDiffCache.DIRECT.get(
            new IntraLineDiffKey(ObjectId.zeroId(), ObjectId.zeroId()),
            () -> {
              loaderCalls[0]++;
              return canned;
            });
    assertThat(loaderCalls[0]).isEqualTo(1);
    assertThat(out).isSameInstanceAs(canned);
  }

  @Test
  public void noIntraLineCacheIsUsedByDefault() throws Exception {
    RevCommit c1 = repo.update("master", repo.commit().add("f", "foo bar baz\n"));
    RevCommit c2 = repo.update("master", repo.commit().parent(c1).add("f", "foo qux baz\n"));
    assertThat(buildHtml("/repo/+diff/" + c2.name() + "^!/f", false)).contains("Diff-mark");
  }

  @Test
  public void intraLineCacheIsConsultedForModifiedFile() throws Exception {
    RevCommit c1 = repo.update("master", repo.commit().add("f", "foo bar baz\n"));
    RevCommit c2 = repo.update("master", repo.commit().parent(c1).add("f", "foo qux baz\n"));
    RecordingIntraLineDiffCache cache = new RecordingIntraLineDiffCache();
    servlet =
        TestGitilesServlet.create(repo, new GitwebRedirectFilter(), new BranchRedirect(), cache);

    String actual = buildHtml("/repo/+diff/" + c2.name() + "^!/f", false);

    assertThat(cache.getCalls).isEqualTo(1);
    assertThat(cache.loaderCalls).isEqualTo(1);
    assertThat(actual).contains("Diff-mark");
  }

  @Test
  public void intraLineCacheServesSecondRenderFromCache() throws Exception {
    RevCommit c1 = repo.update("master", repo.commit().add("f", "foo bar baz\n"));
    RevCommit c2 = repo.update("master", repo.commit().parent(c1).add("f", "foo qux baz\n"));
    RecordingIntraLineDiffCache cache = new RecordingIntraLineDiffCache();
    servlet =
        TestGitilesServlet.create(repo, new GitwebRedirectFilter(), new BranchRedirect(), cache);
    String diffUrl = "/repo/+diff/" + c2.name() + "^!/f";

    buildHtml(diffUrl, false);
    buildHtml(diffUrl, false);

    assertThat(cache.getCalls).isEqualTo(2);
    assertThat(cache.loaderCalls).isEqualTo(1);
  }

  @Test
  public void intraLineCacheKeyMatchesBlobIds() throws Exception {
    ObjectId oldBlob = repo.blob("foo bar baz\n");
    ObjectId newBlob = repo.blob("foo qux baz\n");
    RevCommit c1 = repo.update("master", repo.commit().add("f", "foo bar baz\n"));
    RevCommit c2 = repo.update("master", repo.commit().parent(c1).add("f", "foo qux baz\n"));
    RecordingIntraLineDiffCache cache = new RecordingIntraLineDiffCache();
    servlet =
        TestGitilesServlet.create(repo, new GitwebRedirectFilter(), new BranchRedirect(), cache);

    buildHtml("/repo/+diff/" + c2.name() + "^!/f", false);

    assertThat(cache.lastKey).isNotNull();
    assertThat(cache.lastKey.blobA()).isEqualTo(oldBlob);
    assertThat(cache.lastKey.blobB()).isEqualTo(newBlob);
  }

  @Test
  public void intraLineCacheSkippedForAddedFile() throws Exception {
    RevCommit c1 = repo.update("master", repo.commit().add("kept", "x\n"));
    RevCommit c2 =
        repo.update(
            "master", repo.commit().parent(c1).add("kept", "x\n").add("added", "brand new\n"));
    RecordingIntraLineDiffCache cache = new RecordingIntraLineDiffCache();
    servlet =
        TestGitilesServlet.create(repo, new GitwebRedirectFilter(), new BranchRedirect(), cache);

    buildHtml("/repo/+diff/" + c2.name() + "^!/added", false);

    assertThat(cache.getCalls).isEqualTo(0);
  }

  @Test
  public void nonCacheableResultIsNotStored() throws Exception {
    RecordingIntraLineDiffCache cache = new RecordingIntraLineDiffCache();
    IntraLineDiffKey key = new IntraLineDiffKey(ObjectId.zeroId(), ObjectId.zeroId());
    Callable<IntraLineDiffResult> timeoutLoader =
        () -> IntraLineDiffResult.timeout(ImmutableList.of());

    cache.get(key, timeoutLoader);
    cache.get(key, timeoutLoader);

    assertThat(cache.getCalls).isEqualTo(2);
    assertThat(cache.loaderCalls).isEqualTo(2);
  }

  @Test
  public void cacheBackendFailureFallsBackToLineLevel() throws Exception {
    RevCommit c1 = repo.update("master", repo.commit().add("f", "foo bar baz\n"));
    RevCommit c2 = repo.update("master", repo.commit().parent(c1).add("f", "foo qux baz\n"));
    IntraLineDiffCache failing =
        (key, loader) -> {
          throw new RuntimeException("cache backend down");
        };
    servlet =
        TestGitilesServlet.create(repo, new GitwebRedirectFilter(), new BranchRedirect(), failing);

    String actual = buildHtml("/repo/+diff/" + c2.name() + "^!/f", false);

    assertThat(actual).contains("Diff-delete");
    assertThat(actual).doesNotContain("Diff-mark");
    assertThat(actual).doesNotContain("Diff-intraline");
  }

  private static final class RecordingIntraLineDiffCache implements IntraLineDiffCache {
    int getCalls;
    int loaderCalls;
    IntraLineDiffKey lastKey;
    private final Map<IntraLineDiffKey, IntraLineDiffResult> store = new HashMap<>();

    @Override
    public IntraLineDiffResult get(IntraLineDiffKey key, Callable<IntraLineDiffResult> loader)
        throws Exception {
      getCalls++;
      lastKey = key;
      IntraLineDiffResult cached = store.get(key);
      if (cached != null) {
        return cached;
      }
      loaderCalls++;
      IntraLineDiffResult result = loader.call();
      if (result.isCacheable()) {
        store.put(key, result);
      }
      return result;
    }
  }

  @Test
  public void diffFileHtmlHighlightsIntralineChanges() throws Exception {
    assertFileDiffHtmlContains(
        "foo bar baz\n",
        "foo qux baz\n",
        "<span class=\"Diff-delete Diff-intraline\">foo <span class=\"Diff-mark\">bar</span>"
            + " baz</span>",
        "<span class=\"Diff-insert Diff-intraline\">foo <span class=\"Diff-mark\">qux</span>"
            + " baz</span>");
  }

  @Test
  public void diffFileHtmlHighlightsCompleteRewrite() throws Exception {
    assertFileDiffHtmlContains(
        "abc1\n",
        "def2\n",
        "<span class=\"Diff-delete Diff-intraline\"><span class=\"Diff-mark\">abc1</span>"
            + "</span>",
        "<span class=\"Diff-insert Diff-intraline\"><span class=\"Diff-mark\">def2</span>"
            + "</span>");
  }

  @Test
  public void diffFileHtmlHighlightsRewriteAtStartOfLine() throws Exception {
    assertFileDiffHtmlContains(
        "abc1\n",
        "def1\n",
        "<span class=\"Diff-delete Diff-intraline\"><span class=\"Diff-mark\">abc</span>"
            + "1</span>",
        "<span class=\"Diff-insert Diff-intraline\"><span class=\"Diff-mark\">def</span>"
            + "1</span>");
  }

  @Test
  public void diffFileHtmlHighlightsRewriteAtEndOfLine() throws Exception {
    assertFileDiffHtmlContains(
        "abc1\n",
        "abc2\n",
        "<span class=\"Diff-delete Diff-intraline\">abc<span class=\"Diff-mark\">1</span>"
            + "</span>",
        "<span class=\"Diff-insert Diff-intraline\">abc<span class=\"Diff-mark\">2</span>"
            + "</span>");
  }

  @Test
  public void diffFileHtmlCombinesCloseEdits() throws Exception {
    assertFileDiffHtmlContains(
        "ab1cdef2gh\n",
        "ab2cdef3gh\n",
        "<span class=\"Diff-delete Diff-intraline\">ab<span class=\"Diff-mark\">1cdef2</span>"
            + "gh</span>",
        "<span class=\"Diff-insert Diff-intraline\">ab<span class=\"Diff-mark\">2cdef3</span>"
            + "gh</span>");
  }

  @Test
  public void diffFileHtmlPrefersInsertAfterCommonPart() throws Exception {
    assertFileDiffHtmlContains(
        "start middle end\n",
        "start middlemiddle end\n",
        "<span class=\"Diff-delete Diff-intraline\">start middle end</span>",
        "<span class=\"Diff-insert Diff-intraline\">start middle<span class=\"Diff-mark\">middle"
            + "</span> end</span>");
  }

  @Test
  public void diffFileHtmlPrefersInsertedWhitespaceAfterCommonPart() throws Exception {
    assertFileDiffHtmlContains(
        "abc def\n",
        "abc  def\n",
        "<span class=\"Diff-delete Diff-intraline\">abc def</span>",
        "<span class=\"Diff-insert Diff-intraline\">abc <span class=\"Diff-mark\"> </span>"
            + "def</span>");
  }

  @Test
  public void diffFileHtmlHighlightsInsertedWhitespace() throws Exception {
    assertFileDiffHtmlContains(
        " int *foobar\n",
        " int * foobar\n",
        "<span class=\"Diff-delete Diff-intraline\"> int *foobar</span>",
        "<span class=\"Diff-insert Diff-intraline\"> int *<span class=\"Diff-mark\"> </span>"
            + "foobar</span>");
  }

  @Test
  public void diffFileHtmlHighlightsInsertedWhitespaceInMultipleLines() throws Exception {
    assertFileDiffHtmlContains(
        " int *foobar\n int *foobar\n",
        " int * foobar\n int * foobar\n",
        "<span class=\"Diff-delete Diff-intraline\"> int *foobar</span>\n"
            + "<span class=\"Diff-delete Diff-intraline\"> int *foobar</span>",
        "<span class=\"Diff-insert Diff-intraline\"> int *<span class=\"Diff-mark\"> </span>"
            + "foobar</span>\n"
            + "<span class=\"Diff-insert Diff-intraline\"> int *<span class=\"Diff-mark\"> </span>"
            + "foobar</span>");
  }

  @Test
  public void diffFileHtmlHighlightsWhollyChangedLine() throws Exception {
    assertFileDiffHtmlContains(
        "alpha\n",
        "12345\n",
        "<span class=\"Diff-delete Diff-intraline\"><span class=\"Diff-mark\">alpha</span>"
            + "</span>",
        "<span class=\"Diff-insert Diff-intraline\"><span class=\"Diff-mark\">12345</span>"
            + "</span>");
  }

  @Test
  public void diffFileHtmlEscapesMarkedChanges() throws Exception {
    assertFileDiffHtmlContains(
        "foo <bar> baz\n",
        "foo &bar& baz\n",
        "<span class=\"Diff-delete Diff-intraline\">foo <span class=\"Diff-mark\">&lt;bar&gt;"
            + "</span> baz</span>",
        "<span class=\"Diff-insert Diff-intraline\">foo <span class=\"Diff-mark\">&amp;bar&amp;"
            + "</span> baz</span>");
  }

  @Test
  public void diffFileHtmlUsesDiffDriverHunkFunctionName() throws Exception {
    String contents1 =
        "public class Example {\n"
            + "\n"
            + "  public <T> T identity(T value) {\n"
            + "    Object before = value;\n"
            + "    Object middle = before;\n"
            + "    Object after = middle;\n"
            + "    return value;\n"
            + "  }\n"
            + "}\n";
    String contents2 = contents1.replace("return value;", "return null;");
    RevCommit c1 =
        repo.update(
            "master",
            repo.commit().add(".gitattributes", "*.java diff=java").add("Example.java", contents1));
    RevCommit c2 = repo.update("master", repo.commit().parent(c1).add("Example.java", contents2));

    String actual = buildHtml("/repo/+diff/" + c2.name() + "^!/Example.java", false);

    assertThat(actual)
        .contains(
            "<span class=\"Diff-hunk\">@@ -4,6 +4,6 @@ public &lt;T&gt; T identity(T value) {");
  }

  @Test
  public void diffFileHtmlUsesDiffDriverHunkFunctionNameOnFirstLine() throws Exception {
    String contents1 =
        "public class Foo {\n"
            + "  int a = 1;\n"
            + "  int b = 2;\n"
            + "  int c = 3;\n"
            + "  int d = 4;\n"
            + "  int e = 5;\n"
            + "}\n";
    String contents2 = contents1.replace("int d = 4;", "int d = 9;");
    RevCommit c1 =
        repo.update(
            "master",
            repo.commit().add(".gitattributes", "*.java diff=java").add("Foo.java", contents1));
    RevCommit c2 = repo.update("master", repo.commit().parent(c1).add("Foo.java", contents2));

    String actual = buildHtml("/repo/+diff/" + c2.name() + "^!/Foo.java", false);

    assertThat(actual).contains("@@ public class Foo {");
  }

  @Test
  public void diffFileHtmlHighlightsIntralineWithNoNewlineAtEof() throws Exception {
    RevCommit c1 = repo.update("master", repo.commit().add("f", "foo bar baz"));
    RevCommit c2 = repo.update("master", repo.commit().parent(c1).add("f", "foo qux baz"));

    String actual = buildHtml("/repo/+diff/" + c2.name() + "^!/f", false);

    assertThat(actual)
        .contains(
            "<span class=\"Diff-delete Diff-intraline\">foo <span class=\"Diff-mark\">bar</span>"
                + " baz</span>");
    assertThat(actual)
        .contains(
            "<span class=\"Diff-insert Diff-intraline\">foo <span class=\"Diff-mark\">qux</span>"
                + " baz</span>");
    assertThat(actual).contains("\\ No newline at end of file");
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

  private void assertFileDiffHtmlContains(String contents1, String contents2, String... expected)
      throws Exception {
    String actual = buildFileDiffHtml(contents1, contents2);
    for (String html : expected) {
      assertThat(actual).contains(html);
    }
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

  private String buildFileDiffHtml(String contents1, String contents2) throws Exception {
    RevCommit c1 = repo.update("master", repo.commit().add("f", contents1));
    RevCommit c2 = repo.update("master", repo.commit().parent(c1).add("f", contents2));
    return buildHtml("/repo/+diff/" + c2.name() + "^!/f", false);
  }

  private static Patch parsePatch(byte[] enc) {
    byte[] buf = BaseEncoding.base64().decode(new String(enc, UTF_8));
    Patch p = new Patch();
    p.parse(buf, 0, buf.length);
    assertThat(p.getErrors()).isEqualTo(ImmutableList.of());
    return p;
  }
}
