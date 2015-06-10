// Copyright 2012 Google Inc. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.gitiles.dev;

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.google.gitiles.GitilesServlet.STATIC_PREFIX;

import com.google.gitiles.DebugRenderer;
import com.google.gitiles.GitilesAccess;
import com.google.gitiles.GitilesServlet;
import com.google.gitiles.PathServlet;
import com.google.gitiles.RepositoryDescription;
import com.google.gitiles.RootedDocServlet;

import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.bio.SocketConnector;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.server.handler.ResourceHandler;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.resource.FileResource;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.util.thread.ThreadPool;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.RepositoryCache;
import org.eclipse.jgit.lib.RepositoryCache.FileKey;
import org.eclipse.jgit.storage.file.FileBasedConfig;
import org.eclipse.jgit.transport.resolver.RepositoryResolver;
import org.eclipse.jgit.util.FS;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.Set;

import javax.servlet.Servlet;
import javax.servlet.http.HttpServletRequest;

class DevServer {
  private static final Logger log = LoggerFactory.getLogger(PathServlet.class);

  private static Config defaultConfig() {
    Config cfg = new Config();
    String cwd = System.getProperty("user.dir");
    cfg.setString("gitiles", null, "basePath", cwd);
    cfg.setBoolean("gitiles", null, "exportAll", true);
    cfg.setString("gitiles", null, "baseGitUrl", "file://" + cwd + "/");
    String networkHostName;
    try {
      networkHostName = InetAddress.getLocalHost().getCanonicalHostName();
    } catch (UnknownHostException e) {
      networkHostName = "127.0.0.1";
    }
    cfg.setString("gitiles", null, "siteTitle",
        String.format("Gitiles - %s:%s", networkHostName, cwd));
    cfg.setString("gitiles", null, "canonicalHostName", new File(cwd).getName());
    return cfg;
  }

  private static FileNotFoundException badSourceRoot(URI u) {
    return new FileNotFoundException("Cannot find source root from " + u);
  }

  private static FileNotFoundException badSourceRoot(URI u, Throwable cause) {
    FileNotFoundException notFound = badSourceRoot(u);
    notFound.initCause(cause);
    return notFound;
  }

  private static File findSourceRoot() throws IOException {
    URI u;
    try {
      u = DevServer.class.getResource(DevServer.class.getSimpleName() + ".class").toURI();
    } catch (URISyntaxException e) {
      u = null;
    }
    if (u == null) {
      throw new FileNotFoundException("Cannot find Gitiles source directory");
    }
    if ("jar".equals(u.getScheme())) {
      String path = u.getSchemeSpecificPart();
      int jarEntry = path.indexOf("!/");
      if (jarEntry < 0) {
        throw badSourceRoot(u);
      }
      try {
        return findSourceRoot(new URI(path.substring(0, jarEntry)));
      } catch (URISyntaxException e) {
        throw badSourceRoot(u, e);
      }
    } else {
      return findSourceRoot(u);
    }
  }

  private static File findSourceRoot(URI targetUri) throws IOException {
    if (!"file".equals(targetUri.getScheme())) {
      throw badSourceRoot(targetUri);
    }
    String targetPath = targetUri.getPath();
    // targetPath is an arbitrary path under buck-out/ in our Buck package
    // layout.
    int targetIndex = targetPath.lastIndexOf("buck-out/");
    if (targetIndex < 0) {
      throw badSourceRoot(targetUri);
    }
    String path = targetPath.substring(0, targetIndex);
    URI u;
    try {
      u = new URI("file", path, null).normalize();
    } catch (URISyntaxException e) {
      throw new IOException(e);
    }
    File root = new File(u);
    if (!root.exists() || !root.isDirectory()) {
      throw badSourceRoot(targetUri);
    }
    return root;
  }

  private final File sourceRoot;
  private final Config cfg;
  private final Server httpd;

  private File docRepo;
  private String docBranch;

