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
    assertThat(html).contains("Header-menuItem FileSearch-trigger");
    assertThat(html).contains("id=\"file-search\"");
    assertThat(html).contains("file-search.js");
  }

  @Test
  public void blobViewRendersOverlay() throws Exception {
    repo.branch("master").commit().add("file.txt", "contents").create();
    String html = buildHtml("/" + REPO_NAME + "/+/master/file.txt", false);
    assertThat(html).contains("id=\"file-search\"");
  }

  @Test
  public void markdownDocViewRendersTriggerAndOverlay() throws Exception {
    repo.branch("master").commit().add("README.md", "# Title\n").create();
    String html = buildHtml("/" + REPO_NAME + "/+/master/README.md", false);
    assertThat(html).contains("Header-menuItem FileSearch-trigger");
    assertThat(html).contains("id=\"file-search\"");
    assertThat(html).contains("file-search.js");
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

  /**
   * An annotated tag has its own object ID distinct from the commit it peels to; pinning the
   * listing URL to the peeled commit SHA shares the cached index with commit-addressed views and
   * ensures result links resolve as paths rather than failing {@code PathServlet}'s tag peel.
   */
  @Test
  public void annotatedTagIsPinnedToThePeeledCommitSha() throws Exception {
    RevCommit commit = repo.branch("master").commit().add("file.txt", "contents").create();
    RevTag tag = repo.tag("v1.0", commit);
    repo.update("refs/tags/v1.0", tag);
    String treeUrl = treeUrl(buildHtml("/" + REPO_NAME + "/+/v1.0/", false));
    assertThat(treeUrl).contains("/+/" + commit.name() + "/?");
    assertThat(treeUrl).doesNotContain(tag.name());
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

  /**
   * A host index addresses no revision, so there are no paths to find, but it is a listing all the
   * same and {@code /} should search it. No listing URL accompanies it: the page already contains
   * every repository it lists, so the finder reads them from the document instead of fetching the
   * same names again -- which matters because a host index response names no revision and is
   * therefore served {@code no-store}.
   */
  @Test
  public void hostIndexRendersRepositorySearch() throws Exception {
    String html = buildHtml("/", false);
    assertThat(html).contains("id=\"file-search\"");
    assertThat(html).contains("data-kind=\"repositories\"");
    assertThat(html).doesNotContain("data-tree-url");
    assertThat(html).contains("Find repository");
  }

  /** The attribute that tells the two sources apart; the script keys all its behaviour off it. */
  @Test
  public void treeViewRendersFileKind() throws Exception {
    repo.branch("master").commit().add("file.txt", "contents").create();
    String html = buildHtml("/" + REPO_NAME + "/+/master/", false);
    assertThat(html).contains("data-kind=\"files\"");
    assertThat(html).contains("Find file");
    assertThat(html).doesNotContain("Find repository");
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

  /** One switch covers both listings: the host index branch sits inside the same guard. */
  @Test
  public void fileSearchDisabledByConfigRendersNoRepositorySearch() throws Exception {
    Config config = new Config();
    config.setBoolean("gitiles", null, "fileSearch", false);
    servlet = TestGitilesServlet.create(repo, config);

    String html = buildHtml("/", false);

    assertThat(html).doesNotContain("id=\"file-search\"");
    assertThat(html).doesNotContain("data-kind=\"repositories\"");
  }

  /**
   * The overlay ships as an external script so it can be cached and so it survives a content
   * security policy that forbids inline script. Soy annotates the tag with {@code $ij.csp_nonce}
   * when the request carries a nonce; this test is what keeps it that way.
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
