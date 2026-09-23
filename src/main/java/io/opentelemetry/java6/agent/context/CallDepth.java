/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.java6.agent.context;

/**
 * Per-thread call depth counter, as the main agent's {@code CallDepth}: only the outermost call of
 * a nested chain (wrapper statement -> driver statement, response wrapper -> container response,
 * filter -> servlet) is instrumented. One instance per instrumented API.
 */
public final class CallDepth {

  public static final CallDepth JDBC_STATEMENT = new CallDepth();
  public static final CallDepth HTTP_URL_CONNECTION = new CallDepth();
  public static final CallDepth SERVLET = new CallDepth();
  public static final CallDepth SERVLET_RESPONSE = new CallDepth();

  private final ThreadLocal<int[]> depth =
      new ThreadLocal<int[]>() {
        @Override
        protected int[] initialValue() {
          return new int[1];
        }
      };

  private CallDepth() {}

  /** Increments the depth and returns the value before incrementing. */
  public int getAndIncrement() {
    int[] d = depth.get();
    return d[0]++;
  }

  /** Decrements the depth and returns the new value. */
  public int decrementAndGet() {
    int[] d = depth.get();
    if (d[0] > 0) {
      d[0]--;
    }
    return d[0];
  }
}
