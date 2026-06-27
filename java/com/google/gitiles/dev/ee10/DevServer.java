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

import java.io.File;
import java.io.IOException;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jgit.errors.ConfigInvalidException;

/**
 * EE10 ({@code jakarta.servlet}) dev server overlay.
 *
 * <p>Hand-maintained counterpart of the canonical EE8 {@code DevServer}; kept under {@code dev/ee10}
 * (Bazel package boundary) but in the same {@code com.google.gitiles.dev} Java package, so it stays
 * named {@code DevServer} and {@code Main}'s {@code new DevServer(...)} is untouched. All shared
 * wiring lives in the transform-generated {@link DevServerBase}; only the EE10-specific handler
 * installation differs. See {@code tools/gitiles-ee10/README.md}.
 */
class DevServer extends DevServerBase {
  DevServer(File cfgFile) throws IOException, ConfigInvalidException {
    super(cfgFile);
  }

  @Override
  protected Handler toCoreHandler(ServletContextHandler handler) {
    // The EE10 ServletContextHandler is itself an org.eclipse.jetty.server.Handler;
    // unlike the EE8 adapter, there is no Supplier to unwrap.
    return handler;
  }
}
