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

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.Streams.stream;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.hash.HashCode;
import com.google.common.html.types.LegacyConversions;
import com.google.common.html.types.TrustedResourceUrl;
import com.google.common.io.Resources;
import com.google.template.soy.SoyFileSet;
import com.google.template.soy.data.SanitizedContent;
import com.google.template.soy.data.SanitizedContent.ContentKind;
import com.google.template.soy.data.SanitizedContents;
import com.google.template.soy.jbcsrc.api.SoySauce;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;
import javax.annotation.Nullable;
import javax.servlet.http.HttpServletRequest;

/** {@link Renderer} implementation backed by Google Closure Templates (Soy). */
public class SoyRenderer extends Renderer {
  private static final SanitizedContent THEME_INIT_SCRIPT;
  private static final SanitizedContent THEME_TOGGLE_SCRIPT;
  private static final ConcurrentMap<ImmutableList<URL>, SoySauce> PRECOMPILED_CACHE =
      new ConcurrentHashMap<>();

  static {
    try {
      THEME_INIT_SCRIPT =
          SanitizedContents.fromResource(
              Renderer.class, "static/theme-init.js", UTF_8, ContentKind.JS);
      THEME_TOGGLE_SCRIPT =
          SanitizedContents.fromResource(
              Renderer.class, "static/theme-toggle.js", UTF_8, ContentKind.JS);
    } catch (IOException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  private final ImmutableMap<String, TrustedResourceUrl> staticUrls;
  @Nullable private final SoySauce precompiledSauce;

  public SoyRenderer() {
    this("", ImmutableList.of(), "");
  }

  public SoyRenderer(String staticPrefix, Iterable<URL> customTemplates, String siteTitle) {
    this(ImmutableMap.of(), staticPrefix, customTemplates, siteTitle);
  }

  public SoyRenderer(
      Map<String, String> globals,
      String staticPrefix,
      Iterable<URL> customTemplates,
      String siteTitle) {
    this(
        r -> Resources.getResource(Renderer.class, "templates/" + r),
        globals,
        staticPrefix,
        filterSoyCustomTemplates(customTemplates),
        siteTitle,
        /* precompile= */ true);
  }

  public SoyRenderer(
      String staticPrefix,
      Iterable<String> customTemplatesFilenames,
      final String soyTemplatesRoot,
      String siteTitle) {
    this(
        fileUrlMapper(soyTemplatesRoot + File.separator),
        ImmutableMap.of(),
        staticPrefix,
        filterSoyCustomTemplates(
            stream(customTemplatesFilenames).map(fileUrlMapper()).collect(toImmutableList())),
        siteTitle,
        /* precompile= */ false);
  }

  protected SoyRenderer(
      Function<String, URL> resourceMapper,
      Map<String, String> globals,
      String staticPrefix,
      Iterable<URL> customTemplates,
      String siteTitle,
      boolean precompile) {
    super(resourceMapper, globals, staticPrefix, customTemplates, siteTitle);
    ImmutableMap.Builder<String, TrustedResourceUrl> urls = ImmutableMap.builder();
    for (String key : STATIC_URL_GLOBALS.keySet()) {
      urls.put(
          key.replaceFirst("^gitiles\\.", ""),
          LegacyConversions.riskilyAssumeTrustedResourceUrl(this.globals.get(key)));
    }
    this.staticUrls = urls.buildOrThrow();
    if (precompile) {
      this.precompiledSauce =
          PRECOMPILED_CACHE.computeIfAbsent(
              ImmutableList.copyOf(templates.values()), this::compileSauce);
    } else {
      this.precompiledSauce = null;
    }
  }

  private SoySauce compileSauce(ImmutableList<URL> templateUrls) {
    SoyFileSet.Builder builder = SoyFileSet.builder();
    for (URL template : templateUrls) {
      builder.add(template, soySrcPath(template));
    }
    return builder.build().compileTemplates();
  }

  private static ImmutableList<URL> filterSoyCustomTemplates(
      @Nullable Iterable<URL> customTemplates) {
    if (customTemplates == null) {
      return ImmutableList.of();
    }
    ImmutableList.Builder<URL> result = ImmutableList.builder();
    for (URL url : customTemplates) {
      if (url == null) {
        continue;
      }
      String urlStr = url.toString();
      if (urlStr.endsWith(".soy")) {
        result.add(url);
      } else if (urlStr.endsWith(".html")) {
        String soyUrlStr = urlStr.substring(0, urlStr.length() - 5) + ".soy";
        try {
          URL soyUrl = URI.create(soyUrlStr).toURL();
          try (var ignored = soyUrl.openStream()) {
            result.add(soyUrl);
          }
        } catch (IOException | IllegalArgumentException e) {
          // Ignore HTML-only custom templates in SoyRenderer.
        }
      }
    }
    return result.build();
  }

  @Override
  public HashCode getTemplateHash(String soyFile) {
    if (precompiledSauce == null) {
      return computeTemplateHash(soyFile);
    }
    return super.getTemplateHash(soyFile);
  }

  @Override
  public void render(Appendable out, String templateName, Map<String, ?> data) throws IOException {
    SoySauce.Renderer r = createRenderer(templateName, Optional.empty()).setData(data);
    if (!r.renderHtml(out).result().isDone()) {
      throw new IOException("failed to render HTML");
    }
  }

  @Override
  public String render(String templateName, Optional<HttpServletRequest> req, Map<String, ?> data) {
    return createRenderer(templateName, req).setData(data).renderHtml().get().toString();
  }

  private SoySauce.Renderer createRenderer(String templateName, Optional<HttpServletRequest> req) {
    ImmutableMap.Builder<String, Object> ij =
        ImmutableMap.<String, Object>builder()
            .put("staticUrls", staticUrls)
            .put("SITE_TITLE", siteTitle)
            .put("THEME_INIT_SCRIPT", THEME_INIT_SCRIPT)
            .put("THEME_TOGGLE_SCRIPT", THEME_TOGGLE_SCRIPT);
    Optional<String> nonce = req.map((r) -> (String) r.getAttribute("nonce"));
    if (nonce.isPresent()) {
      ij.put("csp_nonce", nonce.get());
    }
    if (req.isPresent()) {
      try {
        if (fileSearchEnabled(req.get())) {
          String searchTreeUrl = fileSearchTreeUrl(req.get());
          if (searchTreeUrl != null) {
            ij.put("SEARCH_TREE_URL", searchTreeUrl);
          } else if (isHostIndex(req.get())) {
            ij.put("SEARCH_REPOSITORIES", true);
          }
        }
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
    return loadSauce().renderTemplate(templateName).setIj(ij.buildOrThrow());
  }

  private SoySauce loadSauce() {
    if (precompiledSauce != null) {
      return precompiledSauce;
    }
    SoyFileSet.Builder builder = SoyFileSet.builder();
    for (URL template : templates.values()) {
      try {
        checkState(new File(template.toURI()).exists(), "Missing Soy template %s", template);
      } catch (URISyntaxException e) {
        throw new IllegalStateException(e);
      }
      builder.add(template, soySrcPath(template));
    }
    return builder.build().compileTemplates();
  }

  private static String soySrcPath(URL templateUrl) {
    String filePath = templateUrl.getPath();
    String fileName = filePath.substring(filePath.lastIndexOf('/') + 1);
    return "com/google/gitiles/templates/" + fileName;
  }
}
