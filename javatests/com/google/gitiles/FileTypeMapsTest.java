// Copyright 2020 Google Inc. All Rights Reserved.
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

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class FileTypeMapsTest {
  @Test
  public void mimeTypeCheck() throws Exception {
    // File paths that can get a mime type.
    assertThat(FileTypeMaps.getMimeType("test.bmp")).isEqualTo("image/bmp");
    assertThat(FileTypeMaps.getMimeType("ui/resource/class.css")).isEqualTo("text/css");
    assertThat(FileTypeMaps.getMimeType("test-this.csv")).isEqualTo("text/csv");
    assertThat(FileTypeMaps.getMimeType("my_image.gif")).isEqualTo("image/gif");
    assertThat(FileTypeMaps.getMimeType("file.htm")).isEqualTo("text/html");
    assertThat(FileTypeMaps.getMimeType("../file.html")).isEqualTo("text/html");
    assertThat(FileTypeMaps.getMimeType("//image.jpeg")).isEqualTo("image/jpeg");
    assertThat(FileTypeMaps.getMimeType("image.jpg")).isEqualTo("image/jpeg");
    assertThat(FileTypeMaps.getMimeType("1.js")).isEqualTo("application/javascript");
    assertThat(FileTypeMaps.getMimeType("doc.md")).isEqualTo("text/markdown");
    assertThat(FileTypeMaps.getMimeType("form.PDF")).isEqualTo("application/pdf");
    assertThat(FileTypeMaps.getMimeType("//docs/ui/image.png")).isEqualTo("image/png");
    assertThat(FileTypeMaps.getMimeType("new-graph.svg")).isEqualTo("image/svg+xml");
    assertThat(FileTypeMaps.getMimeType("345.tiff")).isEqualTo("image/tiff");
    assertThat(FileTypeMaps.getMimeType("readme.txt")).isEqualTo("text/plain");
    assertThat(FileTypeMaps.getMimeType("./SCHEME.XML")).isEqualTo("text/xml");

    // File paths that can not get a mime type, default to octet stream type.
    assertThat(FileTypeMaps.getMimeType("test.bmp1")).isEqualTo("application/octet-stream");
    assertThat(FileTypeMaps.getMimeType("ui/resource/class.csss")).isEqualTo("application/octet-stream");
    assertThat(FileTypeMaps.getMimeType("ui/resource/class.ccss")).isEqualTo("application/octet-stream");
    assertThat(FileTypeMaps.getMimeType("ms.doc")).isEqualTo("application/octet-stream");
    assertThat(FileTypeMaps.getMimeType("video.webm")).isEqualTo("application/octet-stream");
    assertThat(FileTypeMaps.getMimeType("file.html.text")).isEqualTo("application/octet-stream");
  }

  @Test
  public void languageTypeCheck() throws Exception {
    // File paths that can get a language type.
    assertThat(FileTypeMaps.getLanguageType("test.cc")).isEqualTo("c++");
    assertThat(FileTypeMaps.getLanguageType("//component/test.cpp")).isEqualTo("c++");
    assertThat(FileTypeMaps.getLanguageType("../tt1.h")).isEqualTo("c++");
    assertThat(FileTypeMaps.getLanguageType("./tree_walk.hpp")).isEqualTo("c++");
    assertThat(FileTypeMaps.getLanguageType("driver.c")).isEqualTo("c");
    assertThat(FileTypeMaps.getLanguageType("long_name_test_123.java")).isEqualTo("java");
    assertThat(FileTypeMaps.getLanguageType("file.htm")).isEqualTo("html");
    assertThat(FileTypeMaps.getLanguageType("../file.html")).isEqualTo("html");
    assertThat(FileTypeMaps.getLanguageType("ui/resource/class.css")).isEqualTo("css");
    assertThat(FileTypeMaps.getLanguageType("1.js")).isEqualTo("javascript");
    assertThat(FileTypeMaps.getLanguageType("config.sh")).isEqualTo("shell");
    assertThat(FileTypeMaps.getLanguageType("run_script.py")).isEqualTo("python");
    assertThat(FileTypeMaps.getLanguageType("data.json")).isEqualTo("json");
    assertThat(FileTypeMaps.getLanguageType("browser_frame.mm")).isEqualTo("objectivec");

    // File paths that can not get a language type, default to an empty string type.
    assertThat(FileTypeMaps.getLanguageType("test.c++")).isEqualTo("");
    assertThat(FileTypeMaps.getLanguageType("test.c#")).isEqualTo("");
    assertThat(FileTypeMaps.getLanguageType("../file,html")).isEqualTo("");
    assertThat(FileTypeMaps.getLanguageType("1.javascript")).isEqualTo("");
    assertThat(FileTypeMaps.getLanguageType("config.bsh")).isEqualTo("");
    assertThat(FileTypeMaps.getLanguageType("config.csh")).isEqualTo("");
    assertThat(FileTypeMaps.getLanguageType("")).isEqualTo("");
    assertThat(FileTypeMaps.getLanguageType("test_file")).isEqualTo("");
    assertThat(FileTypeMaps.getLanguageType("/path/to/test_file")).isEqualTo("");
  }
}
