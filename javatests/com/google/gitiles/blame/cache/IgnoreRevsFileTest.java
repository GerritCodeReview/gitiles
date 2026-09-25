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

import com.google.common.collect.ImmutableSet;
import java.util.List;
import org.eclipse.jgit.dircache.DirCacheEditor.PathEdit;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.internal.storage.dfs.DfsRepository;
import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription;
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository;
import org.eclipse.jgit.junit.TestRepository;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.revwalk.RevBlob;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link IgnoreRevsFile}. */
@RunWith(JUnit4.class)
public class IgnoreRevsFileTest {
  private static final ObjectId ID1 =
      ObjectId.fromString("1111111111111111111111111111111111111111");
  private static final ObjectId ID2 =
      ObjectId.fromString("2222222222222222222222222222222222222222");
  private static final ObjectId ID3 =
      ObjectId.fromString("3333333333333333333333333333333333333333");
  private static final ObjectId ID4 =
      ObjectId.fromString("0123456789abcdef0123456789abcdef01234567");

  private TestRepository<DfsRepository> repo;

  @Before
  public void setUp() throws Exception {
    repo = new TestRepository<>(new InMemoryRepository(new DfsRepositoryDescription("test")));
  }

  @Test
  public void parseRevisionsAndComments() {
    IgnoreRevsFile file =
        IgnoreRevsFile.parse(
            "# Reformat all files\n"
                + ID1.name()
                + "\n"
                + "\n"
                + ID2.name()
                + " # Trailing comment\n"
                + "  \t"
                + ID3.name()
                + "\t\n"
                + ID4.name()
                + "#Comment without a space\n");
    assertThat(file.getIds()).containsExactly(ID1, ID2, ID3, ID4).inOrder();
    assertThat(file.isTooLarge()).isFalse();
    assertThat(file.isTruncated()).isFalse();
  }

  @Test
  public void parseEmpty() {
    assertThat(IgnoreRevsFile.parse("").getIds()).isEmpty();
    assertThat(IgnoreRevsFile.parse("# Only comments\n\n  # and blank lines\n").getIds()).isEmpty();
  }

  @Test
  public void parseCrLfAndUppercase() {
    IgnoreRevsFile file =
        IgnoreRevsFile.parse(
            "# Windows line endings\r\n"
                + "0123456789ABCDEF0123456789ABCDEF01234567\r\n"
                + ID1.name()
                + "\r\n");
    assertThat(file.getIds()).containsExactly(ID4, ID1).inOrder();
  }

  @Test
  public void parseIgnoresTextAfterRevision() {
    IgnoreRevsFile file = IgnoreRevsFile.parse(ID1.name() + " Reformat all files\n");
    assertThat(file.getIds()).containsExactly(ID1);
  }

  @Test
  public void parseSkipsMalformedLines() {
    IgnoreRevsFile file =
        IgnoreRevsFile.parse(
            "not a revision\n"
                + ID1.name().substring(0, 7)
                + "\n"
                + ID1.name()
                + "1\n"
                + ID1.name().substring(0, 39)
                + "g\n"
                + ID2.name()
                + "\n");
    assertThat(file.getIds()).containsExactly(ID2);
  }

  @Test
  public void parseSkipsNonAsciiCharacters() {
    // ObjectId.isId() accepts this string, but ObjectId.fromString() rejects it.
    String lookalike = "\u0166".repeat(Constants.OBJECT_ID_STRING_LENGTH);
    IgnoreRevsFile file = IgnoreRevsFile.parse(lookalike + "\n" + ID1.name() + "\n");
    assertThat(file.getIds()).containsExactly(ID1);
  }

  @Test
  public void parseRemovesDuplicates() {
    IgnoreRevsFile file =
        IgnoreRevsFile.parse(ID1.name() + "\n" + ID2.name() + "\n" + ID1.name() + "\n");
    assertThat(file.getIds()).containsExactly(ID1, ID2).inOrder();
  }

  @Test
  public void parseLimitsNumberOfRevisions() {
    StringBuilder content = new StringBuilder();
    for (int i = 0; i <= IgnoreRevsFile.MAX_REVS; i++) {
      content.append(id(i).name()).append('\n');
    }
    IgnoreRevsFile file = IgnoreRevsFile.parse(content.toString());
    assertThat(file.getIds()).hasSize(IgnoreRevsFile.MAX_REVS);
    assertThat(file.getIds()).doesNotContain(id(IgnoreRevsFile.MAX_REVS));
    assertThat(file.isTruncated()).isTrue();
  }

  @Test
  public void parseDuplicatesDoNotCountTowardsLimit() {
    StringBuilder content = new StringBuilder();
    for (int i = 0; i < IgnoreRevsFile.MAX_REVS; i++) {
      content.append(id(i).name()).append('\n');
    }
    content.append(id(0).name()).append('\n');
    IgnoreRevsFile file = IgnoreRevsFile.parse(content.toString());
    assertThat(file.getIds()).hasSize(IgnoreRevsFile.MAX_REVS);
    assertThat(file.isTruncated()).isFalse();
  }

  @Test
  public void readDefaultPath() throws Exception {
    RevCommit c = repo.commit().add(IgnoreRevsFile.DEFAULT_PATH, ID1.name() + "\n").create();
    IgnoreRevsFile file = IgnoreRevsFile.read(repo.getRepository(), c);
    assertThat(file.getIds()).containsExactly(ID1);
    assertThat(file.exists()).isTrue();
    assertThat(file.isTooLarge()).isFalse();
    assertThat(file.isTruncated()).isFalse();
  }

