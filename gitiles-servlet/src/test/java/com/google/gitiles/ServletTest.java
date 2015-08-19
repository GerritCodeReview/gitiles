package com.google.gitiles;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import com.google.common.io.BaseEncoding;
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
  // Deriving class must initialize servlet in setUp().
  protected GitilesServlet servlet;

  protected static String decodeBase64(String in) {
    return new String(BaseEncoding.base64().decode(in), UTF_8);
  }

  @Before
  public void setUp() throws Exception {
    DfsRepository r = new InMemoryRepository(new DfsRepositoryDescription("repo"));
    repo = new TestRepository<>(r);
    servlet = TestGitilesServlet.create(repo);
  }

//  protected class TestRequest {
//    private final GitilesServlet servlet;
//    private String path;
//    private String format;
//    private int expectedStatus;
//
//    public TestRequest(GitilesServlet servlet) {
//      this.servlet = servlet;
//    }
//
//    public TestRequest setPath(String path) {
//      this.path = path;
//      return this;
//    }
//
//    public TestRequest setFormat(String format) {
//      this.format = format;
//      return this;
//    }
//
//    public TestRequest expectStatus(int status) {
//      this.expectedStatus = status;
//      return this;
//    }
//
//    public FakeHttpServletResponse serve() throws Exception {
//      FakeHttpServletRequest req = FakeHttpServletRequest.newRequest();
//      req.setPathInfo(path);
//      if (format != null) {
//        req.setQueryString("format=" + format);
//      }
//      FakeHttpServletResponse res = new FakeHttpServletResponse();
//      servlet.service(req, res);
//      assertEquals(expectedStatus, res.getStatus());
//      return res;
//    }
//
//    protected String serveHtml(String path) throws Exception {
//      FakeHttpServletResponse res = serve(path, null);
//      assertEquals("text/html", res.getHeader(HttpHeaders.CONTENT_TYPE));
//      assertNotNull("has ETag", res.getHeader(HttpHeaders.ETAG));
//      return res.getActualBodyString();
//    }
//
//  }

//  public TestRequest request() {
//    return new TestRequest(servlet);
//  }

  protected FakeHttpServletResponse serve(String path, String format, int expectedStatus) throws Exception {
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

  protected FakeHttpServletResponse serve(String path) throws Exception {
    return serve(path, null, 200);
  }

  protected String serveHtml(String path, boolean assertHasETag) throws Exception {
    FakeHttpServletResponse res = serve(path, null, 200);
//    FakeHttpServletResponse http = res.getResponse();
    assertEquals("text/html", res.getHeader(HttpHeaders.CONTENT_TYPE));
    if (assertHasETag) {
      assertNotNull("has ETag", res.getHeader(HttpHeaders.ETAG));
    }
    return res.getActualBodyString();
  }

  protected String serveHtml(String path) throws Exception {
    return serveHtml(path, true);
  }

  protected FakeHttpServletResponse serveText(String path) throws Exception {
    FakeHttpServletResponse res = serve(path, "text", 200);
    assertEquals("text/plain", res.getHeader(HttpHeaders.CONTENT_TYPE));
    return res;
  }

  private String serveJsonRaw(String path) throws Exception {
    FakeHttpServletResponse res = serve(path, "json", 200);
    assertEquals("application/json", res.getHeader(HttpHeaders.CONTENT_TYPE));
    String body = res.getActualBodyString();
    String magic = ")]}'\n";
    assertEquals(magic, body.substring(0, magic.length()));
    return body.substring(magic.length());
  }

  protected <T> T serveJson(String path, Class<T> classOfT) throws Exception {
    return new Gson().<T>fromJson(serveJsonRaw(path), classOfT);
  }

  protected <T> T serveJson(String path, Type typeOfT) throws Exception {
    return new Gson().<T>fromJson(serveJsonRaw(path), typeOfT);
  }

  protected Map<String, ?> serveData(String path) throws Exception {
    // Render the page through Soy to ensure templates are valid, then return
    // the Soy data for introspection.
    FakeHttpServletRequest req = FakeHttpServletRequest.newRequest();
    req.setPathInfo(path);
    FakeHttpServletResponse res = new FakeHttpServletResponse();
    servlet.service(req, res);
    return BaseServlet.getData(req);
  }

  protected void assertNotFound(String path, String format) throws Exception {
    serve(path, format, 404);
  }
}
