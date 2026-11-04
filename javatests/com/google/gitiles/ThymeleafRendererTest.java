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
import com.google.common.html.types.SafeHtml;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.thymeleaf.templateresolver.ITemplateResolver;

@RunWith(JUnit4.class)
public class ThymeleafRendererTest {
  @Rule public final TemporaryFolder tempFolder = new TemporaryFolder();

  @Test
  public void renderWithFileTemplateResolverAndCustomTemplates() throws Exception {
    Path root = tempFolder.newFolder("templates").toPath();
    Files.writeString(
        root.resolve("Error.html"),
        "<!DOCTYPE html><html xmlns:th=\"http://www.thymeleaf.org\"><body>"
            + "<div th:fragment=\"error\">"
            + "<div th:replace=\"~{CustomTemplates :: customHeader}\"></div>"
            + "<h1 th:text=\"${title}\"></h1>"
            + "</div></body></html>",
        UTF_8);
    Path customHtmlFile = root.resolve("Custom.html");
    Files.writeString(
        customHtmlFile,
        "<html xmlns:th=\"http://www.thymeleaf.org\"><body>"
            + "<div th:fragment=\"customHeader\" class=\"CustomHeaderBanner\">Custom Banner</div>"
            + "</body></html>",
        UTF_8);
    Path customSoyFallback = root.resolve("Custom.soy");

    ThymeleafRenderer renderer =
        new ThymeleafRenderer(
            ImmutableMap.of(),
            "/static/",
            ImmutableList.of(customSoyFallback.toUri().toURL()),
            "Gitiles",
            root.toString());
    assertThat(
            renderer.getTemplateEngine().getTemplateResolvers().stream()
                .map(ITemplateResolver::getOrder))
        .containsExactly(1, 2);

    String html =
        renderer.render(
            "com.google.gitiles.templates.Error.error",
            ImmutableMap.of("title", "Reloaded Error"));
    assertThat(html).contains("Custom Banner");
    assertThat(html).contains("Reloaded Error");

    StringBuilder sb = new StringBuilder();
    renderer.render(
        sb,
        "com.google.gitiles.templates.Error.error",
        ImmutableMap.of("title", "Streamed Error"));
    assertThat(sb.toString()).contains("Custom Banner");
    assertThat(sb.toString()).contains("Streamed Error");

    // Verify fileResolver is non-caching (mutations on disk are reflected immediately).
    Files.writeString(
        root.resolve("Error.html"),
        "<!DOCTYPE html><html xmlns:th=\"http://www.thymeleaf.org\"><body>"
            + "<div th:fragment=\"error\">"
            + "<p>RELOADED_DISK_TEMPLATE</p><h1 th:text=\"${title}\"></h1>"
            + "</div></body></html>",
        UTF_8);
    String updatedHtml =
        renderer.render(
            "com.google.gitiles.templates.Error.error",
            ImmutableMap.of("title", "Updated Error"));
    assertThat(updatedHtml).contains("RELOADED_DISK_TEMPLATE");
    assertThat(updatedHtml).doesNotContain("Custom Banner");
    assertThat(updatedHtml).contains("Updated Error");
  }

