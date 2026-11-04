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

import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.google.common.html.types.SafeHtml;
import com.google.common.io.Resources;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.annotation.Nullable;
import javax.servlet.http.HttpServletRequest;
import org.thymeleaf.IEngineConfiguration;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.TemplateSpec;
import org.thymeleaf.context.Context;
import org.thymeleaf.context.IContext;
import org.thymeleaf.context.ITemplateContext;
import org.thymeleaf.engine.AttributeName;
import org.thymeleaf.model.IProcessableElementTag;
import org.thymeleaf.processor.IProcessor;
import org.thymeleaf.processor.element.AbstractAttributeTagProcessor;
import org.thymeleaf.processor.element.IElementTagStructureHandler;
import org.thymeleaf.standard.StandardDialect;
import org.thymeleaf.standard.processor.StandardEachTagProcessor;
import org.thymeleaf.standard.processor.StandardRemovableAttributeTagProcessor;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.thymeleaf.templateresolver.FileTemplateResolver;
import org.thymeleaf.templateresolver.ITemplateResolver;
import org.thymeleaf.templateresolver.StringTemplateResolver;
import org.thymeleaf.templateresource.ITemplateResource;

/** Renderer backed by Thymeleaf for modern HTML rendering in Gitiles. */
public class ThymeleafRenderer extends Renderer {
  private final TemplateEngine templateEngine;
  private final boolean hasCustomHeadTagPart;
  private final boolean hasCustomHeader;
  private final boolean hasCustomFooter;
  private final boolean hasCustomDocFooter;

  public ThymeleafRenderer() {
    this("", ImmutableList.of(), "Gitiles");
  }

  public ThymeleafRenderer(String staticPrefix, Iterable<URL> customTemplates, String siteTitle) {
    this(ImmutableMap.of(), staticPrefix, customTemplates, siteTitle);
  }

  public ThymeleafRenderer(
      Map<String, String> globals,
      String staticPrefix,
      Iterable<URL> customTemplates,
      String siteTitle) {
    this(globals, staticPrefix, customTemplates, siteTitle, null);
  }

  public ThymeleafRenderer(
      Map<String, String> globals,
      String staticPrefix,
      Iterable<URL> customTemplates,
      String siteTitle,
      @Nullable String templatesRoot) {
    super(
        r -> Resources.getResource(Renderer.class, "templates/" + r),
        globals,
        staticPrefix,
        customTemplates,
        siteTitle);
    String customHtml = loadCustomTemplatesHtml(customTemplates);
    this.hasCustomHeadTagPart = customHtml.contains("customHeadTagPart");
    this.hasCustomHeader = customHtml.contains("customHeader");
    this.hasCustomFooter = customHtml.contains("customFooter");
    this.hasCustomDocFooter = customHtml.contains("customDocFooter");
    this.templateEngine = createTemplateEngine(templatesRoot, customHtml);
  }

  private static String loadCustomTemplatesHtml(@Nullable Iterable<URL> customTemplates) {
    if (customTemplates == null) {
      return "";
    }
    StringBuilder sb = new StringBuilder();
    for (URL url : customTemplates) {
      if (url == null) {
        continue;
      }
      String content = readCustomTemplateUrl(url);
      if (content != null && !content.isEmpty()) {
        sb.append(content).append('\n');
      }
    }
    return sb.toString();
  }

  @Nullable
  private static String readCustomTemplateUrl(URL url) {
    String urlStr = url.toString();
    if (urlStr.endsWith(".html")) {
      try {
        return Resources.toString(url, UTF_8);
      } catch (IOException e) {
        return null;
      }
    }
    if (urlStr.endsWith(".soy")) {
      String htmlUrlStr = urlStr.substring(0, urlStr.length() - 4) + ".html";
      try {
        return Resources.toString(URI.create(htmlUrlStr).toURL(), UTF_8);
      } catch (IOException | IllegalArgumentException e) {
        return null;
      }
    }
    return null;
  }

  private static final class SpanLineProcessor extends AbstractAttributeTagProcessor {
    private final StandardEachTagProcessor fallback;

    SpanLineProcessor(String dialectPrefix) {
      super(
          TemplateMode.HTML,
          dialectPrefix,
          "span",
          false,
          "each",
          true,
          StandardEachTagProcessor.PRECEDENCE - 1,
          false);
      this.fallback = new StandardEachTagProcessor(TemplateMode.HTML, dialectPrefix);
    }

