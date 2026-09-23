/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.java6.agent.trace;

import io.opentelemetry.java6.agent.context.SpanContext;

/**
 * A span. Attribute values keep their OTLP type: {@code String}, {@code long}, {@code double},
 * {@code boolean} or a {@code String[]} array.
 */
public interface Span {

  int STATUS_UNSET = 0;
  int STATUS_OK = 1;
  int STATUS_ERROR = 2;

  SpanContext getSpanContext();

  void setAttribute(String key, String value);

  void setAttribute(String key, long value);

  void setAttribute(String key, double value);

  void setAttribute(String key, boolean value);

  void setAttribute(String key, String[] value);

  /** Records an {@code exception} event (does not change the status, as in the main SDK). */
  void recordException(Throwable throwable);

  /** Sets the status ({@link #STATUS_UNSET}, {@link #STATUS_OK}, {@link #STATUS_ERROR}). */
  void setStatus(int statusCode, String description);

  void updateName(String name);

  void end();

  boolean isRecording();
}
