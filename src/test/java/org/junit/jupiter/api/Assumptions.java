/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.junit.jupiter.api;

/** Java 6 stand-in for JUnit 5's {@code Assumptions}: a failed assumption skips the test. */
public final class Assumptions {
  private Assumptions() {}

  /** Thrown to abort (skip) a test. */
  public static final class Aborted extends RuntimeException {
    private static final long serialVersionUID = 1L;
  }

  public static void assumeTrue(boolean condition) {
    if (!condition) {
      throw new Aborted();
    }
  }

  public static void assumeFalse(boolean condition) {
    assumeTrue(!condition);
  }
}
