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

import com.google.common.collect.ImmutableList;
import com.google.gitiles.DateFormatter.Format;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;

import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.List;
import java.util.Map;

/** Tests for {@link LogServlet}. */
@RunWith(JUnit4.class)
public class LogServletTest extends ServletTest {
  @Test
  public void basicLog() throws Exception {
    RevCommit commit = repo.branch("HEAD").commit().create();
    repo.getRevWalk().parseBody(commit);

    JsonElement result = buildJson("/repo/+log", "");
    JsonArray log = result.getAsJsonObject().get("log").getAsJsonArray();

    GitilesAccess access = new TestGitilesAccess(repo.getRepository()).forRequest(null);
    DateFormatter df = new DateFormatter(access, Format.DEFAULT);

    assertThat(log).hasSize(1);
    verifyJsonCommit(log.get(0), commit, df);
  }

  @Test
  public void treeDiffLog() throws Exception {
    String contents1 = "foo\n";
    String contents2 = "foo\ncontents\n";
    RevCommit c1 = repo.update("master", repo.commit().add("foo", contents1));
    RevCommit c2 = repo.update("master", repo.commit().parent(c1).add("foo", contents2));
    repo.getRevWalk().parseBody(c1);
    repo.getRevWalk().parseBody(c2);

    JsonElement result = buildJson("/repo/+log/master", "&name-status=1");
    JsonArray log = result.getAsJsonObject().get("log").getAsJsonArray();

    GitilesAccess access = new TestGitilesAccess(repo.getRepository()).forRequest(null);
    DateFormatter df = new DateFormatter(access, Format.DEFAULT);

    assertThat(log).hasSize(2);
    verifyJsonCommit(log.get(0), c2, df);
    verifyJsonCommit(log.get(1), c1, df);
  }

  private void verifyJsonCommit(JsonElement element, RevCommit commit, DateFormatter df)
      throws Exception {
    CommitJsonData.Commit jsonCommit = new Gson().fromJson(element, CommitJsonData.Commit.class);
    assertThat(jsonCommit.commit).isEqualTo(commit.name());
    assertThat(jsonCommit.tree).isEqualTo(commit.getTree().name());
    //assertThat(jsonCommit.parents).isEmpty();
    assertThat(jsonCommit.author.name).isEqualTo(commit.getAuthorIdent().getName());
    assertThat(jsonCommit.author.email).isEqualTo(commit.getAuthorIdent().getEmailAddress());
    assertThat(jsonCommit.author.time).isEqualTo(df.format(commit.getAuthorIdent()));
    assertThat(jsonCommit.committer.name).isEqualTo(commit.getCommitterIdent().getName());
    assertThat(jsonCommit.committer.email).isEqualTo(commit.getCommitterIdent().getEmailAddress());
    assertThat(jsonCommit.committer.time).isEqualTo(df.format(commit.getCommitterIdent()));
    assertThat(jsonCommit.message).isEqualTo(commit.getFullMessage());
    assertThat(jsonCommit.treeDiff).isNull();
  }
}
