/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.java6.agent.trace;

import io.opentelemetry.java6.agent.context.SpanContext;

/**
 * A span that records nothing but still carries a span context, so an unsampled trace keeps its
 * trace id and propagates {@code sampled=0} downstream (as the main SDK's non-recording spans).
 */
final class NonRecordingSpan implements Span {

  private final SpanContext context;

  NonRecordingSpan(SpanContext context) {
    this.context = context;
  }

  public SpanContext getSpanContext() {
    return context;
  }

  public void setAttribute(String key, String value) {}

  public void setAttribute(String key, long value) {}

  public void setAttribute(String key, double value) {}

  public void setAttribute(String key, boolean value) {}

  public void setAttribute(String key, String[] value) {}

  public void recordException(Throwable throwable) {}

  public void setStatus(int statusCode, String description) {}

  public void updateName(String name) {}

  public void end() {}

  public boolean isRecording() {
    return false;
  }
}
