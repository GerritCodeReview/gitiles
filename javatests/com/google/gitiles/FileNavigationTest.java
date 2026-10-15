// Copyright (C) 2026 Google Inc. All Rights Reserved.
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.eclipse.jgit.dircache.DirCacheEditor.PathEdit;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.revwalk.RevBlob;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for the file navigation sidebar on file pages. */
@RunWith(JUnit4.class)
public class FileNavigationTest extends ServletTest {
  private void enable(int maxEntries) throws Exception {
    Config cfg = new Config();
    cfg.setBoolean("gitiles", null, "fileNavigation", true);
    cfg.setInt("gitiles", null, "fileNavigationMaxEntries", maxEntries);
    servlet = TestGitilesServlet.create(repo, cfg);
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, ?>> entries(Map<String, ?> level) {
    return (List<Map<String, ?>>) level.get("entries");
  }

  private static List<String> names(Map<String, ?> level) {
    List<String> names = new ArrayList<>();
    for (Map<String, ?> e : entries(level)) {
      names.add((String) e.get("name"));
    }
    return names;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, ?> navigation(Map<String, ?> data) {
    return (Map<String, ?>) data.get("navigation");
  }

  @Test
  public void disabledByDefault() throws Exception {
    repo.branch("master").commit().add("src/a.txt", "a").create();

    assertThat(buildData("/repo/+/master/src/a.txt")).doesNotContainKey("navigation");
    assertThat(buildHtml("/repo/+/master/src/a.txt", false)).doesNotContain("FileNav");
  }

  @Test
  public void expandsPathToCurrentFile() throws Exception {
    enable(500);
    repo.branch("master")
        .commit()
        .add("README", "r")
        .add("docs/guide.md", "g")
        .add("src/main/A.java", "a")
        .add("src/main/B.java", "b")
        .add("src/test/T.java", "t")
        .create();

    Map<String, ?> data = buildData("/repo/+/master/src/main/B.java");
    assertThat(data).containsEntry("containerClass", "Container--fullWidth");
    Map<String, ?> root = navigation(data);
    assertThat(names(root)).containsExactly("docs/", "src/", "README").inOrder();
    assertThat(root).doesNotContainKey("moreCount");
    assertThat(entries(root).get(0)).doesNotContainKey("children");

    Map<String, ?> src = entries(root).get(1);
    assertThat(src).containsEntry("url", "/b/repo/+/master/src/");
    @SuppressWarnings("unchecked")
    Map<String, ?> srcLevel = (Map<String, ?>) src.get("children");
    assertThat(names(srcLevel)).containsExactly("main/", "test/").inOrder();

    @SuppressWarnings("unchecked")
    Map<String, ?> mainLevel = (Map<String, ?>) entries(srcLevel).get(0).get("children");
    assertThat(names(mainLevel)).containsExactly("A.java", "B.java").inOrder();
    assertThat(entries(mainLevel).get(0)).doesNotContainKey("current");
    assertThat(entries(mainLevel).get(1)).containsEntry("current", true);
    assertThat(entries(mainLevel).get(1)).containsEntry("url", "/b/repo/+/master/src/main/B.java");

    String html = buildHtml("/repo/+/master/src/main/B.java", false);
    assertThat(html).contains("class=\"FileNav\"");
    assertThat(html).contains("href=\"/b/repo/+/master/src/main/B.java\" aria-current=\"page\"");
    assertThat(html).doesNotContain("href=\"/b/repo/+/master/src/main/A.java\" aria-current");
  }

  @Test
  public void notShownOnTreePages() throws Exception {
    enable(500);
    repo.branch("master").commit().add("src/a.txt", "a").create();

    assertThat(buildData("/repo/+/master/src/")).doesNotContainKey("navigation");
    assertThat(buildHtml("/repo/+/master/src/", false)).doesNotContain("FileNav");
  }

  @Test
  public void limitKeepsCurrentEntryAndLinksToTree() throws Exception {
    enable(2);
    repo.branch("master")
        .commit()
        .add("a", "1")
        .add("b", "2")
        .add("c", "3")
        .add("d", "4")
        .add("e", "5")
        .create();

    Map<String, ?> root = navigation(buildData("/repo/+/master/e"));
    assertThat(names(root)).containsExactly("a", "e").inOrder();
    assertThat(root).containsEntry("moreCount", 3);
    assertThat(root).containsEntry("moreUrl", "/b/repo/+/master/");

    root = navigation(buildData("/repo/+/master/a"));
    assertThat(names(root)).containsExactly("a", "b").inOrder();
    assertThat(buildHtml("/repo/+/master/a", false)).contains(">3 more...<");
  }

  @Test
  public void shownOnSymlinkPages() throws Exception {
    enable(500);
    RevBlob target = repo.blob("target");
    repo.branch("master")
        .commit()
        .add("target", "t")
        .edit(
            new PathEdit("link") {
              @Override
              public void apply(DirCacheEntry ent) {
                ent.setFileMode(FileMode.SYMLINK);
                ent.setObjectId(target);
              }
            })
        .create();

    Map<String, ?> root = navigation(buildData("/repo/+/master/link"));
    assertThat(names(root)).containsExactly("link", "target").inOrder();
    assertThat(entries(root).get(0)).containsEntry("current", true);
  }
}
