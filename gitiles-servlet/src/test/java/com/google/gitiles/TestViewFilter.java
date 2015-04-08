// Copyright (C) 2014 Google Inc. All Rights Reserved.
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

import static com.google.common.base.Preconditions.checkState;
import static com.google.gitiles.GitilesFilter.REPO_PATH_REGEX;
import static com.google.gitiles.GitilesFilter.REPO_REGEX;
import static com.google.gitiles.GitilesFilter.ROOT_REGEX;
import static org.junit.Assert.assertEquals;

import com.google.common.collect.ImmutableList;

import org.eclipse.jgit.http.server.ServletUtils;
import org.eclipse.jgit.http.server.glue.MetaFilter;
import org.eclipse.jgit.http.server.glue.MetaServlet;
import org.eclipse.jgit.internal.storage.dfs.DfsRepository;
import org.eclipse.jgit.junit.TestRepository;
import org.eclipse.jgit.lib.Config;

import java.io.IOException;
import java.util.regex.Pattern;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/** Run {@link ViewFilter} in a test environment. */
public class TestViewFilter {
  public static class Result {
    private final GitilesView view;
    private final FakeHttpServletRequest req;
    private final FakeHttpServletResponse res;

    private Result(GitilesView view, FakeHttpServletRequest req, FakeHttpServletResponse res) {
      this.view = view;
      this.req = req;
      this.res = res;
    }

    public GitilesView getView() {
      return view;
    }

    public FakeHttpServletRequest getRequest() {
      return req;
    }

    public FakeHttpServletResponse getResponse() {
      return res;
    }
  }

  private static class TestServlet extends HttpServlet {
    private static final long serialVersionUID = 1L;

    private GitilesView view;

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse res) {
      checkState(view == null);
      view = ViewFilter.getView(req);
    }
  }

  public static FakeHttpServletRequest newRequest(TestRepository<? extends DfsRepository> repo,
      String pathAndQuery) {
    FakeHttpServletRequest req = FakeHttpServletRequest.newRequest(repo.getRepository());
    int q = pathAndQuery.indexOf('?');
    if (q > 0) {
      req.setPathInfo(pathAndQuery.substring(0, q));
      req.setQueryString(pathAndQuery.substring(q + 1));
    } else {
      req.setPathInfo(pathAndQuery);
    }
    return req;
  }

  private static MetaServlet dummyServlet(MetaFilter mf) {
    return new MetaServlet(mf) {
      private static final long serialVersionUID = 1L;
    };
  }

  public static class Builder {
    private TestRepository<? extends DfsRepository> repo;
    private Config cfg;
    private FakeHttpServletRequest req;
    private boolean checkRepositoryName = true;

    public Builder(TestRepository<? extends DfsRepository> repo) {
      this.repo = repo;
    }

    public Builder setConfig(Config cfg) {
      this.cfg = cfg;
      return this;
    }

    public Builder setPathAndQuery(String pathAndQuery) {
      req = newRequest(repo, pathAndQuery);
      return this;
    }

    public Builder setRequest(FakeHttpServletRequest req) {
      this.req = req;
      return this;
    }

    public Builder setCheckRepositoryName(boolean checkRepositoryName) {
      this.checkRepositoryName = checkRepositoryName;
      return this;
    }

    public Result service() throws IOException, ServletException {
      return TestViewFilter.service(repo, req, cfg, checkRepositoryName);
    }
  }

  public static Result servicePathAndQuery(TestRepository<? extends DfsRepository> repo,
      String pathAndQuery) throws IOException, ServletException {
    return new Builder(repo).setPathAndQuery(pathAndQuery).service();
  }

  public static Result serviceRequest(TestRepository<? extends DfsRepository> repo,
      FakeHttpServletRequest req) throws IOException, ServletException {
    return new Builder(repo).setRequest(req).service();
  }

  public static Result service(TestRepository<? extends DfsRepository> repo,
      FakeHttpServletRequest req, Config cfg, boolean checkRepositoryName)
          throws IOException, ServletException {
    TestServlet servlet = new TestServlet();
    TestGitilesAccess access = new TestGitilesAccess(repo.getRepository());
    ViewFilter vf = new ViewFilter(
        access,
        TestGitilesUrls.URLS,
        new VisibilityCache(false));
    MetaFilter mf = new MetaFilter();

    if (cfg != null) {
      access.setConfig(cfg);
    }

    for (Pattern p : ImmutableList.of(ROOT_REGEX, REPO_REGEX, REPO_PATH_REGEX)) {
      mf.serveRegex(p)
          .through(new RawFilter(access))
          .through(vf)
          .with(servlet);
    }

    req.setAttribute(ServletUtils.ATTRIBUTE_REPOSITORY, repo.getRepository());
    FakeHttpServletResponse res = new FakeHttpServletResponse();
    dummyServlet(mf).service(req, res);
    if (servlet.view != null) {
      ViewFilter.setView(req, servlet.view);
      if (servlet.view.getRepositoryName() != null && checkRepositoryName) {
        assertEquals(repo.getRepository().getDescription().getRepositoryName(),
            servlet.view.getRepositoryName());
      }
    }
    return new Result(servlet.view, req, res);
  }
}
