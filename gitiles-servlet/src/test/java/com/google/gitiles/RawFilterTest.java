// Copyright 2015 Google Inc. All Rights Reserved.
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

import static org.junit.Assert.assertEquals;

import com.google.gitiles.TestViewFilter.Result;

import org.eclipse.jgit.internal.storage.dfs.DfsRepository;
import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription;
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository;
import org.eclipse.jgit.junit.TestRepository;
import org.eclipse.jgit.lib.Config;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;

import javax.servlet.ServletException;

public class RawFilterTest {
  private TestRepository<DfsRepository> repo;
  private Config cfg;

  @Before
  public void setUp() throws Exception {
    DfsRepository r = new InMemoryRepository(new DfsRepositoryDescription("repo"));
    repo = new TestRepository<>(r);
    cfg = new Config();

    repo.branch("master").commit()
        .add("path", "contents")
        .add("path.html", "contents")
        .create();
  }

  private void setRawFileHostName(String value) {
    cfg.setString("gitiles", null, "rawFileHostName", value);
  }

  @Test
  public void doesNothingWhenDisabled() throws Exception {
    assertFilter("foo", "/newhost/repo/+raw/master/path", "newhost/repo", null);
  }

  @Test
  public void extractsHost() throws Exception {
    setRawFileHostName("foo");
    assertFilter("foo", "/newhost/repo/+raw/master/path", "repo", "newhost");
  }

  @Test
  public void doesNothingOnOtherHostnames() throws Exception {
    setRawFileHostName("foo");
    assertFilter("not-foo", "/newhost/repo/+raw/master/path", "newhost/repo", null);
  }

  @Test
  public void doesNothingWithShortPaths() throws Exception {
    setRawFileHostName("foo");
    assertFilter("foo", "/newhost", "newhost", null);
  }

  private void assertFilter(String host, String pathAndQuery, String expectedRepo,
      String expectedHost) throws IOException, ServletException {
    FakeHttpServletRequest req = TestViewFilter.newRequest(repo, pathAndQuery);
    req.setHostName(host);
    Result res = new TestViewFilter.Builder(repo)
        .setRequest(req)
        .setCheckRepositoryName(false)
        .setConfig(cfg)
        .service();

    assertEquals(expectedRepo, res.getView().getRepositoryName());
    assertEquals(expectedHost, res.getView().getHostNameInPath());
  }
}