  @Test
  public void renderWithSafeTypesCspNonceAndAllTemplateSpecs() throws Exception {
    Path root = tempFolder.newFolder("custom").toPath();
    Path directHtml = root.resolve("Direct.html");
    Files.writeString(
        directHtml,
        """
        <div th:fragment="customView">
          <script th:attr="nonce=${csp_nonce}" th:utext="${THEME_INIT_SCRIPT}"></script>
          <link rel="stylesheet" th:href="${staticUrls['BASE_CSS_URL']}">
          <span th:text="${SITE_TITLE}"></span>
          <div th:text="(${nested.sanitized.equals('')} ? 'SanitizedVal' : 'UNWRAPPED_FAILED') + ' | ' + (${items[0].equals('')} ? 'SafeTitleVal' : 'UNWRAPPED_FAILED')"></div>
          <script th:attr="nonce=${csp_nonce}" th:utext="${THEME_TOGGLE_SCRIPT}"></script>
        </div>\
        """,
        UTF_8);
    Path missingSoy = root.resolve("Missing.soy");
    Path ignoredTxt = root.resolve("Ignored.txt");
    Files.writeString(ignoredTxt, "ignored", UTF_8);

    ThymeleafRenderer renderer =
        new ThymeleafRenderer(
            ImmutableMap.of(),
            "/static/",
            ImmutableList.of(
                directHtml.toUri().toURL(), missingSoy.toUri().toURL(), ignoredTxt.toUri().toURL()),
            "Gitiles",
            /* templatesRoot= */ null);
    assertThat(
            renderer.getTemplateEngine().getTemplateResolvers().stream()
                .map(ITemplateResolver::getOrder))
        .containsExactly(1, 2);

    FakeHttpServletRequest req = FakeHttpServletRequest.newRequest();
    req.setAttribute("nonce", "csp-nonce-xyz");

    ImmutableMap<String, Object> data =
        ImmutableMap.of(
            "title",
            SafeHtml.EMPTY,
            "nested",
            ImmutableMap.of("sanitized", SafeHtml.EMPTY),
            "items",
            ImmutableList.of(SafeHtml.EMPTY));

    String html =
        renderer.render(
            "com.google.gitiles.templates.CustomTemplates.customView", Optional.of(req), data);
    assertThat(html).contains("<script nonce=\"csp-nonce-xyz\">");
    assertThat(html).contains("SanitizedVal | SafeTitleVal");
    assertThat(html).contains("Gitiles");
    assertThat(html).contains("/static/base.css");
    assertThat(html).contains(Renderer.THEME_INIT_SCRIPT_STRING);
    assertThat(html).contains(Renderer.THEME_TOGGLE_SCRIPT_STRING);

    for (String fullTemplate :
        ImmutableList.of(
            "com.google.gitiles.templates.HostIndex.hostIndex",
            "com.google.gitiles.templates.RepositoryIndex.repositoryIndex",
            "com.google.gitiles.templates.RevisionDetail.revisionDetail",
            "com.google.gitiles.templates.ObjectDetail.objectDetail",
            "com.google.gitiles.templates.PathDetail.pathDetail",
            "com.google.gitiles.templates.DiffDetail.diffDetail",
            "com.google.gitiles.templates.LogDetail.logDetail",
            "com.google.gitiles.templates.BlameDetail.blameDetail",
            "com.google.gitiles.templates.RefList.refsDetail",
            "com.google.gitiles.templates.Doc.markdownDoc",
            "com.google.gitiles.templates.Error.error",
            "com.google.gitiles.templates.CustomView.customFragment",
            "SimpleTemplate")) {
      assertThat(renderer.toTemplateSpec(fullTemplate)).isNotNull();
    }
  }

  @Test
  public void renderStreamingPartials() throws Exception {
    Path root = tempFolder.newFolder("streaming").toPath();
    Files.writeString(
        root.resolve("LogDetail.html"),
        """
        <!DOCTYPE html>
        <html xmlns:th="http://www.thymeleaf.org">
        <body>
          <th:block th:fragment="logEntriesHeader">
            <a th:if="${previousUrl != null}" th:href="${previousUrl}">Previous</a>
            <ol class="CommitLog">
          </th:block>
          <th:block th:fragment="logEntryWrapper">
            <li class="CommitLog-item">
              <a th:href="${entry.url}" th:text="${entry.abbrevSha}"></a>
              <span th:text="${entry.shortMessage}"></span>
            </li>
          </th:block>
          <th:block th:fragment="logEntriesFooter">
            </ol>
            <a th:if="${nextUrl != null}" th:href="${nextUrl}" th:text="${nextText}"></a>
          </th:block>
        </body>
        </html>\
        """,
        UTF_8);

    ThymeleafRenderer renderer =
        new ThymeleafRenderer(
            ImmutableMap.of(), "/static/", ImmutableList.of(), "Gitiles", root.toString());

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
                "shortMessage", "Initial commit")));
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
}
