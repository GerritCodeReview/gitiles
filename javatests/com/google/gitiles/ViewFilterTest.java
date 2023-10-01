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

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static com.google.gitiles.MoreAssert.assertThrows;

import com.google.common.net.HttpHeaders;
import java.io.IOException;
import java.util.Optional;
import javax.servlet.ServletException;
import org.eclipse.jgit.internal.storage.dfs.DfsRepository;
import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription;
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository;
import org.eclipse.jgit.junit.TestRepository;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for the view filter. */
@RunWith(JUnit4.class)
public class ViewFilterTest {
  private TestRepository<DfsRepository> repo;

  @Before
  public void setUp() throws Exception {
    repo = new TestRepository<>(new InMemoryRepository(new DfsRepositoryDescription("repo")));
  }

  @Test
  public void noCommand() throws Exception {
    assertThat(getView("/").getType()).isEqualTo(GitilesView.Type.HOST_INDEX);
    assertThat(getView("/repo").getType()).isEqualTo(GitilesView.Type.REPOSITORY_INDEX);
    assertThrows(GitilesRequestFailureException.class, () -> getView("/repo/+"));
    assertThrows(GitilesRequestFailureException.class, () -> getView("/repo/+/"));
  }

  @Test
  public void autoCommand() throws Exception {
    RevCommit parent = repo.commit().create();
    RevCommit master = repo.branch(MASTER).commit().parent(parent).create();
    String hex = master.name();
    String hexBranch = hex.substring(0, 10);
    repo.branch(hexBranch).commit().create();

    assertThat(getView("/repo/+/master").getType()).isEqualTo(GitilesView.Type.REVISION);
    assertThat(getView("/repo/+/" + hexBranch).getType()).isEqualTo(GitilesView.Type.REVISION);
    assertThat(getView("/repo/+/" + hex).getType()).isEqualTo(GitilesView.Type.REVISION);
    assertThat(getView("/repo/+/" + hex.substring(0, 7)).getType())
        .isEqualTo(GitilesView.Type.REVISION);
    assertThat(getView("/repo/+/master/").getType()).isEqualTo(GitilesView.Type.PATH);
    assertThat(getView("/repo/+/" + hex + "/").getType()).isEqualTo(GitilesView.Type.PATH);
    assertThat(getView("/repo/+/" + hex + "/index.c").getType()).isEqualTo(GitilesView.Type.PATH);
    assertThat(getView("/repo/+/" + hex + "/index.md").getType()).isEqualTo(GitilesView.Type.DOC);
    assertThat(getView("/repo/+/master^..master").getType()).isEqualTo(GitilesView.Type.DIFF);
    assertThat(getView("/repo/+/master^..master/").getType()).isEqualTo(GitilesView.Type.DIFF);
    assertThat(getView("/repo/+/" + parent.name() + ".." + hex + "/").getType())
        .isEqualTo(GitilesView.Type.DIFF);
  }

  @Test
  public void hostIndex() throws Exception {
    GitilesView view = getView("/");
    assertThat(view.getType()).isEqualTo(GitilesView.Type.HOST_INDEX);
    assertThat(view.getHostName()).isEqualTo("test-host");
    assertThat(view.getRepositoryName()).isNull();
    assertThat(view.getRevision()).isEqualTo(Revision.NULL);
    assertThat(view.getOldRevision()).isEqualTo(Revision.NULL);
    assertThat(view.getPathPart()).isNull();
  }

  @Test
  public void repositoryIndex() throws Exception {
    GitilesView view = getView("/repo");
    assertThat(view.getType()).isEqualTo(GitilesView.Type.REPOSITORY_INDEX);
    assertThat(view.getRepositoryName()).isEqualTo("repo");
    assertThat(view.getRevision()).isEqualTo(Revision.NULL);
    assertThat(view.getOldRevision()).isEqualTo(Revision.NULL);
    assertThat(view.getPathPart()).isNull();
  }

