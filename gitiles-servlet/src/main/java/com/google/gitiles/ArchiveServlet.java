// Copyright 2013 Google Inc. All Rights Reserved.
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
import static javax.servlet.http.HttpServletResponse.SC_OK;

import org.eclipse.jgit.api.ArchiveCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.archive.TgzFormat;
import org.eclipse.jgit.archive.ZipFormat;
import org.eclipse.jgit.errors.IncorrectObjectTypeException;
import org.eclipse.jgit.http.server.ServletUtils;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevWalk;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public class ArchiveServlet extends BaseServlet {
  private static final long serialVersionUID = 1L;

  public enum Format {
    TGZ("tgz", "application/x-gzip", new TgzFormat()),
    ZIP("zip", "application/zip", new ZipFormat());

    private final String name;
    private final String mimeType;
    private final ArchiveCommand.Format<?> format;

    private Format(String name, String mimeType, ArchiveCommand.Format<?> format) {
      this.name = name;
      this.mimeType = mimeType;
      this.format = format;
      ArchiveCommand.registerFormat(name, format);
    }
  }

  private Format format;

  public ArchiveServlet(Format format) {
    super(null);
    this.format = checkNotNull(format, "format");
  }

  @Override
  protected void doGet(HttpServletRequest req, HttpServletResponse res)
      throws IOException, ServletException {
    GitilesView view = ViewFilter.getView(req);
    Revision rev = view.getRevision();
    Repository repo = ServletUtils.getRepository(req);

    // Check object type before starting the archive. If we just caught the
    // exception from cmd.call() below, we wouldn't know whether it was because
    // the input object is not a tree or something broke later.
    RevWalk walk = new RevWalk(repo);
    try {
      walk.parseTree(rev.getId());
    } catch (IncorrectObjectTypeException e) {
      res.sendError(SC_NOT_FOUND);
      return;
    } finally {
      walk.release();
    }

    String filename = getFilename(view, rev);
    setRawHeaders(req, res, filename, format.mimeType);
    res.setStatus(SC_OK);

    try {
      new ArchiveCommand(repo)
          .setFormat(format.name)
          .setTree(rev.getId())
          .setOutputStream(res.getOutputStream())
          .call();
    } catch (GitAPIException e) {
      throw new IOException(e);
    }
  }

  private String getFilename(GitilesView view, Revision rev) {
    return new StringBuilder()
        .append(view.getRepositoryName().replace('/', '-'))
        .append('-')
        .append(rev.getName())
        .append(format.format.suffixes().iterator().next())
        .toString();
  }
}
