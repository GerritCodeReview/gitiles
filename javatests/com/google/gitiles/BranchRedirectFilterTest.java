// Copyright 2021 Google LLC. All Rights Reserved.
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
import static javax.servlet.http.HttpServletResponse.SC_MOVED_PERMANENTLY;
import static javax.servlet.http.HttpServletResponse.SC_MOVED_TEMPORARILY;
import static javax.servlet.http.HttpServletResponse.SC_OK;

import com.google.common.base.Strings;
import com.google.common.net.HttpHeaders;
import java.util.Optional;
import javax.annotation.Nullable;
import org.eclipse.jgit.internal.storage.dfs.DfsRepository;
import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription;
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository;
import org.eclipse.jgit.junit.TestRepository;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for BranchRedirect. */
@RunWith(JUnit4.class)
public class BranchRedirectFilterTest {
  private static final String MASTER = "refs/heads/master";
  private static final String MAIN = "refs/heads/main";
  private static final String DEVELOP = "refs/heads/develop";
  private static final String FOO = "refs/heads/foo";
  private static final String BAR = "refs/heads/bar";
  private static final String ORIGIN = "http://localhost";
  private static final String QUERY_STRING_HTML = "format=html";
  private static final String QUERY_STRING_JSON = "format=json";

  private TestRepository<DfsRepository> repo;
  private GitilesServlet servlet;

  @Before
  public void setUp() throws Exception {
    repo = new TestRepository<>(new InMemoryRepository(new DfsRepositoryDescription("repo")));
    BranchRedirectFilter branchRedirectFilter =
        new BranchRedirectFilter() {
          @Override
          protected Optional<String> getRedirectBranch(Repository repo, String sourceBranch) {
            if (MASTER.equals(toFullBranchName(sourceBranch))) {
              return Optional.of(MAIN);
            }
            if (FOO.equals(toFullBranchName(sourceBranch))) {
              return Optional.of(BAR);
            }
            return Optional.empty();
          }
        };
    servlet = TestGitilesServlet.create(repo, new GitwebRedirectFilter(), branchRedirectFilter);
  }

  @Test
  public void show_withoutRedirect() throws Exception {
    repo.branch("develop").commit().add("foo", "contents").create();

    String path = "/repo/+/refs/heads/develop/foo";
    FakeHttpServletRequest req = newHttpRequest(path, ORIGIN, QUERY_STRING_HTML);
    FakeHttpServletResponse res = new FakeHttpServletResponse();

    servlet.service(req, res);
    assertThat(res.getStatus()).isEqualTo(SC_OK);
  }

  @Test
  public void show_withRedirect() throws Exception {
    repo.branch(MASTER).commit().add("foo", "contents").create();

    String path = "/repo/+/refs/heads/master/foo";
    FakeHttpServletRequest req = newHttpRequest(path, ORIGIN, QUERY_STRING_HTML);
    FakeHttpServletResponse res = new FakeHttpServletResponse();

    servlet.service(req, res);
    assertThat(res.getStatus()).isEqualTo(SC_MOVED_PERMANENTLY);
    assertThat(res.getHeader(HttpHeaders.LOCATION))
        .isEqualTo("/b/repo/+/refs/heads/main/foo?format=html");
  }

  @Test
  public void show_withRedirect_onDefaultFormatType() throws Exception {
    repo.branch(MASTER).commit().add("foo", "contents").create();

    String path = "/repo/+/refs/heads/master/foo";
    FakeHttpServletRequest req = newHttpRequest(path, ORIGIN, null);
    FakeHttpServletResponse res = new FakeHttpServletResponse();

    servlet.service(req, res);
    assertThat(res.getStatus()).isEqualTo(SC_MOVED_PERMANENTLY);
    assertThat(res.getHeader(HttpHeaders.LOCATION)).isEqualTo("/b/repo/+/refs/heads/main/foo");
  }

