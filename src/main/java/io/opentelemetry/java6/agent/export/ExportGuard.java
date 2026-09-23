/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.java6.agent.export;

/**
 * Marks the current thread as exporting telemetry, so instrumentation (HttpURLConnection) does not
 * trace the agent's own OTLP requests - including exports from the shutdown hook, which do not run
 * on the export worker threads.
 */
public final class ExportGuard {

  private static final ThreadLocal<Boolean> EXPORTING = new ThreadLocal<Boolean>();

  private ExportGuard() {}

  public static boolean isExporting() {
    return Boolean.TRUE.equals(EXPORTING.get());
  }

  /** Enters an export section; returns the previous state for {@link #exit}. */
  static boolean enter() {
    boolean previous = isExporting();
    EXPORTING.set(Boolean.TRUE);
    return previous;
  }

  static void exit(boolean previous) {
    if (!previous) {
      EXPORTING.remove();
    }
  }
}
