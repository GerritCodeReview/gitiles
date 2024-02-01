// Copyright 2015 Google Inc. All Rights Reserved.
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
import static javax.servlet.http.HttpServletResponse.SC_OK;

import com.google.gitiles.CommitJsonData.Commit;
import com.google.gitiles.CommitJsonData.Log;
import com.google.gitiles.DateFormatter.Format;
import com.google.gson.reflect.TypeToken;
import java.util.ArrayList;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link LogServlet}. */
@RunWith(JUnit4.class)
public class LogServletTest extends ServletTest {
  private static final TypeToken<Log> LOG = new TypeToken<Log>() {};
  private static final String MAIN = "main";
  private static final String AUTHOR_METADATA_ELEMENT = "<th class=\"Metadata-title\">author</th>";
  private static final String COMMITTER_METADATA_ELEMENT =
      "<th class=\"Metadata-title\">committer</th>";

  @Test
  public void basicLog() throws Exception {
    RevCommit commit = repo.branch("HEAD").commit().create();

    Log response = buildJson(LOG, "/repo/+log");
    assertThat(response.log).hasSize(1);
    verifyJsonCommit(response.log.get(0), commit);
    assertThat(response.log.get(0).treeDiff).isNull();
  }

  @Test
  public void treeDiffLog() throws Exception {
    String contents1 = "foo\n";
    String contents2 = "foo\ncontents\n";
    RevCommit c1 = repo.update("master", repo.commit().add("foo", contents1));
    RevCommit c2 = repo.update("master", repo.commit().parent(c1).add("foo", contents2));

    Log response = buildJson(LOG, "/repo/+log/master", "name-status=1");
    assertThat(response.log).hasSize(2);

    Commit jc2 = response.log.get(0);
    verifyJsonCommit(jc2, c2);
    assertThat(jc2.treeDiff).hasSize(1);
    assertThat(jc2.treeDiff.get(0).type).isEqualTo("modify");
    assertThat(jc2.treeDiff.get(0).oldPath).isEqualTo("foo");
    assertThat(jc2.treeDiff.get(0).newPath).isEqualTo("foo");

    Commit jc1 = response.log.get(1);
    verifyJsonCommit(jc1, c1);
    assertThat(jc1.treeDiff).hasSize(1);
    assertThat(jc1.treeDiff.get(0).type).isEqualTo("add");
    assertThat(jc1.treeDiff.get(0).oldPath).isEqualTo("/dev/null");
    assertThat(jc1.treeDiff.get(0).newPath).isEqualTo("foo");
  }

  @Test
  public void firstParentLog() throws Exception {
    RevCommit p1 = repo.update("master", repo.commit().add("foo", "foo\n"));
    RevCommit p2 = repo.update("master", repo.commit().add("foo", "foo2\n"));
    RevCommit c = repo.update("master", repo.commit().parent(p1).parent(p2).add("foo", "foo3\n"));

    Log response = buildJson(LOG, "/repo/+log/master", "first-parent");
    assertThat(response.log).hasSize(2);

    verifyJsonCommit(response.log.get(0), c);
    verifyJsonCommit(response.log.get(1), p1);
  }

  @Test
  public void topoKeepBranchTogetherLog() throws Exception {
    RevCommit a = repo.update("master", repo.commit().add("foo", "foo\n"));
    RevCommit b1 = repo.update("master", repo.commit().parent(a).add("foo", "foo3\n"));
    RevCommit c = repo.update("master", repo.commit().parent(a).add("foo", "foo2\n"));
    RevCommit b2 = repo.update("master", repo.commit().parent(b1).add("foo", "foo4\n"));
    RevCommit d = repo.update("master", repo.commit().parent(c).parent(b2).add("foo", "foo5\n"));

    Log response = buildJson(LOG, "/repo/+log/master", "topo-order");
    assertThat(response.log).hasSize(5);

    verifyJsonCommit(response.log.get(0), d);
    verifyJsonCommit(response.log.get(1), b2);
    verifyJsonCommit(response.log.get(2), b1);
    verifyJsonCommit(response.log.get(3), c);
    verifyJsonCommit(response.log.get(4), a);
  }

  @Test
  public void follow() throws Exception {
    String contents = "contents";
    RevCommit c1 = repo.branch("master").commit().add("foo", contents).create();
    RevCommit c2 = repo.branch("master").commit().rm("foo").add("bar", contents).create();
    repo.getRevWalk().parseBody(c1);
    repo.getRevWalk().parseBody(c2);

    Log response = buildJson(LOG, "/repo/+log/master/bar", "follow=0");
    assertThat(response.log).hasSize(1);
    verifyJsonCommit(response.log.get(0), c2);

    response = buildJson(LOG, "/repo/+log/master/bar");
    assertThat(response.log).hasSize(2);
    verifyJsonCommit(response.log.get(0), c2);
    verifyJsonCommit(response.log.get(1), c1);
  }

  private void verifyJsonCommit(Commit jsonCommit, RevCommit commit) throws Exception {
    repo.getRevWalk().parseBody(commit);
    GitilesAccess access = new TestGitilesAccess(repo.getRepository()).forRequest(null);
    DateFormatter df = new DateFormatter(access, Format.DEFAULT);
    assertThat(jsonCommit.commit).isEqualTo(commit.name());
    assertThat(jsonCommit.tree).isEqualTo(commit.getTree().name());

    ArrayList<String> expectedParents = new ArrayList<>();
    for (int i = 0; i < commit.getParentCount(); i++) {
      expectedParents.add(commit.getParent(i).name());
    }
    assertThat(jsonCommit.parents).containsExactlyElementsIn(expectedParents);

    assertThat(jsonCommit.author.name).isEqualTo(commit.getAuthorIdent().getName());
    assertThat(jsonCommit.author.email).isEqualTo(commit.getAuthorIdent().getEmailAddress());
    assertThat(jsonCommit.author.time).isEqualTo(df.format(commit.getAuthorIdent()));
    assertThat(jsonCommit.committer.name).isEqualTo(commit.getCommitterIdent().getName());
    assertThat(jsonCommit.committer.email).isEqualTo(commit.getCommitterIdent().getEmailAddress());
    assertThat(jsonCommit.committer.time).isEqualTo(df.format(commit.getCommitterIdent()));
    assertThat(jsonCommit.message).isEqualTo(commit.getFullMessage());
  }