  DevServer(File cfgFile) throws IOException, ConfigInvalidException {
    sourceRoot = findSourceRoot();

    Config cfg = defaultConfig();
    if (cfgFile.exists() && cfgFile.isFile()) {
      FileBasedConfig fcfg = new FileBasedConfig(cfg, cfgFile, FS.DETECTED);
      fcfg.load();
      cfg = fcfg;
    } else {
      log.info("Config file {} not found, using defaults", cfgFile.getPath());
    }
    this.cfg = cfg;

    httpd = new Server();
    httpd.setConnectors(connectors());
    httpd.setThreadPool(threadPool());
  }

  void setDocRoot(File repoPath, String revision) {
    this.docRepo = repoPath;
    this.docBranch = revision;
  }

  void start() throws Exception {
    httpd.setHandler(handler());
    httpd.start();
    httpd.join();
  }

  private Connector[] connectors() {
    Connector c = new SocketConnector();
    c.setHost(null);
    c.setPort(cfg.getInt("gitiles", null, "port", 8080));
    c.setStatsOn(false);
    return new Connector[]{c};
  }

  private ThreadPool threadPool() {
    QueuedThreadPool pool = new QueuedThreadPool();
    pool.setName("HTTP");
    pool.setMinThreads(2);
    pool.setMaxThreads(10);
    pool.setMaxQueued(50);
    return pool;
  }

  private Handler handler() throws IOException {
    ContextHandlerCollection handlers = new ContextHandlerCollection();
    handlers.addHandler(staticHandler());
    handlers.addHandler(appHandler());
    return handlers;
  }

  private Handler appHandler() {
    DebugRenderer renderer = new DebugRenderer(
        STATIC_PREFIX,
        Arrays.asList(cfg.getStringList("gitiles", null, "customTemplates")),
        new File(sourceRoot, "gitiles-servlet/src/main/resources/com/google/gitiles/templates")
            .getPath(),
        firstNonNull(cfg.getString("gitiles", null, "siteTitle"), "Gitiles"));
    Servlet servlet;
    if (docRepo != null) {
      servlet = new RootedDocServlet(
          new RepositoryResolver<HttpServletRequest>() {
            @Override
            public Repository open(HttpServletRequest req, String name)
                throws RepositoryNotFoundException {
              try {
                return RepositoryCache.open(FileKey.exact(docRepo, FS.DETECTED), true);
              } catch (IOException e) {
                throw new RepositoryNotFoundException(docRepo, e);
              }
            }
          },
          docBranch,
          new RootedDocAccess(),
          renderer);
    } else {
      servlet = new GitilesServlet(
          cfg,
          renderer,
          null, null, null, null, null, null, null);
    }

    ServletContextHandler handler = new ServletContextHandler();
    handler.setContextPath("");
    handler.addServlet(new ServletHolder(servlet), "/*");
    return handler;
  }

  private Handler staticHandler() throws IOException {
    File staticRoot = new File(sourceRoot,
        "gitiles-servlet/src/main/resources/com/google/gitiles/static");
    ResourceHandler rh = new ResourceHandler();
    try {
      rh.setBaseResource(new FileResource(staticRoot.toURI().toURL()));
    } catch (URISyntaxException e) {
      throw new IOException(e);
    }
    rh.setWelcomeFiles(new String[]{});
    rh.setDirectoriesListed(false);
    ContextHandler handler = new ContextHandler("/+static");
    handler.setHandler(rh);
    return handler;
  }

  private class RootedDocAccess implements GitilesAccess.Factory {
    @Override
    public GitilesAccess forRequest(HttpServletRequest req) {
      return new GitilesAccess() {
        @Override
        public Map<String, RepositoryDescription> listRepositories(Set<String> branches) {
          return Collections.emptyMap();
        }

        @Override
        public Object getUserKey() {
          return null;
        }

        @Override
        public String getRepositoryName() {
          return docRepo.getName();
        }

        @Override
        public RepositoryDescription getRepositoryDescription() {
          RepositoryDescription d = new RepositoryDescription();
          d.name = getRepositoryName();
          return d;
        }

        @Override
        public Config getConfig() {
          return cfg;
        }
      };
    }
  }

}
