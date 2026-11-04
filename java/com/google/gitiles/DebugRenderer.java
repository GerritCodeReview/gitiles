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
import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import javax.servlet.http.HttpServletRequest;

/**
 * Renderer that reloads templates from the filesystem on every request using the template engine
 * selected via {@link RendererFactory}.
 */
public class DebugRenderer extends Renderer {
  private final Renderer delegate;

  public DebugRenderer(
      String staticPrefix,
      Iterable<String> customTemplatesFilenames,
      final String templatesRoot,
      String siteTitle) {
    this(
        staticPrefix,
        customTemplatesFilenames,
        templatesRoot,
        siteTitle,
        RendererFactory.DEFAULT_RENDERER);
  }

  public DebugRenderer(
      String staticPrefix,
      Iterable<String> customTemplatesFilenames,
      final String templatesRoot,
      String siteTitle,
      @Nullable String rendererType) {
    this(staticPrefix, customTemplatesFilenames, templatesRoot, siteTitle, () -> rendererType);
  }

  public DebugRenderer(
      String staticPrefix,
      Iterable<String> customTemplatesFilenames,
      final String templatesRoot,
      String siteTitle,
      Supplier<String> rendererTypeSupplier) {
    super(
        fileUrlMapper(templatesRoot + File.separator),
        ImmutableMap.<String, String>of(),
        staticPrefix,
        ImmutableList.of(),
        siteTitle);
    this.delegate =
        RendererFactory.createDebugRenderer(
            rendererTypeSupplier.get(),
            staticPrefix,
            customTemplatesFilenames,
            templatesRoot,
            siteTitle);
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
