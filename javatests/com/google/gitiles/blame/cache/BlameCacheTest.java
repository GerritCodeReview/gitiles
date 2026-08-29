// Copyright 2026 Google LLC
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

package com.google.gitiles.blame.cache;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.util.List;
import org.eclipse.jgit.internal.storage.dfs.DfsRepository;
import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription;
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository;
import org.eclipse.jgit.junit.TestRepository;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link BlameCache} and {@link BlameCacheImpl}. */
@RunWith(JUnit4.class)
public class BlameCacheTest {
  private TestRepository<DfsRepository> repo;
  private BlameCacheImpl blameCache;

  @Before
  public void setUp() throws Exception {
    repo = new TestRepository<>(new InMemoryRepository(new DfsRepositoryDescription("test")));
    blameCache = new BlameCacheImpl();
  }

  @Test
  public void keyEqualsAndHashCode() {
    ObjectId c1 = ObjectId.fromString("1111111111111111111111111111111111111111");
    ObjectId c2 = ObjectId.fromString("2222222222222222222222222222222222222222");
    ObjectId c3 = ObjectId.fromString("3333333333333333333333333333333333333333");

    BlameCacheImpl.Key k1 = new BlameCacheImpl.Key(c1, "foo.txt");
    BlameCacheImpl.Key k2 = new BlameCacheImpl.Key(c1, "foo.txt", ImmutableSet.of());
    BlameCacheImpl.Key k3 = new BlameCacheImpl.Key(c1, "foo.txt", ImmutableSet.of(c2, c3));
    BlameCacheImpl.Key k4 = new BlameCacheImpl.Key(c1, "foo.txt", ImmutableSet.of(c3, c2));
    BlameCacheImpl.Key k5 = new BlameCacheImpl.Key(c1, "foo.txt", ImmutableSet.of(c2));

    assertThat(k1).isEqualTo(k2);
    assertThat(k1.hashCode()).isEqualTo(k2.hashCode());

    assertThat(k3).isEqualTo(k4);
    assertThat(k3.hashCode()).isEqualTo(k4.hashCode());

    assertThat(k1).isNotEqualTo(k3);
    assertThat(k3).isNotEqualTo(k5);
  }

  @Test
  public void keyToString() {
    ObjectId c1 = ObjectId.fromString("1111111111111111111111111111111111111111");
    ObjectId c2 = ObjectId.fromString("2222222222222222222222222222222222222222");
    ObjectId c3 = ObjectId.fromString("3333333333333333333333333333333333333333");

    BlameCacheImpl.Key kNoIgnore = new BlameCacheImpl.Key(c1, "foo.txt");
    assertThat(kNoIgnore.toString()).isEqualTo("1111111111111111111111111111111111111111:foo.txt");

    BlameCacheImpl.Key kWithIgnore = new BlameCacheImpl.Key(c1, "foo.txt", ImmutableSet.of(c3, c2));
    assertThat(kWithIgnore.toString())
        .isEqualTo(
            "1111111111111111111111111111111111111111:foo.txt"
                + " ignore=[2222222222222222222222222222222222222222,"
                + " 3333333333333333333333333333333333333333]");
  }

  @Test
  public void getWithoutIgnoreIdsAttributesToLastCommit() throws Exception {
    RevCommit c1 = repo.commit().add("foo.txt", "line1\nline2\n").create();
    RevCommit c2 =
        repo.commit().parent(c1).add("foo.txt", "line1_formatted\nline2_formatted\n").create();

    List<Region> regions = blameCache.get(repo.getRepository(), c2, "foo.txt");
    assertThat(regions).hasSize(1);
    assertThat(regions.get(0).getSourceCommit()).isEqualTo(c2);
    assertThat(regions.get(0).getStart()).isEqualTo(0);
    assertThat(regions.get(0).getEnd()).isEqualTo(2);
  }