  @Test
  public void refs() throws Exception {
    GitilesView view;

    view = getView("/repo/+refs");
    assertThat(view.getType()).isEqualTo(GitilesView.Type.REFS);
    assertThat(view.getRepositoryName()).isEqualTo("repo");
    assertThat(view.getRevision()).isEqualTo(Revision.NULL);
    assertThat(view.getOldRevision()).isEqualTo(Revision.NULL);
    assertThat(view.getPathPart()).isEqualTo("");

    view = getView("/repo/+refs/");
    assertThat(view.getType()).isEqualTo(GitilesView.Type.REFS);
    assertThat(view.getRepositoryName()).isEqualTo("repo");
    assertThat(view.getRevision()).isEqualTo(Revision.NULL);
    assertThat(view.getOldRevision()).isEqualTo(Revision.NULL);
    assertThat(view.getPathPart()).isEqualTo("");

    view = getView("/repo/+refs/heads");
    assertThat(view.getType()).isEqualTo(GitilesView.Type.REFS);
    assertThat(view.getRepositoryName()).isEqualTo("repo");
    assertThat(view.getRevision()).isEqualTo(Revision.NULL);
    assertThat(view.getOldRevision()).isEqualTo(Revision.NULL);
    assertThat(view.getPathPart()).isEqualTo("heads");

    view = getView("/repo/+refs/heads/");
    assertThat(view.getType()).isEqualTo(GitilesView.Type.REFS);
    assertThat(view.getRepositoryName()).isEqualTo("repo");
    assertThat(view.getRevision()).isEqualTo(Revision.NULL);
    assertThat(view.getOldRevision()).isEqualTo(Revision.NULL);
    assertThat(view.getPathPart()).isEqualTo("heads");

    view = getView("/repo/+refs/heads/master");
    assertThat(view.getType()).isEqualTo(GitilesView.Type.REFS);
    assertThat(view.getRepositoryName()).isEqualTo("repo");
    assertThat(view.getRevision()).isEqualTo(Revision.NULL);
    assertThat(view.getOldRevision()).isEqualTo(Revision.NULL);
    assertThat(view.getPathPart()).isEqualTo("heads/master");
  }

  @Test
  public void describe() throws Exception {
    GitilesView view;

    assertThrows(GitilesRequestFailureException.class, () -> getView("/repo/+describe"));
    assertThrows(GitilesRequestFailureException.class, () -> getView("/repo/+describe/"));

    view = getView("/repo/+describe/deadbeef");
    assertThat(view.getType()).isEqualTo(GitilesView.Type.DESCRIBE);
    assertThat(view.getRepositoryName()).isEqualTo("repo");
    assertThat(view.getRevision()).isEqualTo(Revision.NULL);
    assertThat(view.getOldRevision()).isEqualTo(Revision.NULL);
    assertThat(view.getPathPart()).isEqualTo("deadbeef");

    view = getView("/repo/+describe/refs/heads/master~3^~2");
    assertThat(view.getType()).isEqualTo(GitilesView.Type.DESCRIBE);
    assertThat(view.getRepositoryName()).isEqualTo("repo");
    assertThat(view.getRevision()).isEqualTo(Revision.NULL);
    assertThat(view.getOldRevision()).isEqualTo(Revision.NULL);
    assertThat(view.getPathPart()).isEqualTo("refs/heads/master~3^~2");
  }

  @Test
  public void showBranches() throws Exception {
    RevCommit master = repo.branch(MASTER).commit().create();
    RevCommit stable = repo.branch("refs/heads/stable").commit().create();
    GitilesView view;

    view = getView("/repo/+show/master");
    assertThat(view.getType()).isEqualTo(GitilesView.Type.REVISION);
    assertThat(view.getRevision().getName()).isEqualTo("master");
    assertThat(view.getRevision().getId()).isEqualTo(master);
    assertThat(view.getPathPart()).isNull();

    view = getView("/repo/+show/heads/master");
    assertThat(view.getType()).isEqualTo(GitilesView.Type.REVISION);
    assertThat(view.getRevision().getName()).isEqualTo("heads/master");
    assertThat(view.getRevision().getId()).isEqualTo(master);
    assertThat(view.getPathPart()).isNull();

    view = getView("/repo/+show/refs/heads/master");
    assertThat(view.getType()).isEqualTo(GitilesView.Type.REVISION);
    assertThat(view.getRevision().getName()).isEqualTo(MASTER);
    assertThat(view.getRevision().getId()).isEqualTo(master);
    assertThat(view.getPathPart()).isNull();

    view = getView("/repo/+show/stable");
    assertThat(view.getType()).isEqualTo(GitilesView.Type.REVISION);
    assertThat(view.getRevision().getName()).isEqualTo("stable");
    assertThat(view.getRevision().getId()).isEqualTo(stable);
    assertThat(view.getPathPart()).isNull();

    assertThrows(GitilesRequestFailureException.class, () -> getView("/repo/+show/stable..master"));
  }

