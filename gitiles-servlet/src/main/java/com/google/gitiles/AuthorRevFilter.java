// Copyright 2012 Google Inc. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.gitiles;

import org.eclipse.jgit.errors.IncorrectObjectTypeException;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.errors.StopWalkException;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.revwalk.filter.RevFilter;

import java.io.IOException;

/**
 * A {@link RevFilter} which only includes {@link RevCommit}s by an author pattern.
 *
 * Mostly equivalent to git log --author.
 */
public class AuthorRevFilter extends RevFilter {
  private final String author;

  public AuthorRevFilter(String author) {
    this.author = author;
  }

  @Override
  public boolean include(RevWalk walker, RevCommit commit) throws StopWalkException,
      MissingObjectException, IncorrectObjectTypeException, IOException {
    // git log --author uses a regex, but it's not safe to use Java's regex library because
    // it has a pathologically bad worst case.
    // TODO(kalman): Find/use a port of re2.
    return commit.getAuthorIdent().getName().contains(author)
        || commit.getAuthorIdent().getEmailAddress().contains(author);
  }

  @Override
  public RevFilter clone() {
    return this;
  }
}
