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

import com.google.common.collect.ForwardingList;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

import org.eclipse.jgit.blame.BlameResult;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * List of blame regions in a file.
 * <p>
 * To save space when caching, a single list uses a shared symbol table of
 * commits, authors, and paths.
 */
public class RegionList extends ForwardingList<Region> implements Serializable {
  private static final long serialVersionUID = 1L;

  final ImmutableList<ObjectId> commits;
  final ImmutableList<PersonIdent> authors;
  final ImmutableList<String> paths;
  private final ImmutableList<Region> regions;

  public RegionList(BlameResult blame, int lineCount) {
    if (blame == null) {
      commits = null;
      authors = null;
      paths = null;
      regions = ImmutableList.of();
      return;
    }

    List<ObjectId> commits = Lists.newArrayList();
    List<PersonIdent> authors = Lists.newArrayList();
    List<String> paths = Lists.newArrayListWithExpectedSize(1);

    Map<ObjectId, Integer> commitIdx = Maps.newHashMap();
    Map<PersonIdent, Integer> authorIdx = Maps.newHashMap();
    Map<String, Integer> pathIdx = Maps.newHashMapWithExpectedSize(1);

    List<Region> regions = Lists.newArrayList();
    for (int i = 0; i < lineCount; i++) {
      if (regions.isEmpty()
          || !regions.get(regions.size() - 1).growFrom(blame, i, paths, commits)) {
        Integer ci, ai, pi;

        ObjectId commit = blame.getSourceCommit(i);
        if (commit != null) {
          commit = commit.copy();
          ci = commitIdx.get(commit);
          if (ci == null) {
            ci = commits.size();
            commits.add(commit);
            commitIdx.put(commit, ci);
          }
        } else {
          ci = -1;
        }

        PersonIdent author = blame.getSourceAuthor(i);
        if (author != null) {
          ai = authorIdx.get(author);
          if (ai == null) {
            ai = authors.size();
            authors.add(author);
            authorIdx.put(author, ai);
          }
        } else {
          ai = -1;
        }

        String path = blame.getSourcePath(i);
        if (path != null) {
          pi = pathIdx.get(path);
          if (pi == null) {
            pi = paths.size();
            paths.add(path);
            pathIdx.put(path, pi);
          }
        } else {
          pi = -1;
        }

        regions.add(new Region(this, ci, ai, pi));
      }
    }

    this.commits = ImmutableList.copyOf(commits);
    this.authors = ImmutableList.copyOf(authors);
    this.paths = ImmutableList.copyOf(paths);
    this.regions = ImmutableList.copyOf(regions);
  }

  @Override
  protected List<Region> delegate() {
    return regions;
  }

  private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
    in.defaultReadObject();
    for (Region r : regions) {
      r.list = this;
    }
  }
}