  @Test
  public void ambiguousBranchAndTag() throws Exception {
    RevCommit branch = repo.branch("refs/heads/name").commit().create();
    RevCommit tag = repo.branch("refs/tags/name").commit().create();
    GitilesView view;

    view = getView("/repo/+show/name");
    assertThat(view.getType()).isEqualTo(GitilesView.Type.REVISION);
    assertThat(view.getRevision().getName()).isEqualTo("name");
    assertThat(view.getRevision().getId()).isEqualTo(tag);
    assertThat(view.getPathPart()).isNull();

    view = getView("/repo/+show/heads/name");
    assertThat(view.getType()).isEqualTo(GitilesView.Type.REVISION);
    assertThat(view.getRevision().getName()).isEqualTo("heads/name");
    assertThat(view.getRevision().getId()).isEqualTo(branch);
    assertThat(view.getPathPart()).isNull();

    view = getView("/repo/+show/refs/heads/name");
    assertThat(view.getType()).isEqualTo(GitilesView.Type.REVISION);
    assertThat(view.getRevision().getName()).isEqualTo("refs/heads/name");
    assertThat(view.getRevision().getId()).isEqualTo(branch);
    assertThat(view.getPathPart()).isNull();

    view = getView("/repo/+show/tags/name");
    assertThat(view.getType()).isEqualTo(GitilesView.Type.REVISION);
    assertThat(view.getRevision().getName()).isEqualTo("tags/name");
    assertThat(view.getRevision().getId()).isEqualTo(tag);
    assertThat(view.getPathPart()).isNull();

    view = getView("/repo/+show/refs/tags/name");
    assertThat(view.getType()).isEqualTo(GitilesView.Type.REVISION);
    assertThat(view.getRevision().getName()).isEqualTo("refs/tags/name");
    assertThat(view.getRevision().getId()).isEqualTo(tag);
    assertThat(view.getPathPart()).isNull();
  }

  @Test
  public void path() throws Exception {
    RevCommit master = repo.branch(MASTER).commit().create();
    repo.branch("refs/heads/stable").commit().create();
    GitilesView view;

    view = getView("/repo/+show/master/");
    assertThat(view.getType()).isEqualTo(GitilesView.Type.PATH);
    assertThat(view.getRevision().getId()).isEqualTo(master);
    assertThat(view.getPathPart()).isEqualTo("");

    view = getView("/repo/+show/master/foo");
    assertThat(view.getType()).isEqualTo(GitilesView.Type.PATH);
    assertThat(view.getRevision().getId()).isEqualTo(master);
    assertThat(view.getPathPart()).isEqualTo("foo");

    view = getView("/repo/+show/master/foo/");
    assertThat(view.getType()).isEqualTo(GitilesView.Type.PATH);
    assertThat(view.getRevision().getId()).isEqualTo(master);
    assertThat(view.getPathPart()).isEqualTo("foo");

    view = getView("/repo/+show/master/foo/bar");
    assertThat(view.getType()).isEqualTo(GitilesView.Type.PATH);
    assertThat(view.getRevision().getId()).isEqualTo(master);
    assertThat(view.getPathPart()).isEqualTo("foo/bar");

    assertThrows(
        GitilesRequestFailureException.class, () -> getView("/repo/+show/stable..master/foo"));
  }

  @Test
  public void doc() throws Exception {
    RevCommit master = repo.branch(MASTER).commit().create();
    repo.branch("refs/heads/stable").commit().create();
    GitilesView view;

    view = getView("/repo/+doc/master/");
    assertThat(view.getType()).isEqualTo(GitilesView.Type.DOC);
    assertThat(view.getRevision().getId()).isEqualTo(master);
    assertThat(view.getPathPart()).isEqualTo("");

    view = getView("/repo/+doc/master/index.md");
    assertThat(view.getType()).isEqualTo(GitilesView.Type.DOC);
    assertThat(view.getRevision().getId()).isEqualTo(master);
    assertThat(view.getPathPart()).isEqualTo("index.md");

    view = getView("/repo/+doc/master/foo/");
    assertThat(view.getType()).isEqualTo(GitilesView.Type.DOC);
    assertThat(view.getRevision().getId()).isEqualTo(master);
    assertThat(view.getPathPart()).isEqualTo("foo");

    view = getView("/repo/+doc/master/foo/bar.md");
    assertThat(view.getType()).isEqualTo(GitilesView.Type.DOC);
    assertThat(view.getRevision().getId()).isEqualTo(master);
    assertThat(view.getPathPart()).isEqualTo("foo/bar.md");

    assertThrows(
        GitilesRequestFailureException.class, () -> getView("/repo/+doc/stable..master/foo"));
  }