  @Test
  public void show_withRedirect_usingShortRefInUrl() throws Exception {
    repo.branch(MASTER).commit().add("foo", "contents").create();

    String path = "/repo/+/master/foo";
    FakeHttpServletRequest req = newHttpRequest(path, ORIGIN, QUERY_STRING_HTML);
    FakeHttpServletResponse res = new FakeHttpServletResponse();

    servlet.service(req, res);
    assertThat(res.getStatus()).isEqualTo(SC_MOVED_PERMANENTLY);
    assertThat(res.getHeader(HttpHeaders.LOCATION))
        .isEqualTo("/b/repo/+/refs/heads/main/foo?format=html");
  }

  @Test
  public void show_onAutomationRequest() throws Exception {
    repo.branch(MASTER).commit().add("foo", "contents").create();

    String path = "/repo/+/refs/heads/master/foo";
    FakeHttpServletRequest req = newHttpRequest(path, ORIGIN, QUERY_STRING_JSON);
    FakeHttpServletResponse res = new FakeHttpServletResponse();

    servlet.service(req, res);
    assertThat(res.getStatus()).isEqualTo(SC_OK);
  }

  @Test
  public void showParent_withRedirect() throws Exception {
    RevCommit parent = repo.branch(MASTER).commit().add("foo", "contents").create();
    repo.branch(MASTER).commit().add("bar", "contents").parent(parent).create();

    String path = "/repo/+/refs/heads/master^";
    FakeHttpServletRequest req = newHttpRequest(path, ORIGIN, QUERY_STRING_HTML);
    FakeHttpServletResponse res = new FakeHttpServletResponse();

    servlet.service(req, res);
    // It is resolved to the object id by ViewFilter.
    assertThat(res.getStatus()).isEqualTo(SC_MOVED_TEMPORARILY);
    assertThat(res.getHeader(HttpHeaders.LOCATION))
        .isEqualTo("/b/repo/+/" + parent.toObjectId().name() + "?format=html");
  }

  @Test
  public void diff_withRedirect_onSingleBranch() throws Exception {
    repo.branch(MASTER).commit().add("foo", "contents").create();
    repo.branch(DEVELOP).commit().add("foo", "contents").create();

    String path = "/repo/+/refs/heads/master..refs/heads/develop";
    FakeHttpServletRequest req = newHttpRequest(path, ORIGIN, QUERY_STRING_HTML);
    FakeHttpServletResponse res = new FakeHttpServletResponse();

    servlet.service(req, res);
    assertThat(res.getStatus()).isEqualTo(SC_MOVED_PERMANENTLY);
    assertThat(res.getHeader(HttpHeaders.LOCATION))
        .isEqualTo("/b/repo/+/refs/heads/main..refs/heads/develop/?format=html");
  }

  @Test
  // @Ignore
  public void diff_withRedirect_onBothBranch() throws Exception {
    repo.branch(MASTER).commit().add("foo", "contents").create();
    repo.branch(FOO).commit().add("foo", "contents").create();

    String path = "/repo/+/refs/heads/foo..refs/heads/master";
    FakeHttpServletRequest req = newHttpRequest(path, ORIGIN, QUERY_STRING_HTML);
    FakeHttpServletResponse res = new FakeHttpServletResponse();

    servlet.service(req, res);
    assertThat(res.getStatus()).isEqualTo(SC_MOVED_PERMANENTLY);
    assertThat(res.getHeader(HttpHeaders.LOCATION))
        .isEqualTo("/b/repo/+/refs/heads/bar..refs/heads/main/?format=html");
  }

  @Test
  public void diff_withRedirect() throws Exception {
    repo.branch(MASTER).commit().add("foo", "contents").create();

    String path = "/repo/+diff/refs/heads/master^!";
    FakeHttpServletRequest req = newHttpRequest(path, ORIGIN, QUERY_STRING_HTML);
    FakeHttpServletResponse res = new FakeHttpServletResponse();

    servlet.service(req, res);
    assertThat(res.getStatus()).isEqualTo(SC_MOVED_PERMANENTLY);
    assertThat(res.getHeader(HttpHeaders.LOCATION))
        .isEqualTo("/b/repo/+/refs/heads/main%5E%21/?format=html");
  }

