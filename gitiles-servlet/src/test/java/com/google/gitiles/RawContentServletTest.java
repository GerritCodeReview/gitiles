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

public class RawContentServletTest {
  private TestRepository<DfsRepository> repo;
  private RawContentServlet servlet;
  private Config cfg;

  @Before
  public void setUp() throws Exception {
    DfsRepository r = new InMemoryRepository(new DfsRepositoryDescription("repo"));
    repo = new TestRepository<>(r);
    TestGitilesAccess access = new TestGitilesAccess(repo.getRepository());
    servlet = new RawContentServlet(access, new DefaultMimeTypeFinder());
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
  public void doesNotServeWhenDisabled() throws Exception {
    assertNotFound("foo", "/test-host/repo/+rawc/master/path");
  }

  @Test
  public void servesContents() throws Exception {
    setRawFileHostName("foo");
    FakeHttpServletResponse res =
        service("foo", "/test-host/repo/+rawc/master/path").getResponse();
    assertEquals(200, res.getStatus());
    assertNull(res.getHeader("Content-Type"));
    assertEquals("contents", res.getActualBodyString());
  }

  @Test
  public void servesContentsWithMimeType() throws Exception {
    setRawFileHostName("foo");
    FakeHttpServletResponse res =
        service("foo", "/test-host/repo/+rawc/master/path.html").getResponse();
    assertEquals(200, res.getStatus());
    assertEquals("text/html", res.getHeader("Content-Type"));
    assertEquals("contents", res.getActualBodyString());
  }

  @Test
  public void serves404ForMissingFile() throws Exception {
    setRawFileHostName("foo");
    assertNotFound("foo", "/test-host/repo/+rawc/master/missing_file");
  }

  @Test
  public void doesNotServeOnDifferentHost() throws Exception {
    setRawFileHostName("foo");
    assertNotFound("not-foo", "/test-host/repo/+rawc/master/path");
    assertNotFound(TestGitilesUrls.HOST_NAME, "/test-host/repo/+rawc/master/path");
  }

  private TestViewFilter.Result service(String host, String pathAndQuery) throws Exception {
    FakeHttpServletRequest req = TestViewFilter.newRequest(repo, pathAndQuery);
    req.setHostName(host);
    TestViewFilter.Result res = TestViewFilter.serviceRequest(repo, req);
    assertEquals(200, res.getResponse().getStatus());
    assertEquals(GitilesView.Type.RAW_CONTENT, res.getView().getType());
    servlet.service(res.getRequest(), res.getResponse());
    return res;
  }

  private void assertNotFound(String host, String pathAndQuery) throws Exception {
    assertEquals(404, service(host, pathAndQuery).getResponse().getStatus());
  }
}
