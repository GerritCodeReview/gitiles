package com.google.gitiles;

import static com.google.common.base.Preconditions.checkNotNull;
import static javax.servlet.http.HttpServletResponse.SC_NOT_FOUND;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.eclipse.jgit.http.server.ServletUtils;
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

  public SearchServlet(GitilesAccess.Factory accessFactory, Renderer renderer, Linkifier linkifier) {
    super(renderer, accessFactory);
    this.linkifier = checkNotNull(linkifier, "linkifier");
  }

  @Override
  protected void doGetHtml(HttpServletRequest req, HttpServletResponse res) throws IOException {
    Repository repo = ServletUtils.getRepository(req);
    GitilesView view = getView(req, repo);

    Map<String, Object> data = new HashMap<>();

    String title = "Search - ";
    data.put("title", title);

    data.put("query", view.getParameters().get("query"));
    data.put("revision", view.getRevision().getName());
    data.put("revisionSHA1", view.getRevision().getId().getName());
    data.put("path", view.getPathPart());

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
