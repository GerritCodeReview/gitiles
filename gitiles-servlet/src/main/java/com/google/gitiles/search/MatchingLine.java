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

public class MatchingLine {

  private int lineNumber;
  private String line;
  private Position[] matches;

  MatchingLine() {
  }

  public int getLineNumber() {
    return lineNumber;
  }

  public String getLine() {
    return line;
  }

  public Position[] getMatches() {
    return matches;
  }

  // TODO: emphasize matching parts of the line
  public Map<String, Object> toSoyData(String fileUrl) {
    Map<String, Object> data = new HashMap<>();
    data.put("lineNumber", Integer.toString(lineNumber));
    if (fileUrl != null) {
      data.put("lineUrl", fileUrl + "#" + lineNumber);
    }
    if (line != null) {
      data.put("line", line);
    }
    if (matches != null) {
      data.put("matches", Position.toSoyData(matches));
    }
    return data;
  }

  public static List<Map<String, Object>> toSoyData(MatchingLine[] lines, String fileUrl) {
    List<Map<String, Object>> data = new ArrayList<>();
    for (MatchingLine line: lines) {
      data.add(line.toSoyData(fileUrl));
    }
    return data;
  }
}
