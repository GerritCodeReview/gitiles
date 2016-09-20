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

import com.google.template.soy.data.SoyListData;
import com.google.template.soy.data.SoyMapData;
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

  static List<Map<String,Object>> toSoyData(MatchingFile[] files, String repositoryUrl) {
    List<Map<String, Object>> data = new ArrayList<>();
    for (MatchingFile file: files) {
      data.add(file.toSoyData(repositoryUrl));
    }
    return data;
  }

  private Map<String,Object> toSoyData(String repositoryUrl) {
    Map<String, Object> data = new HashMap<>();
    SoyListData branchMatches = new SoyListData();
    for (String branchName: branches) {
      String branchUrl = repositoryUrl + "+/" + branchName;
      String fileUrl = branchUrl + "/" + fileName;
      SoyMapData fileMatch = new SoyMapData(
          "branchName", branchName,
          "branchUrl", branchUrl,
          "fileName", fileName,
          "fileUrl", fileUrl,
          "lines", MatchingLine.toSoyData(lines, fileUrl));
      branchMatches.add(fileMatch);
    }
    data.put("matches", branchMatches);
    return data;
  }


}
