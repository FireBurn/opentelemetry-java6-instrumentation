/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.java6.agent.trace;

import io.opentelemetry.java6.agent.context.SpanContext;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The recording span. State is guarded by this object's monitor (spans are short-lived and
 * low-contention). Limits follow the main SDK's defaults: 128 attributes and 128 events per span.
 *
 * <p>Timestamps have nanosecond precision: {@code System.nanoTime()} is anchored to the Unix
 * epoch once, as the main SDK's clock does.
 */
final class ReadWriteSpan implements Span {

  static final int MAX_ATTRIBUTES = 128;
  static final int MAX_EVENTS = 128;

  /** {@code currentTimeMillis * 1e6 - nanoTime} at class init; nanoTime + offset = epoch nanos. */
  private static final long EPOCH_OFFSET_NANOS =
      System.currentTimeMillis() * 1000000L - System.nanoTime();

  static long nowEpochNanos() {
    return System.nanoTime() + EPOCH_OFFSET_NANOS;
  }

  private final SpanKind kind;
  private final SpanContext context;
  private final String parentSpanId;
  private final String scopeName;
  private final long startNanos;
  private final BatchSpanProcessor processor;
  private final Map<String, Object> attributes;

  private String name;
  private int droppedAttributes;
  private List<SpanData.Event> events;
  private int statusCode = STATUS_UNSET;
  private String statusDescription;
  private boolean ended;

  ReadWriteSpan(
      String name,
      SpanKind kind,
      SpanContext context,
      String parentSpanId,
      String scopeName,
      Map<String, Object> attributes,
      BatchSpanProcessor processor) {
    this.name = name;
    this.kind = kind;
    this.context = context;
    this.parentSpanId = parentSpanId;
    this.scopeName = scopeName;
    this.processor = processor;
    this.startNanos = nowEpochNanos();
    this.attributes = new LinkedHashMap<String, Object>();
    for (Map.Entry<String, Object> e : attributes.entrySet()) {
      put(e.getKey(), e.getValue());
    }
    // as the standard agent's AddThreadDetailsSpanProcessor
    Thread current = Thread.currentThread();
    put("thread.id", Long.valueOf(current.getId()));
    put("thread.name", current.getName());
  }

  private void put(String key, Object value) {
    if (key == null || value == null) {
      return;
    }
    if (attributes.size() >= MAX_ATTRIBUTES && !attributes.containsKey(key)) {
      droppedAttributes++;
      return;
    }
    attributes.put(key, value);
  }

  public SpanContext getSpanContext() {
    return context;
  }

  public synchronized void setAttribute(String key, String value) {
    if (!ended) {
      put(key, value);
    }
  }

  public synchronized void setAttribute(String key, long value) {
    if (!ended) {
      put(key, Long.valueOf(value));
    }
  }

  public synchronized void setAttribute(String key, double value) {
    if (!ended) {
      put(key, Double.valueOf(value));
    }
  }

  public synchronized void setAttribute(String key, boolean value) {
    if (!ended) {
      put(key, Boolean.valueOf(value));
    }
  }

  public synchronized void setAttribute(String key, String[] value) {
    if (!ended) {
      put(key, value);
    }
  }

  public synchronized void recordException(Throwable throwable) {
    if (ended || throwable == null) {
      return;
    }
    Map<String, Object> attrs = new LinkedHashMap<String, Object>();
    attrs.put("exception.type", throwable.getClass().getName());
    if (throwable.getMessage() != null) {
      attrs.put("exception.message", throwable.getMessage());
    }
    StringWriter sw = new StringWriter();
    PrintWriter pw = new PrintWriter(sw);
    throwable.printStackTrace(pw);
    pw.flush();
    attrs.put("exception.stacktrace", sw.toString());
    if (events == null) {
      events = new ArrayList<SpanData.Event>(2);
    }
    if (events.size() < MAX_EVENTS) {
      events.add(new SpanData.Event("exception", nowEpochNanos(), attrs));
    }
  }

  public synchronized void setStatus(int code, String description) {
    // as the main SDK: once OK, the status is final
    if (ended || statusCode == STATUS_OK) {
      return;
    }
    statusCode = code;
    statusDescription = code == STATUS_ERROR ? description : null;
  }

  public synchronized void updateName(String name) {
    if (!ended && name != null) {
      this.name = name;
    }
  }

  public synchronized boolean isRecording() {
    return !ended;
  }

  public void end() {
    SpanData data;
    synchronized (this) {
      if (ended) {
        return;
      }
      ended = true;
      List<SpanData.Event> e =
          events == null
              ? Collections.<SpanData.Event>emptyList()
              : Collections.unmodifiableList(events);
      data =
          new SpanData(
              name,
              kind,
              context.traceId,
              context.spanId,
              parentSpanId,
              context.traceState,
              scopeName,
              startNanos,
              nowEpochNanos(),
              attributes,
              droppedAttributes,
              e,
              statusCode,
              statusDescription);
    }
    processor.onEnd(data);
  }
}
