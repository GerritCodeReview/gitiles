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
import static com.google.common.base.Predicates.not;
import static com.google.common.collect.Collections2.filter;

import com.google.common.base.Objects;
import com.google.common.base.Predicate;
import com.google.common.base.Throwables;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;

import org.eclipse.jgit.errors.IncorrectObjectTypeException;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefDatabase;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevSort;
import org.eclipse.jgit.revwalk.RevWalk;

import java.io.IOException;
import java.util.Collection;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * Cache of per-user object visibility.
 *
 * @author dborowitz@google.com (Dave Borowitz)
 */
public class VisibilityCache {
  private static class Key {
    private final Object user;
    private final String repositoryName;
    private final ObjectId objectId;

    private Key(Object user, String repositoryName, ObjectId objectId) {
      this.user = checkNotNull(user, "user");
      this.repositoryName = checkNotNull(repositoryName, "repositoryName");
      this.objectId = checkNotNull(objectId, "objectId");
    }

    @Override
    public boolean equals(Object o) {
      if (o instanceof Key) {
        Key k = (Key) o;
        return Objects.equal(user, k.user)
            && Objects.equal(repositoryName, k.repositoryName)
            && Objects.equal(objectId, k.objectId);
      }
      return false;
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(user, repositoryName, objectId);
    }

    @Override
    public String toString() {
      return Objects.toStringHelper(this)
        .add("user", user)
        .add("repositoryName", repositoryName)
        .add("objectId", objectId)
        .toString();
    }
  }

  private final Cache<Key, Boolean> cache;
  private final boolean topoSort;

  public static CacheBuilder<Object, Object> newBuilder() {
    return CacheBuilder.newBuilder()
        .maximumSize(1 << 10)
        .expireAfterWrite(30, TimeUnit.MINUTES);
  }

  public VisibilityCache(boolean topoSort) {
    this(topoSort, newBuilder());
  }

  public VisibilityCache(boolean topoSort, CacheBuilder<Object, Object> builder) {
    this.cache = builder.build();
    this.topoSort = topoSort;
  }

  public Cache<?, Boolean> getCache() {
    return cache;
  }

  boolean isVisible(final Repository repo, final RevWalk walk, GitilesAccess access,
      final ObjectId id) throws IOException {
    try {
      return cache.get(
          new Key(access.getUserKey(), access.getRepositoryName(), id),
          new Callable<Boolean>() {
            @Override
            public Boolean call() throws IOException {
              return isVisible(repo, walk, id);
            }
          });
    } catch (ExecutionException e) {
      Throwables.propagateIfInstanceOf(e.getCause(), IOException.class);
      throw new IOException(e);
    }
  }

  private boolean isVisible(Repository repo, RevWalk walk, ObjectId id) throws IOException {
    RevCommit commit;
    try {
      commit = walk.parseCommit(id);
    } catch (IncorrectObjectTypeException e) {
      return false;
    }

    // If any reference directly points at the requested object, permit display.
    // Common for displays of pending patch sets in Gerrit Code Review, or
    // bookmarks to the commit a tag points at.
    Collection<Ref> allRefs = repo.getRefDatabase().getRefs(RefDatabase.ALL).values();
    for (Ref ref : allRefs) {
      ref = repo.getRefDatabase().peel(ref);
      if (id.equals(ref.getObjectId()) || id.equals(ref.getPeeledObjectId())) {
        return true;
      }
    }

    // Check heads first under the assumption that most requests are for refs
    // close to a head. Tags tend to be much further back in history and just
    // clutter up the priority queue in the common case.
    return isReachableFrom(walk, commit, filter(allRefs, refStartsWith(Constants.R_HEADS)))
        || isReachableFrom(walk, commit, filter(allRefs, refStartsWith(Constants.R_TAGS)))
        || isReachableFrom(walk, commit, filter(allRefs, not(refStartsWith("refs/changes/"))));
  }

  private static Predicate<Ref> refStartsWith(final String prefix) {
    return new Predicate<Ref>() {
      @Override
      public boolean apply(Ref ref) {
        return ref.getName().startsWith(prefix);
      }
    };
  }

  private boolean isReachableFrom(RevWalk walk, RevCommit commit, Collection<Ref> refs)
      throws IOException {
    walk.reset();
    if (topoSort) {
      walk.sort(RevSort.TOPO);
    }
    walk.markStart(commit);
    for (Ref ref : refs) {
      if (ref.getPeeledObjectId() != null) {
        markUninteresting(walk, ref.getPeeledObjectId());
      } else {
        markUninteresting(walk, ref.getObjectId());
      }
    }
    // If the commit is reachable from any branch head, it will appear to be
    // uninteresting to the RevWalk and no output will be produced.
    return walk.next() == null;
  }

  private static void markUninteresting(RevWalk walk, ObjectId id) throws IOException {
    if (id == null) {
      return;
    }
    try {
      walk.markUninteresting(walk.parseCommit(id));
    } catch (IncorrectObjectTypeException e) {
      // Do nothing, doesn't affect reachability.
    } catch (MissingObjectException e) {
      // Do nothing, doesn't affect reachability.
    }
  }
}