  @Test
  public void multipleSlashes() throws Exception {
    repo.branch(MASTER).commit().create();
    assertThat(getView("//").getType()).isEqualTo(GitilesView.Type.HOST_INDEX);
    assertThat(getView("//repo").getType()).isEqualTo(GitilesView.Type.REPOSITORY_INDEX);
    assertThat(getView("//repo//").getType()).isEqualTo(GitilesView.Type.REPOSITORY_INDEX);
    assertThrows(GitilesRequestFailureException.class, () -> getView("/repo/+//master"));
    assertThrows(
        GitilesRequestFailureException.class, () -> getView("/repo/+/refs//heads//master"));
    assertThrows(GitilesRequestFailureException.class, () -> getView("/repo/+//master//"));
    assertThrows(GitilesRequestFailureException.class, () -> getView("/repo/+//master/foo//bar"));
  }

  @Test
  public void diff() throws Exception {
    RevCommit parent = repo.commit().create();
    RevCommit master = repo.branch(MASTER).commit().parent(parent).create();
    GitilesView view;

    view = getView("/repo/+diff/master^..master");
    assertThat(view.getType()).isEqualTo(GitilesView.Type.DIFF);
    assertThat(view.getRevision().getName()).isEqualTo("master");
    assertThat(view.getRevision().getId()).isEqualTo(master);
    assertThat(view.getOldRevision().getName()).isEqualTo("master^");
    assertThat(view.getOldRevision().getId()).isEqualTo(parent);
    assertThat(view.getPathPart()).isEqualTo("");

    view = getView("/repo/+diff/master^..master/");
    assertThat(view.getType()).isEqualTo(GitilesView.Type.DIFF);
    assertThat(view.getRevision().getName()).isEqualTo("master");
    assertThat(view.getRevision().getId()).isEqualTo(master);
    assertThat(view.getOldRevision().getName()).isEqualTo("master^");
    assertThat(view.getOldRevision().getId()).isEqualTo(parent);
    assertThat(view.getPathPart()).isEqualTo("");

    view = getView("/repo/+diff/master^..master/foo");
    assertThat(view.getType()).isEqualTo(GitilesView.Type.DIFF);
    assertThat(view.getRevision().getName()).isEqualTo("master");
    assertThat(view.getRevision().getId()).isEqualTo(master);
    assertThat(view.getOldRevision().getName()).isEqualTo("master^");
    assertThat(view.getOldRevision().getId()).isEqualTo(parent);
    assertThat(view.getPathPart()).isEqualTo("foo");

    view = getView("/repo/+diff/refs/heads/master^..refs/heads/master");
    assertThat(view.getType()).isEqualTo(GitilesView.Type.DIFF);
    assertThat(view.getRevision().getName()).isEqualTo(MASTER);
    assertThat(view.getRevision().getId()).isEqualTo(master);
    assertThat(view.getOldRevision().getName()).isEqualTo("refs/heads/master^");
    assertThat(view.getOldRevision().getId()).isEqualTo(parent);
    assertThat(view.getPathPart()).isEqualTo("");
  }

  @Test
  public void diffAgainstEmptyCommit() throws Exception {
    RevCommit master = repo.branch(MASTER).commit().create();
    GitilesView view = getView("/repo/+diff/master^!");
    assertThat(view.getType()).isEqualTo(GitilesView.Type.DIFF);
    assertThat(view.getRevision().getName()).isEqualTo("master");
    assertThat(view.getRevision().getId()).isEqualTo(master);
    assertThat(view.getOldRevision()).isEqualTo(Revision.NULL);
    assertThat(view.getPathPart()).isEqualTo("");
  }

