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
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.cache.Weigher;

import org.eclipse.jgit.blame.BlameGenerator;
import org.eclipse.jgit.blame.BlameResult;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;

import java.io.IOException;
import java.util.concurrent.ExecutionException;

/** Guava implementation of BlameCache, weighted by blame storage size. */
public class BlameCacheImpl implements BlameCache {
  public static CacheBuilder<Key, RegionList> newBuilder() {
    return CacheBuilder.newBuilder().weigher(new Weigher<Key, RegionList>() {
      @Override
      public int weigh(Key key, RegionList value) {
        return value.weigh();
      }
    }).maximumWeight(10 << 10);
  }

  public static class Key {
    private final ObjectId commitId;
    private final String path;
    private Repository repo;

    public Key(Repository repo, ObjectId commitId, String path) {
      this.commitId = commitId;
      this.path = path;
      this.repo = repo;
    }

    public ObjectId getCommitId() {
      return commitId;
    }

    public String getPath() {
      return path;
    }

    @Override
    public boolean equals(Object o) {
      if (o instanceof Key) {
        Key k = (Key) o;
        return Objects.equal(commitId, k.commitId)
            && Objects.equal(path, k.path);
      }
      return false;
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(commitId, path);
    }
  }

  private final LoadingCache<Key, RegionList> cache;

  public BlameCacheImpl() {
    this(newBuilder());
  }

  public LoadingCache<Key, RegionList> getCache() {
    return cache;
  }

  public BlameCacheImpl(CacheBuilder<Key, RegionList> builder) {
    this.cache = builder.build(new CacheLoader<Key, RegionList>() {
      @Override
      public RegionList load(Key key) throws IOException {
        return loadBlame(key);
      }
    });
  }

  @Override
  public RegionList get(Repository repo, ObjectId commitId, String path)
      throws IOException {
    try {
      return cache.get(new Key(repo, commitId, path));
    } catch (ExecutionException e) {
      throw new IOException(e);
    }
  }

  private RegionList loadBlame(Key key) throws IOException {
    try {
      BlameGenerator gen = new BlameGenerator(key.repo, key.path);
      BlameResult blame;
      try {
        gen.push(null, key.commitId);
        blame = gen.computeBlameResult();
      } finally {
        gen.release();
      }
      if (blame == null) {
        return new RegionList();
      }
      int lineCount = blame.getResultContents().size();
      blame.discardResultContents();
      return new RegionList(blame, lineCount);
    } finally {
      key.repo = null;
    }
  }
}
