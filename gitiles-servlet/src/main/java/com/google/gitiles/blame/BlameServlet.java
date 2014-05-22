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

package com.google.gitiles.blame;

import static com.google.common.base.Preconditions.checkNotNull;
import static javax.servlet.http.HttpServletResponse.SC_NOT_FOUND;

import com.google.common.base.Objects;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.google.gitiles.BaseServlet;
import com.google.gitiles.BlobSoyData;
import com.google.gitiles.CommitSoyData;
import com.google.gitiles.DateFormatter;
import com.google.gitiles.DateFormatter.Format;
import com.google.gitiles.GitilesAccess;
import com.google.gitiles.GitilesView;
import com.google.gitiles.Renderer;
import com.google.gitiles.ViewFilter;
import com.google.template.soy.data.SoyListData;
import com.google.template.soy.data.SoyMapData;

import org.eclipse.jgit.errors.IncorrectObjectTypeException;
import org.eclipse.jgit.http.server.ServletUtils;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.AndTreeFilter;
import org.eclipse.jgit.treewalk.filter.PathFilterGroup;
import org.eclipse.jgit.treewalk.filter.TreeFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/** Serves an HTML page with blame data for a commit. */
public class BlameServlet extends BaseServlet {
  private static final long serialVersionUID = 1L;
  private static final Logger log = LoggerFactory.getLogger(BlameServlet.class);

  private final BlameCache cache;

  public BlameServlet(GitilesAccess.Factory accessFactory, Renderer renderer, BlameCache cache) {
    super(renderer, accessFactory);
    this.cache = checkNotNull(cache, "cache");
  }

  @Override
  protected void doGetHtml(HttpServletRequest req, HttpServletResponse res)
      throws IOException {
    GitilesView view = ViewFilter.getView(req);
    Repository repo = ServletUtils.getRepository(req);

    RevWalk rw = new RevWalk(repo);
    try {
      GitilesAccess access = getAccess(req);
      RevCommit currCommit = rw.parseCommit(view.getRevision().getId());
      ObjectId currCommitBlobId = resolveBlob(view, rw, currCommit);
      if (currCommitBlobId == null) {
        res.setStatus(SC_NOT_FOUND);
        return;
      }

      RevCommit lastCommit = findLastCommit(rw, currCommit, view.getPathPart());
      ObjectId lastCommitBlobId = resolveBlob(view, rw, lastCommit);

      if (!Objects.equal(currCommitBlobId, lastCommitBlobId)) {
        log.warn(String.format("Blob %s in last modified commit %s for repo %s starting from %s"
            + " does not match original blob %s",
            ObjectId.toString(lastCommitBlobId),
            ObjectId.toString(lastCommit),
            access.getRepositoryName(),
            ObjectId.toString(currCommit),
            ObjectId.toString(currCommitBlobId)));
        lastCommitBlobId = currCommitBlobId;
        lastCommit = currCommit;
      }

      String title = "Blame - " + view.getPathPart();
      Map<String, ?> blobData = new BlobSoyData(rw.getObjectReader(), view)
          .toSoyData(view.getPathPart(), lastCommitBlobId);
      if (blobData.get("lines") != null) {
        List<Region> regions = cache.get(repo, lastCommit, view.getPathPart());
        if (regions.isEmpty()) {
          res.setStatus(SC_NOT_FOUND);
          return;
        }
        DateFormatter df = new DateFormatter(access, Format.ISO);
        renderHtml(req, res, "gitiles.blameDetail", ImmutableMap.of(
            "title", title,
            "breadcrumbs", view.getBreadcrumbs(),
            "data", blobData,
            "regions", toSoyData(view, rw.getObjectReader(), regions, df)));
      } else {
        renderHtml(req, res, "gitiles.blameDetail", ImmutableMap.of(
            "title", title,
            "breadcrumbs", view.getBreadcrumbs(),
            "data", blobData));
      }
    } finally {
      rw.release();
    }
  }

  private static RevCommit findLastCommit(RevWalk rw, RevCommit curr, String path)
      throws IOException {
    rw.markStart(curr);
    rw.setRewriteParents(false);
    // Don't use rename detection, even though BlameGenerator does. It is not
    // possible for a commit to modify a path when not doing rename detection
    // but to not modify the same path when taking renames into account.
    rw.setTreeFilter(AndTreeFilter.create(
        PathFilterGroup.createFromStrings(path),
        TreeFilter.ANY_DIFF));
    try {
      return rw.next();
    } finally {
      rw.reset();
    }
  }

  private static ObjectId resolveBlob(GitilesView view, RevWalk rw, RevCommit commit)
      throws IOException {
    try {
      if (commit == null || Strings.isNullOrEmpty(view.getPathPart())) {
        return null;
      }
      TreeWalk tw = TreeWalk.forPath(rw.getObjectReader(), view.getPathPart(), commit.getTree());
      if (tw == null || (tw.getRawMode(0) & FileMode.TYPE_FILE) == 0) {
        return null;
      }
      return tw.getObjectId(0);
    } catch (IncorrectObjectTypeException e) {
      return null;
    }
  }

  private static final ImmutableList<String> CLASSES = ImmutableList.of("bg1", "bg2");
  private static final ImmutableList<SoyMapData> NULLS;
  static {
    ImmutableList.Builder<SoyMapData> nulls = ImmutableList.builder();
    for (String clazz : CLASSES) {
      nulls.add(new SoyMapData("class", clazz));
    }
    NULLS = nulls.build();
  }

  private static SoyListData toSoyData(GitilesView view, ObjectReader reader,
      List<Region> regions, DateFormatter df) throws IOException {
    Map<ObjectId, String> abbrevShas = Maps.newHashMap();
    SoyListData result = new SoyListData();

    for (int i = 0; i < regions.size(); i++) {
      Region r = regions.get(i);
      int c = i % CLASSES.size();
      if (r.getSourceCommit() == null) {
        // JGit bug may fail to blame some regions. We should fix this
        // upstream, but handle it for now.
        result.add(NULLS.get(c));
      } else {
        String abbrevSha = abbrevShas.get(r.getSourceCommit());
        if (abbrevSha == null) {
          abbrevSha = reader.abbreviate(r.getSourceCommit()).name();
          abbrevShas.put(r.getSourceCommit(), abbrevSha);
        }
        Map<String, Object> e = Maps.newHashMapWithExpectedSize(6);
        e.put("abbrevSha", abbrevSha);
        e.put("blameUrl", GitilesView.blame().copyFrom(view)
            .setRevision(r.getSourceCommit().name())
            .setPathPart(r.getSourcePath())
            .toUrl());
        e.put("commitUrl", GitilesView.revision().copyFrom(view)
            .setRevision(r.getSourceCommit().name())
            .toUrl());
        e.put("diffUrl", GitilesView.diff().copyFrom(view)
            .setRevision(r.getSourceCommit().name())
            .setPathPart(r.getSourcePath())
            .toUrl());
        e.put("author", CommitSoyData.toSoyData(r.getSourceAuthor(), df));
        e.put("class", CLASSES.get(c));
        result.add(e);
      }
      // Pad the list with null regions so we can iterate in parallel in the
      // template. We can't do this by maintaining an index variable into the
      // regions list because Soy {let} is an unmodifiable alias scoped to a
      // single block.
      for (int j = 0; j < r.getCount() - 1; j++) {
        result.add(NULLS.get(c));
      }
    }
    return result;
  }
}
