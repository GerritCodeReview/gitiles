// Copyright 2026 Google Inc. All Rights Reserved.
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
import static javax.servlet.http.HttpServletResponse.SC_OK;

import com.google.common.collect.ImmutableList;
import com.google.common.net.HttpHeaders;
import java.net.URL;
import java.util.Collections;
import java.util.Enumeration;
import javax.annotation.Nullable;
import javax.servlet.ServletConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.internal.storage.dfs.DfsRepository;
import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription;
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository;
import org.eclipse.jgit.junit.MockSystemReader;
import org.eclipse.jgit.junit.TestRepository;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.util.SystemReader;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Verification test that compares HTML outputs rendered by Soy vs Thymeleaf engines across all
 * standard Gitiles endpoints to verify structural and content parity.
 */
@RunWith(JUnit4.class)
public class SoyVsThymeleafGoldenTest {
  private TestRepository<DfsRepository> repo;
  private GitilesServlet soyServlet;
  private GitilesServlet thymeleafServlet;

  static GitilesServlet createServlet(TestRepository<DfsRepository> repo, Renderer renderer)
      throws ServletException {
    String repoName = repo.getRepository().getDescription().getRepositoryName();
    GitilesServlet servlet =
        new GitilesServlet(
            new Config(),
            renderer,
            TestGitilesUrls.URLS,
            new TestGitilesAccess(repo.getRepository()),
            (req, name) -> {
              if (!repoName.equals(name)) {
                throw new RepositoryNotFoundException(name);
              }
              return repo.getRepository();
            },
            null,
            null,
            null,
            new GitwebRedirectFilter(),
            new BranchRedirect());
    servlet.init(
        new ServletConfig() {
          @Override
          @Nullable
          public String getInitParameter(String name) {
            return null;
          }

          @Override
          public Enumeration<String> getInitParameterNames() {
            return Collections.enumeration(ImmutableList.<String>of());
          }

          @Override
          @Nullable
          public ServletContext getServletContext() {
            return null;
          }

          @Override
          public String getServletName() {
            return TestGitilesServlet.class.getName();
          }
        });
    return servlet;
  }

  @Before
  public void setUp() throws Exception {
    MockSystemReader mockSystemReader = new MockSystemReader();
    SystemReader.setInstance(mockSystemReader);
    DfsRepository r = new InMemoryRepository(new DfsRepositoryDescription("repo"));
    repo = new TestRepository<>(r, new RevWalk(r), mockSystemReader);

    // Create commits, branches, tags, and markdown docs
    RevCommit c1 =
        repo.branch("master")
            .commit()
            .message("Initial commit with README and Main")
            .add("README.md", "# Title\n\nWelcome to the repository.\n")
            .add(
                "src/Main.java",
                "public class Main { public static void main(String[] args) {} }\n")
            .create();

    RevCommit c2 =
        repo.branch("master")
            .commit()
            .parent(c1)
            .message("Update README and add navbar")
            .add("README.md", "# Title\n\nWelcome to the repository.\nUpdated text.\n")
            .add("docs/navbar.md", "# Site Navbar\n\n* [Home](/)\n")
            .create();

    repo.branch("feature")
        .commit()
        .parent(c2)
        .message("Feature commit")
        .add("feature.txt", "Feature content\n")
        .create();
    repo.branch("empty-readme")
        .commit()
        .parent(c2)
        .message("Empty README")
        .add("README.md", "")
        .add("src/Main.java", "public class Main {}\n")
        .create();
    repo.update("refs/tags/v1.0", c1);

    Renderer soyRenderer =
        new DefaultRenderer(
            GitilesServlet.STATIC_PREFIX, ImmutableList.<URL>of(), "repo test site");
    Renderer thymeleafRenderer =
        new ThymeleafRenderer(
            GitilesServlet.STATIC_PREFIX, ImmutableList.<URL>of(), "repo test site");

    soyServlet = createServlet(repo, soyRenderer);
    thymeleafServlet = createServlet(repo, thymeleafRenderer);
  }

  @After
  public void tearDown() {
    SystemReader.setInstance(null);
  }

  private String renderWithServlet(GitilesServlet servlet, String path) throws Exception {
    return renderWithServlet(servlet, path, null);
  }

  private String renderWithServlet(GitilesServlet servlet, String path, @Nullable String query)
      throws Exception {
    FakeHttpServletRequest req = FakeHttpServletRequest.newRequest();
    req.setHeader(HttpHeaders.ORIGIN, "http://localhost");
    req.setPathInfo(path);
    if (query != null) {
      req.setQueryString(query);
    }
    FakeHttpServletResponse res = new FakeHttpServletResponse();
    servlet.service(req, res);
    assertThat(res.getStatus()).isEqualTo(SC_OK);
    assertThat(res.getHeader(HttpHeaders.CONTENT_TYPE)).isEqualTo("text/html");
    return res.getActualBodyString();
  }

