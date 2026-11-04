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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.hash.HashCode;
import com.google.common.io.Resources;
import java.io.IOException;
import java.net.URL;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import javax.servlet.http.HttpServletRequest;

/**
 * Renderer that precompiles templates from classpath resources using the template engine selected
 * via {@link RendererFactory}.
 */
public class DefaultRenderer extends Renderer {
  private final Renderer delegate;

  DefaultRenderer() {
    this("", ImmutableList.<URL>of(), "");
  }

  public DefaultRenderer(String staticPrefix, Iterable<URL> customTemplates, String siteTitle) {
    this(ImmutableMap.<String, String>of(), staticPrefix, customTemplates, siteTitle);
  }

  public DefaultRenderer(
      String staticPrefix,
      Iterable<URL> customTemplates,
      String siteTitle,
      @Nullable String rendererType) {
    this(ImmutableMap.of(), staticPrefix, customTemplates, siteTitle, rendererType);
  }

  public DefaultRenderer(
      Map<String, String> globals,
      String staticPrefix,
      Iterable<URL> customTemplates,
      String siteTitle) {
    this(globals, staticPrefix, customTemplates, siteTitle, RendererFactory.DEFAULT_RENDERER);
  }

  public DefaultRenderer(
      Map<String, String> globals,
      String staticPrefix,
      Iterable<URL> customTemplates,
      String siteTitle,
      @Nullable String rendererType) {
    this(globals, staticPrefix, customTemplates, siteTitle, () -> rendererType);
  }

  public DefaultRenderer(
      Map<String, String> globals,
      String staticPrefix,
      Iterable<URL> customTemplates,
      String siteTitle,
      Supplier<String> rendererTypeSupplier) {
    super(
        r -> Resources.getResource(Renderer.class, "templates/" + r),
        globals,
        staticPrefix,
        ImmutableList.of(),
        siteTitle);
    this.delegate =
        RendererFactory.createDefaultRenderer(
            rendererTypeSupplier.get(), globals, staticPrefix, customTemplates, siteTitle);
  }

  @Override
  public HashCode getTemplateHash(String templateFile) {
    return delegate.getTemplateHash(templateFile);
  }

  @Override
  public void render(Appendable out, String templateName, Map<String, ?> data) throws IOException {
    delegate.render(out, templateName, data);
  }

  @Override
  public String render(String templateName, Optional<HttpServletRequest> req, Map<String, ?> data) {
    return delegate.render(templateName, req, data);
  }
}
