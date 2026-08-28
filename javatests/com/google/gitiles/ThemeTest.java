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
import static javax.servlet.http.HttpServletResponse.SC_NOT_FOUND;

import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription;
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository;
import org.eclipse.jgit.junit.TestRepository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Comprehensive tests for dark mode theme rendering and toggle across all Gitiles HTML routes. */
@RunWith(JUnit4.class)
public class ThemeTest extends ServletTest {
  private static final String REPO_NAME = "test-repo";

  @Override
  @Before
  public void setUp() throws Exception {
    repo = new TestRepository<>(new InMemoryRepository(new DfsRepositoryDescription(REPO_NAME)));
    servlet = TestGitilesServlet.create(repo);
  }

  @Test
  public void hostIndexIncludesThemeScriptAndToggle() throws Exception {
    String html = buildHtml("/", false);
    assertThemeElementsPresent(html);
  }

  @Test
  public void repositoryIndexIncludesThemeScriptAndToggle() throws Exception {
    String html = buildHtml("/" + REPO_NAME + "/", false);
    assertThemeElementsPresent(html);
  }

  @Test
  public void docPageIncludesThemeScriptAndToggle() throws Exception {
    repo.branch("master").commit().add("README.md", "# Hello Gitiles\nDark mode test.").create();
    String html = buildHtml("/" + REPO_NAME + "/+doc/master/README.md", false);
    assertThemeElementsPresent(html);
  }

  @Test
  public void commitDetailIncludesThemeScriptAndToggle() throws Exception {
    RevCommit commit = repo.branch("master").commit().add("file.txt", "content").create();
    String html = buildHtml("/" + REPO_NAME + "/+/" + commit.name(), false);
    assertThemeElementsPresent(html);
  }

  @Test
  public void logDetailIncludesThemeScriptAndToggle() throws Exception {
    repo.branch("master").commit().add("file.txt", "content").create();
    String html = buildHtml("/" + REPO_NAME + "/+log/master", false);
    assertThemeElementsPresent(html);
  }

  @Test
  public void treeDetailIncludesThemeScriptAndToggle() throws Exception {
    repo.branch("master").commit().add("dir/file.txt", "content").create();
    String html = buildHtml("/" + REPO_NAME + "/+/master/dir/", false);
    assertThemeElementsPresent(html);
  }

  @Test
  public void blobDetailIncludesThemeScriptAndToggle() throws Exception {
    repo.branch("master").commit().add("file.txt", "line 1\nline 2\n").create();
    String html = buildHtml("/" + REPO_NAME + "/+/master/file.txt", false);
    assertThemeElementsPresent(html);
  }

  @Test
  public void diffDetailIncludesThemeScriptAndToggle() throws Exception {
    RevCommit commit = repo.branch("master").commit().add("file.txt", "line 1\nline 2\n").create();
    String html = buildHtml("/" + REPO_NAME + "/+diff/" + commit.name() + "^!/", false);
    assertThemeElementsPresent(html);
  }

  @Test
  public void blameDetailIncludesThemeScriptAndToggle() throws Exception {
    repo.branch("master").commit().add("file.txt", "line 1\nline 2\n").create();
    String html = buildHtml("/" + REPO_NAME + "/+blame/master/file.txt", false);
    assertThemeElementsPresent(html);
  }

  @Test
  public void refsDetailIncludesThemeScriptAndToggle() throws Exception {
    repo.branch("master").commit().add("file.txt", "content").create();
    String html = buildHtml("/" + REPO_NAME + "/+refs/", false);
    assertThemeElementsPresent(html);
  }

  @Test
  public void errorPageIncludesThemeScriptAndToggle() throws Exception {
    FakeHttpServletResponse res = buildResponse("/" + REPO_NAME + "/+/nonexistent-ref", null, SC_NOT_FOUND);
    String html = res.getActualBodyString();
    assertThemeElementsPresent(html);
  }

  private static void assertThemeElementsPresent(String html) {
    assertThat(html).contains("id=\"gitiles-theme-toggle\"");
    assertThat(html).contains("Header-themeToggle");
    assertThat(html).contains("gitiles-theme");
    assertThat(html).contains("data-theme");
    assertThat(html).contains("<button");
    assertThat(html).contains("<script>");
  }
}
