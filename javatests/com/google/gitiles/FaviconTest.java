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

import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for favicon links and static asset mapping in Gitiles HTML pages. */
@RunWith(JUnit4.class)
public class FaviconTest extends ServletTest {

  private void assertFaviconElementsPresent(String html) {
    assertThat(html).contains("rel=\"icon\" href=\"/+static/favicon.ico\" sizes=\"any\"");
    assertThat(html).contains("rel=\"icon\" type=\"image/svg+xml\"");
    assertThat(html).contains("rel=\"icon\" type=\"image/png\" sizes=\"32x32\"");
    assertThat(html).contains("rel=\"icon\" type=\"image/png\" sizes=\"16x16\"");
    assertThat(html).contains("rel=\"apple-touch-icon\" sizes=\"180x180\"");
    assertThat(html).contains("favicon.ico");
    assertThat(html).contains("favicon.svg");
    assertThat(html).contains("favicon-32x32.png");
    assertThat(html).contains("favicon-16x16.png");
    assertThat(html).contains("apple-touch-icon.png");
  }

  @Test
  public void staticUrlGlobalsContainsAllFaviconEntries() {
    assertThat(Renderer.STATIC_URL_GLOBALS)
        .containsEntry("gitiles.FAVICON_ICO_URL", "favicon.ico");
    assertThat(Renderer.STATIC_URL_GLOBALS)
        .containsEntry("gitiles.FAVICON_SVG_URL", "favicon.svg");
    assertThat(Renderer.STATIC_URL_GLOBALS)
        .containsEntry("gitiles.FAVICON_32_URL", "favicon-32x32.png");
    assertThat(Renderer.STATIC_URL_GLOBALS)
        .containsEntry("gitiles.FAVICON_16_URL", "favicon-16x16.png");
    assertThat(Renderer.STATIC_URL_GLOBALS)
        .containsEntry("gitiles.APPLE_TOUCH_ICON_URL", "apple-touch-icon.png");
  }

  @Test
  public void hostIndexIncludesFavicons() throws Exception {
    String html = buildHtml("/", false);
    assertFaviconElementsPresent(html);
  }

  @Test
  public void repositoryIndexIncludesFavicons() throws Exception {
    String html = buildHtml("/repo/", false);
    assertFaviconElementsPresent(html);
  }

  @Test
  public void docPageIncludesFavicons() throws Exception {
    repo.branch("master")
        .commit()
        .add("README.md", "# Test Document\n\nDoc content.")
        .create();

    String html = buildHtml("/repo/+doc/master/README.md", false);
    assertFaviconElementsPresent(html);
  }

  @Test
  public void commitDetailIncludesFavicons() throws Exception {
    RevCommit commit = repo.branch("master").commit().add("foo", "contents").create();
    String html = buildHtml("/repo/+/" + commit.name(), false);
    assertFaviconElementsPresent(html);
  }
}
