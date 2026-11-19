// Copyright (C) 2014 Google Inc. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.gitiles;

import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.UTF_16LE;
import static java.nio.charset.StandardCharsets.UTF_8;
import static javax.servlet.http.HttpServletResponse.SC_BAD_REQUEST;
import static javax.servlet.http.HttpServletResponse.SC_NOT_FOUND;
import static javax.servlet.http.HttpServletResponse.SC_OK;

import com.google.common.io.BaseEncoding;
import com.google.common.net.HttpHeaders;
import com.google.gitiles.FileJsonData.File;
import com.google.gitiles.GitlinkJsonData.Gitlink;
import com.google.gitiles.TreeJsonData.PathList;
import com.google.gitiles.TreeJsonData.Tree;
import com.google.gitiles.lean.LeanRendering;
import com.google.template.soy.data.SoyListData;
import com.google.template.soy.data.restricted.StringData;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.eclipse.jgit.dircache.DirCacheEditor.PathEdit;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.revwalk.RevBlob;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link PathServlet}. */
@SuppressWarnings("unchecked")
@RunWith(JUnit4.class)
public class PathServletTest extends ServletTest {
  @Test
  public void rootTreeHtml() throws Exception {
    repo.branch("master").commit().add("foo", "contents").create();

    Map<String, ?> data = buildData("/repo/+/master/");
    assertThat(data).containsEntry("type", "TREE");
    List<Map<String, ?>> entries = getTreeEntries(data);
    assertThat(entries).hasSize(1);
    assertThat(entries.get(0).get("name")).isEqualTo("foo");
  }

  @Test
  public void rootTreeHtmlHasGrepLink() throws Exception {
    repo.branch("master").commit().add("foo", "contents").create();

    FakeHttpServletResponse res = buildResponse("/repo/+/master/", "format=html", SC_OK);

    assertThat(res.getActualBodyString()).contains("href=\"/b/repo/+grep/master/\"");
  }

  @Test
  public void subTreeHtmlHasPathScopedGrepLink() throws Exception {
    repo.branch("master").commit().add("src/a.txt", "contents").create();

    FakeHttpServletResponse res = buildResponse("/repo/+/master/src", "format=html", SC_OK);

    assertThat(res.getActualBodyString()).contains("href=\"/b/repo/+grep/master/src\"");
  }

  @Test
  public void subTreeHtml() throws Exception {
    repo.branch("master")
        .commit()
        .add("foo/bar", "bar contents")
        .add("baz", "baz contents")
        .create();

    Map<String, ?> data = buildData("/repo/+/master/");
    assertThat(data).containsEntry("type", "TREE");
    List<Map<String, ?>> entries = getTreeEntries(data);
    assertThat(entries).hasSize(2);
    assertThat(entries.get(0).get("name")).isEqualTo("foo/");
    assertThat(entries.get(1).get("name")).isEqualTo("baz");

    data = buildData("/repo/+/master/foo");
    assertThat(data).containsEntry("type", "TREE");
    entries = getTreeEntries(data);
    assertThat(entries).hasSize(1);
    assertThat(entries.get(0).get("name")).isEqualTo("bar");

    data = buildData("/repo/+/master/foo/");
    assertThat(data).containsEntry("type", "TREE");
    entries = getTreeEntries(data);
    assertThat(entries).hasSize(1);
    assertThat(entries.get(0).get("name")).isEqualTo("bar");
  }

  @Test
  public void fileHtml() throws Exception {
    repo.branch("master").commit().add("foo", "foo\ncontents\n").create();

    Map<String, ?> data = buildData("/repo/+/master/foo");
    assertThat(data).containsEntry("type", "REGULAR_FILE");

    SoyListData lines = (SoyListData) getBlobData(data).get("lines");
    assertThat(lines.length()).isEqualTo(2);

    SoyListData spans = lines.getListData(0);
    assertThat(spans.length()).isEqualTo(1);
    assertThat(spans.getMapData(0).get("classes")).isEqualTo(StringData.forValue("pln"));
    assertThat(spans.getMapData(0).get("text")).isEqualTo(StringData.forValue("foo"));

    spans = lines.getListData(1);
    assertThat(spans.length()).isEqualTo(1);
    assertThat(spans.getMapData(0).get("classes")).isEqualTo(StringData.forValue("pln"));
    assertThat(spans.getMapData(0).get("text")).isEqualTo(StringData.forValue("contents"));
  }

  @Test
  public void editUrl() throws Exception {
    repo.branch("master").commit().add("editFoo", "Content").create();
    repo.reset("refs/heads/master");

    Map<String, ?> data = buildData("/repo/+/refs/heads/master/editFoo");

    String editUrl = (String) getBlobData(data).get("editUrl");
    String testUrl =
        "http://test-host-review/path-prefix/admin/repos/edit/repo/repo/branch/refs/heads/master/file/editFoo";
    assertThat(editUrl).isEqualTo(testUrl);
  }

  @Test
  public void fileWithMaxLines() throws Exception {
    int MAX_LINE_COUNT = 50000;
    StringBuilder contentBuilder = new StringBuilder();
    for (int i = 1; i < MAX_LINE_COUNT; i++) {
      contentBuilder.append("\n");
    }
    repo.branch("master").commit().add("bar", contentBuilder.toString()).create();

    Map<String, ?> data = buildData("/repo/+/master/bar");
    SoyListData lines = (SoyListData) getBlobData(data).get("lines");
    assertThat(lines.length()).isEqualTo(MAX_LINE_COUNT - 1);
  }