  @Test
  public void readMissingFile() throws Exception {
    RevCommit c = repo.commit().add("foo.txt", ID1.name() + "\n").create();
    IgnoreRevsFile file = IgnoreRevsFile.read(repo.getRepository(), c);
    assertThat(file.getIds()).isEmpty();
    assertThat(file.exists()).isFalse();
    assertThat(file.isTooLarge()).isFalse();
    assertThat(file.isTruncated()).isFalse();
  }

  @Test
  public void readEmptyFile() throws Exception {
    RevCommit c = repo.commit().add(IgnoreRevsFile.DEFAULT_PATH, "").create();
    IgnoreRevsFile file = IgnoreRevsFile.read(repo.getRepository(), c);
    assertThat(file.getIds()).isEmpty();
    assertThat(file.exists()).isTrue();
    assertThat(file.isTooLarge()).isFalse();
  }

  @Test
  public void readWithExistingRevWalk() throws Exception {
    RevCommit c = repo.commit().add(IgnoreRevsFile.DEFAULT_PATH, ID1.name() + "\n").create();
    try (org.eclipse.jgit.revwalk.RevWalk rw = new org.eclipse.jgit.revwalk.RevWalk(repo.getRepository())) {
      IgnoreRevsFile file = IgnoreRevsFile.read(rw, c);
      assertThat(file.getIds()).containsExactly(ID1);
      assertThat(file.exists()).isTrue();
    }
  }

  @Test
  public void readCustomPath() throws Exception {
    RevCommit c =
        repo.commit()
            .add(IgnoreRevsFile.DEFAULT_PATH, ID1.name() + "\n")
            .add("tools/blame-ignore-revs", ID2.name() + "\n")
            .create();
    IgnoreRevsFile file = IgnoreRevsFile.read(repo.getRepository(), c, "tools/blame-ignore-revs");
    assertThat(file.getIds()).containsExactly(ID2);
  }

  @Test
  public void readTrimsSlashesFromPath() throws Exception {
    RevCommit c = repo.commit().add("tools/blame-ignore-revs", ID1.name() + "\n").create();
    assertThat(IgnoreRevsFile.read(repo.getRepository(), c, "/tools/blame-ignore-revs").getIds())
        .containsExactly(ID1);
    assertThat(IgnoreRevsFile.read(repo.getRepository(), c, "/").getIds()).isEmpty();
    assertThat(IgnoreRevsFile.read(repo.getRepository(), c, "").getIds()).isEmpty();
  }

  @Test
  public void readIgnoresDirectory() throws Exception {
    RevCommit c =
        repo.commit().add(IgnoreRevsFile.DEFAULT_PATH + "/revs", ID1.name() + "\n").create();
    assertThat(IgnoreRevsFile.read(repo.getRepository(), c).getIds()).isEmpty();
  }

  @Test
  public void readIgnoresSymlink() throws Exception {
    // The symlink target is a valid revision, which would be returned if the
    // symlink were read like a regular file.
    RevBlob target = repo.blob(ID1.name());
    RevCommit c =
        repo.commit()
            .edit(
                new PathEdit(IgnoreRevsFile.DEFAULT_PATH) {
                  @Override
                  public void apply(DirCacheEntry ent) {
                    ent.setFileMode(FileMode.SYMLINK);
                    ent.setObjectId(target);
                  }
                })
            .create();
    assertThat(IgnoreRevsFile.read(repo.getRepository(), c).getIds()).isEmpty();
  }

  @Test
  public void readFileAtMaxSize() throws Exception {
    String line = ID1.name() + "\n";
    String content = line + "#".repeat(IgnoreRevsFile.MAX_SIZE - line.length());
    RevCommit c = repo.commit().add(IgnoreRevsFile.DEFAULT_PATH, content).create();
    IgnoreRevsFile file = IgnoreRevsFile.read(repo.getRepository(), c);
    assertThat(file.getIds()).containsExactly(ID1);
    assertThat(file.isTooLarge()).isFalse();
  }

  @Test
  public void readIgnoresFileLargerThanMaxSize() throws Exception {
    String line = ID1.name() + "\n";
    String content = line + "#".repeat(IgnoreRevsFile.MAX_SIZE - line.length() + 1);
    RevCommit c = repo.commit().add(IgnoreRevsFile.DEFAULT_PATH, content).create();
    IgnoreRevsFile file = IgnoreRevsFile.read(repo.getRepository(), c);
    assertThat(file.getIds()).isEmpty();
    assertThat(file.isTooLarge()).isTrue();
  }

  @Test
  public void blameSkipsRevisionsListedInFile() throws Exception {
    RevCommit c1 = repo.commit().add("foo.txt", "line1\nline2\n").create();
    RevCommit c2 =
        repo.commit().parent(c1).add("foo.txt", "line1_formatted\nline2_formatted\n").create();
    RevCommit c3 =
        repo.commit()
            .parent(c2)
            .add(IgnoreRevsFile.DEFAULT_PATH, "# Reformat foo.txt\n" + c2.name() + "\n")
            .create();

    ImmutableSet<ObjectId> ignoreIds = IgnoreRevsFile.read(repo.getRepository(), c3).getIds();
    assertThat(ignoreIds).containsExactly(c2);

    List<Region> regions = new BlameCacheImpl().get(repo.getRepository(), c3, "foo.txt", ignoreIds);
    assertThat(regions).hasSize(1);
    assertThat(regions.get(0).getSourceCommit()).isEqualTo(c1);
    assertThat(regions.get(0).getStart()).isEqualTo(0);
    assertThat(regions.get(0).getEnd()).isEqualTo(2);
  }

  /** Returns a revision whose hash code is {@code n}. */
  private static ObjectId id(int n) {
    return ObjectId.fromRaw(new int[] {0, n, 0, 0, 0});
  }
}
