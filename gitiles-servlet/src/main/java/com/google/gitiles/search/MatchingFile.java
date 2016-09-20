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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

class MatchingFile {

  private String repo;
  private String[] branches;
  private String fileName;
  private MatchingLine[] lines;

  MatchingFile() {
  }

  String getRepositoryName() {
    return repo;
  }

  String[] getBranches() {
    return branches;
  }

  String getFileName() {
    return fileName;
  }

  MatchingLine[] getLines() {
    return lines;
  }

  static List<Map<String,Object>> toSoyData(MatchingFile[] files, String revisionUrl) {
    List<Map<String, Object>> data = new ArrayList<>();
    for (MatchingFile file: files) {
      data.add(file.toSoyData(revisionUrl));
    }
    return data;
  }

  private Map<String,Object> toSoyData(String revisionUrl) {
    Map<String, Object> data = new HashMap<>();
    data.put("repo", repo);
    data.put("fileName", fileName);
    data.put("fileUrl", revisionUrl + "/" + fileName);
    data.put("branch", branches[0]); //TODO handle the case of mutliple branches
    data.put("lines", MatchingLine.toSoyData(lines, (String)data.get("fileUrl")));
    return data;
  }
}