  @Test
  public void fileLargerThanSupportedLines() throws Exception {
    int MAX_LINE_COUNT = 50000;
    StringBuilder contentBuilder = new StringBuilder();
    for (int i = 1; i <= MAX_LINE_COUNT; i++) {
      contentBuilder.append("\n");
    }
    repo.branch("master").commit().add("largebar", contentBuilder.toString()).create();

    Map<String, ?> data = buildData("/repo/+/master/largebar");
    SoyListData lines = (SoyListData) getBlobData(data).get("lines");
    assertThat(lines).isNull();
  }

  @Test
  public void largeFileHtml() throws Exception {
    int largeContentSize = BlobSoyData.MAX_FILE_SIZE + 1;
    repo.branch("master").commit().add("foo", generateContent(largeContentSize)).create();

    Map<String, ?> data = (Map<String, ?>) buildData("/repo/+/master/foo").get("data");
    assertThat(data).containsEntry("lines", null);
    assertThat(data).containsEntry("size", "" + largeContentSize);
  }

  private static String generateContent(int contentSize) {
    char[] str = new char[contentSize];
    for (int i = 0; i < contentSize; i++) {
      str[i] = (char) ('0' + (i % 78));
    }
    return new String(str);
  }

  @Test
  public void symlinkHtml() throws Exception {
    testSymlink("foo", "bar", "foo");
  }

  @Test
  public void relativeSymlinkHtml() throws Exception {
    testSymlink("foo/bar", "foo/baz", "./bar");
  }

  @Test
  public void gitlinkHtml() throws Exception {
    String gitmodules =
        "[submodule \"gitiles\"]\n"
            + "  path = gitiles\n"
            + "  url = https://gerrit.googlesource.com/gitiles\n";
    final String gitilesSha = "2b2f34bba3c2be7e2506ce6b1f040949da350cf9";
    repo.branch("master")
        .commit()
        .add(".gitmodules", gitmodules)
        .edit(
            new PathEdit("gitiles") {
              @Override
              public void apply(DirCacheEntry ent) {
                ent.setFileMode(FileMode.GITLINK);
                ent.setObjectId(ObjectId.fromString(gitilesSha));
              }
            })
        .create();

    Map<String, ?> data = buildData("/repo/+/master/gitiles");
    assertThat(data).containsEntry("type", "GITLINK");

    Map<String, ?> linkData = getBlobData(data);
    assertThat(linkData).containsEntry("sha", gitilesSha);
    assertThat(linkData).containsEntry("remoteUrl", "https://gerrit.googlesource.com/gitiles");
    assertThat(linkData).containsEntry("httpUrl", "https://gerrit.googlesource.com/gitiles");
  }

  @Test
  public void blobText() throws Exception {
    repo.branch("master").commit().add("foo", "contents").create();
    String text = buildBlob("/repo/+/master/foo", "100644");
    assertThat(text).isEqualTo("contents");
  }

  @Test
  public void markdownBlobText() throws Exception {
    repo.branch("master").commit().add("foo.md", "# Markdown\ncontents\n").create();
    String text = buildBlob("/repo/+/master/foo.md", "100644");
    assertThat(text).isEqualTo("# Markdown\ncontents\n");
  }

  @Test
  public void fileJson() throws Exception {
    RevBlob blob = repo.blob("contents");
    repo.branch("master").commit().add("path/to/file", blob).create();

    File file = buildJson(File.class, "/repo/+/master/path/to/file");

    assertThat(file.id).isEqualTo(blob.name());
    assertThat(file.repo).isEqualTo("repo");
    assertThat(file.revision).isEqualTo("master");
    assertThat(file.path).isEqualTo("path/to/file");
  }

  @Test
  public void markdownFileJson() throws Exception {
    RevBlob blob = repo.blob("# Markdown\ncontents\n");
    repo.branch("master").commit().add("foo.md", blob).create();

    File file = buildJson(File.class, "/repo/+/master/foo.md");

    assertThat(file.id).isEqualTo(blob.name());
    assertThat(file.repo).isEqualTo("repo");
    assertThat(file.revision).isEqualTo("master");
    assertThat(file.path).isEqualTo("foo.md");
  }

  @Test
  public void symlinkText() throws Exception {
    final RevBlob link = repo.blob("foo");
    repo.branch("master")
        .commit()
        .edit(
            new PathEdit("baz") {
              @Override
              public void apply(DirCacheEntry ent) {
                ent.setFileMode(FileMode.SYMLINK);
                ent.setObjectId(link);
              }
            })
        .create();
    String text = buildBlob("/repo/+/master/baz", "120000");
    assertThat(text).isEqualTo("foo");
  }

  @Test
  public void treeText() throws Exception {
    RevBlob blob = repo.blob("contents");
    RevTree tree = repo.tree(repo.file("foo/bar", blob));
    repo.branch("master").commit().setTopLevelTree(tree).create();

    String expected = "040000 tree " + repo.get(tree, "foo").name() + "\tfoo\n";
    assertThat(buildBlob("/repo/+/master/", "040000")).isEqualTo(expected);

    expected = "100644 blob " + blob.name() + "\tbar\n";
    assertThat(buildBlob("/repo/+/master/foo", "040000")).isEqualTo(expected);
    assertThat(buildBlob("/repo/+/master/foo/", "040000")).isEqualTo(expected);
  }

