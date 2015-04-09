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

import static com.google.gitiles.GitilesFilter.RAW_CONTENT_REGEX;
import static com.google.gitiles.GitilesFilter.REPO_PATH_REGEX;
import static com.google.gitiles.GitilesFilter.REPO_REGEX;
import static com.google.gitiles.GitilesFilter.ROOT_REGEX;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.regex.Matcher;

/** Tests for the Gitiles filter. */
@RunWith(JUnit4.class)
public class GitilesFilterTest {
  @Test
  public void rootUrls() throws Exception {
    assertFalse(ROOT_REGEX.matcher("").matches());
    assertFalse(ROOT_REGEX.matcher("/foo").matches());
    assertFalse(ROOT_REGEX.matcher("/foo/").matches());
    assertFalse(ROOT_REGEX.matcher("/foo/ ").matches());
    assertFalse(ROOT_REGEX.matcher("/foo/+").matches());
    assertFalse(ROOT_REGEX.matcher("/foo/+").matches());
    assertFalse(ROOT_REGEX.matcher("/foo/ /").matches());
    assertFalse(ROOT_REGEX.matcher("/foo/+/").matches());
    assertFalse(ROOT_REGEX.matcher("/foo/+/bar").matches());
    Matcher m;

    m = ROOT_REGEX.matcher("/");
    assertTrue(m.matches());
    assertEquals("/", m.group(0));
    assertEquals(m.group(0), m.group(1));
    assertEquals("", m.group(2));
    assertEquals("/", m.group(3));
    assertEquals("", m.group(4));
    assertEquals("", m.group(5));

    m = ROOT_REGEX.matcher("//");
    assertTrue(m.matches());
    assertEquals("//", m.group(0));
    assertEquals(m.group(0), m.group(1));
    assertEquals("", m.group(2));
    assertEquals("/", m.group(3));
    assertEquals("", m.group(4));
    assertEquals("", m.group(5));
  }

  @Test
  public void repoUrls() throws Exception {
    assertFalse(REPO_REGEX.matcher("").matches());

    // These match the regex but are served by the root regex binder, which is
    // matched first.
    assertTrue(REPO_REGEX.matcher("/").matches());
    assertTrue(REPO_REGEX.matcher("//").matches());

    assertFalse(REPO_REGEX.matcher("/foo/+").matches());
    assertFalse(REPO_REGEX.matcher("/foo/bar/+").matches());
    assertFalse(REPO_REGEX.matcher("/foo/bar/+/").matches());
    assertFalse(REPO_REGEX.matcher("/foo/bar/+/baz").matches());
    Matcher m;

    m = REPO_REGEX.matcher("/foo");
    assertTrue(m.matches());
    assertEquals("/foo", m.group(0));
    assertEquals(m.group(0), m.group(1));
    assertEquals("", m.group(2));
    assertEquals("/foo", m.group(3));
    assertEquals("", m.group(4));
    assertEquals("", m.group(5));

    m = REPO_REGEX.matcher("/foo/");
    assertTrue(m.matches());
    assertEquals("/foo/", m.group(0));
    assertEquals(m.group(0), m.group(1));
    assertEquals("", m.group(2));
    assertEquals("/foo", m.group(3));
    assertEquals("", m.group(4));
    assertEquals("", m.group(5));

    m = REPO_REGEX.matcher("/foo/bar");
    assertTrue(m.matches());
    assertEquals("/foo/bar", m.group(0));
    assertEquals(m.group(0), m.group(1));
    assertEquals("", m.group(2));
    assertEquals("/foo/bar", m.group(3));
    assertEquals("", m.group(4));
    assertEquals("", m.group(5));

    m = REPO_REGEX.matcher("/foo/bar+baz");
    assertTrue(m.matches());
    assertEquals("/foo/bar+baz", m.group(0));
    assertEquals(m.group(0), m.group(1));
    assertEquals("", m.group(2));
    assertEquals("/foo/bar+baz", m.group(3));
    assertEquals("", m.group(4));
    assertEquals("", m.group(5));
  }

