// Copyright 2012 Google Inc. All Rights Reserved.
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

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.google.gitiles.DateFormatter.Format;
import com.google.gitiles.doc.GitilesMarkdown;
import com.google.gitiles.doc.ImageLoader;
import com.google.gitiles.doc.MarkdownToHtml;
import com.google.gson.reflect.TypeToken;
import com.google.template.soy.data.SanitizedContent;

import org.eclipse.jgit.errors.IncorrectObjectTypeException;
import org.eclipse.jgit.errors.LargeObjectException;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.http.server.ServletUtils;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevObject;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.util.RawParseUtils;
import org.pegdown.ast.RootNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.io.Writer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/** Serves the index page for a repository, if accessed directly by a browser. */
public class RepositoryIndexServlet extends BaseServlet {
  private static final long serialVersionUID = 1L;
  private static final Logger log = LoggerFactory.getLogger(RepositoryIndexServlet.class);

  static final int REF_LIMIT = 10;
  private static final int LOG_LIMIT = 20;

  private final TimeCache timeCache;

  public RepositoryIndexServlet(GitilesAccess.Factory accessFactory, Renderer renderer,
      TimeCache timeCache) {
    super(renderer, accessFactory);
    this.timeCache = checkNotNull(timeCache, "timeCache");
  }

  @Override
  protected void doGetHtml(HttpServletRequest req, HttpServletResponse res) throws IOException {
    GitilesView view = ViewFilter.getView(req);
    Repository repo = ServletUtils.getRepository(req);
    GitilesAccess access = getAccess(req);
    RepositoryDescription desc = access.getRepositoryDescription();

    RevWalk walk = new RevWalk(repo);
    Paginator paginator = null;
    try {
      Map<String, Object> data = Maps.newHashMapWithExpectedSize(7);
      List<Map<String, Object>> tags = RefServlet.getTagsSoyData(req, timeCache, walk, REF_LIMIT);
      ObjectId headId = repo.resolve(Constants.HEAD);
      if (headId != null) {
        RevObject head = walk.parseAny(headId);
        int limit = LOG_LIMIT;
        Map<String, Object> readme = renderReadme(view, walk, head, access.getConfig());
        if (readme != null) {
          data.putAll(readme);
          limit = 5;
        }
        // TODO(dborowitz): Handle non-commit or missing HEAD?
        if (head.getType() == Constants.OBJ_COMMIT) {
          walk.reset();
          walk.markStart((RevCommit) head);
          paginator = new Paginator(walk, limit, null);
        }
      }
      if (!data.containsKey("entries")) {
        data.put("entries", ImmutableList.of());
      }
      List<Map<String, Object>> branches = RefServlet.getBranchesSoyData(req, REF_LIMIT);

      data.put("cloneUrl", desc.cloneUrl);
      data.put("mirroredFromUrl", Strings.nullToEmpty(desc.mirroredFromUrl));
      data.put("description", Strings.nullToEmpty(desc.description));
      data.put("branches", trim(branches));
      if (branches.size() > REF_LIMIT) {
        data.put("moreBranchesUrl", GitilesView.refs().copyFrom(view).toUrl());
      }
      data.put("tags", trim(tags));
      data.put("hasLog", paginator != null);
      if (tags.size() > REF_LIMIT) {
        data.put("moreTagsUrl", GitilesView.refs().copyFrom(view).toUrl());
      }
      GitilesConfig.putVariant(getAccess(req).getConfig(), "logEntry", "logEntryVariant", data);

      if (paginator != null) {
        DateFormatter df = new DateFormatter(access, Format.DEFAULT);
        try (OutputStream out = startRenderStreamingHtml(req, res, "gitiles.repositoryIndex", data)) {
          Writer w = newWriter(out, res);
          new LogSoyData(req, access, "oneline")
              .renderStreaming(paginator, "HEAD", renderer, w, df);
          w.flush();
        }
      } else {
        renderHtml(req, res, "gitiles.repositoryIndex", data);
      }
    } finally {
      walk.release();
    }
  }

  @Override
  protected void doGetJson(HttpServletRequest req, HttpServletResponse res) throws IOException {
    GitilesAccess access = getAccess(req);
    RepositoryDescription desc = access.getRepositoryDescription();
    renderJson(req, res, desc, new TypeToken<RepositoryDescription>() {}.getType());
  }

  private static <T> List<T> trim(List<T> list) {
    return list.size() > REF_LIMIT ? list.subList(0, REF_LIMIT) : list;
  }

  private Map<String, Object> renderReadme(GitilesView view, RevWalk walk,
      RevObject head, Config cfg) throws IOException {
    RevTree rootTree;
    try {
      rootTree = walk.parseTree(head);
    } catch (MissingObjectException | IncorrectObjectTypeException notTreeish) {
      return null;
    }

    String readmePath = null;
    ObjectId readmeId = null;
    ObjectReader reader = walk.getObjectReader();
    TreeWalk tw = new TreeWalk(reader);
    tw.setRecursive(false);
    tw.addTree(rootTree);
    while (tw.next()) {
      String name = tw.getNameString();
      if (TreeSoyData.isReadmeFile(name)
          && (tw.getRawMode(0) & FileMode.TYPE_MASK) == FileMode.TYPE_FILE) {
        readmePath = name;
        readmeId = tw.getObjectId(0);
        break;
      }
    }
    if (readmeId == null || !cfg.getBoolean("markdown", "render", true)) {
      return null;
    }

    try {
      int inputLimit = cfg.getInt("markdown", "inputLimit", 5 << 20);
      byte[] raw = reader.open(readmeId, Constants.OBJ_BLOB).getCachedBytes(inputLimit);
      String md = RawParseUtils.decode(raw);
      RootNode root = GitilesMarkdown.parseFile(view, readmePath, md);
      if (root == null) {
        return null;
      }

      int imageLimit = cfg.getInt("markdown", "imageLimit", 256 << 10);
      ImageLoader img = null;
      if (imageLimit > 0) {
        img = new ImageLoader(reader, view, rootTree, readmePath, imageLimit);
      }

      return ImmutableMap.<String,Object> of("readmeHtml", new MarkdownToHtml(view, cfg)
        .setImageLoader(img)
        .toSoyHtml(root));
    } catch (LargeObjectException | IOException e) {
      log.error(String.format("error rendering %s/%s",
          view.getRepositoryName(), readmePath), e);
      return null;
    }
  }
}
