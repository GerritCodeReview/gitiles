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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.Streams.stream;

import com.google.common.base.Ascii;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;
import java.net.URL;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import javax.annotation.Nullable;

/**
 * Factory and registry for creating {@link Renderer} instances from registered template engine
 * implementations.
 */
public class RendererFactory {
  public static final String RENDERER_SOY = "soy";
  public static final String RENDERER_THYMELEAF = "thymeleaf";
  public static final String DEFAULT_RENDERER = RENDERER_SOY;

  /** Creator for production (precompiled/cached classpath) {@link Renderer} instances. */
  @FunctionalInterface
  public interface DefaultRendererCreator {
    Renderer create(
        Map<String, String> globals,
        String staticPrefix,
        Iterable<URL> customTemplates,
        String siteTitle);
  }

  /** Creator for debug (filesystem hot-reloading) {@link Renderer} instances. */
  @FunctionalInterface
  public interface DebugRendererCreator {
    Renderer create(
        String staticPrefix,
        Iterable<String> customTemplatesFilenames,
        String templatesRoot,
        String siteTitle);
  }

  private static final class Registration {
    final DefaultRendererCreator defaultCreator;
    final DebugRendererCreator debugCreator;

    Registration(DefaultRendererCreator defaultCreator, DebugRendererCreator debugCreator) {
      this.defaultCreator = checkNotNull(defaultCreator, "defaultCreator");
      this.debugCreator = checkNotNull(debugCreator, "debugCreator");
    }
  }

  private static final ConcurrentMap<String, Registration> RENDERERS = new ConcurrentHashMap<>();

  static {
    register(RENDERER_SOY, SoyRenderer::new, SoyRenderer::new);
    register(
        RENDERER_THYMELEAF,
        ThymeleafRenderer::new,
        (staticPrefix, customTemplatesFilenames, templatesRoot, siteTitle) ->
            new ThymeleafRenderer(
                ImmutableMap.of(),
                staticPrefix,
                stream(customTemplatesFilenames)
                    .map(Renderer.fileUrlMapper())
                    .collect(toImmutableList()),
                siteTitle,
                templatesRoot));
  }

  /** Registers a template engine implementation under {@code rendererType} (case-insensitive). */
  public static void register(
      String rendererType,
      DefaultRendererCreator defaultCreator,
      DebugRendererCreator debugCreator) {
    checkArgument(!Strings.isNullOrEmpty(rendererType), "rendererType must not be empty");
    RENDERERS.put(
        Ascii.toLowerCase(rendererType), new Registration(defaultCreator, debugCreator));
  }

  public static Renderer createDefaultRenderer(
      @Nullable String rendererType,
      Map<String, String> globals,
      String staticPrefix,
      Iterable<URL> customTemplates,
      String siteTitle) {
    return lookup(rendererType)
        .defaultCreator
        .create(globals, staticPrefix, customTemplates, siteTitle);
  }

  public static Renderer createDebugRenderer(
      @Nullable String rendererType,
      String staticPrefix,
      Iterable<String> customTemplatesFilenames,
      String templatesRoot,
      String siteTitle) {
    return lookup(rendererType)
        .debugCreator
        .create(staticPrefix, customTemplatesFilenames, templatesRoot, siteTitle);
  }

  private static Registration lookup(@Nullable String rendererType) {
    String key =
        Strings.isNullOrEmpty(rendererType) ? DEFAULT_RENDERER : Ascii.toLowerCase(rendererType);
    Registration reg = RENDERERS.get(key);
    checkArgument(
        reg != null, "Unsupported renderer '%s' (registered: %s)", rendererType, RENDERERS.keySet());
    return reg;
  }

  private RendererFactory() {}
}