  @Test
  public void treeTextEscaped() throws Exception {
    RevBlob blob = repo.blob("contents");
    repo.branch("master").commit().add("foo\nbar\rbaz", blob).create();

    assertThat(buildBlob("/repo/+/master/", "040000"))
        .isEqualTo("100644 blob " + blob.name() + "\t\"foo\\nbar\\rbaz\"\n");
  }

  @Test
  public void commitText() throws Exception {
    String gitmodules =
        "[submodule \"gitiles\"]\n"
            + "  path = gitiles\n"
            + "  url = https://gerrit.googlesource.com/gitiles\n";
    final String gitilesSha = "2b2f34bba3c2be7e2506ce6b1f040949da350cf9";
    repo.branch("master")
        .commit()
        .add("foo/bar", "contents")
        .add(".gitmodules", gitmodules)
        .edit(
            new PathEdit("gitiles") {
              @Override
              public void apply(DirCacheEntry ent) {
                ent.setFileMode(FileMode.GITLINK);
                ent.setObjectId(ObjectId.fromString(gitilesSha));
              }
            })
        .create();

    assertThat(buildBlob("/repo/+/master/gitiles", "160000")).isEqualTo(gitilesSha);
  }

  @Test
  public void notFoundText() throws Exception {
    assertNotFound("/repo/+/master/nonexistent", "format=text");
  }

  @Test
  public void treeJsonSizes() throws Exception {
    RevCommit c = repo.parseBody(repo.branch("master").commit().add("baz", "01234567").create());

    Tree tree = buildJson(Tree.class, "/repo/+/master/", "long=1");

    assertThat(tree.id).isEqualTo(c.getTree().name());
    assertThat(tree.entries).hasSize(1);
    assertThat(tree.entries.get(0).mode).isEqualTo(0100644);
    assertThat(tree.entries.get(0).type).isEqualTo("blob");
    assertThat(tree.entries.get(0).name).isEqualTo("baz");
    assertThat(tree.entries.get(0).size).isEqualTo(8);
  }

  @Test
  public void treeJsonLinkTarget() throws Exception {
    final ObjectId targetID = repo.blob("target");
    RevCommit c =
        repo.parseBody(
            repo.branch("master")
                .commit()
                .edit(
                    new PathEdit("link") {
                      @Override
                      public void apply(DirCacheEntry ent) {
                        ent.setFileMode(FileMode.SYMLINK);
                        ent.setObjectId(targetID);
                      }
                    })
                .create());

    Tree tree = buildJson(Tree.class, "/repo/+/master/", "long=1");

    assertThat(tree.id).isEqualTo(c.getTree().name());
    assertThat(tree.entries).hasSize(1);

    TreeJsonData.Entry e = tree.entries.get(0);
    assertThat(e.mode).isEqualTo(0120000);
    assertThat(e.type).isEqualTo("blob");
    assertThat(e.name).isEqualTo("link");
    assertThat(e.id).isEqualTo(targetID.name());
    assertThat(e.target).isEqualTo("target");
  }

  @Test
  public void treeJsonRecursive() throws Exception {
    RevCommit c =
        repo.parseBody(
            repo.branch("master")
                .commit()
                .add("foo/baz/bar/a", "bar contents")
                .add("foo/baz/bar/b", "bar contents")
                .add("baz", "baz contents")
                .create());
    Tree tree = buildJson(Tree.class, "/repo/+/master/", "recursive=1");

    assertThat(tree.id).isEqualTo(c.getTree().name());
    assertThat(tree.entries).hasSize(3);

    assertThat(tree.entries.get(0).name).isEqualTo("baz");
    assertThat(tree.entries.get(1).name).isEqualTo("foo/baz/bar/a");
    assertThat(tree.entries.get(2).name).isEqualTo("foo/baz/bar/b");

    tree = buildJson(Tree.class, "/repo/+/master/foo/baz", "recursive=1");

    assertThat(tree.entries).hasSize(2);

    assertThat(tree.entries.get(0).name).isEqualTo("bar/a");
    assertThat(tree.entries.get(1).name).isEqualTo("bar/b");
  }

  @Test
  public void treeJson() throws Exception {
    RevCommit c =
        repo.parseBody(
            repo.branch("master")
                .commit()
                .add("foo/bar", "bar contents")
                .add("baz", "baz contents")
                .create());

    Tree tree = buildJson(Tree.class, "/repo/+/master/");
    assertThat(tree.id).isEqualTo(c.getTree().name());
    assertThat(tree.entries).hasSize(2);
    assertThat(tree.entries.get(0).mode).isEqualTo(0100644);
    assertThat(tree.entries.get(0).type).isEqualTo("blob");
    assertThat(tree.entries.get(0).id).isEqualTo(repo.get(c.getTree(), "baz").name());
    assertThat(tree.entries.get(0).name).isEqualTo("baz");
    assertThat(tree.entries.get(1).mode).isEqualTo(040000);
    assertThat(tree.entries.get(1).type).isEqualTo("tree");
    assertThat(tree.entries.get(1).id).isEqualTo(repo.get(c.getTree(), "foo").name());
    assertThat(tree.entries.get(1).name).isEqualTo("foo");

    tree = buildJson(Tree.class, "/repo/+/master/foo");
    assertThat(tree.id).isEqualTo(repo.get(c.getTree(), "foo").name());
    assertThat(tree.entries).hasSize(1);
    assertThat(tree.entries.get(0).mode).isEqualTo(0100644);
    assertThat(tree.entries.get(0).type).isEqualTo("blob");
    assertThat(tree.entries.get(0).id).isEqualTo(repo.get(c.getTree(), "foo/bar").name());
    assertThat(tree.entries.get(0).name).isEqualTo("bar");

    tree = buildJson(Tree.class, "/repo/+/master/foo/");
    assertThat(tree.id).isEqualTo(repo.get(c.getTree(), "foo").name());
    assertThat(tree.entries).hasSize(1);
    assertThat(tree.entries.get(0).mode).isEqualTo(0100644);
    assertThat(tree.entries.get(0).type).isEqualTo("blob");
    assertThat(tree.entries.get(0).id).isEqualTo(repo.get(c.getTree(), "foo/bar").name());
    assertThat(tree.entries.get(0).name).isEqualTo("bar");
  }

