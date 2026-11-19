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

import com.google.common.annotations.VisibleForTesting;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.function.LongSupplier;
import javax.annotation.Nullable;

/**
 * Per-request cumulative resource budget for Lean rendering across multi-file commit diffs and
 * single-file safety deadlines.
 *
 * <p>All instances are strictly per-request / per-call local and not thread-safe.
 */
public final class LeanRequestBudget {
  public static final long MAX_REQUEST_INPUT_BYTES = 4L << 20;
  public static final int MAX_REQUEST_OUTPUT_CHARS = LeanRendering.MAX_OUTPUT_CHARS;
  public static final int MAX_REQUEST_LEAN_FILES = 50;
  public static final long MAX_REQUEST_CPU_NANOS = 750_000_000L;

  private static final ThreadMXBean THREAD_MX = ManagementFactory.getThreadMXBean();
  private static final boolean CPU_TIME_SUPPORTED = detectCpuTimeSupported();

  private final long maxInputBytes;
  private final int maxOutputChars;
  private final int maxFiles;
  private final long maxCpuNanos;
  private final LongSupplier nanoClock;
  private final boolean requireNonNegativeClock;

  private long inputBytesUsed;
  private int outputCharsUsed;
  private int filesRendered;
  private long cpuNanosUsed;
  private long activeStartNanos;
  private boolean hasActiveOperation;
  private boolean exhausted;

  private LeanRequestBudget(
      long maxInputBytes,
      int maxOutputChars,
      int maxFiles,
      long maxCpuNanos,
      @Nullable LongSupplier customClock) {
    this.maxInputBytes = Math.max(0L, maxInputBytes);
    this.maxOutputChars = Math.max(0, maxOutputChars);
    this.maxFiles = Math.max(0, maxFiles);
    this.maxCpuNanos = Math.max(0L, maxCpuNanos);
    boolean[] cpuSelected = new boolean[1];
    this.nanoClock = selectClock(customClock, cpuSelected);
    this.requireNonNegativeClock = cpuSelected[0];
    if (this.maxFiles == 0 || this.maxOutputChars == 0 || this.maxCpuNanos == 0L) {
      this.exhausted = true;
    }
  }

  /** Creates a cumulative per-request budget for commit diffs in {@code DiffServlet}. */
  public static LeanRequestBudget forRequest() {
    return new LeanRequestBudget(
        MAX_REQUEST_INPUT_BYTES,
        MAX_REQUEST_OUTPUT_CHARS,
        MAX_REQUEST_LEAN_FILES,
        MAX_REQUEST_CPU_NANOS,
        null);
  }

  /** Creates a single-file cooperative CPU and output budget for blob or isolated diff calls. */
  public static LeanRequestBudget forSingleFile() {
    return new LeanRequestBudget(
        MAX_REQUEST_INPUT_BYTES, MAX_REQUEST_OUTPUT_CHARS, 1, MAX_REQUEST_CPU_NANOS, null);
  }

  @VisibleForTesting
  public static LeanRequestBudget forTesting(
      long maxInputBytes, int maxOutputChars, int maxFiles, long maxCpuNanos, LongSupplier clock) {
    return new LeanRequestBudget(maxInputBytes, maxOutputChars, maxFiles, maxCpuNanos, clock);
  }

  /**
   * Returns whether another Lean file with raw byte sizes {@code aBytes} and {@code bBytes} fits
   * within remaining cumulative request caps.
   */
  public boolean canAttempt(int aBytes, int bBytes) {
    if (exhausted) {
      return false;
    }
    if (filesRendered >= maxFiles
        || outputCharsUsed >= maxOutputChars
        || cpuNanosUsed >= maxCpuNanos) {
      exhausted = true;
      return false;
    }
    long fileBytes = saturatingAdd(Math.max(0L, (long) aBytes), Math.max(0L, (long) bBytes));
    return fileBytes <= maxInputBytes - inputBytesUsed;
  }

