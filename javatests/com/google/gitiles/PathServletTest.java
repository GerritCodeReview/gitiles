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
import static java.nio.charset.StandardCharsets.UTF_8;
import static javax.servlet.http.HttpServletResponse.SC_BAD_REQUEST;
import static javax.servlet.http.HttpServletResponse.SC_OK;

import com.google.common.io.BaseEncoding;
import com.google.common.net.HttpHeaders;
import com.google.gitiles.FileJsonData.File;
import com.google.gitiles.GitlinkJsonData.Gitlink;
import com.google.gitiles.TreeJsonData.Tree;
import com.google.template.soy.data.SoyListData;
import com.google.template.soy.data.restricted.StringData;
import java.util.List;
import java.util.Map;
import org.eclipse.jgit.dircache.DirCacheEditor.PathEdit;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
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

    Map<String, ?> navigation = getNavigationData(data);
    assertThat(navigation).containsEntry("path", "/");
    assertThat(getNavigationEntries(data)).containsExactlyElementsIn(entries);
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
    assertThat(getNavigationData(data)).containsEntry("activeName", "foo");

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
  public void fileHtmlNavigationListsRepositoryRoot() throws Exception {
    repo.branch("master")
        .commit()
        .add("foo/bar", "bar contents")
        .add("foo/baz", "baz contents")
        .create();

    Map<String, ?> data = buildData("/repo/+/master/foo/bar");

    Map<String, ?> navigation = getNavigationData(data);
    assertThat(navigation).containsEntry("path", "/");
    assertThat(navigation).containsEntry("activeName", "foo/");
    assertThat((String) navigation.get("searchUrl")).contains("format=JSON");
    assertThat((String) navigation.get("searchUrl")).contains("recursive=1");
    assertThat(navigation).containsEntry("searchRootUrl", "/b/repo/+/master/");
    assertThat(navigation).containsEntry("searchMinLength", "2");

    List<Map<String, ?>> entries = getNavigationEntries(data);
    assertThat(entries).hasSize(1);
    assertThat(entries.get(0).get("name")).isEqualTo("foo/");
    assertThat(entries.get(0).get("url")).isEqualTo("/b/repo/+/master/foo/");

    String html = buildHtml("/repo/+/master/foo/bar", false);
    assertThat(html).contains("FileNav-item FileNav-item--gitTree FileNav-item--active");
    assertThat(html)
        .contains(
            "<nav id=\"gitiles-file-nav\" class=\"FileNav\""
                + " aria-labelledby=\"gitiles-file-nav-heading\""
                + " data-file-nav-active-path=\"foo/bar\">");
    assertThat(html).contains("aria-current=\"page\"");
    assertThat(html).contains("foo/");
  }

  @Test
  public void treeHtmlNavigationMarksActiveDirectory() throws Exception {
    repo.branch("master")
        .commit()
        .add("foo/bar", "bar contents")
        .add("foo/baz", "baz contents")
        .create();

    Map<String, ?> data = buildData("/repo/+/master/foo/");
    assertThat(data).containsEntry("type", "TREE");
    // Navigation names for trees carry a trailing solidus, so the active name must too.
    assertThat(getNavigationData(data)).containsEntry("activeName", "foo/");

    String html = buildHtml("/repo/+/master/foo/", false);
    assertThat(html).contains("FileNav-item FileNav-item--gitTree FileNav-item--active");
    assertThat(html).contains("aria-current=\"page\"");
  }

  @Test
  public void navigationEntriesAreTypedAndSorted() throws Exception {
    repo.branch("master")
        .commit()
        .add("zdir/f", "f contents")
        .add("afile", "a contents")
        .add("run.sh", "#!/bin/sh\necho hi\n")
        .edit(
            new PathEdit("run.sh") {
              @Override
              public void apply(DirCacheEntry ent) {
                ent.setFileMode(FileMode.EXECUTABLE_FILE);
              }
            })
        .create();

    List<Map<String, ?>> entries = getNavigationEntries(buildData("/repo/+/master/afile"));
    assertThat(entries).hasSize(3);
    assertThat(entries.get(0).get("name")).isEqualTo("zdir/");
    assertThat(entries.get(0).get("type")).isEqualTo("TREE");
    assertThat(entries.get(1).get("name")).isEqualTo("afile");
    assertThat(entries.get(1).get("type")).isEqualTo("REGULAR_FILE");
    assertThat(entries.get(2).get("name")).isEqualTo("run.sh");
    assertThat(entries.get(2).get("type")).isEqualTo("EXECUTABLE_FILE");
  }

  @Test
  public void navigationTreeUrlsKeepParamsOutsideThePath() throws Exception {
    repo.branch("master")
        .commit()
        .add("foo/bar", "bar contents")
        .add("foo/baz", "baz contents")
        .create();

    String html = buildResponse("/repo/+/master/", "autodive=0", SC_OK).getActualBodyString();
    assertThat(html).contains("href=\"/b/repo/+/master/foo/?autodive=0\"");
  }

  @Test
  public void navigationCanBeDisabled() throws Exception {
    repo.branch("master").commit().add("foo", "contents").create();
    Config cfg = new Config();
    cfg.setBoolean("gitiles", null, "fileNavigation", false);
    servlet = TestGitilesServlet.create(repo, cfg);

    Map<String, ?> data = buildData("/repo/+/master/foo");
    assertThat(data).doesNotContainKey("navigation");
    assertThat(data).doesNotContainKey("containerClass");

    String html = buildHtml("/repo/+/master/foo", false);
    assertThat(html).doesNotContain("FileNav");
    assertThat(html).doesNotContain("Container--fullWidth");
  }

  @Test
  public void treeJsonFilterMatchesPathsIgnoringCase() throws Exception {
    repo.branch("master")
        .commit()
        .add("foo/bar.txt", "bar")
        .add("foo/baz.txt", "baz")
        .add("other/qux.txt", "qux")
        .create();

    Tree t = buildJson(Tree.class, "/repo/+/master/", "format=JSON&recursive=1&filter=BA");
    assertThat(t.entries).hasSize(2);
    assertThat(t.entries.get(0).name).isEqualTo("foo/bar.txt");
    assertThat(t.entries.get(1).name).isEqualTo("foo/baz.txt");
    assertThat(t.truncated).isNull();
  }

  @Test
  public void treeJsonFilterReportsTruncation() throws Exception {
    repo.branch("master")
        .commit()
        .add("foo/bar.txt", "bar")
        .add("foo/baz.txt", "baz")
        .create();

    Tree t = buildJson(Tree.class, "/repo/+/master/", "format=JSON&recursive=1&filter=ba&limit=1");
    assertThat(t.entries).hasSize(1);
    assertThat(t.truncated).isTrue();
  }

  @Test
  public void treeJsonFilterLimitIsCappedByConfig() throws Exception {
    repo.branch("master")
        .commit()
        .add("bar1", "1")
        .add("bar2", "2")
        .add("bar3", "3")
        .create();
    Config cfg = new Config();
    cfg.setInt("gitiles", null, "fileSearchLimit", 2);
    servlet = TestGitilesServlet.create(repo, cfg);

    Tree t =
        buildJson(Tree.class, "/repo/+/master/", "format=JSON&recursive=1&filter=bar&limit=100");
    assertThat(t.entries).hasSize(2);
    assertThat(t.truncated).isTrue();
  }

  @Test
  public void treeJsonWithoutFilterIgnoresLimit() throws Exception {
    repo.branch("master")
        .commit()
        .add("bar1", "1")
        .add("bar2", "2")
        .add("bar3", "3")
        .create();

    // The cap exists to bound search responses; unfiltered listings keep their
    // pre-existing unbounded behavior so the JSON API contract is unchanged.
    Tree t = buildJson(Tree.class, "/repo/+/master/", "format=JSON&recursive=1&limit=1");
    assertThat(t.entries).hasSize(3);
    assertThat(t.truncated).isNull();
  }

  @Test
  public void treeJsonRejectsInvalidFilterParams() throws Exception {
    repo.branch("master").commit().add("foo/bar.txt", "bar").create();

    buildResponse("/repo/+/master/", "format=JSON&recursive=1&filter=b", SC_BAD_REQUEST);
    buildResponse("/repo/+/master/", "format=JSON&recursive=1&filter=ba&limit=0", SC_BAD_REQUEST);
    buildResponse("/repo/+/master/", "format=JSON&recursive=1&filter=ba&limit=x", SC_BAD_REQUEST);
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
  public void executableFileHtml() throws Exception {
    repo.branch("master")
        .commit()
        .add("run.sh", "#!/bin/sh\necho hi\n")
        .edit(
            new PathEdit("run.sh") {
              @Override
              public void apply(DirCacheEntry ent) {
                ent.setFileMode(FileMode.EXECUTABLE_FILE);
              }
            })
        .create();

    Map<String, ?> data = buildData("/repo/+/master/run.sh");
    assertThat(data).containsEntry("type", "EXECUTABLE_FILE");
    assertThat(getNavigationData(data)).containsEntry("activeName", "run.sh");
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
    assertThat(getNavigationData(data)).containsEntry("activeName", "gitiles");

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
    int slash = linkName.indexOf('/');
    String expectedActiveName = slash >= 0 ? linkName.substring(0, slash + 1) : linkName;
    assertThat(getNavigationData(data)).containsEntry("activeName", expectedActiveName);
  }

  private Map<String, ?> getBlobData(Map<String, ?> data) {
    return ((Map<String, Map<String, ?>>) data).get("data");
  }

  private List<Map<String, ?>> getTreeEntries(Map<String, ?> data) {
    return ((Map<String, List<Map<String, ?>>>) data.get("data")).get("entries");
  }

  private Map<String, ?> getNavigationData(Map<String, ?> data) {
    return (Map<String, ?>) data.get("navigation");
  }

  private List<Map<String, ?>> getNavigationEntries(Map<String, ?> data) {
    return ((Map<String, List<Map<String, ?>>>) data.get("navigation")).get("entries");
  }

  private String buildBlob(String path, String expectedMode) throws Exception {
    FakeHttpServletResponse res = buildText(path);
    assertThat(res.getHeader(PathServlet.MODE_HEADER)).isEqualTo(expectedMode);
    String base64 = res.getActualBodyString();
    return new String(BaseEncoding.base64().decode(base64), UTF_8);
  }
}
