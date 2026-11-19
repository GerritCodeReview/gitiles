// Copyright (C) 2026 Google Inc. All Rights Reserved.
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

package com.google.gitiles.lean;

import com.google.gitiles.GitilesView;
import javax.annotation.Nullable;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.lib.Config;

/** Configuration, request eligibility, and size bounds for rich Lean 4 rendering. */
public final class LeanRendering {
  public static final String LEAN_PARAM = "lean";
  public static final String NO_LEAN_VALUE = "0";

  public static final int MAX_BYTES = 2 << 20;
  public static final int MAX_LINES = 20_000;
  public static final int MAX_OUTPUT_CHARS = 16 << 20;

  /**
   * Returns whether rich Lean rendering is enabled by {@code gitiles.leanRendering}.
   *
   * <p>Defaults to {@code true}.
   */
  public static boolean isEnabled(@Nullable Config config) {
    return config == null || config.getBoolean("gitiles", null, "leanRendering", true);
  }

  /**
   * Returns whether rich Lean rendering is requested by the view's query parameters.
   *
   * <p>Returns {@code false} only when a {@code lean} query parameter equals {@code "0"}.
   */
  public static boolean isRequested(@Nullable GitilesView view) {
    return view == null || !view.getParameters().get(LEAN_PARAM).contains(NO_LEAN_VALUE);
  }

  /** Returns whether rich Lean rendering is enabled by both config and request parameters. */
  public static boolean isEnabled(@Nullable Config config, @Nullable GitilesView view) {
    return isEnabled(config) && isRequested(view);
  }

  /** Returns whether {@code path} names a Lean source file ({@code .lean}). */
  public static boolean isLeanPath(@Nullable String path) {
    return path != null && path.endsWith(".lean");
  }

  /** Returns whether {@code byteLength} and {@code content} are within input caps. */
  public static boolean withinInputCaps(int byteLength, @Nullable String content) {
    if (content == null || byteLength < 0 || byteLength > MAX_BYTES) {
      return false;
    }
    int len = content.length();
    if (len == 0) {
      return true;
    }
    int lines = 1;
    for (int i = 0; i < len; i++) {
      char c = content.charAt(i);
      if (c == '\r') {
        if (i + 1 < len && content.charAt(i + 1) == '\n') {
          i++;
        }
        if (i + 1 < len) {
          lines++;
          if (lines > MAX_LINES) {
            return false;
          }
        }
      } else if (c == '\n') {
        if (i + 1 < len) {
          lines++;
          if (lines > MAX_LINES) {
            return false;
          }
        }
      }
    }
    return true;
  }

  /** Returns whether a JGit {@link RawText} side is within input byte and line caps. */
  public static boolean withinInputCaps(@Nullable RawText text) {
    return text != null && text.getRawContent().length <= MAX_BYTES && text.size() <= MAX_LINES;
  }

  /** Returns whether an output HTML character count is within {@link #MAX_OUTPUT_CHARS}. */
  public static boolean withinOutputCap(int outputChars) {
    return outputChars >= 0 && outputChars <= MAX_OUTPUT_CHARS;
  }

  private LeanRendering() {}
}