  @Test
  public void commitJson() throws Exception {
    String gitmodules =
        "[submodule \"gitiles\"]\n"
            + "  path = gitiles\n"
            + "  url = https://gerrit.googlesource.com/gitiles\n";
    final String gitilesSha = "2b2f34bba3c2be7e2506ce6b1f040949da350cf9";
    repo.branch("master")
        .commit()
        .add("foo/bar", "contents")
        .add(".gitmodules", gitmodules)
        .edit(
            new PathEdit("gitiles") {
              @Override
              public void apply(DirCacheEntry ent) {
                ent.setFileMode(FileMode.GITLINK);
                ent.setObjectId(ObjectId.fromString(gitilesSha));
              }
            })
        .create();

    Gitlink commit = buildJson(Gitlink.class, "/repo/+/master/gitiles");
    assertThat(commit.repo).isEqualTo("repo");
    assertThat(commit.url).isEqualTo("https://gerrit.googlesource.com/gitiles");
    assertThat(commit.revision).isEqualTo(gitilesSha);
    assertThat(commit.path).isEqualTo("gitiles");
  }

  @Test
  public void allowOrigin() throws Exception {
    repo.branch("master").commit().add("foo", "contents").create();
    FakeHttpServletResponse res = buildText("/repo/+/master/foo");
    assertThat(res.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN))
        .isEqualTo("http://localhost");
  }

  @Test
  public void rejectOrigin() throws Exception {
    repo.branch("master").commit().add("foo", "contents").create();
    FakeHttpServletResponse res =
        buildResponse("/repo/+/master/foo", "format=text", SC_OK, "http://notlocalhost");
    assertThat(res.getHeader(HttpHeaders.CONTENT_TYPE)).isEqualTo("text/plain");
    assertThat(res.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).isEqualTo(null);
  }

  private void testSymlink(String linkTarget, String linkName, String linkContent)
      throws Exception {
    final RevBlob linkBlob = repo.blob(linkContent);
    repo.branch("master")
        .commit()
        .add(linkTarget, "contents")
        .edit(
            new PathEdit(linkName) {
              @Override
              public void apply(DirCacheEntry ent) {
                ent.setFileMode(FileMode.SYMLINK);
                ent.setObjectId(linkBlob);
              }
            })
        .create();

    Map<String, ?> data = buildData("/repo/+/master/" + linkName);
    assertThat(data).containsEntry("type", "SYMLINK");
    assertThat(getBlobData(data)).containsEntry("target", linkContent);
    assertThat(getBlobData(data)).containsEntry("targetUrl", "/b/repo/+/master/" + linkTarget);
  }

  @Test
  public void pathsOnlyJsonListsBlobPaths() throws Exception {
    RevCommit c =
        repo.parseBody(
            repo.branch("master")
                .commit()
                .add("foo/baz/bar/a", "bar contents")
                .add("foo/baz/bar/b", "bar contents")
                .add("baz", "baz contents")
                .create());

    PathList pl = buildJson(PathList.class, "/repo/+/master/", "recursive=1&paths_only=1");

    assertThat(pl.id).isEqualTo(c.getTree().name());
    assertThat(pl.paths).containsExactly("baz", "foo/baz/bar/a", "foo/baz/bar/b").inOrder();
  }

  @Test
  public void pathsOnlyJsonScopesToSubdirectory() throws Exception {
    RevCommit c =
        repo.parseBody(
            repo.branch("master")
                .commit()
                .add("foo/baz/bar/a", "bar contents")
                .add("foo/baz/bar/b", "bar contents")
                .add("baz", "baz contents")
                .create());

    PathList pl = buildJson(PathList.class, "/repo/+/master/foo/baz", "recursive=1&paths_only=1");

    assertThat(pl.id).isEqualTo(repo.get(c.getTree(), "foo/baz").name());
    assertThat(pl.paths).containsExactly("bar/a", "bar/b").inOrder();
  }

  @Test
  public void pathsOnlyJsonSkipsGitlinks() throws Exception {
    final ObjectId gitlinkId = ObjectId.fromString("deadbeefdeadbeefdeadbeefdeadbeefdeadbeef");
    repo.branch("master")
        .commit()
        .add("a/file.txt", "contents")
        .edit(
            new PathEdit("sub/module") {
              @Override
              public void apply(DirCacheEntry ent) {
                ent.setFileMode(FileMode.GITLINK);
                ent.setObjectId(gitlinkId);
              }
            })
        .add("z/other.txt", "contents")
        .create();

    PathList pl = buildJson(PathList.class, "/repo/+/master/", "recursive=1&paths_only=1");

    assertThat(pl.paths).containsExactly("a/file.txt", "z/other.txt").inOrder();
  }

  @Test
  public void pathsOnlyJsonOmitsPerEntryMetadata() throws Exception {
    repo.branch("master").commit().add("foo", "contents").create();

    FakeHttpServletResponse res =
        buildResponse("/repo/+/master/", "format=JSON&recursive=1&paths_only=1", SC_OK);
    String body = res.getActualBodyString();

    assertThat(body).contains("\"foo\"");
    assertThat(body).doesNotContain("\"mode\"");
    assertThat(body).doesNotContain("\"entries\"");
  }

  @Test
  public void pathsOnlyRequiresRecursive() throws Exception {
    repo.branch("master").commit().add("foo", "contents").create();

    buildResponse("/repo/+/master/", "format=JSON&paths_only=1", SC_BAD_REQUEST);
  }

  @Test
  public void pathsOnlyRejectsLong() throws Exception {
    repo.branch("master").commit().add("foo", "contents").create();

    buildResponse("/repo/+/master/", "format=JSON&recursive=1&paths_only=1&long=1", SC_BAD_REQUEST);
  }

  /**
   * A recursive listing of a non-tree is already {@code 404} today, because {@code
   * WalkResult.recursivePath} returns null for it. {@code paths_only} inherits that, so it needs no
   * separate type check of its own.
   */
  @Test
  public void pathsOnlyOnBlobIsNotFound() throws Exception {
    repo.branch("master").commit().add("foo", "contents").create();

    buildResponse("/repo/+/master/foo", "format=JSON&recursive=1&paths_only=1", SC_NOT_FOUND);
  }

  /**
   * The listing must be exhaustive. A caller cannot distinguish a path that was omitted from a path
   * that does not exist, so a partial listing is a correctness bug rather than a load mitigation.
   * There is deliberately no configurable bound.
   */
  @Test
  public void pathsOnlyListingIsComplete() throws Exception {
    int n = 500;
    var commit = repo.branch("master").commit();
    List<String> expected = new ArrayList<>(n);
    for (int i = 0; i < n; i++) {
      String path = String.format("d%02d/f%04d.txt", i % 20, i);
      commit.add(path, "contents");
      expected.add(path);
    }
    commit.create();
    Collections.sort(expected);

    PathList pl = buildJson(PathList.class, "/repo/+/master/", "recursive=1&paths_only=1");

    assertThat(pl.paths).containsExactlyElementsIn(expected).inOrder();
  }

  /**
   * The file finder pins its fetch URL to a resolved SHA precisely so that {@link
   * BaseServlet#setCacheHeaders} takes its cacheable branch. Serving the same listing under a
   * branch name yields {@code no-store}, which would defeat client caching entirely.
   */
  @Test
  public void pathsOnlyBySha1IsCacheableButByBranchIsNot() throws Exception {
    RevCommit c = repo.branch("master").commit().add("foo", "contents").create();

    FakeHttpServletResponse bySha =
        buildResponse("/repo/+/" + c.name() + "/", "format=JSON&recursive=1&paths_only=1", SC_OK);
    assertThat(bySha.getHeader(HttpHeaders.CACHE_CONTROL)).contains("max-age=7200");

    FakeHttpServletResponse byBranch =
        buildResponse("/repo/+/master/", "format=JSON&recursive=1&paths_only=1", SC_OK);
    assertThat(byBranch.getHeader(HttpHeaders.CACHE_CONTROL)).contains("no-store");
  }

  /**
   * {@code /repo/+/<rev>} without a trailing slash parses to a REVISION view and is served by
   * {@link RevisionServlet}, not {@link PathServlet}. Clients building the listing URL must keep
   * the trailing slash.
   */
  @Test
  public void pathsOnlyNeedsTrailingSlashToReachTheTree() throws Exception {
    RevCommit c = repo.branch("master").commit().add("foo", "contents").create();
    String query = "format=JSON&recursive=1&paths_only=1";

    FakeHttpServletResponse withSlash = buildResponse("/repo/+/" + c.name() + "/", query, SC_OK);
    assertThat(withSlash.getActualBodyString()).contains("\"paths\"");

    FakeHttpServletResponse withoutSlash = buildResponse("/repo/+/" + c.name(), query, SC_OK);
    assertThat(withoutSlash.getActualBodyString()).doesNotContain("\"paths\"");
  }

  @Test
  public void leanFileRichBlobToggleFormatsAndBlameUnaffected() throws Exception {
    String leanSource =
        """
        /-!
        # Example Module

        [blocked](//evil.example/phish)
        -/

        /-- Adds zero on the right. -/
        theorem add_zero_eq (n : Nat) : n + 0 = n := by
          sorry
        """;
    String mainSource = "theorem t : True := by\n  trivial\n";
    RevBlob mainBlob = repo.blob(mainSource);
    String hostileBranch = "feat\"'<&>test";
    String hostilePath = "dir/a\"'<>& .lean";
    repo.branch("master")
        .commit()
        .add("Math/Basic.lean", leanSource)
        .add("Main.lean", mainBlob)
        .create();
    repo.branch(hostileBranch)
        .commit()
        .add(hostilePath, "theorem t : True := by trivial\n")
        .create();

    String html = buildHtml("/repo/+/master/Math/Basic.lean", false);
    assertThat(html).contains("lean.css");
    assertThat(html).contains("doc.css");
    assertThat(html).contains("class=\"LeanView\"");
    assertThat(html).contains("class=\"LeanSummary\"");
    assertThat(html).contains("LeanDecl-name\">add_zero_eq</span>");
    assertThat(html).contains("<h1>Example Module</h1>");
    assertThat(html).doesNotContain("name=\"Example-Module\"");
    assertThat(html).doesNotContain("//evil.example");
    assertThat(html).contains("href=\"about:invalid#zSoyz\"");
    assertThat(html).contains("href=\"#7\"");
    assertThat(html).contains(">1 sorry</a>");
    assertThat(html)
        .contains(
            "[Rendered | <a href=\"/b/repo/+/master/Math/Basic.lean?lean=0\">Plain text</a>]");
    assertThat(html).doesNotContain("<a href=\"/b/repo/+/master/Math/Basic.lean\">Rendered</a>");
    assertThat(html).contains("id=\"8\"");
    assertThat(html).contains("href=\"#8\"");
    assertThat(html).doesNotContain("<table class=\"FileContents\">");

    Map<String, ?> data = buildData("/repo/+/master/Math/Basic.lean");
    assertThat(getBlobData(data).get("lines")).isNotNull();
    assertThat(getBlobData(data).get("leanHtml")).isNotNull();

    FakeHttpServletResponse res = buildResponse("/repo/+/master/Main.lean", "lean=0", SC_OK);
    String zeroHtml = res.getActualBodyString();
    assertThat(zeroHtml).contains("<table class=\"FileContents\">");
    assertThat(zeroHtml).doesNotContain("class=\"LeanView\"");
    assertThat(zeroHtml).doesNotContain("lean.css");
    assertThat(zeroHtml).doesNotContain("doc.css");
    assertThat(zeroHtml)
        .contains("[<a href=\"/b/repo/+/master/Main.lean\">Rendered</a> | Plain text]");
    assertThat(zeroHtml).doesNotContain(">Plain text</a>");

    FakeHttpServletResponse extraParamsRes =
        buildResponse("/repo/+/master/Main.lean", "foo=bar&lean=0", SC_OK);
    assertThat(extraParamsRes.getActualBodyString())
        .contains("[<a href=\"/b/repo/+/master/Main.lean?foo=bar\">Rendered</a> | Plain text]");

    FakeHttpServletResponse richWithExtraParams =
        buildResponse("/repo/+/master/Main.lean", "foo=bar", SC_OK);
    assertThat(richWithExtraParams.getActualBodyString())
        .contains(
            "[Rendered | <a href=\"/b/repo/+/master/Main.lean?foo=bar&amp;lean=0\">Plain"
                + " text</a>]");

    String richHostile =
        buildResponse(
                "/repo/+/" + hostileBranch + "/" + hostilePath, "q=1\"onclick=alert(1)", SC_OK)
            .getActualBodyString();
    assertThat(richHostile)
        .contains(
            "[Rendered | <a"
                + " href=\"/b/repo/+/feat%22%27%3C%26%3Etest/dir/a%22%27%3C%3E%26%20.lean"
                + "?q=1%22onclick%3Dalert%281%29&amp;lean=0\">Plain text</a>]");

    String plainHostile =
        buildResponse(
                "/repo/+/" + hostileBranch + "/" + hostilePath,
                "q=1\"onclick=alert(1)&lean=0",
                SC_OK)
            .getActualBodyString();
    assertThat(plainHostile)
        .contains(
            "[<a href=\"/b/repo/+/feat%22%27%3C%26%3Etest/dir/a%22%27%3C%3E%26%20.lean"
                + "?q=1%22onclick%3Dalert%281%29\">Rendered</a> | Plain text]");

    assertThat(buildBlob("/repo/+/master/Main.lean", "100644")).isEqualTo(mainSource);
    File file = buildJson(File.class, "/repo/+/master/Main.lean");
    assertThat(file.id).isEqualTo(mainBlob.name());
    assertThat(file.repo).isEqualTo("repo");
    assertThat(file.revision).isEqualTo("master");
    assertThat(file.path).isEqualTo("Main.lean");

    String blameHtml = buildHtml("/repo/+blame/master/Main.lean", false);
    assertThat(blameHtml).contains("class=\"Blame\"");
    assertThat(blameHtml).doesNotContain("class=\"LeanView\"");
    assertThat(blameHtml).doesNotContain("lean.css");
  }

  @Test
  public void leanToggleOmittedDisabledAndInvalidConfigFailClosed() throws Exception {
    repo.branch("master")
        .commit()
        .add("notes.txt", "plain text\n")
        .add("Main.lean", "theorem t : True := by trivial\n")
        .add("Upper.LEAN", "theorem u : True := by trivial\n")
        .add("Semi.lean;x", "theorem s : True := by trivial\n")
        .create();
    String txtDefault = buildHtml("/repo/+/master/notes.txt", false);
    assertThat(txtDefault).doesNotContain("Plain text");
    assertThat(txtDefault).doesNotContain("Rendered");
    FakeHttpServletResponse txtLeanZero =
        buildResponse("/repo/+/master/notes.txt", "lean=0", SC_OK);
    assertThat(txtLeanZero.getActualBodyString()).doesNotContain("Plain text");
    assertThat(txtLeanZero.getActualBodyString()).doesNotContain("Rendered");

    for (String nonLeanPath :
        new String[] {"/repo/+/master/Upper.LEAN", "/repo/+/master/Semi.lean;x"}) {
      String html = buildResponse(nonLeanPath, "lean=1", SC_OK).getActualBodyString();
      assertThat(html).doesNotContain("class=\"LeanView\"");
      assertThat(html).doesNotContain("lean.css");
    }
    FakeHttpServletResponse doubleEncodedNotFound =
        buildResponse("/repo/+/master/Main%252elean", "lean=1", SC_NOT_FOUND);
    assertThat(doubleEncodedNotFound.getActualBodyString()).doesNotContain("class=\"LeanView\"");

    Config disabled = new Config();
    disabled.setBoolean("gitiles", null, "leanRendering", false);
    servlet = TestGitilesServlet.create(repo, disabled);
    for (String query : new String[] {null, "lean=0", "lean=1", "lean=1&lean=0"}) {
      String html = buildResponse("/repo/+/master/Main.lean", query, SC_OK).getActualBodyString();
      assertThat(html).contains("<table class=\"FileContents\">");
      assertThat(html).doesNotContain("class=\"LeanView\"");
      assertThat(html).doesNotContain("lean.css");
      assertThat(html).doesNotContain("Plain text");
      assertThat(html).doesNotContain(">Rendered</a>");
    }
    String trailingSlashHtml =
        buildResponse("/repo/+/master/Main.lean/", "lean=1", SC_OK).getActualBodyString();
    assertThat(trailingSlashHtml).doesNotContain("class=\"LeanView\"");
    assertThat(trailingSlashHtml).doesNotContain("lean.css");
    FakeHttpServletResponse encodedDotDisabled =
        buildResponse("/repo/+/master/Main%2elean", "lean=1", SC_NOT_FOUND);
    assertThat(encodedDotDisabled.getActualBodyString()).doesNotContain("class=\"LeanView\"");
    assertThat(encodedDotDisabled.getActualBodyString()).doesNotContain("lean.css");

    Config badLeanConfig = new Config();
    badLeanConfig.setString("gitiles", null, "leanRendering", "not_a_boolean");
    assertThat(LeanRendering.isEnabled(badLeanConfig)).isFalse();

    Config invalidMarkdownConfig = new Config();
    invalidMarkdownConfig.setString("markdown", null, "render", "not_a_boolean");
    servlet = TestGitilesServlet.create(repo, invalidMarkdownConfig);
    String invalidMdHtml = buildHtml("/repo/+/master/Main.lean", false);
    assertThat(invalidMdHtml).contains("<table class=\"FileContents\">");
    assertThat(invalidMdHtml).doesNotContain("class=\"LeanView\"");
    assertThat(invalidMdHtml).doesNotContain("lean.css");
    Map<String, ?> data = buildData("/repo/+/master/Main.lean");
    assertThat(getBlobData(data).get("lines")).isNotNull();
    assertThat(getBlobData(data)).doesNotContainKey("leanHtml");
  }

  @Test
  public void leanOversizeBinaryAfter8kAndCrlfPathsDegradeToPlain() throws Exception {
    String overLineCap = "\n".repeat(LeanRendering.MAX_LINES + 1);
    String overByteCap = ("-- " + " ".repeat(64_996) + "\n").repeat(33);
    String overLineCharsCap = "def x := " + "a".repeat(LeanRendering.MAX_LINE_CHARS) + "\n";
    String crOnlyOverCap =
        "-- " + "a".repeat(8_000) + "\n" + "x\r".repeat(LeanRendering.MAX_LINES + 1);
    assertThat(LeanRendering.withinInputCaps(crOnlyOverCap.getBytes(UTF_8).length, crOnlyOverCap))
        .isFalse();
    String prefix8k = "-- " + "a".repeat(8_200) + "\n";
    RevBlob nulAfter8k = repo.blob((prefix8k + "def bad\0 := 1\n").getBytes(UTF_8));
    RevBlob crAfter8k = repo.blob((prefix8k + "def x := 1\rdef y := 2\n").getBytes(UTF_8));
    RevBlob utf16LeNoNul = repo.blob("\uFEFF測試數學".getBytes(UTF_16LE));
    RevBlob invalidUtf8 =
        repo.blob(new byte[] {'d', 'e', 'f', ' ', 'x', ' ', '=', ' ', '1', '\n', (byte) 0x80});

    repo.branch("master")
        .commit()
        .add("Huge.lean", overLineCap)
        .add("HugeBytes.lean", overByteCap)
        .add("LongLine.lean", overLineCharsCap)
        .add("CrHuge.lean", crOnlyOverCap)
        .edit(regularFileEdit("NulAfter8k.lean", nulAfter8k))
        .edit(regularFileEdit("CrAfter8k.lean", crAfter8k))
        .edit(regularFileEdit("Utf16LeNoNul.lean", utf16LeNoNul))
        .edit(regularFileEdit("InvalidUtf8.lean", invalidUtf8))
        .create();

    Map<String, ?> overLineData = getBlobData(buildData("/repo/+/master/Huge.lean"));
    assertThat(overLineData.get("lines")).isNotNull();
    assertThat(overLineData).doesNotContainKey("leanHtml");

    Map<String, ?> overByteData = getBlobData(buildData("/repo/+/master/HugeBytes.lean"));
    assertThat(overByteData.get("lines")).isNotNull();
    assertThat(overByteData).doesNotContainKey("leanHtml");

    String longLineHtml = buildHtml("/repo/+/master/LongLine.lean", false);
    assertThat(longLineHtml).contains("<table class=\"FileContents\">");
    assertThat(longLineHtml).doesNotContain("class=\"LeanView\"");
    assertThat(longLineHtml).doesNotContain("lean.css");
    assertThat(longLineHtml).doesNotContain(">Plain text</a>");
    assertThat(longLineHtml).doesNotContain(">Rendered</a>");
    FakeHttpServletResponse longLineZero =
        buildResponse("/repo/+/master/LongLine.lean", "lean=0", SC_OK);
    assertThat(longLineZero.getActualBodyString()).doesNotContain(">Rendered</a>");

    String crHtml = buildHtml("/repo/+/master/CrHuge.lean", false);
    assertThat(crHtml).doesNotContain("class=\"LeanView\"");
    assertThat(crHtml).doesNotContain("lean.css");
    assertThat(crHtml).doesNotContain(">Rendered</a>");

    for (String file :
        new String[] {
          "NulAfter8k.lean", "CrAfter8k.lean", "Utf16LeNoNul.lean", "InvalidUtf8.lean"
        }) {
      String html = buildHtml("/repo/+/master/" + file, false);
      assertThat(html).contains("<table class=\"FileContents\">");
      assertThat(html).doesNotContain("class=\"LeanView\"");
      assertThat(html).doesNotContain("lean.css");
      assertThat(html).doesNotContain(">Plain text</a>");
      assertThat(html).doesNotContain(">Rendered</a>");
    }
    for (String file : new String[] {"NulAfter8k.lean", "Utf16LeNoNul.lean"}) {
      String plain = buildResponse("/repo/+/master/" + file, "lean=0", SC_OK).getActualBodyString();
      assertThat(plain).doesNotContain(">Rendered</a>");
    }

    Config badMd = new Config();
    badMd.setString("markdown", null, "render", "invalid_bool");
    RevBlob blob = repo.blob("theorem ok : True := by trivial\n");
    GitilesView crlfView =
        GitilesView.path()
            .setHostName("host")
            .setServletPath("/b")
            .setRepositoryName("repo")
            .setRevision(Revision.named("master"))
            .setPathPart("evil\r\nFORGED_LOG.lean")
            .build();
    try (ObjectReader reader = repo.getRevWalk().getObjectReader()) {
      Map<String, Object> fallbackData =
          new BlobSoyData(reader, crlfView)
              .setLeanConfig(badMd, null)
              .toSoyData("evil\r\nFORGED_LOG.lean", blob);
      assertThat(fallbackData).doesNotContainKey("leanHtml");
      assertThat(fallbackData.get("lines")).isNotNull();
    }
  }

  @Test
  public void leanMalformedLean3DeepMarkdownAndTrojanBanner() throws Exception {
    String lean3AndMalformed =
        """
        /-! Lean 3 style file -/
        meta def tactic.my_tac : tactic unit := `[exact trivial]
        constant MyConst : Type
        lemma old_style : true :=
        begin
          trivial,
        end
        def unclosed_str := "never closed
        /- unclosed block comment
        """;
    String adversarialSource =
        "/-!\n"
            + "> ".repeat(70)
            + "deep\n-/\n"
            + "theorem t\u0435st : True := by -- \u202Ebidi\n  trivial\n";
    repo.branch("master")
        .commit()
        .add("Legacy.lean", lean3AndMalformed)
        .add("Adv.lean", adversarialSource)
        .create();

    String html = buildHtml("/repo/+/master/Legacy.lean", false);
    assertThat(html).contains("class=\"LeanView\"");
    assertThat(html).contains("LeanDecl-name\">old_style</span>");
    assertThat(html).contains("id=\"1\"");
    assertThat(html).contains("id=\"3\"");
    assertThat(html)
        .contains("<span class=\"LeanPill LeanPill--neutral\">unterminated string/comment");
    assertThat(html).contains("counts may be incomplete</span>");
    assertThat(html).doesNotContain("no sorry found");

    String advHtml = buildHtml("/repo/+/master/Adv.lean", false);
    assertThat(advHtml).contains("class=\"LeanView\"");
    assertThat(advHtml).contains("<div class=\"LeanTrojanBanner\" role=\"alert\">");
    assertThat(advHtml).contains("<span class=\"LeanTrojanChar\">U+202E</span>");
    assertThat(advHtml).contains("homoglyph");
    assertThat(advHtml).doesNotContain("\u202E");
  }

  private static PathEdit regularFileEdit(String name, RevBlob blob) {
    return new PathEdit(name) {
      @Override
      public void apply(DirCacheEntry ent) {
        ent.setFileMode(FileMode.REGULAR_FILE);
        ent.setObjectId(blob);
      }
    };
  }

  private Map<String, ?> getBlobData(Map<String, ?> data) {
    return ((Map<String, Map<String, ?>>) data).get("data");
  }

  private List<Map<String, ?>> getTreeEntries(Map<String, ?> data) {
    return ((Map<String, List<Map<String, ?>>>) data.get("data")).get("entries");
  }

  private String buildBlob(String path, String expectedMode) throws Exception {
    FakeHttpServletResponse res = buildText(path);
    assertThat(res.getHeader(PathServlet.MODE_HEADER)).isEqualTo(expectedMode);
    String base64 = res.getActualBodyString();
    return new String(BaseEncoding.base64().decode(base64), UTF_8);
  }
}