  @Test
  public void repoPathUrls() throws Exception {
    assertFalse(REPO_PATH_REGEX.matcher("").matches());
    assertFalse(REPO_PATH_REGEX.matcher("/").matches());
    assertFalse(REPO_PATH_REGEX.matcher("//").matches());
    assertFalse(REPO_PATH_REGEX.matcher("/foo").matches());
    assertFalse(REPO_PATH_REGEX.matcher("/foo/ ").matches());
    assertFalse(REPO_PATH_REGEX.matcher("/foo/ /").matches());
    assertFalse(REPO_PATH_REGEX.matcher("/foo/ /bar").matches());
    assertFalse(REPO_PATH_REGEX.matcher("/foo/bar").matches());
    assertFalse(REPO_PATH_REGEX.matcher("/foo/bar+baz").matches());
    Matcher m;

    m = REPO_PATH_REGEX.matcher("/foo/+");
    assertTrue(m.matches());
    assertEquals("/foo/+", m.group(0));
    assertEquals(m.group(0), m.group(1));
    assertEquals("", m.group(2));
    assertEquals("/foo", m.group(3));
    assertEquals("+", m.group(4));
    assertEquals("", m.group(5));

    m = REPO_PATH_REGEX.matcher("/foo/+/");
    assertTrue(m.matches());
    assertEquals("/foo/+/", m.group(0));
    assertEquals(m.group(0), m.group(1));
    assertEquals("", m.group(2));
    assertEquals("/foo", m.group(3));
    assertEquals("+", m.group(4));
    assertEquals("/", m.group(5));

    m = REPO_PATH_REGEX.matcher("/foo/+/bar/baz");
    assertTrue(m.matches());
    assertEquals("/foo/+/bar/baz", m.group(0));
    assertEquals(m.group(0), m.group(1));
    assertEquals("", m.group(2));
    assertEquals("/foo", m.group(3));
    assertEquals("+", m.group(4));
    assertEquals("/bar/baz", m.group(5));

    m = REPO_PATH_REGEX.matcher("/foo/+/bar/baz/");
    assertTrue(m.matches());
    assertEquals("/foo/+/bar/baz/", m.group(0));
    assertEquals(m.group(0), m.group(1));
    assertEquals("", m.group(2));
    assertEquals("/foo", m.group(3));
    assertEquals("+", m.group(4));
    assertEquals("/bar/baz/", m.group(5));

    m = REPO_PATH_REGEX.matcher("/foo/+/bar baz");
    assertTrue(m.matches());
    assertEquals("/foo/+/bar baz", m.group(0));
    assertEquals(m.group(0), m.group(1));
    assertEquals("", m.group(2));
    assertEquals("/foo", m.group(3));
    assertEquals("+", m.group(4));
    assertEquals("/bar baz", m.group(5));

    m = REPO_PATH_REGEX.matcher("/foo/+/bar/+/baz");
    assertTrue(m.matches());
    assertEquals("/foo/+/bar/+/baz", m.group(0));
    assertEquals(m.group(0), m.group(1));
    assertEquals("", m.group(2));
    assertEquals("/foo", m.group(3));
    assertEquals("+", m.group(4));
    assertEquals("/bar/+/baz", m.group(5));

    m = REPO_PATH_REGEX.matcher("/foo/+bar/baz");
    assertTrue(m.matches());
    assertEquals("/foo/+bar/baz", m.group(0));
    assertEquals(m.group(0), m.group(1));
    assertEquals("", m.group(2));
    assertEquals("/foo", m.group(3));
    assertEquals("+bar", m.group(4));
    assertEquals("/baz", m.group(5));
  }

  @Test
  public void rawContentUrls() throws Exception {
    assertFalse(RAW_CONTENT_REGEX.matcher("").matches());
    assertFalse(RAW_CONTENT_REGEX.matcher("/").matches());
    assertFalse(RAW_CONTENT_REGEX.matcher("//").matches());
    assertFalse(RAW_CONTENT_REGEX.matcher("/foo").matches());
    assertFalse(RAW_CONTENT_REGEX.matcher("/foo/ ").matches());
    assertFalse(RAW_CONTENT_REGEX.matcher("/foo/ /").matches());
    assertFalse(RAW_CONTENT_REGEX.matcher("/foo/ /bar").matches());
    assertFalse(RAW_CONTENT_REGEX.matcher("/foo/bar").matches());
    assertFalse(RAW_CONTENT_REGEX.matcher("/foo/bar+baz").matches());
    assertFalse(RAW_CONTENT_REGEX.matcher("/foo/+").matches());
    assertFalse(RAW_CONTENT_REGEX.matcher("/foo/+/bar").matches());
    assertFalse(RAW_CONTENT_REGEX.matcher("/host/repo/+").matches());
    assertFalse(RAW_CONTENT_REGEX.matcher("/host/repo/+/path").matches());
    assertFalse(RAW_CONTENT_REGEX.matcher("/host/repo/+raw").matches());
    assertFalse(RAW_CONTENT_REGEX.matcher("/host/repo/+raw/path").matches());
    Matcher m;

    m = RAW_CONTENT_REGEX.matcher("/host/repo/+rawc");
    assertTrue(m.matches());
    assertEquals("/host/repo/+rawc", m.group(0));
    assertEquals(m.group(0), m.group(1));
    assertEquals("/host", m.group(2));
    assertEquals("/repo", m.group(3));
    assertEquals("+rawc", m.group(4));
    assertEquals("", m.group(5));

    m = RAW_CONTENT_REGEX.matcher("/host/repo/+rawc/");
    assertTrue(m.matches());
    assertEquals("/host/repo/+rawc/", m.group(0));
    assertEquals(m.group(0), m.group(1));
    assertEquals("/host", m.group(2));
    assertEquals("/repo", m.group(3));
    assertEquals("+rawc", m.group(4));
    assertEquals("/", m.group(5));

    m = RAW_CONTENT_REGEX.matcher("/host/repo/+rawc/foo/bar");
    assertTrue(m.matches());
    assertEquals("/host/repo/+rawc/foo/bar", m.group(0));
    assertEquals(m.group(0), m.group(1));
    assertEquals("/host", m.group(2));
    assertEquals("/repo", m.group(3));
    assertEquals("+rawc", m.group(4));
    assertEquals("/foo/bar", m.group(5));

    m = RAW_CONTENT_REGEX.matcher("/host/repo/+rawc/foo/bar/");
    assertTrue(m.matches());
    assertEquals("/host/repo/+rawc/foo/bar/", m.group(0));
    assertEquals(m.group(0), m.group(1));
    assertEquals("/host", m.group(2));
    assertEquals("/repo", m.group(3));
    assertEquals("+rawc", m.group(4));
    assertEquals("/foo/bar/", m.group(5));

    m = RAW_CONTENT_REGEX.matcher("/host/re/po/+rawc/foo/bar/");
    assertTrue(m.matches());
    assertEquals("/host/re/po/+rawc/foo/bar/", m.group(0));
    assertEquals(m.group(0), m.group(1));
    assertEquals("/host", m.group(2));
    assertEquals("/re/po", m.group(3));
    assertEquals("+rawc", m.group(4));
    assertEquals("/foo/bar/", m.group(5));
  }
}
