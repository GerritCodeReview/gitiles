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
import static javax.servlet.http.HttpServletResponse.SC_NOT_FOUND;
import static javax.servlet.http.HttpServletResponse.SC_OK;

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
    assertThat(result.matches.get(0).truncated).isFalse();
    assertThat(result.matches.get(1).path).isEqualTo("dir/b.txt");
    assertThat(result.hasMore).isFalse();
  }

  @Test
  public void grepJsonCanSearchDirectoryPath() throws Exception {
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
  public void grepHtmlShowsSearchForm() throws Exception {
    repo.branch("master").commit().add("foo", "contents").create();

    FakeHttpServletResponse res = buildResponse("/repo/+grep/master", "format=html", SC_OK);

    String html = res.getActualBodyString();
    assertThat(html).contains("name=\"s\"");
    assertThat(html).contains("Search");
    assertThat(html).contains("href=\"?format=TEXT\"");
    assertThat(html).contains("href=\"?format=JSON\"");
  }

  @Test
  public void grepHtmlSearchesFileContents() throws Exception {
    repo.branch("master")
        .commit()
        .add("dir/a.txt", "alpha\nneedle here\n")
        .add("dir/b.txt", "no match\n")
        .create();

    FakeHttpServletResponse res =
        buildResponse("/repo/+grep/master", "format=html&s=needle", SC_OK);

    assertThat(res.getActualBodyString()).contains("dir/a.txt");
    assertThat(res.getActualBodyString()).contains("2: needle here");
    assertThat(res.getActualBodyString()).contains("#2");
    assertThat(res.getActualBodyString()).doesNotContain("dir/b.txt");
    assertThat(res.getActualBodyString()).doesNotContain("More matches exist");
  }

  @Test
  public void grepHtmlJsonFormatLinkPreservesSearch() throws Exception {
    repo.branch("master").commit().add("src/a.txt", "a&b\n").create();

    FakeHttpServletResponse res =
        buildResponse("/repo/+grep/master/src", "format=html&s=a%26b", SC_OK);

    String html = res.getActualBodyString();
    assertThat(html)
        .contains("href=\"/b/repo/+grep/master/src?format=JSON&amp;s=a%26b\"");
    assertThat(html).contains("href=\"?format=TEXT\"");
  }

  @Test
  public void grepJsonDoesNotSearchNonExistingPathPrefix() throws Exception {
    repo.branch("master").commit().add("src/a.txt", "needle\n").create();

    buildResponse("/repo/+grep/master/sr", "s=needle&format=JSON", SC_NOT_FOUND);
  }

  @Test
  public void grepJsonDoesNotReportMoreForExactLimit() throws Exception {
    repo.branch("master")
        .commit()
        .add("matches.txt", "needle\n".repeat(GrepServlet.MAX_MATCHES))
        .create();

    GrepResult result = buildJson(GrepResult.class, "/repo/+grep/master", "s=needle");

    assertThat(result.matches).hasSize(GrepServlet.MAX_MATCHES);
    assertThat(result.hasMore).isFalse();
  }

  @Test
  public void grepJsonReportsAdditionalMatches() throws Exception {
    repo.branch("master")
        .commit()
        .add("a.txt", "needle\n".repeat(GrepServlet.MAX_MATCHES))
        .add("b.txt", "needle\n")
        .create();

    GrepResult result = buildJson(GrepResult.class, "/repo/+grep/master", "s=needle");

    assertThat(result.matches).hasSize(GrepServlet.MAX_MATCHES);
    assertThat(result.hasMore).isTrue();
  }

  @Test
  public void grepHtmlReportsAdditionalMatches() throws Exception {
    repo.branch("master")
        .commit()
        .add("matches.txt", "needle\n".repeat(GrepServlet.MAX_MATCHES + 1))
        .create();

    FakeHttpServletResponse res =
        buildResponse("/repo/+grep/master", "format=html&s=needle", SC_OK);

    assertThat(res.getActualBodyString()).contains("More matches exist");
  }

  @Test
  public void grepJsonShortensLongLines() throws Exception {
    String line = "a".repeat(2000) + "needle" + "b".repeat(2000);
    repo.branch("master").commit().add("min.js", line + "\n").create();

    GrepResult result = buildJson(GrepResult.class, "/repo/+grep/master", "s=needle");

    assertThat(result.matches).hasSize(1);
    Match match = result.matches.get(0);
    assertThat(match.line).hasLength(GrepServlet.MAX_LINE_LENGTH);
    assertThat(match.line).contains("needle");
    assertThat(match.truncated).isTrue();
  }

  @Test
  public void grepJsonKeepsWholeMatchLongerThanLineLimit() throws Exception {
    String needle = "needle".repeat(100);
    assertThat(needle.length()).isGreaterThan(GrepServlet.MAX_LINE_LENGTH);
    String line = "a".repeat(1000) + needle + "b".repeat(1000);
    repo.branch("master").commit().add("min.js", line + "\n").create();

    GrepResult result = buildJson(GrepResult.class, "/repo/+grep/master", "s=" + needle);

    assertThat(result.matches).hasSize(1);
    Match match = result.matches.get(0);
    assertThat(match.line).isEqualTo(needle);
    assertThat(match.truncated).isTrue();
  }

  @Test
  public void grepHtmlMarksShortenedLines() throws Exception {
    repo.branch("master").commit().add("min.js", "needle" + "b".repeat(2000) + "\n").create();

    FakeHttpServletResponse res =
        buildResponse("/repo/+grep/master", "format=html&s=needle", SC_OK);

    String html = res.getActualBodyString();
    assertThat(html).contains("1: needle");
    assertThat(html).contains("b&hellip;</code>");
  }

  @Test
  public void grepJsonRequiresSubstring() throws Exception {
    repo.branch("master").commit().add("foo", "contents").create();

    buildResponse("/repo/+grep/master", "format=JSON", SC_BAD_REQUEST);
  }

  private static class GrepResult {
    List<Match> matches;
    boolean hasMore;
  }

  private static class Match {
    String path;
    int lineNumber;
    String line;
    boolean truncated;
  }
}
