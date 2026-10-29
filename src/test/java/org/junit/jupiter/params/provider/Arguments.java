/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.junit.jupiter.params.provider;

/** Java 6 stand-in for JUnit 5's {@code Arguments}. */
public final class Arguments {
  private final Object[] values;

  private Arguments(Object[] values) {
    this.values = values;
  }

  public static Arguments of(Object... values) {
    return new Arguments(values);
  }

  public static Arguments arguments(Object... values) {
    return new Arguments(values);
  }

  /** The name is only a display label in JUnit 5; the shim ignores it. */
  public static Arguments argumentSet(String name, Object... values) {
    return new Arguments(values);
  }

  public Object[] get() {
    return values;
  }
}
