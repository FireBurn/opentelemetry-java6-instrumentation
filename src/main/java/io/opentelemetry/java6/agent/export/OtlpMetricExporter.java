/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.java6.agent.export;

import io.opentelemetry.java6.agent.metrics.Attrs;
import io.opentelemetry.java6.agent.metrics.LongCounter;
import io.opentelemetry.java6.agent.metrics.MeterRegistry;
import io.opentelemetry.java6.agent.metrics.MetricData;
import io.opentelemetry.java6.agent.metrics.MetricExporter;
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.metrics.v1.AggregationTemporality;
import io.opentelemetry.proto.metrics.v1.Exemplar;
import io.opentelemetry.proto.metrics.v1.Gauge;
import io.opentelemetry.proto.metrics.v1.Histogram;
import io.opentelemetry.proto.metrics.v1.HistogramDataPoint;
import io.opentelemetry.proto.metrics.v1.Metric;
import io.opentelemetry.proto.metrics.v1.NumberDataPoint;
import io.opentelemetry.proto.metrics.v1.ResourceMetrics;
import io.opentelemetry.proto.metrics.v1.ScopeMetrics;
import io.opentelemetry.proto.metrics.v1.Sum;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** OTLP/HTTP protobuf metric exporter ({@code /v1/metrics}). */
public final class OtlpMetricExporter implements MetricExporter {

  private static final String SELF_SCOPE = "io.opentelemetry.exporters.otlp-http";
  private static final Attrs SEEN = Attrs.of("type", "metric");
  private static final Attrs SUCCESS = Attrs.of("type", "metric", "success", Boolean.TRUE);
  private static final Attrs FAILURE = Attrs.of("type", "metric", "success", Boolean.FALSE);

  private final HttpSender sender;
  private final Map<String, Object> resource;
  private final LongCounter seen;
  private final LongCounter exported;

  public OtlpMetricExporter(HttpSender sender, Map<String, Object> resource) {
    this.sender = sender;
    this.resource = resource;
    // the same instruments as the span exporter's (one set per scope, as in the main SDK)
    this.seen = MeterRegistry.counter(SELF_SCOPE, null, "otlp.exporter.seen", "", "");
    this.exported = MeterRegistry.counter(SELF_SCOPE, null, "otlp.exporter.exported", "", "");
  }

  public void export(List<MetricData> metrics) {
    seen.add(metrics.size(), SEEN);
    boolean ok = sender.post(encode(metrics).toByteArray());
    exported.add(metrics.size(), ok ? SUCCESS : FAILURE);
  }

  ExportMetricsServiceRequest encode(List<MetricData> metrics) {
    Map<String, ScopeMetrics.Builder> scopes = new LinkedHashMap<String, ScopeMetrics.Builder>();
    for (int i = 0; i < metrics.size(); i++) {
      MetricData m = metrics.get(i);
      String key = m.scope + "|" + m.scopeVersion;
      ScopeMetrics.Builder scope = scopes.get(key);
      if (scope == null) {
        scope = ScopeMetrics.newBuilder().setScope(Otlp.scope(m.scope, m.scopeVersion));
        scopes.put(key, scope);
      }
      scope.addMetrics(metric(m));
    }
    ResourceMetrics.Builder rm = ResourceMetrics.newBuilder().setResource(Otlp.resource(resource));
    for (ScopeMetrics.Builder scope : scopes.values()) {
      rm.addScopeMetrics(scope);
    }
    return ExportMetricsServiceRequest.newBuilder().addResourceMetrics(rm).build();
  }

  private static Metric metric(MetricData m) {
    Metric.Builder b = Metric.newBuilder().setName(m.name).setUnit(m.unit == null ? "" : m.unit);
    if (m.description != null) {
      b.setDescription(m.description);
    }
    AggregationTemporality temporality =
        m.delta
            ? AggregationTemporality.AGGREGATION_TEMPORALITY_DELTA
            : AggregationTemporality.AGGREGATION_TEMPORALITY_CUMULATIVE;
    if (m.type == MetricData.HISTOGRAM) {
      Histogram.Builder h = Histogram.newBuilder().setAggregationTemporality(temporality);
      for (MetricData.Point p : m.points) {
        h.addDataPoints(histogramPoint(p));
      }
      b.setHistogram(h);
    } else if (m.type == MetricData.SUM) {
      Sum.Builder s =
          Sum.newBuilder().setAggregationTemporality(temporality).setIsMonotonic(m.monotonic);
      for (MetricData.Point p : m.points) {
        s.addDataPoints(numberPoint(p));
      }
      b.setSum(s);
    } else {
      Gauge.Builder g = Gauge.newBuilder();
      for (MetricData.Point p : m.points) {
        g.addDataPoints(numberPoint(p));
      }
      b.setGauge(g);
    }
    return b.build();
  }

  private static NumberDataPoint numberPoint(MetricData.Point p) {
    final NumberDataPoint.Builder b =
        NumberDataPoint.newBuilder().setTimeUnixNano(p.epochNanos);
    if (p.startEpochNanos != 0) {
      b.setStartTimeUnixNano(p.startEpochNanos);
    }
    if (p.isDouble) {
      b.setAsDouble(p.doubleValue);
    } else {
      b.setAsInt(p.longValue);
    }
    Otlp.addAll(p.attributes.asMap(), new Otlp.Adder() {
      public void add(KeyValue kv) {
        b.addAttributes(kv);
      }
    });
    return b.build();
  }

  private static HistogramDataPoint histogramPoint(MetricData.Point p) {
    final HistogramDataPoint.Builder b =
        HistogramDataPoint.newBuilder()
            .setStartTimeUnixNano(p.startEpochNanos)
            .setTimeUnixNano(p.epochNanos)
            .setCount(p.count)
            .setSum(p.sum);
    if (p.count > 0) {
      b.setMin(p.min).setMax(p.max);
    }
    for (int i = 0; i < p.bounds.length; i++) {
      b.addExplicitBounds(p.bounds[i]);
    }
    for (int i = 0; i < p.counts.length; i++) {
      b.addBucketCounts(p.counts[i]);
    }
    for (MetricData.Exemplar e : p.exemplars) {
      b.addExemplars(
          Exemplar.newBuilder()
              .setTimeUnixNano(e.epochNanos)
              .setAsDouble(e.value)
              .setTraceId(Otlp.id(e.traceId))
              .setSpanId(Otlp.id(e.spanId)));
    }
    Otlp.addAll(p.attributes.asMap(), new Otlp.Adder() {
      public void add(KeyValue kv) {
        b.addAttributes(kv);
      }
    });
    return b.build();
  }
}
