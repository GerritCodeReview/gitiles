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

import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;

import java.io.IOException;
import java.util.List;

public interface BlameCache {
  /** @return the blame of a path at a given commit. */
  public List<Region> get(Repository repo, ObjectId commitId, String path) throws IOException;

  /**
   * @return the last commit that modified a path, starting at the given
   *     commit.
   */
  public ObjectId findLastCommit(Repository repo, ObjectId commitId, String path)
      throws IOException;
}