  @Test
  public void log() throws Exception {
    RevCommit parent = repo.commit().create();
    RevCommit master = repo.branch(MASTER).commit().parent(parent).create();
    GitilesView view;

    view = getView("/repo/+log");
    assertThat(view.getType()).isEqualTo(GitilesView.Type.LOG);
    assertThat(view.getRevision()).isEqualTo(Revision.NULL);
    assertThat(view.getPathPart()).isNull();

    view = getView("/repo/+log/");
    assertThat(view.getType()).isEqualTo(GitilesView.Type.LOG);
    assertThat(view.getRevision()).isEqualTo(Revision.NULL);
    assertThat(view.getPathPart()).isNull();

    view = getView("/repo/+log/master");
    assertThat(view.getType()).isEqualTo(GitilesView.Type.LOG);
    assertThat(view.getRevision().getName()).isEqualTo("master");
    assertThat(view.getRevision().getId()).isEqualTo(master);
    assertThat(view.getOldRevision()).isEqualTo(Revision.NULL);
    assertThat(view.getPathPart()).isEqualTo("");

    view = getView("/repo/+log/master/");
    assertThat(view.getType()).isEqualTo(GitilesView.Type.LOG);
    assertThat(view.getRevision().getName()).isEqualTo("master");
    assertThat(view.getRevision().getId()).isEqualTo(master);
    assertThat(view.getOldRevision()).isEqualTo(Revision.NULL);
    assertThat(view.getPathPart()).isEqualTo("");

    view = getView("/repo/+log/master/foo");
    assertThat(view.getType()).isEqualTo(GitilesView.Type.LOG);
    assertThat(view.getRevision().getName()).isEqualTo("master");
    assertThat(view.getRevision().getId()).isEqualTo(master);
    assertThat(view.getOldRevision()).isEqualTo(Revision.NULL);
    assertThat(view.getPathPart()).isEqualTo("foo");

    view = getView("/repo/+log/master^..master");
    assertThat(view.getType()).isEqualTo(GitilesView.Type.LOG);
    assertThat(view.getRevision().getName()).isEqualTo("master");
    assertThat(view.getRevision().getId()).isEqualTo(master);
    assertThat(view.getOldRevision().getName()).isEqualTo("master^");
    assertThat(view.getOldRevision().getId()).isEqualTo(parent);
    assertThat(view.getPathPart()).isEqualTo("");

    view = getView("/repo/+log/master^..master/");
    assertThat(view.getType()).isEqualTo(GitilesView.Type.LOG);
    assertThat(view.getRevision().getName()).isEqualTo("master");
    assertThat(view.getRevision().getId()).isEqualTo(master);
    assertThat(view.getOldRevision().getName()).isEqualTo("master^");
    assertThat(view.getOldRevision().getId()).isEqualTo(parent);
    assertThat(view.getPathPart()).isEqualTo("");

    view = getView("/repo/+log/master^..master/foo");
    assertThat(view.getType()).isEqualTo(GitilesView.Type.LOG);
    assertThat(view.getRevision().getName()).isEqualTo("master");
    assertThat(view.getRevision().getId()).isEqualTo(master);
    assertThat(view.getOldRevision().getName()).isEqualTo("master^");
    assertThat(view.getOldRevision().getId()).isEqualTo(parent);
    assertThat(view.getPathPart()).isEqualTo("foo");

    view = getView("/repo/+log/refs/heads/master^..refs/heads/master");
    assertThat(view.getType()).isEqualTo(GitilesView.Type.LOG);
    assertThat(view.getRevision().getName()).isEqualTo(MASTER);
    assertThat(view.getRevision().getId()).isEqualTo(master);
    assertThat(view.getOldRevision().getName()).isEqualTo("refs/heads/master^");
    assertThat(view.getOldRevision().getId()).isEqualTo(parent);
    assertThat(view.getPathPart()).isEqualTo("");
  }