    @Override
    protected void doProcess(
        ITemplateContext context,
        IProcessableElementTag tag,
        AttributeName attributeName,
        String attributeValue,
        IElementTagStructureHandler structureHandler) {
      if (!"span : ${line}".equals(attributeValue)
          || !(context.getVariable("line") instanceof Iterable<?> line)) {
        fallback.process(context, tag, structureHandler);
        return;
      }
      StringBuilder sb = new StringBuilder(64);
      for (Object item : line) {
        if (item instanceof Map<?, ?> span) {
          Object classes = span.get("classes");
          Object text = span.get("text");
          sb.append("<span");
          if (classes != null) {
            String clsStr = classes.toString();
            if (!clsStr.isEmpty()) {
              sb.append(" class=\"");
              appendHtmlEscaped(sb, clsStr);
              sb.append('"');
            }
          }
          sb.append('>');
          if (text != null) {
            appendHtmlEscaped(sb, text.toString());
          }
          sb.append("</span>");
        }
      }
      structureHandler.replaceWith(sb.toString(), false);
    }

    private static void appendHtmlEscaped(StringBuilder sb, String s) {
      int len = s.length();
      int last = 0;
      for (int i = 0; i < len; i++) {
        String repl =
            switch (s.charAt(i)) {
              case '&' -> "&amp;";
              case '<' -> "&lt;";
              case '>' -> "&gt;";
              case '"' -> "&quot;";
              case '\'' -> "&#39;";
              default -> null;
            };
        if (repl != null) {
          if (i > last) {
            sb.append(s, last, i);
          }
          sb.append(repl);
          last = i + 1;
        }
      }
      if (last == 0) {
        sb.append(s);
      } else if (last < len) {
        sb.append(s, last, len);
      }
    }
  }

  private static TemplateEngine createTemplateEngine(
      @Nullable String templatesRoot, String customHtml) {
    TemplateEngine engine = new TemplateEngine();
    engine.setDialect(
        new StandardDialect() {
          @Override
          public Set<IProcessor> getProcessors(String dialectPrefix) {
            Set<IProcessor> processors = super.getProcessors(dialectPrefix);
            processors.add(
                new StandardRemovableAttributeTagProcessor(dialectPrefix, "data-line-number"));
            processors.add(new SpanLineProcessor(dialectPrefix));
            return processors;
          }
        });
    if (customHtml != null && !customHtml.isEmpty()) {
      StringTemplateResolver customResolver =
          new StringTemplateResolver() {
            @Override
            @Nullable
            protected ITemplateResource computeTemplateResource(
                IEngineConfiguration configuration,
                String ownerTemplate,
                String template,
                Map<String, Object> templateResolutionAttributes) {
              if (template.equals("CustomTemplates")) {
                return super.computeTemplateResource(
                    configuration, ownerTemplate, customHtml, templateResolutionAttributes);
              }
              return null;
            }
          };
      customResolver.setOrder(1);
      engine.addTemplateResolver(customResolver);
    }
    ITemplateResolver resolver;
    if (templatesRoot != null) {
      FileTemplateResolver fileResolver = new FileTemplateResolver();
      fileResolver.setOrder(2);
      fileResolver.setPrefix(new File(templatesRoot).getPath() + File.separator);
      fileResolver.setSuffix(".html");
      fileResolver.setCacheable(false);
      resolver = fileResolver;
    } else {
      ClassLoaderTemplateResolver classLoaderResolver = new ClassLoaderTemplateResolver();
      classLoaderResolver.setOrder(2);
      classLoaderResolver.setPrefix("com/google/gitiles/templates/");
      classLoaderResolver.setSuffix(".html");
      resolver = classLoaderResolver;
    }
    engine.addTemplateResolver(resolver);
    return engine;
  }

  public TemplateEngine getTemplateEngine() {
    return templateEngine;
  }

  public TemplateSpec toTemplateSpec(String templateName) {
    String name = templateName.replaceFirst("^com\\.google\\.gitiles\\.templates\\.", "");
    int dot = name.lastIndexOf('.');
    if (dot > 0) {
      String template = name.substring(0, dot);
      String fragment = name.substring(dot + 1);
      if (isPageTemplate(template, fragment)) {
        return new TemplateSpec(template, null, TemplateMode.HTML, null);
      }
      return new TemplateSpec(template, ImmutableSet.of(fragment), TemplateMode.HTML, null);
    }
    if (name.endsWith(".html")) {
      name = name.substring(0, name.length() - 5);
    }
    return new TemplateSpec(name, null, TemplateMode.HTML, null);
  }

