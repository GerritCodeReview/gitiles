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
import static org.junit.Assert.assertNull;

import org.eclipse.jgit.internal.storage.dfs.DfsRepository;
import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription;
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository;
import org.eclipse.jgit.junit.TestRepository;
import org.eclipse.jgit.lib.Config;
import org.junit.Before;
import org.junit.Test;

public class RawServletTest {
  private TestRepository<DfsRepository> repo;
  private RawServlet servlet;
  private Config cfg;

  @Before
  public void setUp() throws Exception {
    DfsRepository r = new InMemoryRepository(new DfsRepositoryDescription("repo"));
    repo = new TestRepository<>(r);
    TestGitilesAccess access = new TestGitilesAccess(repo.getRepository());
    servlet = new RawServlet(access, TestGitilesUrls.URLS, new DefaultMimeTypeFinder());
    cfg = new Config();
    access.setConfig(cfg);

    repo.branch("master").commit()
        .add("path", "contents")
        .add("path.html", "contents")
        .create();
  }

  private void setRawFileHostName(String value) {
    cfg.setString("gitiles", null, "rawFileHostName", value);
  }

  @Test
  public void doesNotRedirectWhenDisabled() throws Exception {
    assertNotFound(TestGitilesUrls.HOST_NAME, 80, "/repo/+raw/master/path");
  }

  @Test
  public void redirectsToRawFileHost() throws Exception {
    setRawFileHostName("foo");
    assertRedirectTo(TestGitilesUrls.HOST_NAME, 80, "/repo/+raw/master/path",
        "http://foo/b/test-host/repo/+raw/master/path");
    assertRedirectTo(TestGitilesUrls.HOST_NAME, 443, "/repo/+raw/master/path",
        "https://foo/b/test-host/repo/+raw/master/path");
    assertRedirectTo(TestGitilesUrls.HOST_NAME, 8080, "/repo/+raw/master/path",
        "http://foo:8080/b/test-host/repo/+raw/master/path");
  }

  @Test
  public void redirectsToRawFileHostForMissingFile() throws Exception {
    setRawFileHostName("foo");
    assertRedirectTo(TestGitilesUrls.HOST_NAME, 80, "/repo/+raw/master/missing_file",
        "http://foo/b/test-host/repo/+raw/master/missing_file");
    assertRedirectTo(TestGitilesUrls.HOST_NAME, 443, "/repo/+raw/master/missing_file",
        "https://foo/b/test-host/repo/+raw/master/missing_file");
    assertRedirectTo(TestGitilesUrls.HOST_NAME, 8080, "/repo/+raw/master/missing_file",
        "http://foo:8080/b/test-host/repo/+raw/master/missing_file");
  }

  @Test
  public void servesContents() throws Exception {
    setRawFileHostName("foo");
    FakeHttpServletResponse res =
        service("foo", 80, "/test-host/repo/+raw/master/path").getResponse();
    assertEquals(200, res.getStatus());
    assertNull(res.getHeader("Content-Type"));
    assertEquals("contents", res.getActualBodyString());
  }

  @Test
  public void servesContentsWithMimeType() throws Exception {
    setRawFileHostName("foo");
    FakeHttpServletResponse res =
        service("foo", 80, "/test-host/repo/+raw/master/path.html").getResponse();
    assertEquals(200, res.getStatus());
    assertEquals("text/html", res.getHeader("Content-Type"));
    assertEquals("contents", res.getActualBodyString());
  }

  @Test
  public void serves404ForMissingFile() throws Exception {
    setRawFileHostName("foo");
    assertNotFound("foo", 80, "/test-host/repo/+raw/master/missing_file");
  }

  @Test
  public void doesNotServeOnDifferentHost() throws Exception {
    setRawFileHostName("foo");
    assertRedirectTo("not-foo", 80, "/test-host/repo/+raw/master/path",
        "http://foo/b/test-host/test-host/repo/+raw/master/path");
    assertRedirectTo(TestGitilesUrls.HOST_NAME, 80, "/test-host/repo/+raw/master/path",
        "http://foo/b/test-host/test-host/repo/+raw/master/path");
  }

  private TestViewFilter.Result service(String host, int serverPort,
      String pathAndQuery) throws Exception {
    FakeHttpServletRequest req = TestViewFilter.newRequest(repo, pathAndQuery);
    req.setHostName(host);
    req.setServerPort(serverPort);
    TestViewFilter.Result res = new TestViewFilter.Builder(repo)
        .setRequest(req)
        .setConfig(cfg)
        .setCheckRepositoryName(false)
        .service();
    assertEquals(200, res.getResponse().getStatus());
    assertEquals(GitilesView.Type.RAW, res.getView().getType());
    servlet.service(res.getRequest(), res.getResponse());
    return res;
  }

  private void assertNotFound(String host, int serverPort, String pathAndQuery) throws Exception {
    assertEquals(404, service(host, serverPort, pathAndQuery).getResponse().getStatus());
  }

  private void assertRedirectTo(String host, int serverPort, String pathAndQuery,
      String target) throws Exception {
    FakeHttpServletResponse res = service(host, serverPort, pathAndQuery).getResponse();
    assertEquals(302, res.getStatus());
    assertEquals(target, res.getHeader("Location"));
  }
}
