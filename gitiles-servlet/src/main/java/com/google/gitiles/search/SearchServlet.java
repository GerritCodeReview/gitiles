// Copyright 2016 Google Inc. All Rights Reserved.
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

package com.google.gitiles.search;

import static javax.servlet.http.HttpServletResponse.SC_OK;

import com.google.common.base.CharMatcher;
import com.google.gitiles.BaseServlet;
import com.google.gitiles.GitilesAccess;
import com.google.gitiles.GitilesView;
import com.google.gitiles.LogServlet;
import com.google.gitiles.Renderer;
import com.google.gitiles.RepositoryDescription;
import com.google.gitiles.Revision;
import com.google.gitiles.ViewFilter;
import com.google.gson.JsonSyntaxException;
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

  private final String searchEndpoint;

  public SearchServlet(GitilesAccess.Factory accessFactory, Renderer renderer,
      Config cfg) {
    super(renderer, accessFactory);
    String zoektServer = cfg.getString("codeSearch", null, "zoektServer");
    searchEndpoint = zoektServer != null
        ? CharMatcher.is('/').trimTrailingFrom(zoektServer).concat("/api/search")
        : null;
  }

  @Override
  protected void doGetHtml(HttpServletRequest req, HttpServletResponse res)
      throws IOException {
    Repository repo = ServletUtils.getRepository(req);
    GitilesView view = getView(req, repo);

    // TODO use  view.getParameters() instead
    String query = req.getParameter("query");
    Restriction restriction = new Restriction(view.getRepositoryName(),
        view.getRevision().getName());
    Search search = new Search(query, restriction);

    SearchResult searchResult = null;
    try (CloseableHttpClient httpclient = HttpClients.createDefault()) {
      HttpPost post = new HttpPost(searchEndpoint);
      StringEntity reqEntity = new StringEntity(search.toJson(),
          ContentType.create("application/json", Consts.UTF_8));
      post.setEntity(reqEntity);
      try (CloseableHttpResponse response = httpclient.execute(post)) {
        if (response.getStatusLine().getStatusCode() != SC_OK) {
          log.error("Unexpected response: " + response.getStatusLine());
        }
        HttpEntity respEntity = response.getEntity();
        if (respEntity != null) {
          String respContent = EntityUtils.toString(respEntity);
          try {
            searchResult = SearchResult.valueOf(respContent);
          } catch (JsonSyntaxException e) {
            log.error("Response content was: '" + respContent + "'");
          }
        }
      }
    }

    Map<String, Object> data = new HashMap<>();
    data.put("title", "Results for " + query);
    data.put("query", query);
    data.put("repoUrl", getRepositoryUrl(req, view));

    if (searchResult == null || searchResult.isEmpty()) {
      renderHtml(req, res, "gitiles.emptyCodeSearchResult", data);
      return;
    }

    String revisionUrl = getRevisionUrl(view);
    data.put("revisionUrl", revisionUrl);
    data.put("searchResult", searchResult.toSoyData(revisionUrl));
    renderHtml(req, res, "gitiles.codeSearchResult", data);
  }

  private String getRepositoryUrl(HttpServletRequest req, GitilesView view)
      throws IOException {
    RepositoryDescription repoDescr = getAccess(req).getRepositoryDescription();
    return GitilesView.repositoryIndex().copyFrom(view)
        .setRepositoryName(repoDescr.name).toUrl();
  }

  private String getRevisionUrl(GitilesView view) {
    return GitilesView.revision().copyFrom(view)
        .setRevision(view.getRevision()).toUrl();
  }

  private static GitilesView getView(HttpServletRequest req, Repository repo)
      throws IOException {
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
