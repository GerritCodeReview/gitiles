// Copyright 2021 Google LLC. All Rights Reserved.
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

import static com.google.gitiles.FormatType.HTML;

import java.util.Optional;
import javax.servlet.http.HttpServletRequest;
import org.eclipse.jgit.lib.Repository;

/**
 * Utility that provides information to replace the URL string that contains a branch name to a new
 * branch name. The updated branch mapping is provided by {@code BranchRedirect#getRedirectBranch}
 * method. If it should update the branch then it is the caller's responsibility to update the URL
 * with updated branch name as redirect.
 *
 * <p>This implementation does not provide a branch redirect mapping. Hence, including this as-is
 * would be a no-op. To make this effective {@code BranchRedirect#getRedirectBranch} needs to be
 * overridden that provides a mapping to the requested repo/branch.
 */
public class BranchRedirect {

  static final BranchRedirect EMPTY = new BranchRedirect();

  /**
   * Provides an extendable interface that can be used to provide implementation for determining
   * redirect branch
   *
   * @param repo Repository
   * @param sourceBranch full branch name eg. refs/heads/master
   * @return Returns the branch that should be redirected to on a given repo. {@code
   *     Optional.empty()} means no redirect.
   */
  protected Optional<String> getRedirectBranch(Repository repo, String sourceBranch) {
    return Optional.empty();
  }

  static boolean isForAutomation(HttpServletRequest req) {
    FormatType formatType = FormatType.getFormatType(req).orElse(HTML);
    switch (formatType) {
      case HTML:
      case DEFAULT:
        return false;
      case JSON:
      case TEXT:
      default:
        return true;
    }
  }
}