  @Test
  public void archive() throws Exception {
    RevCommit master = repo.branch(MASTER).commit().create();
    repo.branch("refs/heads/branch").commit().create();
    GitilesView view;

    assertThrows(GitilesRequestFailureException.class, () -> getView("/repo/+archive"));
    assertThrows(GitilesRequestFailureException.class, () -> getView("/repo/+archive/"));
    assertThrows(
        GitilesRequestFailureException.class, () -> getView("/repo/+archive/master..branch"));
    assertThrows(GitilesRequestFailureException.class, () -> getView("/repo/+archive/master.foo"));
    assertThrows(GitilesRequestFailureException.class, () -> getView("/repo/+archive/master.zip"));
    assertThrows(
        GitilesRequestFailureException.class, () -> getView("/repo/+archive/master/.tar.gz"));
    assertThrows(
        GitilesRequestFailureException.class, () -> getView("/repo/+archive/master/foo/.tar.gz"));

    view = getView("/repo/+archive/master.tar.gz");
    assertThat(view.getType()).isEqualTo(GitilesView.Type.ARCHIVE);
    assertThat(view.getRepositoryName()).isEqualTo("repo");
    assertThat(view.getRevision().getName()).isEqualTo("master");
    assertThat(view.getRevision().getId()).isEqualTo(master);
    assertThat(view.getOldRevision()).isEqualTo(Revision.NULL);
    assertThat(view.getExtension()).isEqualTo(".tar.gz");
    assertThat(view.getPathPart()).isNull();

    view = getView("/repo/+archive/master.tar.bz2");
    assertThat(view.getType()).isEqualTo(GitilesView.Type.ARCHIVE);
    assertThat(view.getRepositoryName()).isEqualTo("repo");
    assertThat(view.getRevision().getName()).isEqualTo("master");
    assertThat(view.getRevision().getId()).isEqualTo(master);
    assertThat(view.getOldRevision()).isEqualTo(Revision.NULL);
    assertThat(view.getExtension()).isEqualTo(".tar.bz2");
    assertThat(view.getPathPart()).isNull();

    view = getView("/repo/+archive/master/foo/bar.tar.gz");
    assertThat(view.getType()).isEqualTo(GitilesView.Type.ARCHIVE);
    assertThat(view.getRepositoryName()).isEqualTo("repo");
    assertThat(view.getRevision().getName()).isEqualTo("master");
    assertThat(view.getRevision().getId()).isEqualTo(master);
    assertThat(view.getOldRevision()).isEqualTo(Revision.NULL);
    assertThat(view.getExtension()).isEqualTo(".tar.gz");
    assertThat(view.getPathPart()).isEqualTo("foo/bar");
  }

  @Test
  public void blame() throws Exception {
    RevCommit master = repo.branch(MASTER).commit().create();
    repo.branch("refs/heads/branch").commit().create();
    GitilesView view;

    assertThrows(GitilesRequestFailureException.class, () -> getView("/repo/+blame"));
    assertThrows(GitilesRequestFailureException.class, () -> getView("/repo/+blame/"));
    assertThrows(GitilesRequestFailureException.class, () -> getView("/repo/+blame/master"));
    assertThrows(
        GitilesRequestFailureException.class, () -> getView("/repo/+blame/master..branch"));

    view = getView("/repo/+blame/master/foo/bar");
    assertThat(view.getType()).isEqualTo(GitilesView.Type.BLAME);
    assertThat(view.getRepositoryName()).isEqualTo("repo");
    assertThat(view.getRevision().getName()).isEqualTo("master");
    assertThat(view.getRevision().getId()).isEqualTo(master);
    assertThat(view.getOldRevision()).isEqualTo(Revision.NULL);
    assertThat(view.getPathPart()).isEqualTo("foo/bar");
  }

  @Test
  public void testNormalizeParents() throws Exception {
    RevCommit parent = repo.commit().create();
    RevCommit master = repo.branch(MASTER).commit().parent(parent).create();
    GitilesView view;

    assertThat(getView("/repo/+/master").toUrl()).isEqualTo("/b/repo/+/master");
    assertThat(getView("/repo/+/" + master.name()).toUrl()).isEqualTo("/b/repo/+/" + master.name());
    assertThat(getRedirectUrl("/repo/+/master~")).isEqualTo("/b/repo/+/" + parent.name());
    assertThat(getRedirectUrl("/repo/+/master^")).isEqualTo("/b/repo/+/" + parent.name());

    view = getView("/repo/+log/master~..master/");
    assertThat(view.getRevision().getName()).isEqualTo("master");
    assertThat(view.getOldRevision().getName()).isEqualTo("master~");

    view = getView("/repo/+log/master^!/");
    assertThat(view.getRevision().getName()).isEqualTo("master");
    assertThat(view.getOldRevision().getName()).isEqualTo("master^");
  }

  private static final String MASTER = "refs/heads/master";
  private static final String MAIN = "refs/heads/main";

