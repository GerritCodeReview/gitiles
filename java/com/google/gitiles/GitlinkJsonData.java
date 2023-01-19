// Copyright (C) 2023 Google LLC. All Rights Reserved.
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

/** Submodule data to be returned to the client as JSON. */
class GitlinkJsonData {
  static class Gitlink {
    String repo;
    String url;
    String revision;
    String path;
  }

  static Gitlink toJsonData(String repo, String url, String revision, String path) {
    Gitlink gitlink = new Gitlink();
    gitlink.repo = repo;
    gitlink.url = url;
    gitlink.revision = revision;
    gitlink.path = path;
    return gitlink;
  }

  private GitlinkJsonData() {}
}
