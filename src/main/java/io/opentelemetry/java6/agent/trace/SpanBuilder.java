/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.java6.agent.trace;

import io.opentelemetry.java6.agent.context.Context;

/** Builder for {@link Span}s. The parent defaults to {@link Context#current()}. */
public interface SpanBuilder {

  SpanBuilder setParent(Context parent);

  SpanBuilder setSpanKind(SpanKind kind);

  SpanBuilder setAttribute(String key, String value);

  SpanBuilder setAttribute(String key, long value);

  SpanBuilder setAttribute(String key, double value);

  SpanBuilder setAttribute(String key, boolean value);

  SpanBuilder setAttribute(String key, String[] value);

  /** Sets the instrumentation scope name (for example {@code io.opentelemetry.jdbc}). */
  SpanBuilder setScopeName(String scopeName);

  Span startSpan();
}