  @Test
  public void autoCommand_branchRedirect() throws Exception {
    RevCommit parent = repo.commit().create();
    RevCommit master = repo.branch(MASTER).commit().parent(parent).create();
    RevCommit main = repo.branch(MAIN).commit().parent(parent).create();
    RevCommit someBranch =
        repo.branch("refs/heads/some@branch")
            .commit()
            .parent(main)
            .add("README", "This is a test README")
            .create();
    repo.branch("refs/heads/another@level").commit().parent(someBranch).create();

    String hex = master.name();
    String hexBranch = hex.substring(0, 10);
    repo.branch(hexBranch).commit().create();

    BranchRedirect branchRedirect =
        new BranchRedirect() {
          @Override
          protected Optional<String> getRedirectBranch(Repository repo, String sourceBranch) {
            if (MASTER.equals(toFullBranchName(sourceBranch))) {
              return Optional.of(MAIN);
            }
            if ("refs/heads/some@branch".equals(toFullBranchName(sourceBranch))) {
              return Optional.of(MAIN);
            }
            return Optional.empty();
          }
        };

    GitilesView view = getView("/repo/+/master", branchRedirect);
    assertThat(view.getType()).isEqualTo(GitilesView.Type.REVISION);
    assertThat(view.getRevision().getName()).isEqualTo(MAIN);
    assertThat(view.getRevision().getId()).isEqualTo(main);

    view = getView("/repo/+/master/index.c", branchRedirect);
    assertThat(view.getType()).isEqualTo(GitilesView.Type.PATH);
    assertThat(view.getPathPart()).isEqualTo("index.c");
    assertThat(view.getRevision().getName()).isEqualTo(MAIN);
    assertThat(view.getRevision().getId()).isEqualTo(main);

    view = getView("/repo/+/some@branch", branchRedirect);
    assertThat(view.getType()).isEqualTo(GitilesView.Type.REVISION);
    assertThat(view.getRevision().getName()).isEqualTo(MAIN);
    assertThat(view.getRevision().getId()).isEqualTo(main);

    view = getView("/repo/+/master/master", branchRedirect);
    assertThat(view.getType()).isEqualTo(GitilesView.Type.PATH);
    assertThat(view.getPathPart()).isEqualTo("master");
    assertThat(view.getRevision().getName()).isEqualTo(MAIN);
    assertThat(view.getRevision().getId()).isEqualTo(main);

    FakeHttpServletResponse response = getResponse("/repo/+/master^1", branchRedirect);
    assertThat(response.getHeader(HttpHeaders.LOCATION))
        .contains("/b/repo/+/" + parent.toObjectId().name());

    response = getResponse("/repo/+/master~1", branchRedirect);
    assertThat(response.getHeader(HttpHeaders.LOCATION))
        .contains("/b/repo/+/" + parent.toObjectId().name());

    response = getResponse("/repo/+/another@level^1~2", branchRedirect);
    assertThat(response.getHeader(HttpHeaders.LOCATION))
        .contains("/b/repo/+/" + parent.toObjectId().name());

    assertThrows(
        GitilesRequestFailureException.class,
        () -> getView("/repo/+/some@branch:README", branchRedirect));

    assertThrows(
        GitilesRequestFailureException.class, () -> getView("/repo/+/master@{1}", branchRedirect));
  }

  @Test
  public void diff_branchRedirect() throws Exception {
    RevCommit parent = repo.commit().create();
    repo.branch(MASTER).commit().parent(parent).create();
    RevCommit main = repo.branch(MAIN).commit().parent(parent).create();
    BranchRedirect branchRedirect =
        new BranchRedirect() {
          @Override
          protected Optional<String> getRedirectBranch(Repository repo, String sourceBranch) {
            if (MASTER.equals(toFullBranchName(sourceBranch))) {
              return Optional.of(MAIN);
            }
            return Optional.empty();
          }
        };

    GitilesView view;

    view = getView("/repo/+diff/master^..master", branchRedirect);
    assertThat(view.getType()).isEqualTo(GitilesView.Type.DIFF);
    assertThat(view.getRevision().getName()).isEqualTo(MAIN);
    assertThat(view.getRevision().getId()).isEqualTo(main);
    assertThat(view.getOldRevision().getName()).isEqualTo("refs/heads/main^");
    assertThat(view.getOldRevision().getId()).isEqualTo(parent);
    assertThat(view.getPathPart()).isEmpty();

    view = getView("/repo/+diff/master..master^", branchRedirect);
    assertThat(view.getType()).isEqualTo(GitilesView.Type.DIFF);
    assertThat(view.getRevision().getName()).isEqualTo("refs/heads/main^");
    assertThat(view.getRevision().getId()).isEqualTo(parent);
    assertThat(view.getOldRevision().getName()).isEqualTo(MAIN);
    assertThat(view.getOldRevision().getId()).isEqualTo(main);
    assertThat(view.getPathPart()).isEmpty();

    view = getView("/repo/+diff/master^..master/", branchRedirect);
    assertThat(view.getType()).isEqualTo(GitilesView.Type.DIFF);
    assertThat(view.getRevision().getName()).isEqualTo(MAIN);
    assertThat(view.getRevision().getId()).isEqualTo(main);
    assertThat(view.getOldRevision().getName()).isEqualTo("refs/heads/main^");
    assertThat(view.getOldRevision().getId()).isEqualTo(parent);
    assertThat(view.getPathPart()).isEmpty();

    view = getView("/repo/+diff/master^..master/foo", branchRedirect);
    assertThat(view.getType()).isEqualTo(GitilesView.Type.DIFF);
    assertThat(view.getRevision().getName()).isEqualTo(MAIN);
    assertThat(view.getRevision().getId()).isEqualTo(main);
    assertThat(view.getOldRevision().getName()).isEqualTo("refs/heads/main^");
    assertThat(view.getOldRevision().getId()).isEqualTo(parent);
    assertThat(view.getPathPart()).isEqualTo("foo");

    view = getView("/repo/+diff/refs/heads/master^..refs/heads/master", branchRedirect);
    assertThat(view.getType()).isEqualTo(GitilesView.Type.DIFF);
    assertThat(view.getRevision().getName()).isEqualTo(MAIN);
    assertThat(view.getRevision().getId()).isEqualTo(main);
    assertThat(view.getOldRevision().getName()).isEqualTo("refs/heads/main^");
    assertThat(view.getOldRevision().getId()).isEqualTo(parent);
    assertThat(view.getPathPart()).isEmpty();
  }