  public int remainingOutputChars() {
    return Math.max(0, maxOutputChars - outputCharsUsed);
  }

  public boolean isExhausted() {
    return exhausted;
  }

  @VisibleForTesting
  public int filesRendered() {
    return filesRendered;
  }

  @VisibleForTesting
  public long inputBytesUsed() {
    return inputBytesUsed;
  }

  @VisibleForTesting
  public int outputCharsUsed() {
    return outputCharsUsed;
  }

  /**
   * Marks the start of a single Lean render operation; returns {@code false} if already exhausted.
   */
  boolean beginOperation() {
    if (exhausted || cpuNanosUsed >= maxCpuNanos) {
      exhausted = true;
      return false;
    }
    activeStartNanos = readClock();
    if (exhausted) {
      hasActiveOperation = false;
      return false;
    }
    hasActiveOperation = true;
    return true;
  }

  /** Returns {@code true} if cumulative CPU time (including the active operation) hit the cap. */
  boolean isCpuDeadlineExceeded() {
    if (exhausted) {
      return true;
    }
    long active = activeElapsedNanos();
    if (exhausted || saturatingAdd(cpuNanosUsed, active) >= maxCpuNanos) {
      exhausted = true;
      return true;
    }
    return false;
  }

  /**
   * Records CPU time consumed and, if {@code rendered} is true, cumulative file/byte/output use.
   */
  void recordOutcome(int aBytes, int bBytes, int emittedOutputChars, boolean rendered) {
    if (hasActiveOperation) {
      hasActiveOperation = false;
      long now = readClock();
      if (!exhausted) {
        long elapsed = Math.max(0L, now - activeStartNanos);
        cpuNanosUsed = saturatingAdd(cpuNanosUsed, elapsed);
      }
    }
    if (cpuNanosUsed >= maxCpuNanos) {
      exhausted = true;
    }
    if (rendered) {
      filesRendered++;
      long fileBytes = saturatingAdd(Math.max(0L, (long) aBytes), Math.max(0L, (long) bBytes));
      inputBytesUsed = saturatingAdd(inputBytesUsed, fileBytes);
      outputCharsUsed =
          (int) Math.min(maxOutputChars, (long) outputCharsUsed + Math.max(0, emittedOutputChars));
      if (filesRendered >= maxFiles || outputCharsUsed >= maxOutputChars) {
        exhausted = true;
      }
    }
  }

  private long activeElapsedNanos() {
    if (!hasActiveOperation) {
      return 0L;
    }
    long now = readClock();
    if (exhausted) {
      return maxCpuNanos;
    }
    return Math.max(0L, now - activeStartNanos);
  }

  private long readClock() {
    try {
      long val = nanoClock.getAsLong();
      if (requireNonNegativeClock && val < 0L) {
        exhausted = true;
        return 0L;
      }
      return val;
    } catch (UnsupportedOperationException | SecurityException e) {
      exhausted = true;
      return 0L;
    }
  }

  private static LongSupplier selectClock(
      @Nullable LongSupplier customClock, boolean[] cpuSelected) {
    if (customClock != null) {
      return customClock;
    }
    if (CPU_TIME_SUPPORTED) {
      try {
        if (THREAD_MX.isThreadCpuTimeEnabled() && THREAD_MX.getCurrentThreadCpuTime() >= 0L) {
          cpuSelected[0] = true;
          return THREAD_MX::getCurrentThreadCpuTime;
        }
      } catch (UnsupportedOperationException | SecurityException ignored) {
        // Fall back to monotonic wall clock below.
      }
    }
    return System::nanoTime;
  }

  private static boolean detectCpuTimeSupported() {
    try {
      return THREAD_MX.isCurrentThreadCpuTimeSupported();
    } catch (UnsupportedOperationException | SecurityException e) {
      return false;
    }
  }

  private static long saturatingAdd(long a, long b) {
    long sum = a + b;
    if (((a ^ sum) & (b ^ sum)) < 0L) {
      return Long.MAX_VALUE;
    }
    return sum;
  }
}
