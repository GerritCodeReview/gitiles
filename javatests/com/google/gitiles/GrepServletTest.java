// Copyright 2026 Google Inc. All Rights Reserved.
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
import static javax.servlet.http.HttpServletResponse.SC_BAD_REQUEST;

import java.util.List;
import org.junit.Test;

public class GrepServletTest extends ServletTest {
  @Test
  public void grepJsonSearchesRepositoryRoot() throws Exception {
    repo.branch("master")
        .commit()
        .add("dir/a.txt", "alpha\nneedle here\n")
        .add("dir/b.txt", "needle too\n")
        .add("dir/c.txt", "no match\n")
        .create();

    GrepResult result = buildJson(GrepResult.class, "/repo/+grep/master", "s=needle");

    assertThat(result.matches).hasSize(2);
    assertThat(result.matches.get(0).path).isEqualTo("dir/a.txt");
    assertThat(result.matches.get(0).lineNumber).isEqualTo(2);
    assertThat(result.matches.get(0).line).isEqualTo("needle here");
    assertThat(result.matches.get(1).path).isEqualTo("dir/b.txt");
  }

  @Test
  public void grepJsonCanSearchPathPrefix() throws Exception {
    repo.branch("master")
        .commit()
        .add("src/a.txt", "needle\n")
        .add("test/a.txt", "needle\n")
        .create();

    GrepResult result = buildJson(GrepResult.class, "/repo/+grep/master/src", "s=needle");

    assertThat(result.matches).hasSize(1);
    assertThat(result.matches.get(0).path).isEqualTo("src/a.txt");
  }

  @Test
  public void grepJsonLimitsMatches() throws Exception {
    var commit = repo.branch("master").commit();
    for (int i = 0; i < GrepServlet.MAX_MATCHES + 1; i++) {
      commit.add(String.format("file%04d.txt", i), "needle\n");
    }
    commit.create();

    GrepResult result = buildJson(GrepResult.class, "/repo/+grep/master", "s=needle");

    assertThat(result.matches).hasSize(GrepServlet.MAX_MATCHES);
  }

  @Test
  public void grepJsonRequiresSubstring() throws Exception {
    repo.branch("master").commit().add("foo", "contents").create();

    buildResponse("/repo/+grep/master", "format=JSON", SC_BAD_REQUEST);
  }

  private static class GrepResult {
    List<Match> matches;
  }

  private static class Match {
    String path;
    int lineNumber;
    String line;
  }
}
