/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.java6.agent.export;

import io.opentelemetry.java6.agent.trace.Span;
import io.opentelemetry.java6.agent.trace.SpanData;
import io.opentelemetry.java6.agent.trace.SpanExporter;
import io.opentelemetry.java6.agent.trace.SpanKind;
import java.util.List;
import java.util.Map;

/**
 * Zipkin v2 JSON exporter ({@code otel.traces.exporter=zipkin}), mapping spans as the main
 * project's Zipkin exporter: attributes and {@code otel.scope.*} as tags, error status as {@code
 * otel.status_code=ERROR} plus {@code error}.
 */
public final class ZipkinSpanExporter implements SpanExporter {

  private final HttpSender sender;
  private final String serviceName;
  private final String scopeVersion;

  public ZipkinSpanExporter(HttpSender sender, String serviceName, String agentVersion) {
    this.sender = sender;
    this.serviceName = serviceName;
    this.scopeVersion = agentVersion + "-alpha";
  }

  public void export(List<SpanData> spans) {
    try {
      sender.post(toJson(spans).getBytes("UTF-8"));
    } catch (java.io.UnsupportedEncodingException e) {
      // UTF-8 always exists
    }
  }

  String toJson(List<SpanData> spans) {
    StringBuilder sb = new StringBuilder(spans.size() * 256);
    sb.append('[');
    for (int i = 0; i < spans.size(); i++) {
      SpanData span = spans.get(i);
      sb.append(i == 0 ? "{" : ",{");
      sb.append("\"traceId\":").append(quote(span.traceId));
      sb.append(",\"id\":").append(quote(span.spanId));
      if (span.parentSpanId != null) {
        sb.append(",\"parentId\":").append(quote(span.parentSpanId));
      }
      sb.append(",\"name\":").append(quote(span.name));
      // zipkin uses microseconds since the epoch
      sb.append(",\"timestamp\":").append(span.startEpochNanos / 1000L);
      sb.append(",\"duration\":").append(Math.max(1L, span.durationNanos() / 1000L));
      if (span.kind != SpanKind.INTERNAL) {
        sb.append(",\"kind\":").append(quote(span.kind.zipkinName()));
      }
      sb.append(",\"localEndpoint\":{\"serviceName\":").append(quote(serviceName)).append('}');
      sb.append(",\"tags\":{");
      sb.append("\"otel.scope.name\":").append(quote(span.scopeName));
      sb.append(",\"otel.scope.version\":").append(quote(scopeVersion));
      for (Map.Entry<String, Object> entry : span.attributes.entrySet()) {
        sb.append(',').append(quote(entry.getKey())).append(':').append(quote(tag(entry.getValue())));
      }
      if (span.statusCode == Span.STATUS_ERROR) {
        sb.append(",\"otel.status_code\":\"ERROR\",\"error\":")
            .append(quote(span.statusDescription == null ? "" : span.statusDescription));
      }
      sb.append("}}");
    }
    return sb.append(']').toString();
  }

  private static String tag(Object value) {
    if (value instanceof String[]) {
      StringBuilder sb = new StringBuilder();
      String[] values = (String[]) value;
      for (int i = 0; i < values.length; i++) {
        sb.append(i == 0 ? "" : ",").append(values[i]);
      }
      return sb.toString();
    }
    return String.valueOf(value);
  }

  private static String quote(String value) {
    StringBuilder sb = new StringBuilder(value.length() + 2);
    sb.append('"');
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c == '"' || c == '\\') {
        sb.append('\\').append(c);
      } else if (c < 0x20) {
        sb.append(String.format("\\u%04x", Integer.valueOf(c)));
      } else {
        sb.append(c);
      }
    }
    return sb.append('"').toString();
  }

  public void shutdown() {}
}
