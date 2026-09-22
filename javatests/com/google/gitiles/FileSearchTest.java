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

package com.google.gitiles;

import static com.google.common.truth.Truth.assertThat;
import static javax.servlet.http.HttpServletResponse.SC_OK;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription;
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository;
import org.eclipse.jgit.junit.TestRepository;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTag;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for the file finder markup emitted by {@code Common.soy}. */
@RunWith(JUnit4.class)
public class FileSearchTest extends ServletTest {
  private static final String REPO_NAME = "test-repo";

  private static final Pattern TREE_URL =
      Pattern.compile("data-tree-url=\"([^\"]*)\"");

  @Override
  @Before
  public void setUp() throws Exception {
    repo = new TestRepository<>(new InMemoryRepository(new DfsRepositoryDescription(REPO_NAME)));
    servlet = TestGitilesServlet.create(repo);
  }

  @Test
  public void treeViewRendersTriggerAndOverlay() throws Exception {
    repo.branch("master").commit().add("dir/file.txt", "contents").create();
    String html = buildHtml("/" + REPO_NAME + "/+/master/dir/", false);
    assertThat(html).contains("FileSearch-trigger");
    assertThat(html).contains("id=\"file-search\"");
    assertThat(html).contains("file-search.js");
  }

  @Test
  public void blobViewRendersOverlay() throws Exception {
    repo.branch("master").commit().add("file.txt", "contents").create();
    String html = buildHtml("/" + REPO_NAME + "/+/master/file.txt", false);
    assertThat(html).contains("id=\"file-search\"");
  }

  /**
   * The listing URL must name the commit by object ID, not by the branch the reader typed.
   * {@code BaseServlet#setCacheHeaders} only permits caching when {@code Revision#nameIsId} holds,
   * so a branch-named URL would be re-fetched on every open.
   */
  @Test
  public void treeUrlIsPinnedToTheCommitSha() throws Exception {
    RevCommit commit = repo.branch("master").commit().add("file.txt", "contents").create();
    String treeUrl = treeUrl(buildHtml("/" + REPO_NAME + "/+/master/", false));
    assertThat(treeUrl).contains("/+/" + commit.name() + "/?");
    assertThat(treeUrl).doesNotContain("master");
  }

  /** A recursive listing scoped to the root tree; the trailing slash is what selects the tree. */
  @Test
  public void treeUrlRequestsACompleteRecursiveListing() throws Exception {
    repo.branch("master").commit().add("file.txt", "contents").create();
    String treeUrl = treeUrl(buildHtml("/" + REPO_NAME + "/+/master/", false));
    assertThat(treeUrl).contains("format=JSON");
    assertThat(treeUrl).contains("recursive=1");
    assertThat(treeUrl).contains("paths_only=1");
  }

  /** The listing URL is a live route, not a string we hope resolves. */
  @Test
  public void treeUrlResolvesToTheBlobListing() throws Exception {
    repo.branch("master").commit().add("dir/file.txt", "contents").add("top.txt", "x").create();
    String treeUrl = treeUrl(buildHtml("/" + REPO_NAME + "/+/master/", false));
    int q = treeUrl.indexOf('?');
    String path =
        treeUrl.substring(FakeHttpServletRequest.SERVLET_PATH.length(), q);
    TreeJsonData.PathList list =
        buildJson(TreeJsonData.PathList.class, path, treeUrl.substring(q + 1));
    assertThat(list.paths).containsExactly("dir/file.txt", "top.txt").inOrder();
  }

  /**
   * The repository index is where readers arrive, and it already displays HEAD's tree. If {@code /}
   * does not work here it never becomes muscle memory.
   */
  @Test
  public void repositoryIndexRendersFileSearchScopedToHead() throws Exception {
    RevCommit commit = repo.branch("master").commit().add("file.txt", "contents").create();
    repo.getRepository().updateRef("HEAD").link("refs/heads/master");
    String html = buildHtml("/" + REPO_NAME + "/", false);
    assertThat(html).contains("id=\"file-search\"");
    assertThat(treeUrl(html)).contains("/+/" + commit.name() + "/?");
  }

