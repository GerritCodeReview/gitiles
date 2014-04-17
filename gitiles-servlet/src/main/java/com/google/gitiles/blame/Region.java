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

import com.google.common.base.Objects;

import org.eclipse.jgit.blame.BlameResult;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;

import java.io.Serializable;

/** Region of the blame of a file. */
public class Region implements Serializable {
  private static final long serialVersionUID = 1L;

  private final String sourcePath;
  private final ObjectId sourceCommit;
  private final PersonIdent sourceAuthor;
  private int count;

  public Region(String path, ObjectId commit, PersonIdent author) {
    this.sourcePath = path;
    this.sourceCommit = commit;
    this.sourceAuthor = author;
    this.count = 1;
  }

  public int getCount() {
    return count;
  }

  public String getSourcePath() {
    return sourcePath;
  }

  public ObjectId getSourceCommit() {
    return sourceCommit;
  }

  public PersonIdent getSourceAuthor() {
    return sourceAuthor;
  }

  public boolean growFrom(BlameResult blame, int i) {
    // Don't compare line numbers, so we collapse regions from the same source
    // but with deleted lines into one.
    if (Objects.equal(blame.getSourcePath(i), sourcePath)
        && Objects.equal(blame.getSourceCommit(i), sourceCommit)) {
      count++;
      return true;
    } else {
      return false;
    }
  }
}
