package com.google.gitiles;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.base.CharMatcher;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.apache.http.Consts;
import org.apache.http.HttpEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.eclipse.jgit.http.server.ServletUtils;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevWalk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Serves an HTML page with code-search results */
public class SearchServlet extends BaseServlet {
  private static final long serialVersionUID = 1L;
  private static final Logger log = LoggerFactory.getLogger(LogServlet.class);

  private final Linkifier linkifier;
  private final String searchEndpoint;

  public SearchServlet(GitilesAccess.Factory accessFactory, Renderer renderer,
      Linkifier linkifier, Config cfg) {
    super(renderer, accessFactory);
    this.linkifier = checkNotNull(linkifier, "linkifier");
    String zoektServer = cfg.getString("codeSearch", null, "zoektServer");
    searchEndpoint = zoektServer != null
        ? CharMatcher.is('/').trimTrailingFrom(zoektServer).concat("/api/search")
        : null;
  }

  @Override
  protected void doGetHtml(HttpServletRequest req, HttpServletResponse res) throws IOException {
    Repository repo = ServletUtils.getRepository(req);
    GitilesView view = getView(req, repo);

    StringBuilder json = new StringBuilder();
    json
      .append("{\"Query\":\"")
      .append(req.getParameter("query"))
      .append("\",\"Restrict\":[{\"Repo\":\"")
      .append(view.getRepositoryName())
      .append("\"")
      .append(",\"Branches\":[\"master\"]}]}");

    CloseableHttpClient httpclient = HttpClients.createDefault();
    HttpPost post = new HttpPost(searchEndpoint);
    StringEntity reqEntity = new StringEntity(json.toString(),
        ContentType.create("application/json", Consts.UTF_8));
    post.setEntity(reqEntity);
    String searchResult = "none";
    try (CloseableHttpResponse response = httpclient.execute(post)) {
        HttpEntity respEntity = response.getEntity();
        if (respEntity != null) {
          searchResult = EntityUtils.toString(respEntity);
        }
    }

    Map<String, Object> data = new HashMap<>();

    String title = "Search - ";
    data.put("title", title);

    data.put("query", view.getParameters().get("query"));
    data.put("revision", view.getRevision().getName());
    data.put("revisionSHA1", view.getRevision().getId().getName());
    data.put("path", view.getPathPart());
    data.put("searchResult", searchResult);

    renderHtml(req, res, "gitiles.codeSearchResult", data);
  }

  private static GitilesView getView(HttpServletRequest req, Repository repo) throws IOException {
    GitilesView view = ViewFilter.getView(req);
    if (view.getRevision() != Revision.NULL) {
      return view;
    }
    Ref headRef = repo.exactRef(Constants.HEAD);
    if (headRef == null) {
      return null;
    }
    try (RevWalk walk = new RevWalk(repo)) {
      return GitilesView.search()
          .copyFrom(view)
          .setRevision(Revision.peel(Constants.HEAD, walk.parseAny(headRef.getObjectId()), walk))
          .build();
    }
  }
}
