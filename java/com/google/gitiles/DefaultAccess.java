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

import static com.google.common.base.Preconditions.checkNotNull;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.base.CharMatcher;
import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Queues;
import com.google.common.io.BaseEncoding;
import com.google.gitiles.UrlSigner.ValidatedSignature;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.text.Collator;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import javax.annotation.Nullable;
import javax.servlet.http.HttpServletRequest;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.http.server.ServletUtils;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.transport.resolver.FileResolver;
import org.eclipse.jgit.transport.resolver.ServiceNotEnabledException;
import org.eclipse.jgit.util.IO;

/**
 * Default implementation of {@link GitilesAccess} with local repositories.
 *
 * <p>Repositories are scanned on-demand under the given path, configured by default from {@code
 * gitiles.basePath}. There is no access control beyond what user the JVM is running under.
 */
public class DefaultAccess implements GitilesAccess {
  private static final String ANONYMOUS_USER_KEY = "anonymous user";

  private static final String DEFAULT_DESCRIPTION =
      "Unnamed repository; edit this file 'description' to name the repository.";

  private static final Collator US_COLLATOR = Collator.getInstance(Locale.US);

  public static class Factory implements GitilesAccess.Factory {
    private final File basePath;
    private final String canonicalBasePath;
    private final String baseGitUrl;
    private final Config baseConfig;
    private final FileResolver<HttpServletRequest> resolver;
    private final UrlSigner urlSigner;

    Factory(
        File basePath,
        String baseGitUrl,
        Config baseConfig,
        FileResolver<HttpServletRequest> resolver)
        throws IOException {
      this.basePath = checkNotNull(basePath, "basePath");
      this.baseGitUrl = checkNotNull(baseGitUrl, "baseGitUrl");
      this.baseConfig = checkNotNull(baseConfig, "baseConfig");
      this.resolver = checkNotNull(resolver, "resolver");
      this.canonicalBasePath = basePath.getCanonicalPath();
      this.urlSigner = new HmacUrlSigner(baseConfig);
    }

    @Override
    public GitilesAccess forRequest(HttpServletRequest req) {
      return newAccess(basePath, canonicalBasePath, baseGitUrl, resolver, urlSigner, req);
    }

    protected DefaultAccess newAccess(
        File basePath,
        String canonicalBasePath,
        String baseGitUrl,
        FileResolver<HttpServletRequest> resolver,
        UrlSigner urlSigner,
        HttpServletRequest req) {
      return new DefaultAccess(
          basePath, canonicalBasePath, baseGitUrl, baseConfig, resolver, urlSigner, req);
    }
  }

  protected final File basePath;
  protected final String canonicalBasePath;
  protected final String baseGitUrl;
  protected final Config baseConfig;
  protected final FileResolver<HttpServletRequest> resolver;
  protected final UrlSigner urlSigner;
  protected final HttpServletRequest req;

  protected DefaultAccess(
      File basePath,
      String canonicalBasePath,
      String baseGitUrl,
      Config baseConfig,
      FileResolver<HttpServletRequest> resolver,
      UrlSigner urlSigner,
      HttpServletRequest req) {
    this.basePath = checkNotNull(basePath, "basePath");
    this.canonicalBasePath = checkNotNull(canonicalBasePath, "canonicalBasePath");
    this.baseGitUrl = checkNotNull(baseGitUrl, "baseGitUrl");
    this.baseConfig = checkNotNull(baseConfig, "baseConfig");
    this.resolver = checkNotNull(resolver, "resolver");
    this.urlSigner = checkNotNull(urlSigner, "urlSigner");
    this.req = checkNotNull(req, "req");
  }

  @Override
  public Map<String, RepositoryDescription> listRepositories(String prefix, Set<String> branches)
      throws IOException {
    Map<String, RepositoryDescription> repos = Maps.newTreeMap(US_COLLATOR);
    for (Repository repo : scanRepositories(basePath, prefix, req)) {
      repos.put(getRepositoryName(repo), buildDescription(repo, branches));
      repo.close();
    }
    return repos;
  }

