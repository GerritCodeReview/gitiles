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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.eclipse.jgit.lib.Constants.OBJ_COMMIT;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.google.common.hash.Funnels;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import com.google.common.html.types.LegacyConversions;
import com.google.common.io.ByteStreams;
import com.google.common.net.HttpHeaders;
import com.google.template.soy.data.SanitizedContent;
import com.google.template.soy.data.SanitizedContent.ContentKind;
import com.google.template.soy.data.SanitizedContents;
import com.google.template.soy.jbcsrc.api.SoySauce;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;
import java.util.zip.GZIPOutputStream;
import javax.annotation.Nullable;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.eclipse.jgit.http.server.ServletUtils;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevObject;
import org.eclipse.jgit.revwalk.RevWalk;

/**
 * Renderer for Soy templates used by Gitiles.
 *
 * <p>Most callers should not use the methods in this class directly, and instead use one of the
 * HTML methods in {@link BaseServlet}.
 */
public abstract class Renderer {
  // Must match .streamingPlaceholder.
  private static final String PLACEHOLDER = "id=\"STREAMED-OUTPUT-BLOCK\"";

  private static final SanitizedContent THEME_INIT_SCRIPT;
  private static final SanitizedContent THEME_TOGGLE_SCRIPT;

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

  private static final ImmutableList<String> SOY_FILENAMES =
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

  public static final ImmutableMap<String, String> STATIC_URL_GLOBALS =
      ImmutableMap.<String, String>builder()
          .put("gitiles.BASE_CSS_URL", "base.css")
          .put("gitiles.DOC_CSS_URL", "doc.css")
          .put("gitiles.PRETTIFY_CSS_URL", "prettify/prettify.css")
          .put("gitiles.FAVICON_ICO_URL", "favicon.ico")
          .put("gitiles.FAVICON_SVG_URL", "favicon.svg")
          .put("gitiles.FAVICON_32_URL", "favicon-32x32.png")
          .put("gitiles.FAVICON_16_URL", "favicon-16x16.png")
          .put("gitiles.APPLE_TOUCH_ICON_URL", "apple-touch-icon.png")
          .put("gitiles.FILE_SEARCH_JS_URL", "file-search.js")
          .put("gitiles.FILE_SEARCH_WORKER_JS_URL", "file-search-worker.js")
          .buildOrThrow();

  protected static Function<String, URL> fileUrlMapper() {
    return fileUrlMapper("");
  }

  protected static Function<String, URL> fileUrlMapper(String prefix) {
    checkNotNull(prefix);
    return filename -> {
      if (filename == null) {
        return null;
      }
      try {
        return new File(prefix + filename).toURI().toURL();
      } catch (MalformedURLException e) {
        throw new IllegalArgumentException(e);
      }
    };
  }

  protected ImmutableMap<String, URL> templates;
  protected ImmutableMap<String, String> globals;
  protected final String siteTitle;
  private final ConcurrentMap<String, HashCode> hashes =
      new ConcurrentHashMap<>(SOY_FILENAMES.size());

  protected Renderer(
      Function<String, URL> resourceMapper,
      Map<String, String> globals,
      String staticPrefix,
      Iterable<URL> customTemplates,
      String siteTitle) {
    checkNotNull(staticPrefix, "staticPrefix");

    ImmutableMap.Builder<String, URL> b = ImmutableMap.builder();
    for (String name : SOY_FILENAMES) {
      b.put(name, resourceMapper.apply(name));
    }
    for (URL u : customTemplates) {
      b.put(u.toString(), u);
    }
    templates = b.buildOrThrow();

    Map<String, String> allGlobals = Maps.newHashMap();
    for (Map.Entry<String, String> e : STATIC_URL_GLOBALS.entrySet()) {
      allGlobals.put(e.getKey(), staticPrefix + e.getValue());
    }
    allGlobals.putAll(globals);
    this.globals = ImmutableMap.copyOf(allGlobals);
    this.siteTitle = siteTitle;
  }

