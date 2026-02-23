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
import static javax.servlet.http.HttpServletResponse.SC_OK;

import com.google.common.net.HttpHeaders;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class ArchiveServletTest extends ServletTest {

  @Test
  public void tarGzArchive_smokeTest() throws Exception {
    // Create a commit on master so refs/heads/master exists and points to a tree.
    repo.branch("master").commit().add("README.md", "hello\n").create();

    FakeHttpServletResponse res =
        buildResponse("/repo/+archive/refs/heads/master.tar.gz", /* queryString= */ null, SC_OK);

    // Content-Type depends on ArchiveFormat mapping; be tolerant but ensure it's gzip-ish.
    String contentType = res.getHeader(HttpHeaders.CONTENT_TYPE);
    assertThat(contentType).isNotNull();
    assertThat(contentType.toLowerCase()).contains("gzip");

    // Content-Disposition should suggest a downloadable filename.
    String cd = res.getHeader(HttpHeaders.CONTENT_DISPOSITION);
    assertThat(cd).isNotNull();
    assertThat(cd).contains("attachment");

    // Be robust: Gitiles' filename includes the "revision name", which may be "master" or
    // "refs/heads/master" depending on how the URL was formed/resolved.
    assertThat(cd).contains("filename=");
    assertThat(cd).contains("repo-");
    assertThat(cd).contains("master");
    assertThat(cd).contains(".tar.gz");

    // Body should be non-empty. Note: FakeHttpServletResponse writes via a Writer (UTF-8),
    // so binary bytes are not preserved; don't assert gzip magic bytes here.
    byte[] body = res.getActualBody();
    assertThat(body.length).isGreaterThan(20);
  }
}
