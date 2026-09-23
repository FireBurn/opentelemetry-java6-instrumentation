/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.java6.agent.context;

/**
 * Immutable span identifiers, following W3C Trace Context: 128-bit trace id and 64-bit span id as
 * lowercase hex, the sampled flag, whether the context came from a remote process, and the W3C
 * {@code tracestate} (propagated unchanged, as the main SDK does).
 */
public final class SpanContext {

  public final String traceId;
  public final String spanId;
  public final boolean sampled;
  public final boolean remote;
  /** Raw W3C tracestate value, or {@code null}. */
  public final String traceState;

  public SpanContext(String traceId, String spanId, boolean sampled) {
    this(traceId, spanId, sampled, false, null);
  }

  public SpanContext(
      String traceId, String spanId, boolean sampled, boolean remote, String traceState) {
    this.traceId = traceId;
    this.spanId = spanId;
    this.sampled = sampled;
    this.remote = remote;
    this.traceState = traceState;
  }

  @Override
  public String toString() {
    return "SpanContext{traceId=" + traceId + ", spanId=" + spanId + ", sampled=" + sampled
        + ", remote=" + remote + "}";
  }
}