  @Test
  public void log_withRedirect() throws Exception {
    repo.branch(MASTER).commit().add("foo", "contents").create();

    String path = "/repo/+log/refs/heads/master";
    FakeHttpServletRequest req = newHttpRequest(path, ORIGIN, QUERY_STRING_HTML);
    FakeHttpServletResponse res = new FakeHttpServletResponse();

    servlet.service(req, res);
    assertThat(res.getStatus()).isEqualTo(SC_MOVED_PERMANENTLY);
    assertThat(res.getHeader(HttpHeaders.LOCATION))
        .isEqualTo("/b/repo/+log/refs/heads/main/?format=html");
  }

  @Test
  @Ignore
  public void diff_withGrandParent_redirect() throws Exception {
    RevCommit parent1 = repo.branch(MASTER).commit().add("foo", "contents").create();
    RevCommit parent2 =
        repo.branch(MASTER).commit().add("bar", "contents").parent(parent1).create();
    repo.branch(MASTER).commit().add("bar", "contents").parent(parent2).create();

    String path = "/repo/+diff/refs/heads/master^^..refs/heads/master";
    FakeHttpServletRequest req = newHttpRequest(path, ORIGIN, QUERY_STRING_HTML);
    FakeHttpServletResponse res = new FakeHttpServletResponse();

    servlet.service(req, res);
    assertThat(res.getStatus()).isEqualTo(SC_MOVED_PERMANENTLY);
    assertThat(res.getHeader(HttpHeaders.LOCATION))
        .isEqualTo("/b/repo/+/refs/heads/main%5E%5E..refs/heads/main/?format=html");
  }

  @Test
  @Ignore
  public void diff_withRelativeParent_redirect() throws Exception {
    RevCommit parent1 = repo.branch(MASTER).commit().add("foo", "contents").create();
    RevCommit parent2 =
        repo.branch(MASTER).commit().add("bar", "contents").parent(parent1).create();
    repo.branch(MASTER).commit().add("bar", "contents").parent(parent2).create();

    String path = "/repo/+diff/refs/heads/master~1..refs/heads/master";
    FakeHttpServletRequest req = newHttpRequest(path, ORIGIN, QUERY_STRING_HTML);
    FakeHttpServletResponse res = new FakeHttpServletResponse();

    servlet.service(req, res);
    assertThat(res.getStatus()).isEqualTo(SC_MOVED_PERMANENTLY);
    assertThat(res.getHeader(HttpHeaders.LOCATION))
        .isEqualTo("/b/repo/+/refs/heads/main%5E%21/?format=html");
  }

  @Test
  @Ignore
  public void diff_withRelativeGrandParent_redirect() throws Exception {
    RevCommit parent1 = repo.branch(MASTER).commit().add("foo", "contents").create();
    RevCommit parent2 =
        repo.branch(MASTER).commit().add("bar", "contents").parent(parent1).create();
    repo.branch(MASTER).commit().add("bar", "contents").parent(parent2).create();

    String path = "/repo/+diff/refs/heads/master~2..refs/heads/master";
    FakeHttpServletRequest req = newHttpRequest(path, ORIGIN, QUERY_STRING_HTML);
    FakeHttpServletResponse res = new FakeHttpServletResponse();

    servlet.service(req, res);
    assertThat(res.getStatus()).isEqualTo(SC_MOVED_PERMANENTLY);
    assertThat(res.getHeader(HttpHeaders.LOCATION))
        .isEqualTo("/b/repo/+/refs/heads/main%7E2..refs/heads/main/?format=html");
  }

  private static String toFullBranchName(String sourceBranch) {
    if (sourceBranch.startsWith(Constants.R_REFS)) {
      return sourceBranch;
    }
    return Constants.R_HEADS + sourceBranch;
  }

  private static FakeHttpServletRequest newHttpRequest(
      String path, String origin, @Nullable String queryString) {
    FakeHttpServletRequest req = FakeHttpServletRequest.newRequest();
    req.setHeader(HttpHeaders.ORIGIN, origin);
    req.setPathInfo(path);
    if (!Strings.isNullOrEmpty(queryString)) {
      req.setQueryString(queryString);
    }
    return req;
  }
}
