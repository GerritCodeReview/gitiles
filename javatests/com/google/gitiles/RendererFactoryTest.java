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
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.Resources;
import java.io.IOException;
import java.net.URL;
import java.util.Map;
import java.util.Optional;
import javax.servlet.http.HttpServletRequest;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class RendererFactoryTest {
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

    Renderer defaultCreated =
        RendererFactory.createDefaultRenderer(
            "custom-test-engine", ImmutableMap.of(), "/static/", ImmutableList.of(), "Gitiles");
    assertThat(defaultCreated.render("MyTemplate", ImmutableMap.of()))
        .isEqualTo("CUSTOM_DEFAULT:MyTemplate");

    Renderer debugCreated =
        RendererFactory.createDebugRenderer(
            "custom-test-engine", "/static/", ImmutableList.of(), "/tmp", "Gitiles");
    assertThat(debugCreated.render("MyTemplate", ImmutableMap.of()))
        .isEqualTo("CUSTOM_DEBUG:MyTemplate");
  }
}
