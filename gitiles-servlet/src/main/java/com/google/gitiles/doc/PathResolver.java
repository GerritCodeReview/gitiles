// Copyright (C) 2016 The Android Open Source Project
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

package com.google.gitiles.doc;

import com.google.common.base.CharMatcher;

import javax.annotation.Nullable;

class PathResolver {
  /**
   * Resolve a path within the repository.
   *
   * @param file path of the Markdown file in the repository that is making the
   *        reference. Should not start with {@code '/'}. May be null.
   * @param target destination within the repository. If {@code target} starts
   *        with {@code '/'}, {@code file} may be null and {@code target} is
   *        evaluated as from the root directory of the repository.
   * @return resolved form of {@code target} within the repository. Null if
   *         {@code target} is not valid from {@code file}. Does not begin with
   *         {@code '/'}, even if {@code target} does.
   */
  @Nullable
  static String resolve(@Nullable String file, String target) {
    if (target.startsWith("/")) {
      return target.substring(1);
    } else if (file == null) {
      return null;
    }

    String dir = trimLastComponent(CharMatcher.is('/').trimLeadingFrom(file));
    while (!target.isEmpty()) {
      if (target.startsWith("../") || target.equals("..")) {
        if (dir.isEmpty()) {
          return null;
        }
        dir = trimLastComponent(dir);
        target = target.equals("..") ? "" : target.substring(3);
      } else if (target.startsWith("./")) {
        target = target.substring(2);
      } else if (target.equals(".")) {
        target = "";
      } else {
        break;
      }
    }
    return CharMatcher.is('/').trimLeadingFrom(dir + '/' + target);
  }

  private static String trimLastComponent(String path) {
    int slash = path.lastIndexOf('/');
    return slash < 0 ? "" : path.substring(0, slash);
  }

  private PathResolver() {}
}