  public HashCode getTemplateHash(String soyFile) {
    return hashes.computeIfAbsent(soyFile, this::computeTemplateHash);
  }

  HashCode computeTemplateHash(String soyFile) {
    URL u = templates.get(soyFile);
    checkState(u != null, "Missing Soy template %s", soyFile);

    Hasher h = Hashing.murmur3_128().newHasher();
    try (InputStream is = u.openStream();
        OutputStream os = Funnels.asOutputStream(h)) {
      ByteStreams.copy(is, os);
    } catch (IOException e) {
      throw new IllegalStateException("Missing Soy template " + soyFile, e);
    }
    return h.hash();
  }

  void renderHtml(
      HttpServletRequest req, HttpServletResponse res, String templateName, Map<String, ?> soyData)
      throws IOException {
    res.setContentType("text/html");
    res.setCharacterEncoding("UTF-8");
    byte[] data =
        newRenderer(templateName, Optional.of(req))
            .setData(soyData)
            .renderHtml()
            .get()
            .toString()
            .getBytes(UTF_8);
    if (BaseServlet.acceptsGzipEncoding(req)) {
      res.addHeader(HttpHeaders.VARY, HttpHeaders.ACCEPT_ENCODING);
      res.setHeader(HttpHeaders.CONTENT_ENCODING, "gzip");
      data = BaseServlet.gzip(data);
    }
    res.setContentLength(data.length);
    res.getOutputStream().write(data);
  }

  OutputStream renderHtmlStreaming(
      HttpServletRequest req, HttpServletResponse res, String templateName, Map<String, ?> soyData)
      throws IOException {
    return renderHtmlStreaming(req, res, false, templateName, soyData);
  }

  OutputStream renderHtmlStreaming(
      HttpServletRequest req,
      HttpServletResponse res,
      boolean gzip,
      String templateName,
      Map<String, ?> soyData)
      throws IOException {
    String html =
        newRenderer(templateName, Optional.of(req)).setData(soyData).renderHtml().get().toString();
    int id = html.indexOf(PLACEHOLDER);
    checkArgument(id >= 0, "Template must contain %s", PLACEHOLDER);

    int lt = html.lastIndexOf('<', id);
    int gt = html.indexOf('>', id + PLACEHOLDER.length());

    OutputStream out = gzip ? new GZIPOutputStream(res.getOutputStream()) : res.getOutputStream();
    out.write(html.substring(0, lt).getBytes(UTF_8));
    out.flush();

    byte[] tail = html.substring(gt + 1).getBytes(UTF_8);
    return new OutputStream() {
      @Override
      public void write(byte[] b, int off, int len) throws IOException {
        out.write(b, off, len);
      }

      @Override
      public void write(int b) throws IOException {
        out.write(b);
      }

      @Override
      public void flush() throws IOException {
        out.flush();
      }

      @Override
      public void close() throws IOException {
        try (out) {
          out.write(tail);
        }
      }
    };
  }

  SoySauce.Renderer newRenderer(String templateName) throws IOException {
    return newRenderer(templateName, Optional.empty());
  }

  SoySauce.Renderer newRenderer(String templateName, Optional<HttpServletRequest> req)
      throws IOException {
    ImmutableMap.Builder<String, Object> staticUrls = ImmutableMap.builder();
    for (String key : STATIC_URL_GLOBALS.keySet()) {
      staticUrls.put(
          key.replaceFirst("^gitiles\\.", ""),
          LegacyConversions.riskilyAssumeTrustedResourceUrl(globals.get(key)));
    }
    ImmutableMap.Builder<String, Object> ij =
        ImmutableMap.<String, Object>builder()
            .put("staticUrls", staticUrls.buildOrThrow())
            .put("SITE_TITLE", siteTitle)
            .put("THEME_INIT_SCRIPT", THEME_INIT_SCRIPT)
            .put("THEME_TOGGLE_SCRIPT", THEME_TOGGLE_SCRIPT);
    Optional<String> nonce = req.map((r) -> (String) r.getAttribute("nonce"));
    if (nonce.isPresent()) {
      ij.put("csp_nonce", nonce.get());
    }
    if (req.isPresent()) {
      String searchTreeUrl = fileSearchTreeUrl(req.get());
      if (searchTreeUrl != null) {
        ij.put("SEARCH_TREE_URL", searchTreeUrl);
      }
    }
    return getSauce().renderTemplate(templateName).setIj(ij.buildOrThrow());
  }

