// Copyright (C) 2020 Google Inc. All Rights Reserved.
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

package com.google.gitiles.doc;

import static org.junit.Assert.assertEquals;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class LiveSnippetHandlerTest {
  private LiveSnippetHandler handler;

  @Before
  public void setup() {
    handler = new LiveSnippetHandler(null, null, null);
  }

  @Test
  public void parseQueryTest() {
    // markdown path should not be null.
    String markdownPath = null;
    handler.parseQuery(markdownPath, "file:");
    assertEquals(null, handler.filePath);
    assertEquals(null, handler.contentMatcher);

    handler.parseQuery(markdownPath, "file:test.cc content:function_name");
    assertEquals(null, handler.filePath);
    assertEquals(null, handler.contentMatcher);

    // markdown path is a valid file path.
    markdownPath = "index.md";
    handler.parseQuery(markdownPath, "file:");
    assertEquals("", handler.filePath);
    assertEquals(null, handler.contentMatcher);

    handler.parseQuery(markdownPath, "file:test.cc content:function_name");
    assertEquals("test.cc", handler.filePath);
    assertEquals("function_name", handler.contentMatcher.pattern());

    handler.parseQuery(markdownPath, "file:test.cc contents:test.*$");
    assertEquals("test.cc", handler.filePath);
    assertEquals(null, handler.contentMatcher);

    handler.parseQuery(markdownPath, " content:file:test.*$ file:test.cc");
    assertEquals("test.cc", handler.filePath);
    assertEquals("file:test.*$", handler.contentMatcher.pattern());

    markdownPath = "docs/index.md";
    handler.parseQuery(markdownPath, "file:../test.cc content:message Echo:");
    assertEquals("test.cc", handler.filePath);
    assertEquals("message Echo:", handler.contentMatcher.pattern());
  }
}
