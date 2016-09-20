// Copyright 2016 Google Inc. All Rights Reserved.
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

package com.google.gitiles.search;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.GsonBuilder;
import java.util.HashMap;
import java.util.Map;

class SearchResult {
  private MatchingFile[] files;

  SearchResult() {
  }

  static SearchResult valueOf(String s) {
    return new GsonBuilder()
        .setFieldNamingPolicy(FieldNamingPolicy.UPPER_CAMEL_CASE)
        .create().fromJson(s, SearchResult.class);
  }

  MatchingFile[] getFiles() {
    return files;
  }

  boolean isEmpty() {
    return files == null || files.length == 0;
  }

  Map<String, Object> toSoyData(String revisionUrl) {
    Map<String, Object> data = new HashMap<>();
    if (files != null) {
      data.put("files", MatchingFile.toSoyData(files, revisionUrl));
    }
    return data;
  }
}
