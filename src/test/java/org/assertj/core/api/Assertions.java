/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.assertj.core.api;

/** Java 6 stand-in for AssertJ's {@code Assertions} entry point (see ObjectAssert). */
public class Assertions {
  protected Assertions() {}

  public static ObjectAssert assertThat(Object actual) {
    return new ObjectAssert(actual);
  }

  public static ObjectAssert assertThat(long actual) {
    return new ObjectAssert(Long.valueOf(actual));
  }

  public static ObjectAssert assertThat(int actual) {
    return new ObjectAssert(Integer.valueOf(actual));
  }

  public static ObjectAssert assertThat(boolean actual) {
    return new ObjectAssert(Boolean.valueOf(actual));
  }

  public static ObjectAssert assertThat(double actual) {
    return new ObjectAssert(Double.valueOf(actual));
  }

  public static void fail(String message) {
    throw new AssertionError(message);
  }
}