  @Test
  public void getWithIgnoredCommitAttributesToParentCommit() throws Exception {
    RevCommit c1 = repo.commit().add("foo.txt", "line1\nline2\n").create();
    RevCommit c2 =
        repo.commit().parent(c1).add("foo.txt", "line1_formatted\nline2_formatted\n").create();

    List<Region> regions = blameCache.get(repo.getRepository(), c2, "foo.txt", ImmutableSet.of(c2));
    assertThat(regions).hasSize(1);
    assertThat(regions.get(0).getSourceCommit()).isEqualTo(c1);
    assertThat(regions.get(0).getStart()).isEqualTo(0);
    assertThat(regions.get(0).getEnd()).isEqualTo(2);
  }

  @Test
  public void cacheIsolationWithDifferentIgnoreIds() throws Exception {
    RevCommit c1 = repo.commit().add("foo.txt", "line1\nline2\n").create();
    RevCommit c2 =
        repo.commit().parent(c1).add("foo.txt", "line1_formatted\nline2_formatted\n").create();

    // Query 1: standard blame
    List<Region> unignored = blameCache.get(repo.getRepository(), c2, "foo.txt");
    assertThat(unignored.get(0).getSourceCommit()).isEqualTo(c2);

    // Query 2: ignored blame
    List<Region> ignored = blameCache.get(repo.getRepository(), c2, "foo.txt", ImmutableSet.of(c2));
    assertThat(ignored.get(0).getSourceCommit()).isEqualTo(c1);

    // Verify both are cached under separate keys
    assertThat(blameCache.getCache().asMap()).containsKey(new BlameCacheImpl.Key(c2, "foo.txt"));
    assertThat(blameCache.getCache().asMap())
        .containsKey(new BlameCacheImpl.Key(c2, "foo.txt", ImmutableSet.of(c2)));
    assertThat(blameCache.getCache().size()).isEqualTo(2);
  }

  @Test
  public void getWithMultipleConsecutiveIgnoredCommits() throws Exception {
    RevCommit c1 = repo.commit().add("foo.txt", "line1\nline2\n").create();
    RevCommit c2 =
        repo.commit().parent(c1).add("foo.txt", "line1_format1\nline2_format1\n").create();
    RevCommit c3 =
        repo.commit().parent(c2).add("foo.txt", "line1_format2\nline2_format2\n").create();

    // Ignore both c2 and c3 -> blame should walk back across both to c1
    List<Region> regions =
        blameCache.get(repo.getRepository(), c3, "foo.txt", ImmutableSet.of(c2, c3));
    assertThat(regions).hasSize(1);
    assertThat(regions.get(0).getSourceCommit()).isEqualTo(c1);
    assertThat(regions.get(0).getStart()).isEqualTo(0);
    assertThat(regions.get(0).getEnd()).isEqualTo(2);
  }

  @Test
  public void defaultInterfaceMethodDelegatesWhenEmpty() throws Exception {
    BlameCache customCache =
        new BlameCache() {
          @Override
          public ImmutableList<Region> get(Repository repo, ObjectId commitId, String path) {
            return ImmutableList.of(new Region(null, null, null, 0, 1));
          }

          @Override
          public ObjectId findLastCommit(Repository repo, ObjectId commitId, String path) {
            return ObjectId.zeroId();
          }
        };

    // When ignoreIds is null or empty, default method delegates to get(repo, commit, path)
    List<Region> res1 = customCache.get(repo.getRepository(), ObjectId.zeroId(), "foo.txt", null);
    assertThat(res1).hasSize(1);

    List<Region> res2 =
        customCache.get(repo.getRepository(), ObjectId.zeroId(), "foo.txt", ImmutableSet.of());
    assertThat(res2).hasSize(1);

    // When ignoreIds is non-empty, default method throws UnsupportedOperationException
    DfsRepository repo2 = repo.getRepository();
    ObjectId commitId = ObjectId.zeroId();
    ImmutableSet<ObjectId> ignoreIds = ImmutableSet.of(ObjectId.zeroId());
    assertThrows(
        UnsupportedOperationException.class,
        () -> customCache.get(repo2, commitId, "foo.txt", ignoreIds));
  }
}