  /** The ref list addresses a repository but no revision; HEAD is the neutral default. */
  @Test
  public void refListRendersFileSearchScopedToHead() throws Exception {
    RevCommit commit = repo.branch("master").commit().add("file.txt", "contents").create();
    repo.getRepository().updateRef("HEAD").link("refs/heads/master");
    String html = buildHtml("/" + REPO_NAME + "/+refs/", false);
    assertThat(treeUrl(html)).contains("/+/" + commit.name() + "/?");
  }

  @Test
  public void logViewRendersFileSearch() throws Exception {
    repo.branch("master").commit().add("file.txt", "contents").create();
    assertThat(buildHtml("/" + REPO_NAME + "/+log/master", false)).contains("id=\"file-search\"");
  }

  /** No repository means no paths; the host index would need a different index entirely. */
  @Test
  public void hostIndexRendersNoFileSearch() throws Exception {
    String html = buildHtml("/", false);
    assertThat(html).doesNotContain("id=\"file-search\"");
    assertThat(html).doesNotContain("FileSearch-trigger");
  }

  /** An empty repository has an unborn HEAD and nothing to find. */
  @Test
  public void emptyRepositoryRendersNoFileSearch() throws Exception {
    String html = buildHtml("/" + REPO_NAME + "/", false);
    assertThat(html).doesNotContain("id=\"file-search\"");
  }

  /**
   * A revision that peels to something other than a commit does not fall back to HEAD: answering
   * about HEAD while the reader looks at a tagged tree would silently search different content.
   * {@code TreeSoyData} suppresses its own links under the same condition (TreeSoyData.java:165).
   */
  @Test
  public void tagPeelingToATreeRendersNoFileSearch() throws Exception {
    RevCommit commit = repo.branch("master").commit().add("file.txt", "contents").create();
    repo.getRepository().updateRef("HEAD").link("refs/heads/master");
    RevTag tag = repo.tag("treetag", repo.parseBody(commit).getTree());
    repo.update("refs/tags/treetag", tag);
    String html = buildHtml("/" + REPO_NAME + "/+/treetag", false);
    assertThat(html).doesNotContain("id=\"file-search\"");
    assertThat(html).doesNotContain("FileSearch-trigger");
  }

  /**
   * A deployment can decline the finder. The index it builds is linear in the number of paths in
   * the revision and uncapped by design, so an administrator whose repositories are larger than
   * anything upstream has measured needs a switch rather than a patch.
   */
  @Test
  public void fileSearchDisabledByConfigRendersNothing() throws Exception {
    Config config = new Config();
    config.setBoolean("gitiles", null, "fileSearch", false);
    servlet = TestGitilesServlet.create(repo, config);
    repo.branch("master").commit().add("dir/file.txt", "contents").create();

    String html = buildHtml("/" + REPO_NAME + "/+/master/dir/", false);

    assertThat(html).doesNotContain("FileSearch-trigger");
    assertThat(html).doesNotContain("id=\"file-search\"");
    assertThat(html).doesNotContain("file-search.js");
  }

  /**
   * The overlay ships as an external script so it can be cached and so it survives a content
   * security policy that forbids inline script. Soy annotates the tag with the request nonce
   * because the template is strict-html; this test is what keeps it that way.
   */
  @Test
  public void scriptTagCarriesTheRequestNonce() throws Exception {
    repo.branch("master").commit().add("file.txt", "contents").create();
    FakeHttpServletRequest req = FakeHttpServletRequest.newRequest();
    req.setPathInfo("/" + REPO_NAME + "/+/master/");
    req.setAttribute("nonce", "test-nonce-value");
    FakeHttpServletResponse res = new FakeHttpServletResponse();
    servlet.service(req, res);
    assertThat(res.getStatus()).isEqualTo(SC_OK);

    String html = res.getActualBodyString();
    Matcher m =
        Pattern.compile("<script[^>]*file-search\\.js[^>]*>").matcher(html);
    assertThat(m.find()).isTrue();
    assertThat(m.group()).contains("nonce=\"test-nonce-value\"");
  }

  private static String treeUrl(String html) {
    Matcher m = TREE_URL.matcher(html);
    assertThat(m.find()).isTrue();
    // The attribute is HTML-escaped in the document; the browser unescapes it for us.
    return m.group(1).replace("&amp;", "&");
  }
}