  private static boolean isPageTemplate(String template, String fragment) {
    return switch (template) {
      case "HostIndex" -> fragment.equals("hostIndex");
      case "RepositoryIndex" -> fragment.equals("repositoryIndex");
      case "RevisionDetail" -> fragment.equals("revisionDetail");
      case "PathDetail" -> fragment.equals("pathDetail");
      case "LogDetail" -> fragment.equals("logDetail");
      case "DiffDetail" -> fragment.equals("diffDetail");
      case "BlameDetail" -> fragment.equals("blameDetail");
      case "GrepDetail" -> fragment.equals("grepDetail");
      case "RefList" -> fragment.equals("refsDetail");
      case "Doc" -> fragment.equals("markdownDoc");
      case "Error" -> fragment.equals("error");
      default -> false;
    };
  }

  private static boolean containsSafeHtml(@Nullable Object value) {
    if (value instanceof SafeHtml) {
      return true;
    }
    if (value == null) {
      return false;
    }
    if (value instanceof Map<?, ?> map) {
      for (Object v : map.values()) {
        if (containsSafeHtml(v)) {
          return true;
        }
      }
      return false;
    }
    if (value instanceof List<?> list) {
      int limit = Math.min(list.size(), 4);
      for (int i = 0; i < limit; i++) {
        if (containsSafeHtml(list.get(i))) {
          return true;
        }
      }
      return false;
    }
    if (value instanceof Iterable<?> iterable) {
      int i = 0;
      for (Object item : iterable) {
        if (i++ >= 4) {
          break;
        }
        if (containsSafeHtml(item)) {
          return true;
        }
      }
      return false;
    }
    return false;
  }

  @Nullable
  private static Object unwrapSafeTypes(@Nullable Object value) {
    if (!containsSafeHtml(value)) {
      return value;
    }
    if (value instanceof SafeHtml safeHtml) {
      return safeHtml.getSafeHtmlString();
    }
    if (value instanceof Map<?, ?> map) {
      Map<String, Object> result = Maps.newHashMapWithExpectedSize(map.size());
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        result.put(String.valueOf(entry.getKey()), unwrapSafeTypes(entry.getValue()));
      }
      return result;
    }
    if (value instanceof Iterable<?> iterable) {
      List<Object> result = new ArrayList<>();
      for (Object item : iterable) {
        result.add(unwrapSafeTypes(item));
      }
      return result;
    }
    return value;
  }

  private IContext createContext(Optional<HttpServletRequest> req, Map<String, ?> data) {
    Map<String, String> staticUrls = new HashMap<>();
    for (String key : STATIC_URL_GLOBALS.keySet()) {
      staticUrls.put(key.replaceFirst("^gitiles\\.", ""), globals.get(key));
    }

    Context ctx = new Context();
    ctx.setVariable("staticUrls", staticUrls);
    ctx.setVariable("SITE_TITLE", siteTitle);
    ctx.setVariable("THEME_INIT_SCRIPT", THEME_INIT_SCRIPT_STRING);
    ctx.setVariable("THEME_TOGGLE_SCRIPT", THEME_TOGGLE_SCRIPT_STRING);
    ctx.setVariable("hasCustomHeadTagPart", hasCustomHeadTagPart);
    ctx.setVariable("hasCustomHeader", hasCustomHeader);
    ctx.setVariable("hasCustomFooter", hasCustomFooter);
    ctx.setVariable("hasCustomDocFooter", hasCustomDocFooter);

    if (req.isPresent()) {
      Object nonce = req.get().getAttribute("nonce");
      if (nonce != null) {
        ctx.setVariable("csp_nonce", nonce.toString());
      }
      try {
        if (fileSearchEnabled(req.get())) {
          String searchTreeUrl = fileSearchTreeUrl(req.get());
          if (searchTreeUrl != null) {
            ctx.setVariable("SEARCH_TREE_URL", searchTreeUrl);
          } else if (isHostIndex(req.get())) {
            ctx.setVariable("SEARCH_REPOSITORIES", true);
          }
        }
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }

    if (data != null) {
      for (Map.Entry<String, ?> entry : data.entrySet()) {
        ctx.setVariable(entry.getKey(), unwrapSafeTypes(entry.getValue()));
      }
    }
    return ctx;
  }

  @Override
  public void render(Appendable out, String templateName, Map<String, ?> data) throws IOException {
    TemplateSpec spec = toTemplateSpec(templateName);
    IContext ctx = createContext(Optional.empty(), data);
    if (out instanceof Writer writer) {
      templateEngine.process(spec, ctx, writer);
    } else {
      String result = templateEngine.process(spec, ctx);
      out.append(result);
    }
  }

  @Override
  public String render(String templateName, Map<String, ?> data) {
    return render(templateName, Optional.empty(), data);
  }

  @Override
  public String render(String templateName, Optional<HttpServletRequest> req, Map<String, ?> data) {
    TemplateSpec spec = toTemplateSpec(templateName);
    IContext ctx = createContext(req, data);
    return templateEngine.process(spec, ctx);
  }
}
