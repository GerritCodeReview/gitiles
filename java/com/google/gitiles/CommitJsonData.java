// Copyright (C) 2013 Google Inc. All Rights Reserved.
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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.gitiles.CommitData.DiffList;
import com.google.gitiles.CommitData.Field;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import javax.servlet.http.HttpServletRequest;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;

public class CommitJsonData {
  static final ImmutableSet<Field> DEFAULT_FIELDS =
      Sets.immutableEnumSet(
          Field.SHA,
          Field.TREE,
          Field.PARENTS,
          Field.AUTHOR,
          Field.COMMITTER,
          Field.MESSAGE,
          Field.NOTES);

  public static class Log {
    public List<Commit> log;
    public String previous;
    public String next;
  }

  public static class Ident {
    public String name;
    public String email;
    public String time;
  }

  static class Commit {
    String commit;
    String tree;
    List<String> parents;
    Ident author;
    Ident committer;
    String message;
    String notes;

    List<Diff> treeDiff;
  }

  /** @see DiffEntry */
  static class Diff {
    String type;
    String oldId;
    int oldMode;
    String oldPath;
    String newId;
    int newMode;
    String newPath;
    Integer score;
  }

  Commit toJsonData(HttpServletRequest req, RevWalk walk, RevCommit c, DateFormatter df)
      throws IOException {
    return toJsonData(req, walk, c, DEFAULT_FIELDS, df);
  }

  Commit toJsonData(
      HttpServletRequest req, RevWalk walk, RevCommit c, Set<Field> fs, DateFormatter df)
      throws IOException {
    CommitData cd = new CommitData.Builder().build(req, walk, c, fs);

    Commit result = new Commit();
    if (cd.sha != null) {
      result.commit = cd.sha.name();
    }
    if (cd.tree != null) {
      result.tree = cd.tree.name();
    }
    if (cd.parents != null) {
      result.parents = Lists.newArrayListWithCapacity(cd.parents.size());
      for (RevCommit parent : cd.parents) {
        result.parents.add(parent.name());
      }
    }
    if (cd.author != null) {
      result.author = toJsonData(cd.author, df);
    }
    if (cd.committer != null) {
      result.committer = toJsonData(cd.committer, df);
    }
    if (cd.message != null) {
      result.message = cd.message;
    }
    if (cd.notes != null && !cd.notes.isEmpty()){
      result.notes = cd.notes;
    }
    if (cd.diffEntries != null) {
      result.treeDiff = toJsonData(cd.diffEntries);
    }
    return result;
  }

  private static Ident toJsonData(PersonIdent ident, DateFormatter df) {
    Ident result = new Ident();
    result.name = ident.getName();
    result.email = ident.getEmailAddress();
    result.time = df.format(ident);
    return result;
  }

  private static List<Diff> toJsonData(DiffList dl) {
    if (dl.entries == null) {
      return ImmutableList.of();
    }
    List<Diff> result = Lists.newArrayListWithCapacity(dl.entries.size());
    for (DiffEntry de : dl.entries) {
      Diff d = new Diff();
      d.type = de.getChangeType().name().toLowerCase();
      d.oldId = de.getOldId().name();
      d.oldMode = de.getOldMode().getBits();
      d.oldPath = de.getOldPath();
      d.newId = de.getNewId().name();
      d.newMode = de.getNewMode().getBits();
      d.newPath = de.getNewPath();

      switch (de.getChangeType()) {
        case COPY:
        case RENAME:
          d.score = de.getScore();
          break;
        case ADD:
        case DELETE:
        case MODIFY:
        default:
          break;
      }

      result.add(d);
    }
    return result;
  }
}
