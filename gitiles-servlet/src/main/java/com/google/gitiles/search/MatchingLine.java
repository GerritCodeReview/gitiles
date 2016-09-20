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

class MatchingLine {

  private int lineNumber;
  private String line;
  private Position[] matches;

  MatchingLine() {
  }

  int getLineNumber() {
    return lineNumber;
  }

  String getLine() {
    return line;
  }

  Position[] getMatches() {
    return matches;
  }

  static List<Map<String, Object>> toSoyData(MatchingLine[] lines, String fileUrl) {
    List<Map<String, Object>> data = new ArrayList<>();
    for (MatchingLine line: lines) {
      data.add(line.toSoyData(fileUrl));
    }
    return data;
  }

  private Map<String, Object> toSoyData(String fileUrl) {
    Map<String, Object> data = new HashMap<>();
    data.put("lineNumber", Integer.toString(lineNumber));
    data.put("lineUrl", fileUrl + "#" + lineNumber);

    SoyListData highlighted = new SoyListData();
    int pos = 0;
    for (Position match: matches) {
      if (match.getStart() - pos > 0) {
        addSpan(highlighted, pos, match.getStart(), "plain");
      }
      addSpan(highlighted, match.getStart(), match.getEnd(), "emphasized");
      pos = match.getEnd();
    }
    addSpan(highlighted, pos, line.length(), "pln");

    data.put("line", highlighted);
    return data;
  }

  private void addSpan(SoyListData data, int start, int end, String cssClass) {
    if (end - start > 0) {
      data.add(new SoyMapData("class", cssClass, "text", line.substring(start, end)));
    }
  }
}
