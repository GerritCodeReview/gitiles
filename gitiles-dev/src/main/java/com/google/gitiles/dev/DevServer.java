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

import com.google.gitiles.GitilesServlet;

import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.nio.SelectChannelConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.util.thread.ThreadPool;
import org.eclipse.jgit.lib.Config;

class DevServer {
  private final Server httpd;

  DevServer(Config cfg) {
    httpd = new Server();
    httpd.setConnectors(connectors(cfg));
    httpd.setThreadPool(threadPool(cfg));
    httpd.setHandler(handler(cfg));
  }

  void start() throws Exception {
    httpd.start();
    httpd.join();
  }

  private Connector[] connectors(Config cfg) {
    Connector c = new SelectChannelConnector();
    c.setHost(null);
    c.setPort(cfg.getInt("gitiles", null, "port", 8080));
    c.setStatsOn(false);
    return new Connector[]{c};
  }

  private ThreadPool threadPool(Config cfg) {
    QueuedThreadPool pool = new QueuedThreadPool();
    pool.setName("HTTP");
    pool.setMinThreads(2);
    pool.setMaxThreads(10);
    pool.setMaxQueued(50);
    return pool;
  }

  private Handler handler(Config cfg) {
    ServletContextHandler handler = new ServletContextHandler();
    handler.setContextPath("");
    handler.addServlet(new ServletHolder(new GitilesServlet()), "/*");
    return handler;
  }
}
