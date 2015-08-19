package com.google.gitiles;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import com.google.common.net.HttpHeaders;
import com.google.gson.Gson;
import org.eclipse.jgit.internal.storage.dfs.DfsRepository;
import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription;
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository;
import org.eclipse.jgit.junit.TestRepository;
import org.junit.Before;

import java.lang.reflect.Type;
import java.util.Map;

/** Base class for servlet tests */
public class ServletTest {
  protected TestRepository<DfsRepository> repo;
  protected GitilesServlet servlet;

  @Before
  public void setUp() throws Exception {
    DfsRepository r = new InMemoryRepository(new DfsRepositoryDescription("repo"));
    repo = new TestRepository<>(r);
    servlet = TestGitilesServlet.create(repo);
  }

  protected FakeHttpServletResponse build(String path, String format, int expectedStatus)
      throws Exception {
    FakeHttpServletRequest req = FakeHttpServletRequest.newRequest();
    req.setPathInfo(path);
    if (format != null) {
      req.setQueryString("format=" + format);
    }
    FakeHttpServletResponse res = new FakeHttpServletResponse();
    servlet.service(req, res);
    assertEquals(expectedStatus, res.getStatus());
    return res;
  }

  protected FakeHttpServletResponse build(String path) throws Exception {
    return build(path, null, 200);
  }

  protected String buildHtml(String path, boolean assertHasETag) throws Exception {
    FakeHttpServletResponse res = build(path);
    assertEquals("text/html", res.getHeader(HttpHeaders.CONTENT_TYPE));
    if (assertHasETag) {
      assertNotNull("has ETag", res.getHeader(HttpHeaders.ETAG));
    }
    return res.getActualBodyString();
  }

  protected String buildHtml(String path) throws Exception {
    return buildHtml(path, true);
  }

  protected Map<String, ?> buildData(String path) throws Exception {
    // Render the page through Soy to ensure templates are valid, then return
    // the Soy data for introspection.
    FakeHttpServletRequest req = FakeHttpServletRequest.newRequest();
    req.setPathInfo(path);
    FakeHttpServletResponse res = new FakeHttpServletResponse();
    servlet.service(req, res);
    return BaseServlet.getData(req);
  }

  protected FakeHttpServletResponse buildText(String path) throws Exception {
    FakeHttpServletResponse res = build(path, "text", 200);
    assertEquals("text/plain", res.getHeader(HttpHeaders.CONTENT_TYPE));
    return res;
  }

  private String buildJsonRaw(String path) throws Exception {
    FakeHttpServletResponse res = build(path, "json", 200);
    assertEquals("application/json", res.getHeader(HttpHeaders.CONTENT_TYPE));
    String body = res.getActualBodyString();
    String magic = ")]}'\n";
    assertEquals(magic, body.substring(0, magic.length()));
    return body.substring(magic.length());
  }

  protected <T> T buildJson(String path, Class<T> classOfT) throws Exception {
    return new Gson().<T>fromJson(buildJsonRaw(path), classOfT);
  }

  protected <T> T buildJson(String path, Type typeOfT) throws Exception {
    return new Gson().<T>fromJson(buildJsonRaw(path), typeOfT);
  }

  protected void assertNotFound(String path, String format) throws Exception {
    build(path, format, 404);
  }
}
