// Copyright 2020 Google Inc. All Rights Reserved.
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

import static com.google.common.net.HttpHeaders.LOCATION;
import static com.google.gitiles.FormatType.DEFAULT;
import static com.google.gitiles.FormatType.HTML;
import static javax.servlet.http.HttpServletResponse.SC_MOVED_PERMANENTLY;
import static org.eclipse.jgit.http.server.ServletUtils.ATTRIBUTE_REPOSITORY;

import com.google.common.annotations.VisibleForTesting;
import java.io.IOException;
import java.util.Optional;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.eclipse.jgit.http.server.ServletUtils;
import org.eclipse.jgit.lib.Repository;

/** Filter to redirect on a given branch. */
public class BranchRedirectFilter extends AbstractHttpFilter {

  public static final String REFS = "refs/";
  public static final String R_HEADS = "refs/heads/";

  /**
   * Provides an extendable interface that can be used to provide implementation for determining
   * redirect branch
   *
   * @param repo Repository
   * @param sourceBranch source branch
   * @param formatType format type of the request
   * @return Returns the branch that should be redirected to on a given repo. {@code
   *     Optional.empty()} means no redirect.
   */
  public Optional<String> getRedirectBranch(
      Repository repo, String sourceBranch, Optional<FormatType> formatType) {
    return Optional.empty();
  }

  @Override
  public void doFilter(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws IOException, ServletException {
    if (!hasRepository(req)) {
      chain.doFilter(req, res);
      return;
    }

    GitilesView view = ViewFilter.getView(req);
    Repository repo = ServletUtils.getRepository(req);
    Optional<FormatType> formatType = getFormat(req);
    Optional<String> targetBranchOnCurrentRevision =
        getRedirectBranch(repo, view.getRevision().getName(), formatType);
    Optional<String> targetBranchOnOldRevision =
        getRedirectBranch(repo, view.getOldRevision().getName(), formatType);

    if (!targetBranchOnCurrentRevision.isPresent() && !targetBranchOnOldRevision.isPresent()) {
      chain.doFilter(req, res);
      return;
    }

    Revision currentRevision =
        updateTargetAtRevision(view.getRevision(), targetBranchOnCurrentRevision);
    Revision oldRevision = updateTargetAtRevision(view.getOldRevision(), targetBranchOnOldRevision);

    String url = view.toBuilder().setRevision(currentRevision).setOldRevision(oldRevision).toUrl();
    res.setStatus(SC_MOVED_PERMANENTLY);
    res.setHeader(LOCATION, url);
  }

  private static boolean hasRepository(HttpServletRequest req) {
    return req.getAttribute(ATTRIBUTE_REPOSITORY) != null;
  }

  private static Revision updateTargetAtRevision(Revision revision, Optional<String> targetBranch) {
    if (!targetBranch.isPresent()) {
      return revision;
    }

    return new Revision(
        guessFullRefName(targetBranch.get()),
        revision.getId(),
        revision.getType(),
        revision.getPeeledId(),
        revision.getPeeledType());
  }

  @VisibleForTesting
  static String guessFullRefName(String targetBranch) {
    return targetBranch.startsWith(REFS) ? targetBranch : R_HEADS + targetBranch;
  }

  private static Optional<FormatType> getFormat(HttpServletRequest req) {
    Optional<FormatType> format = FormatType.getFormatType(req);
    if (format.isPresent() && format.get() == DEFAULT) {
      return Optional.of(HTML);
    }
    return format;
  }
}