  /**
   * Returns the markup between {@code open} (inclusive) and {@code close} (inclusive) with
   * inter-tag whitespace removed, so that Soy's line-joined output can be compared with Thymeleaf's
   * indented source.
   */
  private static String snippet(String html, String open, String close) {
    int start = html.indexOf(open);
    assertThat(start).isAtLeast(0);
    int end = html.indexOf(close, start);
    assertThat(end).isAtLeast(0);
    return html.substring(start, end + close.length()).replaceAll(">\\s+<", "><").trim();
  }

  @Test
  public void testHostIndexParity() throws Exception {
    String soyHtml = renderWithServlet(soyServlet, "/");
    String thymeleafHtml = renderWithServlet(thymeleafServlet, "/");

    assertThat(soyHtml).contains("<!DOCTYPE html>");
    assertThat(thymeleafHtml).contains("<!DOCTYPE html>");
    assertThat(thymeleafHtml).contains("repo");
    assertThat(thymeleafHtml).contains("Git repositories");
  }

  @Test
  public void testRepositoryIndexParity() throws Exception {
    String soyHtml = renderWithServlet(soyServlet, "/repo");
    String thymeleafHtml = renderWithServlet(thymeleafServlet, "/repo");

    assertThat(soyHtml).contains("<!DOCTYPE html>");
    assertThat(thymeleafHtml).contains("<!DOCTYPE html>");
    assertThat(thymeleafHtml).contains("git clone");
    assertThat(thymeleafHtml).contains("Branches");
    assertThat(thymeleafHtml).contains("Tags");
  }

  @Test
  public void testLogDetailParity() throws Exception {
    String soyHtml = renderWithServlet(soyServlet, "/repo/+log/master");
    String thymeleafHtml = renderWithServlet(thymeleafServlet, "/repo/+log/master");

    assertThat(soyHtml).contains("CommitLog");
    assertThat(thymeleafHtml).contains("CommitLog");
    assertThat(thymeleafHtml).contains("Initial commit");
    assertThat(thymeleafHtml).contains("Update README");
  }

  @Test
  public void testRevisionDetailParity() throws Exception {
    String soyHtml = renderWithServlet(soyServlet, "/repo/+/master");
    String thymeleafHtml = renderWithServlet(thymeleafServlet, "/repo/+/master");

    assertThat(soyHtml).contains("Metadata");
    assertThat(thymeleafHtml).contains("Metadata");
    assertThat(thymeleafHtml).contains("commit");
    assertThat(thymeleafHtml).contains("author");
    assertThat(thymeleafHtml).contains("tree");
  }

  @Test
  public void testPathDetailBlobParity() throws Exception {
    String soyHtml = renderWithServlet(soyServlet, "/repo/+/master/src/Main.java");
    String thymeleafHtml = renderWithServlet(thymeleafServlet, "/repo/+/master/src/Main.java");

    assertThat(soyHtml).contains("FileContents");
    assertThat(thymeleafHtml).contains("FileContents");
    assertThat(thymeleafHtml).contains("Main");
  }

  @Test
  public void testPathDetailTreeParity() throws Exception {
    String soyHtml = renderWithServlet(soyServlet, "/repo/+/master/src/");
    String thymeleafHtml = renderWithServlet(thymeleafServlet, "/repo/+/master/src/");

    assertThat(soyHtml).contains("FileList");
    assertThat(thymeleafHtml).contains("FileList");
    assertThat(thymeleafHtml).contains("Main.java");
    // Both engines expose the same [path history] [search] links in the tree header.
    assertThat(snippet(soyHtml, "<div class=\"u-sha1 u-monospace TreeDetail-sha1\">", "</div>"))
        .isEqualTo(
            snippet(thymeleafHtml, "<div class=\"u-sha1 u-monospace TreeDetail-sha1\">", "</div>"));
    assertThat(thymeleafHtml).contains("+grep/master/src\">search</a>]");
  }

