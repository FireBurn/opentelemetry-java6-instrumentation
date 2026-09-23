/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.java6.agent.trace;

import io.opentelemetry.java6.agent.context.Context;
import io.opentelemetry.java6.agent.context.SpanContext;
import java.util.LinkedHashMap;
import java.util.Map;

final class SpanBuilderImpl implements SpanBuilder {

  private final String name;
  private final Tracer tracer;
  private Context parent;
  private SpanKind kind = SpanKind.INTERNAL;
  private String scopeName = "io.opentelemetry.java6.agent";
  private final Map<String, Object> attributes = new LinkedHashMap<String, Object>();

  SpanBuilderImpl(String name, Tracer tracer) {
    this.name = name;
    this.tracer = tracer;
  }

  public SpanBuilder setParent(Context parent) {
    this.parent = parent;
    return this;
  }

  public SpanBuilder setSpanKind(SpanKind kind) {
    if (kind != null) {
      this.kind = kind;
    }
    return this;
  }

  private SpanBuilder put(String key, Object value) {
    if (key != null && value != null) {
      attributes.put(key, value);
    }
    return this;
  }

  public SpanBuilder setAttribute(String key, String value) {
    return put(key, value);
  }

  public SpanBuilder setAttribute(String key, long value) {
    return put(key, Long.valueOf(value));
  }

  public SpanBuilder setAttribute(String key, double value) {
    return put(key, Double.valueOf(value));
  }

  public SpanBuilder setAttribute(String key, boolean value) {
    return put(key, Boolean.valueOf(value));
  }

  public SpanBuilder setAttribute(String key, String[] value) {
    return put(key, value);
  }

  public SpanBuilder setScopeName(String scopeName) {
    if (scopeName != null) {
      this.scopeName = scopeName;
    }
    return this;
  }

  public Span startSpan() {
    Context parentContext = parent != null ? parent : Context.current();
    SpanContext parentSpanContext = parentContext.spanContext();
    // a child continues the parent's trace (and trace state); a root span starts a new trace
    String traceId = parentSpanContext == null ? IdGenerator.traceId() : parentSpanContext.traceId;
    String traceState = parentSpanContext == null ? null : parentSpanContext.traceState;
    boolean sampled =
        tracer.processor != null && tracer.sampler.shouldSample(parentSpanContext, traceId);
    SpanContext context =
        new SpanContext(traceId, IdGenerator.spanId(), sampled, false, traceState);
    if (!sampled) {
      return new NonRecordingSpan(context);
    }
    return new ReadWriteSpan(
        name,
        kind,
        context,
        parentSpanContext == null ? null : parentSpanContext.spanId,
        scopeName,
        attributes,
        tracer.processor);
  }
}
