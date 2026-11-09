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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.eclipse.jgit.dircache.DirCacheEditor.PathEdit;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.revwalk.RevBlob;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.Test;

public class GrepServletTest extends ServletTest {
  private static final String MARK = "<mark class=\"GrepResults-match\">";

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
    assertThat(result.timedOut).isFalse();
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
    assertThat(html).contains("placeholder=\"Search file contents\"");
    assertThat(html).contains("autofocus");
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
    assertThat(res.getActualBodyString()).contains("dir/a.txt#2\">2</a> <code>" + MARK);
    assertThat(res.getActualBodyString()).contains(">needle</span></mark>");
    assertThat(res.getActualBodyString()).doesNotContain("dir/b.txt");
    assertThat(res.getActualBodyString()).doesNotContain("More matches exist");
    assertThat(res.getActualBodyString()).contains("1 match in 1 file");
    assertThat(res.getActualBodyString()).doesNotContain("Showing");
    assertThat(res.getActualBodyString()).doesNotContain("autofocus");
  }

  @Test
  public void grepHtmlCountsMatchesAndFiles() throws Exception {
    repo.branch("master")
        .commit()
        .add("a.txt", "needle\nneedle\n")
        .add("b.txt", "needle\n")
        .create();

    FakeHttpServletResponse res =
        buildResponse("/repo/+grep/master", "format=html&s=needle", SC_OK);

    String html = res.getActualBodyString();
    assertThat(html).contains("3 matches in 2 files");
    // Each file is listed once, with its matching lines under it.
    assertThat(html.indexOf(">a.txt</a>")).isEqualTo(html.lastIndexOf(">a.txt</a>"));
    assertThat(html).contains(">a.txt</a> <span class=\"GrepResults-count\">(2)</span>");
    assertThat(html).contains(">b.txt</a> <span class=\"GrepResults-count\">(1)</span>");
    assertThat(html).contains("a.txt#1\">1</a>");
    assertThat(html).contains("a.txt#2\">2</a>");
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
    assertThat(result.next).isEqualTo("0:b.txt");
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
    assertThat(res.getActualBodyString())
        .contains("Showing " + GrepServlet.MAX_MATCHES + " matches in 1 file");
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
    assertThat(html).contains(">1</a> <code>" + MARK);
    assertThat(html).contains(">needle</span></mark>");
    assertThat(html).contains("b</span>&hellip;</code>");
  }

  @Test
  public void grepJsonSkipsSymlinks() throws Exception {
    addFileAndSymlink();

    GrepResult result = buildJson(GrepResult.class, "/repo/+grep/master", "s=target");

    assertThat(result.matches).hasSize(1);
    assertThat(result.matches.get(0).path).isEqualTo("a.txt");
  }

  @Test
  public void grepJsonSkipsSymlinkPath() throws Exception {
    addFileAndSymlink();

    GrepResult result = buildJson(GrepResult.class, "/repo/+grep/master/link", "s=target");

    assertThat(result.matches).isEmpty();
  }

  @Test
  public void grepJsonReportsTimeout() throws Exception {
    repo.branch("master").commit().add("a.txt", "needle\n").add("b.txt", "needle\n").create();
    useGrepTimeout("0");

    GrepResult result = buildJson(GrepResult.class, "/repo/+grep/master", "s=needle");

    // One file is always searched, so that continuing makes progress.
    assertThat(result.matches).hasSize(1);
    assertThat(result.matches.get(0).path).isEqualTo("a.txt");
    assertThat(result.hasMore).isTrue();
    assertThat(result.timedOut).isTrue();
    assertThat(result.next).isEqualTo("0:b.txt");
  }

  @Test
  public void grepJsonContinuesAfterTimeout() throws Exception {
    RevCommit c =
        repo.branch("master")
            .commit()
            .add("a.txt", "needle\n")
            .add("b.txt", "needle\n")
            .create();
    useGrepTimeout("0");

    GrepResult result =
        buildJson(GrepResult.class, "/repo/+grep/" + c.name(), "s=needle&start=0%3Ab.txt");

    assertThat(result.matches).hasSize(1);
    assertThat(result.matches.get(0).path).isEqualTo("b.txt");
    assertThat(result.hasMore).isFalse();
    assertThat(result.timedOut).isFalse();
    assertThat(result.next).isNull();
  }

  @Test
  public void grepJsonContinuesWithinFile() throws Exception {
    repo.branch("master")
        .commit()
        .add("a.txt", "needle\n".repeat(GrepServlet.MAX_MATCHES + 1))
        .create();

    GrepResult first = buildJson(GrepResult.class, "/repo/+grep/master", "s=needle");
    assertThat(first.next).isEqualTo(GrepServlet.MAX_MATCHES + ":a.txt");

    GrepResult second =
        buildJson(
            GrepResult.class,
            "/repo/+grep/" + first.revision,
            "s=needle&start=" + GrepServlet.MAX_MATCHES + "%3Aa.txt");
    assertThat(second.matches).hasSize(1);
    assertThat(second.matches.get(0).lineNumber).isEqualTo(GrepServlet.MAX_MATCHES + 1);
    assertThat(second.hasMore).isFalse();
  }

  @Test
  public void grepJsonRejectsInvalidStart() throws Exception {
    repo.branch("master").commit().add("a.txt", "needle\n").create();

    buildResponse("/repo/+grep/master", "format=JSON&s=needle&start=abc", SC_BAD_REQUEST);
    buildResponse("/repo/+grep/master", "format=JSON&s=needle&start=-1%3Aa.txt", SC_BAD_REQUEST);
    buildResponse(
        "/repo/+grep/master", "format=JSON&s=needle&start=0%3Amissing.txt", SC_BAD_REQUEST);
  }

  @Test
  public void grepHtmlReportsTimeout() throws Exception {
    RevCommit c =
        repo.branch("master")
            .commit()
            .add("a.txt", "needle\n")
            .add("b.txt", "needle\n")
            .create();
    useGrepTimeout("0");

    FakeHttpServletResponse res =
        buildResponse("/repo/+grep/master", "format=html&s=needle", SC_OK);

    String html = res.getActualBodyString();
    assertThat(html).contains("Search stopped before finishing");
    assertThat(html).doesNotContain("No matches.");
    assertThat(html).doesNotContain("More matches exist");
    // The continue link names the searched commit rather than the branch.
    assertThat(html).contains("/b/repo/+grep/" + c.name());
    assertThat(html).contains("start=0%3Ab.txt");
    assertThat(html).contains("Continue search");
  }

  @Test
  public void grepHtmlHighlightsEveryMatchInLine() throws Exception {
    repo.branch("master").commit().add("a.txt", "a needle and a needle\n").create();

    FakeHttpServletResponse res =
        buildResponse("/repo/+grep/master", "format=html&s=needle", SC_OK);

    String html = res.getActualBodyString();
    assertThat(html.split(MARK, -1)).hasLength(3);
    assertThat(html).contains("prettify.css");
  }

  @Test
  public void toPiecesMarksEveryMatch() {
    List<Map<String, Object>> pieces = GrepServlet.toPieces("a needle and needle", "needle", null);
    assertThat(joinText(pieces)).isEqualTo("a needle and needle");
    assertThat(matchedTexts(pieces)).containsExactly("needle", "needle");

    // Occurrences do not overlap.
    assertThat(matchedTexts(GrepServlet.toPieces("aaa", "aa", null))).containsExactly("aa");
  }

  @Test
  public void toPiecesColorsLikeFileView() {
    List<Map<String, Object>> pieces = GrepServlet.toPieces("int grep = 1;", "grep", "java");

    assertThat(joinText(pieces)).isEqualTo("int grep = 1;");
    assertThat(matchedTexts(pieces)).containsExactly("grep");
    Map<String, Object> first = pieces.get(0);
    assertThat(first.get("text")).isEqualTo("int");
    assertThat(first.get("classes")).isEqualTo("kwd");
  }

  private static String joinText(List<Map<String, Object>> pieces) {
    StringBuilder b = new StringBuilder();
    for (Map<String, Object> piece : pieces) {
      b.append(piece.get("text"));
    }
    return b.toString();
  }

  /** Returns the text of matching pieces, joining pieces of one match that colors split. */
  private static List<String> matchedTexts(List<Map<String, Object>> pieces) {
    List<String> texts = new ArrayList<>();
    StringBuilder current = null;
    for (Map<String, Object> piece : pieces) {
      if ((Boolean) piece.get("match")) {
        if (current == null) {
          current = new StringBuilder();
        }
        current.append(piece.get("text"));
      } else if (current != null) {
        texts.add(current.toString());
        current = null;
      }
    }
    if (current != null) {
      texts.add(current.toString());
    }
    return texts;
  }

  @Test
  public void grepJsonRequiresSubstring() throws Exception {
    repo.branch("master").commit().add("foo", "contents").create();

    buildResponse("/repo/+grep/master", "format=JSON", SC_BAD_REQUEST);
  }

  private void useGrepTimeout(String timeout) throws Exception {
    Config config = new Config();
    config.setString("grep", null, "timeout", timeout);
    servlet = TestGitilesServlet.create(repo, config);
  }

  private void addFileAndSymlink() throws Exception {
    RevBlob target = repo.blob("target/file.txt");
    repo.branch("master")
        .commit()
        .add("a.txt", "target\n")
        .edit(
            new PathEdit("link") {
              @Override
              public void apply(DirCacheEntry ent) {
                ent.setFileMode(FileMode.SYMLINK);
                ent.setObjectId(target);
              }
            })
        .create();
  }

  private static class GrepResult {
    List<Match> matches;
    boolean hasMore;
    boolean timedOut;
    String revision;
    String next;
  }

  private static class Match {
    String path;
    int lineNumber;
    String line;
    boolean truncated;
  }
}
