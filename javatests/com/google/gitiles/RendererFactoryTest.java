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
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.Resources;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import javax.servlet.http.HttpServletRequest;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class RendererFactoryTest {
  @Rule public final TemporaryFolder tempFolder = new TemporaryFolder();

  @Test
  public void createsSoyRendererByDefault() {
    Renderer renderer =
        RendererFactory.createDefaultRenderer(
            /* rendererType= */ null,
            ImmutableMap.of(),
            "/static/",
            ImmutableList.<URL>of(),
            "Test Site");
    assertThat(renderer).isInstanceOf(SoyRenderer.class);

    Renderer explicitSoy =
        RendererFactory.createDefaultRenderer(
            RendererFactory.RENDERER_SOY,
            ImmutableMap.of(),
            "/static/",
            ImmutableList.<URL>of(),
            "Test Site");
    assertThat(explicitSoy).isInstanceOf(SoyRenderer.class);
  }

  @Test
  public void createsThymeleafRendererWhenConfigured() {
    Renderer renderer =
        RendererFactory.createDefaultRenderer(
            RendererFactory.RENDERER_THYMELEAF,
            ImmutableMap.of("customGlobal", "val"),
            "/static/",
            ImmutableList.<URL>of(),
            "Test Site");
    assertThat(renderer).isInstanceOf(ThymeleafRenderer.class);
  }

  @Test
  public void throwsOnUnsupportedRendererType() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            RendererFactory.createDefaultRenderer(
                "nonexistent-engine",
                ImmutableMap.of(),
                "/static/",
                ImmutableList.<URL>of(),
                "Test Site"));
  }

  @Test
  public void registersCustomRendererImplementation() throws Exception {
    RendererFactory.register(
        "custom-test-engine",
        (globals, staticPrefix, customTemplates, siteTitle) ->
            new Renderer(
                r -> Resources.getResource(Renderer.class, "templates/" + r),
                globals,
                staticPrefix,
                customTemplates,
                siteTitle) {
              @Override
              public void render(Appendable out, String templateName, Map<String, ?> data)
                  throws IOException {
                out.append("CUSTOM_DEFAULT:").append(templateName);
              }

              @Override
              public String render(
                  String templateName, Optional<HttpServletRequest> req, Map<String, ?> data) {
                return "CUSTOM_DEFAULT:" + templateName;
              }
            },
        (staticPrefix, customTemplatesFilenames, templatesRoot, siteTitle) ->
            new Renderer(
                r -> Resources.getResource(Renderer.class, "templates/" + r),
                ImmutableMap.of(),
                staticPrefix,
                ImmutableList.of(),
                siteTitle) {
              @Override
              public void render(Appendable out, String templateName, Map<String, ?> data)
                  throws IOException {
                out.append("CUSTOM_DEBUG:").append(templateName);
              }

              @Override
              public String render(
                  String templateName, Optional<HttpServletRequest> req, Map<String, ?> data) {
                return "CUSTOM_DEBUG:" + templateName;
              }
            });

    DefaultRenderer defaultRenderer =
        new DefaultRenderer(
            ImmutableMap.of(),
            "/static/",
            ImmutableList.of(),
            "Gitiles",
            () -> "custom-test-engine");
    assertThat(defaultRenderer.render("MyTemplate", ImmutableMap.of()))
        .isEqualTo("CUSTOM_DEFAULT:MyTemplate");

    DebugRenderer debugRenderer =
        new DebugRenderer(
            "/static/", ImmutableList.of(), "/tmp", "Gitiles", () -> "custom-test-engine");
    assertThat(debugRenderer.render("MyTemplate", ImmutableMap.of()))
        .isEqualTo("CUSTOM_DEBUG:MyTemplate");
  }

  @Test
  public void defaultAndDebugRendererDelegateByTypeAndSupplier() throws Exception {
    Path root = tempFolder.newFolder("templates").toPath();
    Files.writeString(
        root.resolve("Error.html"),
        "<!DOCTYPE html><html xmlns:th=\"http://www.thymeleaf.org\"><body>"
            + "<h1 th:text=\"'THYMELEAF_DEBUG:' + ${title}\"></h1></body></html>",
        UTF_8);

    DefaultRenderer soyDefault =
        new DefaultRenderer(
            ImmutableMap.of(),
            "/static/",
            ImmutableList.of(),
            "Gitiles",
            () -> RendererFactory.RENDERER_SOY);
    assertThat((Object) soyDefault).isNotInstanceOf(SoyRenderer.class);
    assertThat((Object) soyDefault).isNotInstanceOf(ThymeleafRenderer.class);

    ImmutableMap<String, Object> data = ImmutableMap.of("title", "404 Not Found");
    String soyHtml = soyDefault.render("com.google.gitiles.templates.Error.error", data);
    assertThat(soyHtml).contains("404 Not Found");
    assertThat(soyDefault.getTemplateHash("Common.soy")).isNotNull();

    DebugRenderer thymeleafDebug =
        new DebugRenderer(
            "/static/",
            ImmutableList.of(),
            root.toString(),
            "Gitiles",
            RendererFactory.RENDERER_THYMELEAF);
    String debugHtml = thymeleafDebug.render("Error.html", data);
    assertThat(debugHtml).contains("THYMELEAF_DEBUG:404 Not Found");
  }
}
