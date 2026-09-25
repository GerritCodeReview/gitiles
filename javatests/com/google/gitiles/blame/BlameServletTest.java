// Copyright (C) 2014 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.gitiles.blame;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.Iterables;
import com.google.gitiles.CommitJsonData.Ident;
import com.google.gitiles.ServletTest;
import com.google.gson.reflect.TypeToken;
import java.util.List;
import java.util.Map;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class BlameServletTest extends ServletTest {
  private static final String NAME = "J. Author";
  private static final String EMAIL = "jauthor@example.com";
  private static final String IGNORE_REVS_FILE = ".git-blame-ignore-revs";

  private static class RegionJsonData {
    int start;
    int count;
    String path;
    String commit;
    Ident author;
  }

  @Test
  public void blameJson() throws Exception {
    String contents1 = "foo\n";
    String contents2 = "foo\ncontents\n";
    RevCommit c1 = repo.update("master", repo.commit().add("foo", contents1));
    String c1Time = currentTimeFormatted();
    RevCommit c2 = repo.update("master", repo.commit().tick(10).parent(c1).add("foo", contents2));
    String c2Time = currentTimeFormatted();

    Map<String, List<RegionJsonData>> result = getBlameJson("/repo/+blame/" + c2.name() + "/foo");
    assertThat(Iterables.getOnlyElement(result.keySet())).isEqualTo("regions");
    List<RegionJsonData> regions = result.get("regions");
    assertThat(regions.size()).isEqualTo(2);

    RegionJsonData r1 = regions.get(0);
    assertThat(r1.start).isEqualTo(1);
    assertThat(r1.count).isEqualTo(1);
    assertThat(r1.path).isEqualTo("foo");
    assertThat(r1.commit).isEqualTo(c1.name());
    assertThat(r1.author.name).isEqualTo(NAME);
    assertThat(r1.author.email).isEqualTo(EMAIL);
    assertThat(r1.author.time).isEqualTo(c1Time);

    RegionJsonData r2 = regions.get(1);
    assertThat(r2.start).isEqualTo(2);
    assertThat(r2.count).isEqualTo(1);
    assertThat(r2.path).isEqualTo("foo");
    assertThat(r2.commit).isEqualTo(c2.name());
    assertThat(r2.author.name).isEqualTo(NAME);
    assertThat(r2.author.email).isEqualTo(EMAIL);
    assertThat(r2.author.time).isEqualTo(c2Time);
  }

  @Test
  public void blameJsonHonorsIgnoreRevsFileByDefault() throws Exception {
    RevCommit c1 = repo.update("master", repo.commit().add("foo", "foo\nbar\n"));
    RevCommit c2 =
        repo.update("master", repo.commit().tick(10).parent(c1).add("foo", "Foo\nBar\n"));
    RevCommit c3 =
        repo.update(
            "master",
            repo.commit()
                .tick(10)
                .parent(c2)
                .add(IGNORE_REVS_FILE, "# Reformat foo\n" + c2.name() + "\n"));

    // No config set; .git-blame-ignore-revs is honored by default.
    List<RegionJsonData> regions =
        getBlameJson("/repo/+blame/" + c3.name() + "/foo").get("regions");
    assertThat(regions).hasSize(1);
    assertThat(regions.get(0).start).isEqualTo(1);
    assertThat(regions.get(0).count).isEqualTo(2);
    assertThat(regions.get(0).commit).isEqualTo(c1.name());
  }

  @Test
  public void blameJsonWithDisabledIgnoreRevsFile() throws Exception {
    RevCommit c1 = repo.update("master", repo.commit().add("foo", "foo\nbar\n"));
    RevCommit c2 =
        repo.update("master", repo.commit().tick(10).parent(c1).add("foo", "Foo\nBar\n"));
    RevCommit c3 =
        repo.update(
            "master",
            repo.commit()
                .tick(10)
                .parent(c2)
                .add(IGNORE_REVS_FILE, "# Reformat foo\n" + c2.name() + "\n"));
    setIgnoreRevsFile("none");

    List<RegionJsonData> regions =
        getBlameJson("/repo/+blame/" + c3.name() + "/foo").get("regions");
    assertThat(regions).hasSize(1);
    assertThat(regions.get(0).commit).isEqualTo(c2.name());
  }

  @Test
  public void blameJsonWithCustomIgnoreRevsFile() throws Exception {
    RevCommit c1 = repo.update("master", repo.commit().add("foo", "foo\nbar\n"));
    RevCommit c2 =
        repo.update("master", repo.commit().tick(10).parent(c1).add("foo", "Foo\nBar\n"));
    RevCommit c3 =
        repo.update(
            "master",
            repo.commit()
                .tick(10)
                .parent(c2)
                .add("build/custom-ignore-revs", "# Reformat foo\n" + c2.name() + "\n"));
    setIgnoreRevsFile("build/custom-ignore-revs");

    List<RegionJsonData> regions =
        getBlameJson("/repo/+blame/" + c3.name() + "/foo").get("regions");
    assertThat(regions).hasSize(1);
    assertThat(regions.get(0).start).isEqualTo(1);
    assertThat(regions.get(0).count).isEqualTo(2);
    assertThat(regions.get(0).commit).isEqualTo(c1.name());
  }

  @Test
  public void blameJsonWithMissingIgnoreRevsFile() throws Exception {
    RevCommit c1 = repo.update("master", repo.commit().add("foo", "foo\nbar\n"));
    RevCommit c2 =
        repo.update("master", repo.commit().tick(10).parent(c1).add("foo", "Foo\nBar\n"));

    List<RegionJsonData> regions =
        getBlameJson("/repo/+blame/" + c2.name() + "/foo").get("regions");
    assertThat(regions).hasSize(1);
    assertThat(regions.get(0).commit).isEqualTo(c2.name());
  }

  @Test
  public void blameJsonWhenCommitPredatesIgnoreRevsFile() throws Exception {
    RevCommit c1 = repo.update("master", repo.commit().add("foo", "foo\nbar\n"));
    RevCommit c2 =
        repo.update("master", repo.commit().tick(10).parent(c1).add("foo", "Foo\nBar\n"));
    repo.update(
        "master",
        repo.commit()
            .tick(10)
            .parent(c2)
            .add(IGNORE_REVS_FILE, "# Reformat foo\n" + c2.name() + "\n"));

    // Blame at c2, which predates the commit that added IGNORE_REVS_FILE to master.
    // The file is read from c2, so c2 is not ignored.
    List<RegionJsonData> regions =
        getBlameJson("/repo/+blame/" + c2.name() + "/foo").get("regions");
    assertThat(regions).hasSize(1);
    assertThat(regions.get(0).commit).isEqualTo(c2.name());
  }

  @Test
  public void blameJsonByBranchNameWithIgnoreRevsFile() throws Exception {
    RevCommit c1 = repo.update("master", repo.commit().add("foo", "foo\nbar\n"));
    RevCommit c2 =
        repo.update("master", repo.commit().tick(10).parent(c1).add("foo", "Foo\nBar\n"));
    repo.update(
        "master",
        repo.commit()
            .tick(10)
            .parent(c2)
            .add(IGNORE_REVS_FILE, "# Reformat foo\n" + c2.name() + "\n"));

    // Blame by branch name, which reads the file from the branch tip commit.
    List<RegionJsonData> regions = getBlameJson("/repo/+blame/master/foo").get("regions");
    assertThat(regions).hasSize(1);
    assertThat(regions.get(0).start).isEqualTo(1);
    assertThat(regions.get(0).count).isEqualTo(2);
    assertThat(regions.get(0).commit).isEqualTo(c1.name());
  }

  @Test
  public void blameJsonWithOversizedIgnoreRevsFile() throws Exception {
    RevCommit c1 = repo.update("master", repo.commit().add("foo", "foo\nbar\n"));
    RevCommit c2 =
        repo.update("master", repo.commit().tick(10).parent(c1).add("foo", "Foo\nBar\n"));
    String oversized = (c2.name() + "\n").repeat(30_000);
    repo.update(
        "master",
        repo.commit()
            .tick(10)
            .parent(c2)
            .add(IGNORE_REVS_FILE, oversized));

    // Oversized ignore file is skipped, so c2 is not ignored.
    List<RegionJsonData> regions =
        getBlameJson("/repo/+blame/master/foo").get("regions");
    assertThat(regions).hasSize(1);
    assertThat(regions.get(0).commit).isEqualTo(c2.name());
  }

  @Test
  public void blameHtmlWithIgnoreRevsFile() throws Exception {
    RevCommit c1 = repo.update("master", repo.commit().add("foo", "foo\nbar\n"));
    RevCommit c2 =
        repo.update("master", repo.commit().tick(10).parent(c1).add("foo", "Foo\nBar\n"));
    RevCommit c3 =
        repo.update(
            "master", repo.commit().tick(10).parent(c2).add(IGNORE_REVS_FILE, c2.name() + "\n"));

    String html = buildHtml("/repo/+blame/" + c3.name() + "/foo", false);
    assertThat(html).contains(c1.name());
    assertThat(html).doesNotContain(c2.name());
  }

  private void setIgnoreRevsFile(String path) {
    repo.getRepository().getConfig().setString("blame", null, "ignoreRevsFile", path);
  }

  private Map<String, List<RegionJsonData>> getBlameJson(String path) throws Exception {
    return buildJson(new TypeToken<Map<String, List<RegionJsonData>>>() {}, path);
  }
}