  @Override
  public Object getUserKey() {
    // Always return the same anonymous user key (effectively running with the
    // same user permissions as the JVM). Subclasses may override this behavior.
    return ANONYMOUS_USER_KEY;
  }

  @Override
  public String getRepositoryName() {
    return getRepositoryName(ServletUtils.getRepository(req));
  }

  @Override
  public RepositoryDescription getRepositoryDescription() throws IOException {
    return buildDescription(ServletUtils.getRepository(req), Collections.<String>emptySet());
  }

  @Override
  public Config getConfig() {
    return baseConfig;
  }

  @Override
  public Optional<RawUrls> getRawUrls() {
    String safe = getConfig().getString("gitiles", null, "safeUrl");
    String raw = getConfig().getString("gitiles", null, "rawUrl");
    if (Strings.isNullOrEmpty(safe) || Strings.isNullOrEmpty(raw)) {
      return Optional.empty();
    }
    return Optional.<RawUrls>of(new DefaultRawUrls(safe, raw));
  }

  private String getRepositoryName(Repository repo) {
    String path = getRelativePath(repo);
    if (repo.isBare() && path.endsWith(".git")) {
      path = path.substring(0, path.length() - 4);
    }
    return path;
  }

  private String getRelativePath(Repository repo) {
    String path = repo.isBare() ? repo.getDirectory().getPath() : repo.getDirectory().getParent();
    if (repo.isBare()) {
      path = repo.getDirectory().getPath();
      if (path.endsWith(".git")) {
        path = path.substring(0, path.length() - 4);
      }
    } else {
      path = repo.getDirectory().getParent();
    }
    return getRelativePath(path);
  }

  private String getRelativePath(String path) {
    String base = basePath.getPath();
    if (path.equals(base)) {
      return "";
    }
    if (path.startsWith(base)) {
      return path.substring(base.length() + 1);
    }
    if (path.startsWith(canonicalBasePath)) {
      return path.substring(canonicalBasePath.length() + 1);
    }
    throw new IllegalStateException(
        String.format("Repository path %s is outside base path %s", path, base));
  }

  private String loadDescriptionText(Repository repo) throws IOException {
    String desc = null;
    StoredConfig config = repo.getConfig();
    IOException configError = null;
    try {
      config.load();
      desc = config.getString("gitweb", null, "description");
    } catch (ConfigInvalidException e) {
      configError = new IOException(e);
    }
    if (desc == null) {
      File descFile = new File(repo.getDirectory(), "description");
      if (descFile.exists()) {
        desc = new String(IO.readFully(descFile), UTF_8);
        if (DEFAULT_DESCRIPTION.equals(CharMatcher.whitespace().trimFrom(desc))) {
          desc = null;
        }
      } else if (configError != null) {
        throw configError;
      }
    }
    return desc;
  }

  private RepositoryDescription buildDescription(Repository repo, Set<String> branches)
      throws IOException {
    RepositoryDescription desc = new RepositoryDescription();
    desc.name = getRepositoryName(repo);
    desc.cloneUrl = baseGitUrl + getRelativePath(repo);
    desc.description = loadDescriptionText(repo);
    if (!branches.isEmpty()) {
      desc.branches = Maps.newLinkedHashMap();
      for (String name : branches) {
        Ref ref = repo.exactRef(normalizeRefName(name));
        if ((ref != null) && (ref.getObjectId() != null)) {
          desc.branches.put(name, ref.getObjectId().name());
        }
      }
    }
    return desc;
  }

  private static String normalizeRefName(String name) {
    if (name.startsWith("refs/")) {
      return name;
    }
    return "refs/heads/" + name;
  }

