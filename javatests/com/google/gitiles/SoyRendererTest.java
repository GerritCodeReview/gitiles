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
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.Resources;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class SoyRendererTest {
  @Rule public final TemporaryFolder tempFolder = new TemporaryFolder();

  @Test
  public void renderHostIndex() {
    SoyRenderer renderer = new SoyRenderer("/static/", ImmutableList.of(), "Gitiles");
    ImmutableMap<String, Object> data =
        ImmutableMap.of(
            "hostName",
            "localhost",
            "repositories",
            ImmutableList.of(
                ImmutableMap.of(
                    "name", "my-repo",
                    "description", "My test repository",
                    "url", "/my-repo/")));

    String html = renderer.render("com.google.gitiles.templates.HostIndex.hostIndex", data);
    assertThat(html).contains("<!DOCTYPE html>");
    assertThat(html).contains("my-repo");
    assertThat(html).contains("My test repository");
    assertThat(html).contains("localhost Git repositories - Gitiles");
  }

  @Test
  public void renderErrorPage() throws Exception {
    SoyRenderer renderer = new SoyRenderer();
    ImmutableMap<String, Object> data = ImmutableMap.of("title", "404 Not Found");

    String html = renderer.render("com.google.gitiles.templates.Error.error", data);
    assertThat(html).contains("<!DOCTYPE html>");
    assertThat(html).contains("404 Not Found");

    StringBuilder sb = new StringBuilder();
    renderer.render(sb, "com.google.gitiles.templates.Error.error", data);
    assertThat(sb.toString()).contains("404 Not Found");
  }

  @Test
  public void renderWithCustomTemplatesAndCspNonce() throws Exception {
    Path root = tempFolder.newFolder("custom_soy").toPath();
    Path customSoy = root.resolve("Custom.soy");
    Files.writeString(
        customSoy,
        "{namespace gitiles}\n"
            + "{deltemplate gitiles.customHeader variant=\"'custom'\"}\n"
            + "  <div class=\"CustomSoyHeader\">Custom Soy Banner</div>\n"
            + "{/deltemplate}\n",
        UTF_8);

    SoyRenderer renderer =
        new SoyRenderer(
            ImmutableMap.of(),
            "/static/",
            ImmutableList.of(customSoy.toUri().toURL()),
            "Gitiles");

    FakeHttpServletRequest req = FakeHttpServletRequest.newRequest();
    req.setAttribute("nonce", "soy-nonce-xyz");

    ImmutableMap<String, Object> data =
        ImmutableMap.of("title", "404 Not Found", "customVariant", "custom");
    String html =
        renderer.render("com.google.gitiles.templates.Error.error", Optional.of(req), data);
    assertThat(html).contains("Custom Soy Banner");
    assertThat(html).contains("nonce=\"soy-nonce-xyz\"");
    assertThat(html).contains("/static/base.css");
  }

  @Test
  public void renderWithDebugFilesystemTemplates() throws Exception {
    Path root = tempFolder.newFolder("soy_root").toPath();
    ImmutableList<String> soyFiles =
        ImmutableList.of(
            "BlameDetail.soy",
            "Common.soy",
            "DiffDetail.soy",
            "Doc.soy",
            "Error.soy",
            "GrepDetail.soy",
            "HostIndex.soy",
            "LogDetail.soy",
            "ObjectDetail.soy",
            "PathDetail.soy",
            "RefList.soy",
            "RevisionDetail.soy",
            "RepositoryIndex.soy");
    for (String name : soyFiles) {
      String content =
          Resources.toString(Resources.getResource(Renderer.class, "templates/" + name), UTF_8);
      Files.writeString(root.resolve(name), content, UTF_8);
    }

    SoyRenderer debugRenderer =
        new SoyRenderer("/static/", ImmutableList.of(), root.toString(), "Gitiles");
    String html =
        debugRenderer.render(
            "com.google.gitiles.templates.Error.error", ImmutableMap.of("title", "Debug Error"));
    assertThat(html).contains("Debug Error");
    assertThat(debugRenderer.getTemplateHash("Common.soy")).isNotNull();
  }

  @Test
  public void renderLogStreamingPartials() throws Exception {
    SoyRenderer renderer = new SoyRenderer("/static/", ImmutableList.of(), "Gitiles");

    StringWriter headerWriter = new StringWriter();
    renderer.render(
        headerWriter,
        "com.google.gitiles.templates.LogDetail.logEntriesHeader",
        ImmutableMap.of("previousUrl", "/log?start=prev"));
    assertThat(headerWriter.toString()).contains("<ol class=\"CommitLog\">");
    assertThat(headerWriter.toString()).contains("Previous");

    StringWriter entryWriter = new StringWriter();
    renderer.render(
        entryWriter,
        "com.google.gitiles.templates.LogDetail.logEntryWrapper",
        ImmutableMap.of(
            "variant",
            "oneline",
            "entry",
            ImmutableMap.of(
                "abbrevSha", "abcdef1",
                "sha", "abcdef1234567890",
                "url", "/commit/abcdef1",
                "shortMessage", "Initial commit",
                "author",
                    ImmutableMap.of(
                        "name",
                        "Author",
                        "email",
                        "a@example.com",
                        "time",
                        "2026-08-31",
                        "relativeTime",
                        "1 hour ago"),
                "committer",
                    ImmutableMap.of(
                        "name",
                        "Author",
                        "email",
                        "a@example.com",
                        "time",
                        "2026-08-31",
                        "relativeTime",
                        "1 hour ago"),
                "branches",
                ImmutableList.of(),
                "tags",
                ImmutableList.of())));
    assertThat(entryWriter.toString()).contains("CommitLog-item");
    assertThat(entryWriter.toString()).contains("abcdef1");
    assertThat(entryWriter.toString()).contains("Initial commit");

    StringWriter footerWriter = new StringWriter();
    renderer.render(
        footerWriter,
        "com.google.gitiles.templates.LogDetail.logEntriesFooter",
        ImmutableMap.of("nextUrl", "/log?start=next", "nextText", "Next"));
    assertThat(footerWriter.toString()).contains("</ol>");
    assertThat(footerWriter.toString()).contains("Next");
  }

  @Test
  public void renderDiffHeaderPartial() {
    SoyRenderer renderer = new SoyRenderer("/static/", ImmutableList.of(), "Gitiles");
    String result =
        renderer.render(
            "com.google.gitiles.templates.DiffDetail.diffHeader",
            ImmutableMap.of(
                "firstParts",
                ImmutableList.of(
                    ImmutableMap.of("text", "a/file.txt", "url", "/file.txt"),
                    ImmutableMap.of("text", "b/file.txt", "url", "/file.txt")),
                "rest",
                "index 1234..5678 100644\n--- a/file.txt\n+++ b/file.txt",
                "fileIndex",
                0));
    assertThat(result).contains("Diff");
    assertThat(result).contains("name=\"F0\"");
    assertThat(result).contains("a/file.txt");
  }
}
