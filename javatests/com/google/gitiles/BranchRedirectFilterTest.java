// Copyright 2020 Google Inc. All Rights Reserved.
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
import static javax.servlet.http.HttpServletResponse.SC_OK;

import com.google.common.net.HttpHeaders;
import java.util.Optional;
import org.eclipse.jgit.internal.storage.dfs.DfsRepository;
import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription;
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository;
import org.eclipse.jgit.junit.TestRepository;
import org.eclipse.jgit.lib.Repository;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for BranchRedirect. */
@RunWith(JUnit4.class)
public class BranchRedirectFilterTest {

  public static final String MASTER = "refs/heads/master";
  public static final String DEVELOP = "refs/heads/develop";
  public static final String FOO = "refs/heads/foo";
  private TestRepository<DfsRepository> repo;
  private GitilesServlet servlet;

  @Before
  public void setUp() throws Exception {
    repo = new TestRepository<>(new InMemoryRepository(new DfsRepositoryDescription("repo")));
    BranchRedirectFilter branchRedirectFilter =
        new BranchRedirectFilter() {
          @Override
          public Optional<String> getRedirectBranch(
              Repository repo, String sourceBranch, FormatType formatType) {
            if (MASTER.equals(sourceBranch)) {
              return Optional.of("refs/heads/main");
            }
            if ("refs/heads/foo".equals(sourceBranch)) {
              return Optional.of("refs/heads/bar");
            }
            return Optional.empty();
          }
        };
    servlet = TestGitilesServlet.create(repo, new GitwebRedirectFilter(), branchRedirectFilter);
  }

  @Test
  public void show_withRedirect() throws Exception {
    repo.branch(MASTER).commit().add("foo", "contents").create();

    String path = "/repo/+/refs/heads/master/foo";
    String origin = "http://localhost";
    String queryString = "format=text";

    FakeHttpServletRequest req = FakeHttpServletRequest.newRequest();
    req.setHeader(HttpHeaders.ORIGIN, origin);
    req.setPathInfo(path);
    req.setQueryString(queryString);

    FakeHttpServletResponse res = new FakeHttpServletResponse();
    servlet.service(req, res);
    assertThat(res.getStatus()).isEqualTo(SC_MOVED_PERMANENTLY);
    assertThat(res.getHeader(HttpHeaders.LOCATION))
        .isEqualTo("/b/repo/+/refs/heads/main/foo?format=text");
  }

  @Test
  public void show_withoutRedirect() throws Exception {
    repo.branch("develop").commit().add("foo", "contents").create();

    String path = "/repo/+/refs/heads/develop/foo";
    String origin = "http://localhost";
    String queryString = "format=text";

    FakeHttpServletRequest req = FakeHttpServletRequest.newRequest();
    req.setHeader(HttpHeaders.ORIGIN, origin);
    req.setPathInfo(path);
    req.setQueryString(queryString);

    FakeHttpServletResponse res = new FakeHttpServletResponse();
    servlet.service(req, res);
    assertThat(res.getStatus()).isEqualTo(SC_OK);
  }

  @Test
  public void diff_withRedirect_onSingleBranch() throws Exception {
    repo.branch(MASTER).commit().add("foo", "contents").create();
    repo.branch(DEVELOP).commit().add("foo", "contents").create();

    String path = "/repo/+/refs/heads/master..refs/heads/develop";
    String origin = "http://localhost";
    String queryString = "format=text";

    FakeHttpServletRequest req = FakeHttpServletRequest.newRequest();
    req.setHeader(HttpHeaders.ORIGIN, origin);
    req.setPathInfo(path);
    req.setQueryString(queryString);

    FakeHttpServletResponse res = new FakeHttpServletResponse();
    servlet.service(req, res);
    assertThat(res.getStatus()).isEqualTo(SC_MOVED_PERMANENTLY);
    assertThat(res.getHeader(HttpHeaders.LOCATION))
        .isEqualTo("/b/repo/+/refs/heads/main..refs/heads/develop/?format=text");
  }

  @Test
  public void diff_withRedirect_onBothBranch() throws Exception {
    repo.branch(MASTER).commit().add("foo", "contents").create();
    repo.branch(FOO).commit().add("foo", "contents").create();

    String path = "/repo/+/refs/heads/foo..refs/heads/master";
    String origin = "http://localhost";
    String queryString = "format=text";

    FakeHttpServletRequest req = FakeHttpServletRequest.newRequest();
    req.setHeader(HttpHeaders.ORIGIN, origin);
    req.setPathInfo(path);
    req.setQueryString(queryString);

    FakeHttpServletResponse res = new FakeHttpServletResponse();
    servlet.service(req, res);
    assertThat(res.getStatus()).isEqualTo(SC_MOVED_PERMANENTLY);
    assertThat(res.getHeader(HttpHeaders.LOCATION))
        .isEqualTo("/b/repo/+/refs/heads/bar..refs/heads/main/?format=text");
  }
}
