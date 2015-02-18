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

import com.google.common.base.CharMatcher;
import com.google.common.collect.Maps;

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

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.servlet.http.HttpServletRequest;

/**
 * Default implementation of {@link GitilesAccess} with local repositories.
 * <p>
 * Repositories are scanned on-demand under the given path, configured by
 * default from {@code gitiles.basePath}. There is no access control beyond what
 * user the JVM is running under.
 */
public class DefaultAccess implements GitilesAccess {
  private static final String ANONYMOUS_USER_KEY = "anonymous user";

  private static final String DEFAULT_DESCRIPTION =
    "Unnamed repository; edit this file 'description' to name the repository.";

  private static final Collator US_COLLATOR = Collator.getInstance(Locale.US);

  public static class Factory implements GitilesAccess.Factory {
    private final Path basePath;
    private final String baseGitUrl;
    private final Config baseConfig;
    private final FileResolver<HttpServletRequest> resolver;

    Factory(Path basePath, String baseGitUrl, Config baseConfig,
        FileResolver<HttpServletRequest> resolver) {
      this.basePath = checkNotNull(basePath, "basePath");
      this.baseGitUrl = checkNotNull(baseGitUrl, "baseGitUrl");
      this.baseConfig = checkNotNull(baseConfig, "baseConfig");
      this.resolver = checkNotNull(resolver, "resolver");
    }

    @Override
    public GitilesAccess forRequest(HttpServletRequest req) {
      return newAccess(basePath, baseGitUrl, resolver, req);
    }

    protected DefaultAccess newAccess(Path basePath, String baseGitUrl,
        FileResolver<HttpServletRequest> resolver, HttpServletRequest req) {
      return new DefaultAccess(basePath, baseGitUrl, baseConfig, resolver, req);
    }
  }

  protected final Path basePath;
  protected final String baseGitUrl;
  protected final Config baseConfig;
  protected final FileResolver<HttpServletRequest> resolver;
  protected final HttpServletRequest req;

  private DefaultAccess(Path basePath, String baseGitUrl, Config baseConfig,
      FileResolver<HttpServletRequest> resolver, HttpServletRequest req) {
    this.basePath = checkNotNull(basePath, "basePath");
    this.baseGitUrl = checkNotNull(baseGitUrl, "baseGitUrl");
    this.baseConfig = checkNotNull(baseConfig, "baseConfig");
    this.resolver = checkNotNull(resolver, "resolver");
    this.req = checkNotNull(req, "req");
  }

  @Override
  public Map<String, RepositoryDescription> listRepositories(Set<String> branches)
      throws IOException {
    Map<String, RepositoryDescription> repos = Maps.newTreeMap(US_COLLATOR);
    for (Repository repo : scanRepositories(basePath, req)) {
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
    return buildDescription(ServletUtils.getRepository(req), Collections.<String> emptySet());
  }

  @Override
  public Config getConfig() {
    return baseConfig;
  }

  private String getRepositoryName(Repository repo) {
    String path = getRelativePath(repo);
    if (repo.isBare() && path.endsWith(".git")) {
      path = path.substring(0, path.length() - 4);
    }
    return path;
  }

  private String getRelativePath(Repository repo) {
    Path p = repo.getDirectory().getAbsoluteFile().toPath();
    if (!repo.isBare()) {
      p = p.getParent();
    }
    return basePath.relativize(p).toString();
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
        desc = new String(IO.readFully(descFile));
        if (DEFAULT_DESCRIPTION.equals(CharMatcher.WHITESPACE.trimFrom(desc))) {
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
        Ref ref = repo.getRef(normalizeRefName(name));
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

  private Collection<Repository> scanRepositories(final Path basePath, final HttpServletRequest req)
      throws IOException {
    if (!Files.isDirectory(basePath)) {
      throw new IOException("base path is not a directory: " + basePath);
    }
    final List<Repository> repos = new ArrayList<>();
    Files.walkFileTree(basePath, new SimpleFileVisitor<Path>() {
      @Override
      public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
          throws IOException {
        if (dir.equals(basePath)) {
          return FileVisitResult.CONTINUE;
        }
        System.out.println("Visiting " + dir);
        try {
          String p = basePath.relativize(dir).toString();
          System.out.println("Attempting to resolve " + p);
          repos.add(resolver.open(req, p));
          return FileVisitResult.SKIP_SUBTREE;
        } catch (RepositoryNotFoundException e) {
          return FileVisitResult.CONTINUE;
        } catch (ServiceNotEnabledException e) {
          throw new IOException(e);
        }
      }
    });
    return repos;
  }
}
