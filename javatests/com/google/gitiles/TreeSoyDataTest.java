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
import static com.google.gitiles.TreeSoyData.getTargetDisplayName;
import static com.google.gitiles.TreeSoyData.resolveTargetUrl;
import static com.google.gitiles.TreeSoyData.sortByTypeAlpha;

import com.google.common.base.Strings;
import java.util.HashMap;
import java.util.Map;
import org.eclipse.jgit.lib.ObjectId;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link TreeSoyData}. */
@RunWith(JUnit4.class)
public class TreeSoyDataTest {
  @Test
  public void getTargetDisplayNameReturnsDisplayName() throws Exception {
    assertThat(getTargetDisplayName("foo")).isEqualTo("foo");
    assertThat(getTargetDisplayName("foo/bar")).isEqualTo("foo/bar");
    assertThat(getTargetDisplayName(Strings.repeat("a/", 10) + "bar"))
        .isEqualTo("a/a/a/a/a/a/a/a/a/a/bar");
    assertThat(getTargetDisplayName(Strings.repeat("a/", 34) + "bar"))
        .isEqualTo("a/a/a/a/a/a/a/a/a/a/a/a/a/a/a/a/a/a/a/a/a/a/a/a/a/a/a/a/a/a/a/a/a/a/bar");
    assertThat(getTargetDisplayName(Strings.repeat("a/", 35) + "bar")).isEqualTo(".../bar");
    assertThat(getTargetDisplayName(Strings.repeat("a/", 100) + "bar")).isEqualTo(".../bar");
    assertThat(getTargetDisplayName(Strings.repeat("a", 80)))
        .isEqualTo(
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
  }

  @Test
  public void resolveTargetUrlReturnsUrl() throws Exception {
    ObjectId id = ObjectId.fromString("abcd1234abcd1234abcd1234abcd1234abcd1234");
    GitilesView view =
        GitilesView.path()
            .setServletPath("/x")
            .setHostName("host")
            .setRepositoryName("repo")
            .setRevision(Revision.unpeeled("m", id))
            .setPathPart("a/b/c")
            .build();
    assertThat(resolveTargetUrl(view, "/foo")).isNull();
    assertThat(resolveTargetUrl(view, "../../")).isEqualTo("/x/repo/+/m/a");
    assertThat(resolveTargetUrl(view, ".././../")).isEqualTo("/x/repo/+/m/a");
    assertThat(resolveTargetUrl(view, "..//../")).isEqualTo("/x/repo/+/m/a");
    assertThat(resolveTargetUrl(view, "../../d")).isEqualTo("/x/repo/+/m/a/d");
    assertThat(resolveTargetUrl(view, "../../..")).isEqualTo("/x/repo/+/m/");
    assertThat(resolveTargetUrl(view, "../../d/e")).isEqualTo("/x/repo/+/m/a/d/e");
    assertThat(resolveTargetUrl(view, "../d/../e/../")).isEqualTo("/x/repo/+/m/a/b");
    assertThat(resolveTargetUrl(view, "../../../../")).isNull();
    assertThat(resolveTargetUrl(view, "../../a/../../..")).isNull();
  }

  @Test
  public void sortByTypeSortsCorrect() throws Exception {
    Map<String, String> m1 = new HashMap<>();
    Map<String, String> m2 = new HashMap<>();
    Map<String, String> m3 = new HashMap<>();
    Map<String, String> m4 = new HashMap<>();
    Map<String, String> m5 = new HashMap<>();
    Map<String, String> m6 = new HashMap<>();
    m1.put("type", "TREE");
    m1.put("name", "aa");
    m2.put("type", "TREE");
    m2.put("name", "BB");
    m3.put("type", "SYMLINK");
    m4.put("type", "REGULAR_FILE");
    m5.put("type", "GITLINK");
    m6.put("type", "TREE");
    m6.put("name", "AA");
    assertThat(sortByTypeAlpha(m1, m2)).isEqualTo(-1);
    assertThat(sortByTypeAlpha(m2, m3)).isEqualTo(-1);
    assertThat(sortByTypeAlpha(m3, m4)).isEqualTo(-1);
    assertThat(sortByTypeAlpha(m4, m1)).isEqualTo(1);
    assertThat(sortByTypeAlpha(m1, m4)).isEqualTo(-1);
    assertThat(sortByTypeAlpha(m5, m2)).isEqualTo(1);
    assertThat(sortByTypeAlpha(m2, m5)).isEqualTo(-1);
    assertThat(sortByTypeAlpha(m1, m6)).isEqualTo(0);
    assertThat(sortByTypeAlpha(m2, m1)).isEqualTo(1);
  }

  @Test
  public void sortByShortestPathFirst() throws Exception {
    Map<String, String> p1 = new HashMap<>();
    Map<String, String> p2 = new HashMap<>();
    Map<String, String> p3 = new HashMap<>();
    p1.put("type", "TREE");
    p1.put("name", "short/");
    p2.put("type", "TREE");
    p2.put("name", "shortpath/");
    p3.put("type", "TREE");
    p3.put("name", "short.path/");
    assertThat(sortByTypeAlpha(p1, p2)).isLessThan(0);
    assertThat(sortByTypeAlpha(p1, p3)).isLessThan(0);
  }
}