  @Test
  public void testGrepDetailEmptyFormParity() throws Exception {
    String soyHtml = renderWithServlet(soyServlet, "/repo/+grep/master");
    String thymeleafHtml = renderWithServlet(thymeleafServlet, "/repo/+grep/master");

    assertThat(snippet(soyHtml, "<form method=\"GET\" class=\"GrepSearch\">", "</form>"))
        .isEqualTo(snippet(thymeleafHtml, "<form method=\"GET\" class=\"GrepSearch\">", "</form>"));
    assertThat(soyHtml).doesNotContain("GrepResults");
    assertThat(thymeleafHtml).doesNotContain("GrepResults");
    assertThat(soyHtml).doesNotContain("GrepSearch-path");
    assertThat(thymeleafHtml).doesNotContain("GrepSearch-path");
  }

  @Test
  public void testGrepDetailMatchesParity() throws Exception {
    String soyHtml = renderWithServlet(soyServlet, "/repo/+grep/master/src", "s=class");
    String thymeleafHtml = renderWithServlet(thymeleafServlet, "/repo/+grep/master/src", "s=class");

    assertThat(snippet(soyHtml, "<form method=\"GET\" class=\"GrepSearch\">", "</form>"))
        .isEqualTo(snippet(thymeleafHtml, "<form method=\"GET\" class=\"GrepSearch\">", "</form>"));
    assertThat(snippet(soyHtml, "<p class=\"GrepSearch-path\">", "</p>"))
        .isEqualTo(snippet(thymeleafHtml, "<p class=\"GrepSearch-path\">", "</p>"));
    assertThat(snippet(soyHtml, "<ol class=\"GrepResults\">", "</ol>"))
        .isEqualTo(snippet(thymeleafHtml, "<ol class=\"GrepResults\">", "</ol>"));
    assertThat(thymeleafHtml).contains("src/Main.java");
    assertThat(thymeleafHtml).contains("1: public class Main");
  }

  @Test
  public void testGrepDetailNoMatchesParity() throws Exception {
    String soyHtml = renderWithServlet(soyServlet, "/repo/+grep/master", "s=nosuchneedle");
    String thymeleafHtml =
        renderWithServlet(thymeleafServlet, "/repo/+grep/master", "s=nosuchneedle");

    assertThat(snippet(soyHtml, "<p class=\"GrepResults-empty\">", "</p>"))
        .isEqualTo(snippet(thymeleafHtml, "<p class=\"GrepResults-empty\">", "</p>"));
    assertThat(thymeleafHtml).contains("No matches.");
    assertThat(thymeleafHtml).doesNotContain("<ol class=\"GrepResults\">");
  }

  @Test
  public void testDiffDetailParity() throws Exception {
    String soyHtml = renderWithServlet(soyServlet, "/repo/+diff/master^..master");
    String thymeleafHtml = renderWithServlet(thymeleafServlet, "/repo/+diff/master^..master");

    assertThat(soyHtml).contains("Diff");
    assertThat(thymeleafHtml).contains("Diff");
    assertThat(thymeleafHtml).contains("README.md");
  }

  @Test
  public void testBlameDetailParity() throws Exception {
    String soyHtml = renderWithServlet(soyServlet, "/repo/+blame/master/README.md");
    String thymeleafHtml = renderWithServlet(thymeleafServlet, "/repo/+blame/master/README.md");

    assertThat(soyHtml).contains("Blame");
    assertThat(thymeleafHtml).contains("Blame");
    assertThat(thymeleafHtml).contains("README.md");
  }

  @Test
  public void testRefListParity() throws Exception {
    String soyHtml = renderWithServlet(soyServlet, "/repo/+refs");
    String thymeleafHtml = renderWithServlet(thymeleafServlet, "/repo/+refs");

    assertThat(soyHtml).contains("RefList");
    assertThat(thymeleafHtml).contains("RefList");
    assertThat(thymeleafHtml).contains("master");
    assertThat(thymeleafHtml).contains("v1.0");
  }

  @Test
  public void testDocParity() throws Exception {
    String soyHtml = renderWithServlet(soyServlet, "/repo/+doc/master/README.md");
    String thymeleafHtml = renderWithServlet(thymeleafServlet, "/repo/+doc/master/README.md");

    assertThat(soyHtml).contains("Site-Content--markdown");
    assertThat(thymeleafHtml).contains("Site-Content--markdown");
    assertThat(thymeleafHtml).contains("Welcome to the repository.");
  }

  @Test
  public void testEmptyReadmeTreeParity() throws Exception {
    String soyHtml = renderWithServlet(soyServlet, "/repo/+/empty-readme/");
    String thymeleafHtml = renderWithServlet(thymeleafServlet, "/repo/+/empty-readme/");

    assertThat(soyHtml).contains("InlineReadme");
    assertThat(thymeleafHtml).contains("InlineReadme");
    assertThat(soyHtml).contains("doc.css");
    assertThat(thymeleafHtml).contains("doc.css");
  }
}