  @Test
  public void verifyPreviousButtonAction() throws Exception {
    repo.branch(MAIN).commit().add("foo", "contents").create();
    RevCommit grandParent = repo.branch(MAIN).commit().add("foo", "contents").create();
    RevCommit parent =
        repo.branch(MAIN).commit().parent(grandParent).add("foo", "contents").create();
    RevCommit main = repo.branch(MAIN).commit().parent(parent).create();

    int numCommitsPerPage = 2;
    String path =
        "/repo/+log/" + grandParent.toObjectId().getName() + ".." + main.toObjectId().getName();
    FakeHttpServletResponse res =
        buildResponse(
            path,
            "format=html" + "&n=" + numCommitsPerPage + "&s=" + parent.toObjectId().getName(),
            SC_OK);

    assertThat(res.getActualBodyString())
        .contains(
            "<a class=\"LogNav-prev\""
                + " href=\"/b/repo/+log/"
                + grandParent.toObjectId().getName()
                + ".."
                + main.toObjectId().getName()
                + "/?format=html"
                + "&amp;n=2"
                + "\">");
  }

  @Test
  public void verifyNextButtonAction() throws Exception {
    repo.branch(MAIN).commit().add("foo", "contents").create();
    RevCommit grandParent = repo.branch(MAIN).commit().add("foo", "contents").create();
    RevCommit parent =
        repo.branch(MAIN).commit().parent(grandParent).add("foo", "contents").create();
    RevCommit main = repo.branch(MAIN).commit().parent(parent).create();

    int numCommitsPerPage = 1;
    String path =
        "/repo/+log/" + grandParent.toObjectId().getName() + ".." + main.toObjectId().getName();
    FakeHttpServletResponse res =
        buildResponse(path, "format=html" + "&n=" + numCommitsPerPage, SC_OK);

    assertThat(res.getActualBodyString())
        .contains(
            "<a class=\"LogNav-next\""
                + " href=\"/b/repo/+log/"
                + grandParent.toObjectId().getName()
                + ".."
                + main.toObjectId().getName()
                + "/?format=html"
                + "&amp;n=1"
                + "&amp;s="
                + parent.toObjectId().getName()
                + "\">");
  }

  @Test
  public void prettyDefaultUsesDefaultCssClass() throws Exception {
    RevCommit parent = repo.branch(MAIN).commit().add("foo", "contents").create();
    RevCommit main = repo.branch(MAIN).commit().parent(parent).create();

    String path =
        "/repo/+log/" + parent.toObjectId().getName() + ".." + main.toObjectId().getName();
    FakeHttpServletResponse res = buildResponse(path, "format=html", SC_OK);

    assertThat(res.getActualBodyString())
        .contains("<li class=\"CommitLog-item CommitLog-item--default\">");
    assertThat(res.getActualBodyString()).doesNotContain(AUTHOR_METADATA_ELEMENT);
    assertThat(res.getActualBodyString()).doesNotContain(COMMITTER_METADATA_ELEMENT);
  }

  @Test
  public void prettyExplicitlyDefaultUsesDefaultCssClass() throws Exception {
    testPrettyHtmlOutput(
        "default", /* shouldShowAuthor= */ false, /* shouldShowCommitter= */ false);
  }

  @Test
  public void prettyOnelineUsesOnelineCssClass() throws Exception {
    testPrettyHtmlOutput(
        "oneline", /* shouldShowAuthor= */ false, /* shouldShowCommitter= */ false);
  }

  @Test
  public void prettyCustomTypeUsesCustomCssClass() throws Exception {
    testPrettyHtmlOutput(
        "aCustomPrettyType", /* shouldShowAuthor= */ false, /* shouldShowCommitter= */ false);
  }

  @Test
  public void prettyFullerUsesFullerCssClass() throws Exception {
    testPrettyHtmlOutput("fuller", /* shouldShowAuthor= */ true, /* shouldShowCommitter= */ true);
  }

  private void testPrettyHtmlOutput(
      String prettyType, boolean shouldShowAuthor, boolean shouldShowCommitter) throws Exception {
    RevCommit parent = repo.branch(MAIN).commit().add("foo", "contents").create();
    RevCommit main = repo.branch(MAIN).commit().parent(parent).create();

    String path =
        "/repo/+log/" + parent.toObjectId().getName() + ".." + main.toObjectId().getName();
    FakeHttpServletResponse res =
        buildResponse(path, "format=html" + "&pretty=" + prettyType, SC_OK);

    assertThat(res.getActualBodyString())
        .contains("<li class=\"CommitLog-item CommitLog-item--" + prettyType + "\">");

    if (shouldShowAuthor) {
      assertThat(res.getActualBodyString()).contains(AUTHOR_METADATA_ELEMENT);
    } else {
      assertThat(res.getActualBodyString()).doesNotContain(AUTHOR_METADATA_ELEMENT);
    }

    if (shouldShowCommitter) {
      assertThat(res.getActualBodyString()).contains(COMMITTER_METADATA_ELEMENT);
    } else {
      assertThat(res.getActualBodyString()).doesNotContain(COMMITTER_METADATA_ELEMENT);
    }
  }
}
