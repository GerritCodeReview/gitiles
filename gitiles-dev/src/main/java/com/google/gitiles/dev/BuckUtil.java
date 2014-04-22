// Copyright (C) 2014 Google Inc. All Rights Reserved.
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

package com.google.gitiles.dev;

import com.google.common.base.Objects;
import com.google.common.io.ByteStreams;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Utilities for running buck from a dev server environment. */
public class BuckUtil {
  private static final Logger log = LoggerFactory.getLogger(BuckUtil.class);

  public static void build(Path root, String target)
      throws IOException, BuildFailureException {
    log.info("buck build " + target);
    Properties properties = loadBuckProperties(buckOutGen(root));
    String buck = Objects.firstNonNull(properties.getProperty("buck"), "buck");
    ProcessBuilder proc = new ProcessBuilder(buck, "build", target)
        .directory(root.toFile())
        .redirectErrorStream(true);
    if (properties.containsKey("PATH")) {
      proc.environment().put("PATH", properties.getProperty("PATH"));
    }
    long start = System.currentTimeMillis();
    Process rebuild = proc.start();
    byte[] out;
    InputStream in = rebuild.getInputStream();
    try {
      out = ByteStreams.toByteArray(in);
    } finally {
      rebuild.getOutputStream().close();
      in.close();
    }

    int status;
    try {
      status = rebuild.waitFor();
    } catch (InterruptedException e) {
      throw new InterruptedIOException("interrupted waiting for " + buck);
    }
    if (status != 0) {
      throw new BuildFailureException(out);
    }

    long time = System.currentTimeMillis() - start;
    log.info(String.format("UPDATED    %s in %.3fs", target, time / 1000.0));
  }

  public static Path buckOutGen(Path root) {
    return root.resolve("buck-out").resolve("gen");
  }

  private static Properties loadBuckProperties(Path gen)
      throws FileNotFoundException, IOException {
    Properties properties = new Properties();
    InputStream in = Files.newInputStream(
        gen.resolve("bucklets").resolve("tools").resolve("buck.properties"));
    try {
      properties.load(in);
    } finally {
      in.close();
    }
    return properties;
  }

  @SuppressWarnings("serial")
  static class BuildFailureException extends Exception {
    final String why;

    BuildFailureException(byte[] why) {
      this.why = new String(why, StandardCharsets.UTF_8);
    }
  }
}