  @Test
  public void path_branchRedirect() throws Exception {
    RevCommit parent = repo.commit().create();
    RevCommit main = repo.branch(MAIN).commit().parent(parent).create();
    repo.branch(MASTER).commit().parent(parent).create();
    BranchRedirect branchRedirect =
        new BranchRedirect() {
          @Override
          protected Optional<String> getRedirectBranch(Repository repo, String sourceBranch) {
            if (MASTER.equals(toFullBranchName(sourceBranch))) {
              return Optional.of(MAIN);
            }
            return Optional.empty();
          }
        };

    repo.branch("refs/heads/stable").commit().create();
    GitilesView view;

    view = getView("/repo/+show/master/", branchRedirect);
    assertThat(view.getRevision().getName()).isEqualTo(MAIN);
    assertThat(view.getType()).isEqualTo(GitilesView.Type.PATH);
    assertThat(view.getRevision().getId()).isEqualTo(main);
    assertThat(view.getPathPart()).isEmpty();

    view = getView("/repo/+show/master/foo", branchRedirect);
    assertThat(view.getRevision().getName()).isEqualTo(MAIN);
    assertThat(view.getType()).isEqualTo(GitilesView.Type.PATH);
    assertThat(view.getRevision().getId()).isEqualTo(main);
    assertThat(view.getPathPart()).isEqualTo("foo");
  }

  private static String toFullBranchName(String sourceBranch) {
    if (sourceBranch.startsWith(Constants.R_REFS)) {
      return sourceBranch;
    }
    return Constants.R_HEADS + sourceBranch;
  }

  private String getRedirectUrl(String pathAndQuery) throws ServletException, IOException {
    TestViewFilter.Result result = TestViewFilter.service(repo, pathAndQuery, new BranchRedirect());
    assertThat(result.getResponse().getStatus()).isEqualTo(302);
    return result.getResponse().getHeader(HttpHeaders.LOCATION);
  }

  private GitilesView getView(String pathAndQuery) throws ServletException, IOException {
    TestViewFilter.Result result = TestViewFilter.service(repo, pathAndQuery, new BranchRedirect());
    FakeHttpServletResponse resp = result.getResponse();
    assertWithMessage("expected non-redirect status, got " + resp.getStatus())
        .that(resp.getStatus() < 300 || resp.getStatus() >= 400)
        .isTrue();
    return result.getView();
  }

  private GitilesView getView(String pathAndQuery, BranchRedirect branchRedirect)
      throws ServletException, IOException {
    TestViewFilter.Result result = TestViewFilter.service(repo, pathAndQuery, branchRedirect);
    FakeHttpServletResponse resp = result.getResponse();
    assertWithMessage("expected non-redirect status, got " + resp.getStatus())
        .that(resp.getStatus() < 300 || resp.getStatus() >= 400 || resp.getStatus() == 302)
        .isTrue();
    return result.getView();
  }

  private FakeHttpServletResponse getResponse(String pathAndQuery, BranchRedirect branchRedirect)
      throws ServletException, IOException {
    TestViewFilter.Result result = TestViewFilter.service(repo, pathAndQuery, branchRedirect);
    return result.getResponse();
  }
}
