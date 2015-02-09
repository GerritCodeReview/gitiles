// Copyright 2015 Google Inc. All Rights Reserved.
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

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import prettify.PrettifyParser;

public class PrettifyCache {
  private static BlockingQueue<Parser> parserCache =
      new ArrayBlockingQueue<>(1);

  public static Parser getParser() {
    try {
      Parser p = parserCache.poll(0, TimeUnit.MILLISECONDS);
      if (p == null) {
        p = new Parser();
      }
      return p;
    } catch (InterruptedException e) {
      return new Parser();
    }
  }

  private PrettifyCache() {
  }

  public static class Parser extends PrettifyParser implements AutoCloseable {
    @Override
    public void close() {
      parserCache.offer(this);
    }
  }
}
