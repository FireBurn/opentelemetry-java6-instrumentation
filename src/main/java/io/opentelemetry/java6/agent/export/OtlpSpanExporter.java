/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.java6.agent.export;

import io.opentelemetry.java6.agent.metrics.Attrs;
import io.opentelemetry.java6.agent.metrics.LongCounter;
import io.opentelemetry.java6.agent.metrics.MeterRegistry;
import io.opentelemetry.java6.agent.trace.SpanData;
import io.opentelemetry.java6.agent.trace.SpanExporter;
import io.opentelemetry.java6.agent.trace.SpanKind;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.trace.v1.ResourceSpans;
import io.opentelemetry.proto.trace.v1.ScopeSpans;
import io.opentelemetry.proto.trace.v1.Span;
import io.opentelemetry.proto.trace.v1.Status;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OTLP/HTTP protobuf span exporter. Spans are grouped by instrumentation scope under one resource;
 * the scope version is {@code <agent version>-alpha} as in the standard agent. Records the SDK's
 * own {@code otlp.exporter.seen}/{@code otlp.exporter.exported} metrics.
 */
public final class OtlpSpanExporter implements SpanExporter {

  private static final String SELF_SCOPE = "io.opentelemetry.exporters.otlp-http";
  private static final Attrs SEEN = Attrs.of("type", "span");
  private static final Attrs SUCCESS = Attrs.of("type", "span", "success", Boolean.TRUE);
  private static final Attrs FAILURE = Attrs.of("type", "span", "success", Boolean.FALSE);

  private final HttpSender sender;
  private final Map<String, Object> resource;
  private final String scopeVersion;
  private final LongCounter seen;
  private final LongCounter exported;

  public OtlpSpanExporter(HttpSender sender, Map<String, Object> resource, String agentVersion) {
    this.sender = sender;
    this.resource = resource;
    this.scopeVersion = agentVersion + "-alpha";
    this.seen = MeterRegistry.counter(SELF_SCOPE, null, "otlp.exporter.seen", "", "");
    this.exported = MeterRegistry.counter(SELF_SCOPE, null, "otlp.exporter.exported", "", "");
  }

  public void export(List<SpanData> spans) {
    seen.add(spans.size(), SEEN);
    boolean ok = sender.post(encode(spans).toByteArray());
    exported.add(spans.size(), ok ? SUCCESS : FAILURE);
  }

  ExportTraceServiceRequest encode(List<SpanData> spans) {
    Map<String, List<SpanData>> byScope = new LinkedHashMap<String, List<SpanData>>();
    for (int i = 0; i < spans.size(); i++) {
      SpanData span = spans.get(i);
      List<SpanData> list = byScope.get(span.scopeName);
      if (list == null) {
        list = new java.util.ArrayList<SpanData>();
        byScope.put(span.scopeName, list);
      }
      list.add(span);
    }
    ResourceSpans.Builder resourceSpans =
        ResourceSpans.newBuilder().setResource(Otlp.resource(resource));
    for (Map.Entry<String, List<SpanData>> entry : byScope.entrySet()) {
      ScopeSpans.Builder scopeSpans =
          ScopeSpans.newBuilder().setScope(Otlp.scope(entry.getKey(), scopeVersion));
      for (SpanData span : entry.getValue()) {
        scopeSpans.addSpans(span(span));
      }
      resourceSpans.addScopeSpans(scopeSpans);
    }
    return ExportTraceServiceRequest.newBuilder().addResourceSpans(resourceSpans).build();
  }

  private static Span span(SpanData span) {
    final Span.Builder b =
        Span.newBuilder()
            .setTraceId(Otlp.id(span.traceId))
            .setSpanId(Otlp.id(span.spanId))
            .setName(span.name)
            .setKind(kind(span.kind))
            .setStartTimeUnixNano(span.startEpochNanos)
            .setEndTimeUnixNano(span.endEpochNanos)
            .setDroppedAttributesCount(span.droppedAttributes);
    if (span.parentSpanId != null) {
      b.setParentSpanId(Otlp.id(span.parentSpanId));
    }
    if (span.traceState != null) {
      b.setTraceState(span.traceState);
    }
    Otlp.addAll(span.attributes, new Otlp.Adder() {
      public void add(KeyValue kv) {
        b.addAttributes(kv);
      }
    });
    for (SpanData.Event event : span.events) {
      final Span.Event.Builder e =
          Span.Event.newBuilder().setName(event.name).setTimeUnixNano(event.epochNanos);
      Otlp.addAll(event.attributes, new Otlp.Adder() {
        public void add(KeyValue kv) {
          e.addAttributes(kv);
        }
      });
      b.addEvents(e);
    }
    if (span.statusCode == io.opentelemetry.java6.agent.trace.Span.STATUS_ERROR) {
      Status.Builder status = Status.newBuilder().setCode(Status.StatusCode.STATUS_CODE_ERROR);
      if (span.statusDescription != null) {
        status.setMessage(span.statusDescription);
      }
      b.setStatus(status);
    } else if (span.statusCode == io.opentelemetry.java6.agent.trace.Span.STATUS_OK) {
      b.setStatus(Status.newBuilder().setCode(Status.StatusCode.STATUS_CODE_OK));
    }
    return b.build();
  }

  private static Span.SpanKind kind(SpanKind kind) {
    switch (kind) {
      case CLIENT:
        return Span.SpanKind.SPAN_KIND_CLIENT;
      case SERVER:
        return Span.SpanKind.SPAN_KIND_SERVER;
      case PRODUCER:
        return Span.SpanKind.SPAN_KIND_PRODUCER;
      case CONSUMER:
        return Span.SpanKind.SPAN_KIND_CONSUMER;
      default:
        return Span.SpanKind.SPAN_KIND_INTERNAL;
    }
  }

  public void shutdown() {}
}
