/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.java6.agent.trace;

import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * {@code otel.traces.exporter=logging|console}: one INFO line per span on the {@code
 * io.opentelemetry.exporter.logging.LoggingSpanExporter} JUL logger, in the main project's format
 * ({@code 'name' : traceId spanId KIND [tracer: scope:version] {attributes}}).
 */
public final class LoggingSpanExporter implements SpanExporter {

  private static final Logger logger =
      Logger.getLogger("io.opentelemetry.exporter.logging.LoggingSpanExporter");

  private final String scopeVersion;

  public LoggingSpanExporter(String agentVersion) {
    this.scopeVersion = agentVersion + "-alpha";
  }

  public void export(List<SpanData> spans) {
    for (int i = 0; i < spans.size(); i++) {
      logger.info(format(spans.get(i), scopeVersion));
    }
  }

  static String format(SpanData span, String scopeVersion) {
    StringBuilder sb = new StringBuilder(256);
    sb.append('\'').append(span.name).append("' : ").append(span.traceId).append(' ')
        .append(span.spanId).append(' ').append(span.kind).append(" [tracer: ")
        .append(span.scopeName).append(':').append(scopeVersion).append("] ");
    sb.append("AttributesMap{data={");
    boolean first = true;
    for (Map.Entry<String, Object> e : span.attributes.entrySet()) {
      sb.append(first ? "" : ", ").append(e.getKey()).append('=');
      Object v = e.getValue();
      sb.append(v instanceof String[] ? java.util.Arrays.toString((String[]) v) : String.valueOf(v));
      first = false;
    }
    sb.append("}, capacity=128, totalAddedValues=").append(span.attributes.size()).append('}');
    if (span.parentSpanId != null) {
      sb.append(" parent=").append(span.parentSpanId);
    }
    if (span.statusCode == Span.STATUS_ERROR) {
      sb.append(" status=ERROR");
      if (span.statusDescription != null) {
        sb.append('(').append(span.statusDescription).append(')');
      }
    }
    return sb.toString();
  }

  public void shutdown() {}
}
