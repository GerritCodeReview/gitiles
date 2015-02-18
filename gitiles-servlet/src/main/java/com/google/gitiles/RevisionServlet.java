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
import static javax.servlet.http.HttpServletResponse.SC_NOT_FOUND;
import static org.eclipse.jgit.lib.Constants.OBJ_BLOB;
import static org.eclipse.jgit.lib.Constants.OBJ_COMMIT;
import static org.eclipse.jgit.lib.Constants.OBJ_TAG;
import static org.eclipse.jgit.lib.Constants.OBJ_TREE;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.io.BaseEncoding;
import com.google.gitiles.CommitData.Field;
import com.google.gitiles.CommitJsonData.Commit;
import com.google.gitiles.DateFormatter.Format;

import org.eclipse.jgit.errors.IncorrectObjectTypeException;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.http.server.ServletUtils;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevObject;
import org.eclipse.jgit.revwalk.RevTag;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.io.Writer;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/** Serves an HTML page with detailed information about a ref. */
public class RevisionServlet extends BaseServlet {
  private static final ImmutableSet<Field> COMMIT_SOY_FIELDS =
      Field.setOf(CommitSoyData.DEFAULT_FIELDS, Field.DIFF_TREE);
  private static final ImmutableSet<Field> COMMIT_JSON_FIELDS =
      Field.setOf(CommitJsonData.DEFAULT_FIELDS, Field.DIFF_TREE);

  private static final long serialVersionUID = 1L;
  private static final Logger log = LoggerFactory.getLogger(RevisionServlet.class);

  private final Linkifier linkifier;

  public RevisionServlet(GitilesAccess.Factory accessFactory, Renderer renderer,
      Linkifier linkifier) {
    super(renderer, accessFactory);
    this.linkifier = checkNotNull(linkifier, "linkifier");
  }

  @Override
  protected void doGetHtml(HttpServletRequest req, HttpServletResponse res) throws IOException {
    GitilesView view = ViewFilter.getView(req);
    Repository repo = ServletUtils.getRepository(req);
    GitilesAccess access = getAccess(req);
    Config cfg = getAccess(req).getConfig();

    RevWalk walk = new RevWalk(repo);
    try {
      DateFormatter df = new DateFormatter(access, Format.DEFAULT);
      List<RevObject> objects = listObjects(walk, view.getRevision());
      List<Map<String, ?>> soyObjects = Lists.newArrayListWithCapacity(objects.size());
      boolean hasBlob = false;
      boolean hasReadme = false;

      // TODO(sop): Allow caching commits by SHA-1 when no S cookie is sent.
      for (RevObject obj : objects) {
        try {
          switch (obj.getType()) {
            case OBJ_COMMIT:
              soyObjects.add(ImmutableMap.of(
                  "type", Constants.TYPE_COMMIT,
                  "data", new CommitSoyData()
                      .setLinkifier(linkifier)
                      .setRevWalk(walk)
                      .setArchiveFormat(getArchiveFormat(access))
                      .toSoyData(req, (RevCommit) obj, COMMIT_SOY_FIELDS, df)));
              break;
            case OBJ_TREE:
              Map<String, Object> tree =
                  new TreeSoyData(walk.getObjectReader(), view, cfg, (RevTree) obj)
                      .toSoyData(obj);
              soyObjects.add(ImmutableMap.of(
                  "type", Constants.TYPE_TREE,
                  "data", tree));
              hasReadme = tree.containsKey("readmeHtml");
              break;
            case OBJ_BLOB:
              soyObjects.add(ImmutableMap.of(
                  "type", Constants.TYPE_BLOB,
                  "data", new BlobSoyData(walk.getObjectReader(), view).toSoyData(obj)));
              hasBlob = true;
              break;
            case OBJ_TAG:
              soyObjects.add(ImmutableMap.of(
                  "type", Constants.TYPE_TAG,
                  "data", new TagSoyData(linkifier, req).toSoyData((RevTag) obj, df)));
              break;
            default:
              log.warn("Bad object type for {}: {}", ObjectId.toString(obj.getId()), obj.getType());
              res.setStatus(SC_NOT_FOUND);
              return;
          }
        } catch (MissingObjectException e) {
          log.warn("Missing object " + ObjectId.toString(obj.getId()), e);
          res.setStatus(SC_NOT_FOUND);
          return;
        } catch (IncorrectObjectTypeException e) {
          log.warn("Incorrect object type for " + ObjectId.toString(obj.getId()), e);
          res.setStatus(SC_NOT_FOUND);
          return;
        }
      }

      renderHtml(req, res, "gitiles.revisionDetail", ImmutableMap.of(
          "title", view.getRevision().getName(),
          "objects", soyObjects,
          "hasBlob", hasBlob,
          "hasReadme", hasReadme));
    } finally {
      walk.release();
    }
  }

  @Override
  protected void doGetText(HttpServletRequest req, HttpServletResponse res) throws IOException {
    GitilesView view = ViewFilter.getView(req);
    Repository repo = ServletUtils.getRepository(req);
    ObjectReader reader = repo.newObjectReader();
    try {
        ObjectLoader loader = reader.open(view.getRevision().getId());
        if (loader.getType() != OBJ_COMMIT) {
          res.setStatus(SC_NOT_FOUND);
        } else {
          PathServlet.setTypeHeader(res, loader.getType());
          try (Writer writer = startRenderText(req, res);
              OutputStream out = BaseEncoding.base64().encodingStream(writer)) {
            loader.copyTo(out);
          }
        }
    } finally {
      reader.release();
    }
  }

  @Override
  protected void doGetJson(HttpServletRequest req, HttpServletResponse res) throws IOException {
    GitilesView view = ViewFilter.getView(req);
    Repository repo = ServletUtils.getRepository(req);

    RevWalk walk = new RevWalk(repo);
    try {
      DateFormatter df = new DateFormatter(getAccess(req), Format.DEFAULT);
      RevObject obj = walk.parseAny(view.getRevision().getId());
      switch (obj.getType()) {
        case OBJ_COMMIT:
          renderJson(req, res, new CommitJsonData()
                .setRevWalk(walk)
                .toJsonData(req, (RevCommit) obj, COMMIT_JSON_FIELDS, df),
              Commit.class);
          break;
        default:
          // TODO(dborowitz): Support showing other types.
          res.setStatus(SC_NOT_FOUND);
          break;
      }
    } finally {
      walk.release();
    }
  }

  // TODO(dborowitz): Extract this.
  static List<RevObject> listObjects(RevWalk walk, Revision rev)
      throws MissingObjectException, IOException {
    List<RevObject> objects = Lists.newArrayListWithExpectedSize(1);
    ObjectId id = rev.getId();
    RevObject cur;
    while (true) {
      cur = walk.parseAny(id);
      objects.add(cur);
      if (cur.getType() != Constants.OBJ_TAG) {
        break;
      }
      id = ((RevTag) cur).getObject();
    }
    if (cur.getType() == Constants.OBJ_COMMIT) {
      objects.add(walk.parseTree(((RevCommit) cur).getTree()));
    }
    return objects;
  }
}
