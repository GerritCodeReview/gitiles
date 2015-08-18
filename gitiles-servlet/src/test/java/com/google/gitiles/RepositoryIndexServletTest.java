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

import static org.junit.Assert.assertTrue;

import com.google.template.soy.data.SanitizedContent;

import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.Map;

/** Tests for {RepositoryIndexServlet}. */
@RunWith(JUnit4.class)
public class RepositoryIndexServletTest extends ServletTest {
  @Test
  public void relativeLinksInReadme() throws Exception {
    RevCommit commit = repo.branch("master").commit()
        .add("README.md", "[foo](foo.md) [bar](/bar.md)")
        .create();
    repo.update(Constants.HEAD, commit);

    Map<String, ?> data = buildData("/repo/");
    SanitizedContent html = (SanitizedContent) data.get("readmeHtml");
    assertTrue(html.getContent().contains("<a href=\"/b/repo/+/HEAD/foo.md\">foo</a>"));
    assertTrue(html.getContent().contains("<a href=\"/b/repo/+/HEAD/bar.md\">bar</a>"));

    // Path without trailing slash
    data = buildData("/repo");
    html = (SanitizedContent) data.get("readmeHtml");
    assertTrue(html.getContent().contains("<a href=\"/b/repo/+/HEAD/foo.md\">foo</a>"));
    assertTrue(html.getContent().contains("<a href=\"/b/repo/+/HEAD/bar.md\">bar</a>"));
  }
}

