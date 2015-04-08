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

public class RawRedirectServletTest {
  private TestRepository<DfsRepository> repo;
  private RawRedirectServlet servlet;
  private Config cfg;

  @Before
  public void setUp() throws Exception {
    DfsRepository r = new InMemoryRepository(new DfsRepositoryDescription("repo"));
    repo = new TestRepository<>(r);
    TestGitilesAccess access = new TestGitilesAccess(repo.getRepository());
    servlet = new RawRedirectServlet(access, TestGitilesUrls.URLS);
    cfg = new Config();
    access.setConfig(cfg);

    repo.branch("master").commit()
        .add("path", "contents")
        .create();
  }

  private void setRawFileHostName(String value) {
    cfg.setString("gitiles", null, "rawFileHostName", value);
  }

  @Test
  public void rawFileHostName() {
    assertNull(RawRedirectServlet.rawFileHostName(cfg));

    setRawFileHostName("foo");
    assertEquals("foo", RawRedirectServlet.rawFileHostName(cfg));
  }

  @Test
  public void doesNotRedirectWhenDisabled() throws Exception {
    assertNotFound("/repo/+raw/master/path");
  }

  @Test
  public void redirectsToRawFileHost() throws Exception {
    setRawFileHostName("foo");
    assertRedirectTo("/repo/+raw/master/path",
        "http://foo:80/b/test-host/repo/+rawc/master/path");
  }

  @Test
  public void redirectsToRawFileHostForMissingFile() throws Exception {
    setRawFileHostName("foo");
    assertRedirectTo("/repo/+raw/master/missing_file",
        "http://foo:80/b/test-host/repo/+rawc/master/missing_file");
  }

  private TestViewFilter.Result service(String pathAndQuery) throws Exception {
    TestViewFilter.Result res = TestViewFilter.service(repo, pathAndQuery);
    assertEquals(200, res.getResponse().getStatus());
    assertEquals(GitilesView.Type.RAW_REDIRECT, res.getView().getType());
    servlet.service(res.getRequest(), res.getResponse());
    return res;
  }

  private void assertRedirectTo(String pathAndQuery, String target) throws Exception {
    FakeHttpServletResponse res = service(pathAndQuery).getResponse();
    assertEquals(302, res.getStatus());
    assertEquals(target, res.getHeader("Location"));
  }

  private void assertNotFound(String pathAndQuery) throws Exception {
    assertEquals(404, service(pathAndQuery).getResponse().getStatus());
  }
}
