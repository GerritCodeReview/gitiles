// Copyright (C) 2020 The Android Open Source Project
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

package com.google.gitiles;

import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Properties;

public class FileTypeMaps {
  private static final String MIME_ANY = "application/octet-stream";
  private static final ImmutableMap<String, String> MIME_TYPES;
  private static final ImmutableMap<String, String> LANGUAGE_TYPES;

  private static ImmutableMap<String, String> initMap(String typePropertiesFile) {
    Properties p = new Properties();
    try (InputStream in = FileTypeMaps.class.getResourceAsStream(typePropertiesFile)) {
      p.load(in);
    } catch (IOException e) {
      throw new RuntimeException("Cannot load language-types.properties", e);
    }

    ImmutableMap.Builder<String, String> m = ImmutableMap.builder();
    for (Map.Entry<Object, Object> e : p.entrySet()) {
      m.put(((String) e.getKey()).toLowerCase(), (String) e.getValue());
    }
    return m.build();
  }

  static {
    MIME_TYPES = initMap("mime-types.properties");
    LANGUAGE_TYPES = initMap("language-types.properties");
  }

  private static String getType(ImmutableMap<String, String> map, String path, String defaultVal) {
    int d = path.lastIndexOf('.');
    if (d == -1) {
      return defaultVal;
    }

    String ext = path.substring(d + 1);
    String type = map.get(ext.toLowerCase());
    return MoreObjects.firstNonNull(type, defaultVal);
  }

  public static String getMimeType(String path) {
    return getType(MIME_TYPES, path, MIME_ANY);
  }

  public static String getLanguageType(String path) {
    return getType(LANGUAGE_TYPES, path, "");
  }

  private FileTypeMaps() {}
}
