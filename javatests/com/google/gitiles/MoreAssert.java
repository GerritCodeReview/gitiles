package com.google.gitiles;

import static org.junit.Assert.fail;

public class MoreAssert {
  private MoreAssert() {}

  /** Simple version of assertThrows that will be introduced in JUnit 4.13. */
  public static <T extends Throwable> T assertThrows(Class<T> expected, ThrowingRunnable r) {
    try {
      r.run();
      throw new AssertionError("Expected " + expected.getSimpleName() + " to be thrown");
    } catch (Throwable actual) {
      if (expected.isAssignableFrom(actual.getClass())) {
        return (T) actual;
      }
      throw new AssertionError("Expected " + expected.getSimpleName() + ", but got " + actual.getClass().getSimpleName(), actual);
    }
  }

  public interface ThrowingRunnable {
    void run() throws Throwable;
  }
}
