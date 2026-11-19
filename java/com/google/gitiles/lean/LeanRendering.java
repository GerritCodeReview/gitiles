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
import java.util.Locale;
import javax.annotation.Nullable;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.lib.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Configuration, request eligibility, and size bounds for rich Lean 4 rendering. */
public final class LeanRendering {
  private static final Logger log = LoggerFactory.getLogger(LeanRendering.class);
  private static final int MAX_LOG_LENGTH = 256;

  public static final String LEAN_PARAM = "lean";
  public static final String NO_LEAN_VALUE = "0";

  public static final int MAX_BYTES = 2 << 20;
  public static final int MAX_LINES = 20_000;
  public static final int MAX_LINE_CHARS = 65_536;
  public static final int MAX_OUTPUT_CHARS = 16 << 20;

  /**
   * Returns whether rich Lean rendering is enabled by {@code gitiles.leanRendering}.
   *
   * <p>Defaults to {@code true}. Invalid config values fail closed to {@code false}.
   */
  public static boolean isEnabled(@Nullable Config config) {
    if (config == null) {
      return true;
    }
    try {
      return config.getBoolean("gitiles", null, "leanRendering", true);
    } catch (IllegalArgumentException e) {
      log.warn("Invalid boolean for gitiles.leanRendering; disabling Lean rendering");
      return false;
    }
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

  /**
   * Returns a relative URL for {@code view} with {@code lean=0}, preserving other query parameters.
   */
  public static String plainTextUrl(@Nullable GitilesView view) {
    if (view == null) {
      return "?" + LEAN_PARAM + "=" + NO_LEAN_VALUE;
    }
    return view.toBuilder().replaceParam(LEAN_PARAM, NO_LEAN_VALUE).build().toUrl();
  }

  /**
   * Returns a relative URL for {@code view} with {@code lean} removed, preserving other query
   * parameters.
   */
  public static String renderedUrl(@Nullable GitilesView view) {
    if (view == null) {
      return "?";
    }
    return view.toBuilder().removeParam(LEAN_PARAM).build().toUrl();
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
    int lineChars = 0;
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
        lineChars = 0;
      } else if (c == '\n') {
        if (i + 1 < len) {
          lines++;
          if (lines > MAX_LINES) {
            return false;
          }
        }
        lineChars = 0;
      } else {
        lineChars++;
        if (lineChars > MAX_LINE_CHARS) {
          return false;
        }
      }
    }
    return true;
  }

  /**
   * Returns whether a JGit {@link RawText} side is within input byte, line, and per-line bounds.
   */
  public static boolean withinInputCaps(@Nullable RawText text) {
    if (text == null) {
      return false;
    }
    byte[] raw = text.getRawContent();
    if (raw.length > MAX_BYTES) {
      return false;
    }
    if (raw.length == 0) {
      return true;
    }
    int lines = 1;
    int lineBytes = 0;
    for (int i = 0; i < raw.length; i++) {
      byte b = raw[i];
      if (b == '\r') {
        if (i + 1 < raw.length && raw[i + 1] == '\n') {
          i++;
        }
        if (i + 1 < raw.length) {
          lines++;
          if (lines > MAX_LINES) {
            return false;
          }
        }
        lineBytes = 0;
      } else if (b == '\n') {
        if (i + 1 < raw.length) {
          lines++;
          if (lines > MAX_LINES) {
            return false;
          }
        }
        lineBytes = 0;
      } else {
        lineBytes++;
        if (lineBytes > MAX_LINE_CHARS) {
          return false;
        }
      }
    }
    return true;
  }

  /**
   * Sanitizes an arbitrary string (such as a Git tree path or URL) for single-line logging by
   * escaping control characters, CR/LF, and non-ASCII sequences and bounding its length.
   */
  public static String sanitizeForLog(@Nullable String value) {
    if (value == null) {
      return "<null>";
    }
    int limit = Math.min(value.length(), MAX_LOG_LENGTH);
    StringBuilder out = new StringBuilder(limit);
    for (int i = 0; i < limit; i++) {
      char c = value.charAt(i);
      if (c == '\r') {
        out.append("\\r");
      } else if (c == '\n') {
        out.append("\\n");
      } else if (c == '\t') {
        out.append("\\t");
      } else if (c < 0x20 || c >= 0x7F) {
        out.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
      } else {
        out.append(c);
      }
    }
    if (value.length() > MAX_LOG_LENGTH) {
      out.append("...");
    }
    return out.toString();
  }

  /** Returns whether an output HTML character count is within {@link #MAX_OUTPUT_CHARS}. */
  public static boolean withinOutputCap(int outputChars) {
    return outputChars >= 0 && outputChars <= MAX_OUTPUT_CHARS;
  }

  private LeanRendering() {}
}