  private Collection<Repository> scanRepositories(
      File basePath, String prefix, HttpServletRequest req) throws IOException {
    List<Repository> repos = Lists.newArrayList();
    Queue<File> todo = initScan(basePath, prefix);
    while (!todo.isEmpty()) {
      File file = todo.remove();
      try {
        repos.add(resolver.open(req, getRelativePath(file.getPath())));
      } catch (RepositoryNotFoundException e) {
        File[] children = file.listFiles();
        if (children != null) {
          Collections.addAll(todo, children);
        }
      } catch (ServiceNotEnabledException e) {
        throw new IOException(e);
      }
    }
    return repos;
  }

  private Queue<File> initScan(File basePath, String prefix) throws IOException {
    Queue<File> todo = Queues.newArrayDeque();
    File[] entries;
    if (isValidPrefix(prefix)) {
      entries = new File(basePath, CharMatcher.is('/').trimFrom(prefix)).listFiles();
    } else {
      entries = basePath.listFiles();
    }
    if (entries != null) {
      Collections.addAll(todo, entries);
    } else if (!basePath.isDirectory()) {
      throw new IOException("base path is not a directory: " + basePath.getPath());
    }
    return todo;
  }

  private static boolean isValidPrefix(String prefix) {
    return !Strings.isNullOrEmpty(prefix)
        && !prefix.equals(".")
        && !prefix.equals("..")
        && !prefix.contains("../")
        && !prefix.endsWith("/..");
  }

  private class DefaultRawUrls implements RawUrls {
    private final String safe;
    private final String raw;
    private final String rawHost;
    private final int rawPort;

    DefaultRawUrls(String safeUrl, String rawUrl) {
      safe = CharMatcher.is('/').trimTrailingFrom(safeUrl);
      raw = CharMatcher.is('/').trimTrailingFrom(rawUrl);

      URI rawUri = URI.create(raw);
      rawHost = rawUri.getHost();
      rawPort = port(rawUri);
    }

    @Override
    public String createRedirectUrl(GitilesView view) {
      return safe + view.toUrl();
    }

    @Override
    public SignedRawUrl createRawUrl(GitilesView view) {
      String url = view.toUrl();
      if (exportAll()) {
        // TODO(sop) allow caching exportAll raw URLs
        return new SignedRawUrl(raw + url, 0);
      }

      UrlSigner.Signature sig = urlSigner.sign(url);
      String key = BaseEncoding.base64Url().omitPadding().encode(sig.getSignature());
      url = view.toBuilder().putParam(PARAM_AUTHKEY, key).build().toUrl();
      return new SignedRawUrl(raw + url, sig.getExpires());
    }

    @Override
    public ValidatedAuthKey validateAuthKey(GitilesView view, @Nullable String key) {
      if (Strings.isNullOrEmpty(key)) {
        if (exportAll()) {
          // TODO(sop) allow caching exportAll raw URLs
          return new ValidatedAuthKey(false, 0);
        }
        return null;
      }

      byte[] token;
      try {
        token = BaseEncoding.base64Url().decode(key);
      } catch (IllegalArgumentException e) {
        return null;
      }

      ValidatedSignature sig = urlSigner.verify(view.toUrl(), token);
      if (sig != null) {
        return new ValidatedAuthKey(true, sig.getExpires());
      }
      return null;
    }

    @Override
    public boolean isRawDomain() {
      int reqPort = req.getServerPort();
      if (reqPort <= 0) {
        reqPort = req.isSecure() ? 443 : 80;
      }
      return reqPort == rawPort && rawHost.equals(req.getServerName());
    }

    private boolean exportAll() {
      Config cfg = getConfig();
      return cfg.getBoolean("gitiles", null, "exportAll", false)
          && cfg.getBoolean("gitiles", null, "exportAllRaw", false);
    }
  }

  private static int port(URI uri) {
    int p = uri.getPort();
    if (p <= 0) {
      if ("http".equals(uri.getScheme())) {
        return 80;
      } else if ("https".equals(uri.getScheme())) {
        return 443;
      }
    }
    return p;
  }
}
