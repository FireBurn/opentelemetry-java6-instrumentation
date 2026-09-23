/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.java6.agent.trace;

import java.util.List;
import java.util.Map;

/** Immutable snapshot of a finished span, as handed to exporters. */
public final class SpanData {

  /** A span event (for example {@code exception}). */
  public static final class Event {
    public final String name;
    public final long epochNanos;
    public final Map<String, Object> attributes;

    public Event(String name, long epochNanos, Map<String, Object> attributes) {
      this.name = name;
      this.epochNanos = epochNanos;
      this.attributes = attributes;
    }
  }

  public final String name;
  public final SpanKind kind;
  public final String traceId;
  public final String spanId;
  /** Parent span id, or {@code null} for a root span. */
  public final String parentSpanId;
  public final String traceState;
  /** Instrumentation scope name (for example {@code io.opentelemetry.jdbc}). */
  public final String scopeName;
  public final long startEpochNanos;
  public final long endEpochNanos;
  /** Typed attribute values: String, Long, Double, Boolean or String[]. */
  public final Map<String, Object> attributes;
  public final int droppedAttributes;
  public final List<Event> events;
  /** One of {@link Span#STATUS_UNSET}, {@link Span#STATUS_OK}, {@link Span#STATUS_ERROR}. */
  public final int statusCode;
  public final String statusDescription;

  public SpanData(
      String name,
      SpanKind kind,
      String traceId,
      String spanId,
      String parentSpanId,
      String traceState,
      String scopeName,
      long startEpochNanos,
      long endEpochNanos,
      Map<String, Object> attributes,
      int droppedAttributes,
      List<Event> events,
      int statusCode,
      String statusDescription) {
    this.name = name;
    this.kind = kind;
    this.traceId = traceId;
    this.spanId = spanId;
    this.parentSpanId = parentSpanId;
    this.traceState = traceState;
    this.scopeName = scopeName;
    this.startEpochNanos = startEpochNanos;
    this.endEpochNanos = endEpochNanos;
    this.attributes = attributes;
    this.droppedAttributes = droppedAttributes;
    this.events = events;
    this.statusCode = statusCode;
    this.statusDescription = statusDescription;
  }

  public long durationNanos() {
    return endEpochNanos - startEpochNanos;
  }
}