  /**
   * URL of the complete blob path listing the file finder should index, or null if this request
   * addresses no tree at all.
   *
   * <p>The URL is pinned to the resolved commit SHA rather than to whatever name the user typed.
   * {@link BaseServlet#setCacheHeaders} only marks a response cacheable when the revision is named
   * by object ID; a branch-named URL is served {@code no-store} and would therefore be re-fetched
   * on every use. Content addressing additionally means a new commit produces a new URL, so a
   * client's copy is invalidated without a revalidation roundtrip.
   *
   * <p>Note the empty path part: a {@code PATH} view renders as {@code /+/<rev>/}, and the trailing
   * slash is what distinguishes the root tree from the revision itself.
   */
  @Nullable
  private static String fileSearchTreeUrl(HttpServletRequest req) throws IOException {
    GitilesView view = ViewFilter.getView(req);
    if (view == null || view.getRepositoryName() == null) {
      return null;
    }
    ObjectId commit = fileSearchCommit(req, view);
    if (commit == null) {
      return null;
    }
    return GitilesView.path()
        .setHostName(view.getHostName())
        .setServletPath(view.getServletPath())
        .setRepositoryName(view.getRepositoryName())
        .setRevision(commit.name())
        .setPathPart("")
        .putParam("format", "JSON")
        .putParam("recursive", "1")
        .putParam("paths_only", "1")
        .toUrl();
  }

  /**
   * The commit whose paths the finder should offer, or null if there is no sensible one.
   *
   * <p>Pages that address a revision use it, so the finder always searches what the reader is
   * looking at. The repository index and the ref list address a repository but no revision, and
   * skipping them would be the worst outcome for a keyboard shortcut: the repository index is where
   * readers arrive, so {@code /} has to work there or it never becomes muscle memory. Those pages
   * fall back to {@code HEAD}, which for the repository index is exactly the tree already on
   * screen.
   *
   * <p>A revision that peels to something other than a commit deliberately does <em>not</em> fall
   * back. Searching {@code HEAD} while the reader is looking at a tagged tree would silently answer
   * about different content, and scoping the finder to what is displayed is a property worth more
   * than one extra page's coverage.
   */
  @Nullable
  private static ObjectId fileSearchCommit(HttpServletRequest req, GitilesView view)
      throws IOException {
    Revision rev = view.getRevision();
    if (rev != null && !Revision.isNull(rev) && rev.getId() != null) {
      return rev.getPeeledType() == OBJ_COMMIT ? rev.getId() : null;
    }
    Object attr = req.getAttribute(ServletUtils.ATTRIBUTE_REPOSITORY);
    if (!(attr instanceof Repository)) {
      return null;
    }
    Repository repo = (Repository) attr;
    ObjectId headId = repo.resolve(Constants.HEAD);
    if (headId == null) {
      // Unborn HEAD: an empty repository has nothing to find.
      return null;
    }
    try (RevWalk walk = new RevWalk(repo)) {
      RevObject head = walk.peel(walk.parseAny(headId));
      return head.getType() == OBJ_COMMIT ? head.copy() : null;
    }
  }

  protected abstract SoySauce getSauce();

  /**
   * Give a resource URL of a soy template file, returns the import path for use in a Soy import
   * statement.
   */
  protected String toSoySrcPath(URL templateUrl) {
    String filePath = templateUrl.getPath();
    String fileName = filePath.substring(filePath.lastIndexOf('/') + 1);
    return "com/google/gitiles/templates/" + fileName;
  }
}
