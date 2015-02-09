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

import java.util.Collections;

import prettify.PrettifyParser;
import prettify.parser.Prettify;

public class PrettifyCache {
  private static final PrettifyParser parser = new Parser();

  public static PrettifyParser getParser() {
    return parser;
  }

  private PrettifyCache() {
  }

  private static class Parser extends PrettifyParser {
    Parser() {
      // Prettify is not thread safe ... unless we do this.
      prettify = new Prettify() {
        {
          langHandlerRegistry =
              Collections.synchronizedMap(langHandlerRegistry);
        }
      };
    }
  }
}
