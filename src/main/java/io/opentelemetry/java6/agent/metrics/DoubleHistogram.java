/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.java6.agent.metrics;

import io.opentelemetry.java6.agent.context.Context;
import io.opentelemetry.java6.agent.context.SpanContext;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Explicit-bucket histogram, as the main SDK's default aggregation: upper-inclusive buckets,
 * sum/count/min/max, and one exemplar per bucket (the latest measurement recorded while a sampled
 * span was current - the SDK's default {@code trace_based} exemplar filter with the aligned
 * bucket reservoir).
 */
public final class DoubleHistogram extends Instrument {

  private final double[] bounds;
  private final Map<Attrs, State> states = new HashMap<Attrs, State>();

  private static final class State {
    long count;
    double sum;
    double min = Double.POSITIVE_INFINITY;
    double max = Double.NEGATIVE_INFINITY;
    final long[] counts;
    final MetricData.Exemplar[] exemplars;

    State(int buckets) {
      counts = new long[buckets];
      exemplars = new MetricData.Exemplar[buckets];
    }
  }

  DoubleHistogram(
      String scope, String scopeVersion, String name, String description, String unit,
      double[] bounds) {
    super(scope, scopeVersion, name, description, unit, MetricData.HISTOGRAM, true, true);
    this.bounds = bounds;
  }

  /** Records with the current span (if sampled) as the exemplar. */
  public void record(double value, Attrs attributes) {
    record(value, attributes, Context.current().spanContext());
  }

  /** Records with {@code exemplarContext} (if sampled) as the exemplar. */
  public void record(double value, Attrs attributes, SpanContext exemplarContext) {
    if (Double.isNaN(value) || value < 0) {
      return;
    }
    int bucket = Arrays.binarySearch(bounds, value);
    if (bucket < 0) {
      bucket = -bucket - 1;
    }
    MetricData.Exemplar exemplar = null;
    SpanContext sc = exemplarContext;
    if (sc != null && sc.sampled) {
      exemplar =
          new MetricData.Exemplar(value, MeterRegistry.nowEpochNanos(), sc.traceId, sc.spanId);
    }
    synchronized (states) {
      State s = states.get(attributes);
      if (s == null) {
        s = new State(bounds.length + 1);
        states.put(attributes, s);
      }
      s.count++;
      s.sum += value;
      s.min = Math.min(s.min, value);
      s.max = Math.max(s.max, value);
      s.counts[bucket]++;
      if (exemplar != null) {
        s.exemplars[bucket] = exemplar;
      }
    }
  }

  List<MetricData.Point> collect(boolean delta, long start, long now) {
    List<MetricData.Point> points = new ArrayList<MetricData.Point>();
    synchronized (states) {
      for (Iterator<Map.Entry<Attrs, State>> it = states.entrySet().iterator(); it.hasNext(); ) {
        Map.Entry<Attrs, State> e = it.next();
        State s = e.getValue();
        List<MetricData.Exemplar> exemplars = new ArrayList<MetricData.Exemplar>(2);
        for (int i = 0; i < s.exemplars.length; i++) {
          if (s.exemplars[i] != null) {
            exemplars.add(s.exemplars[i]);
            s.exemplars[i] = null;
          }
        }
        points.add(
            new MetricData.Point(
                e.getKey(), start, now, true, 0, 0, s.count, s.sum, s.min, s.max, bounds,
                s.counts.clone(), exemplars));
        if (delta) {
          it.remove();
        }
      }
    }
    return points;
  }
}
