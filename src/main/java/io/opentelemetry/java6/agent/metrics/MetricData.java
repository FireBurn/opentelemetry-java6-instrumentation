/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.java6.agent.metrics;

import java.util.List;

/** One exported metric: definition plus the points collected for it. */
public final class MetricData {

  public static final int GAUGE = 0;
  public static final int SUM = 1;
  public static final int HISTOGRAM = 2;

  public final String scope;
  public final String scopeVersion;
  public final String name;
  public final String description;
  public final String unit;
  public final int type;
  public final boolean monotonic;
  public final boolean delta;
  public final List<Point> points;

  MetricData(
      Instrument instrument, boolean delta, List<Point> points) {
    this.scope = instrument.scope;
    this.scopeVersion = instrument.scopeVersion;
    this.name = instrument.name;
    this.description = instrument.description;
    this.unit = instrument.unit;
    this.type = instrument.type;
    this.monotonic = instrument.monotonic;
    this.delta = delta;
    this.points = points;
  }

  /** A data point. Number points use {@code longValue}/{@code doubleValue}; histograms the rest. */
  public static final class Point {
    public final Attrs attributes;
    public final long startEpochNanos;
    public final long epochNanos;
    public final boolean isDouble;
    public final long longValue;
    public final double doubleValue;
    // histogram
    public final long count;
    public final double sum;
    public final double min;
    public final double max;
    public final double[] bounds;
    public final long[] counts;
    public final List<Exemplar> exemplars;

    Point(Attrs attributes, long start, long now, long value) {
      this(attributes, start, now, false, value, 0, 0, 0, 0, 0, null, null, null);
    }

    Point(Attrs attributes, long start, long now, double value) {
      this(attributes, start, now, true, 0, value, 0, 0, 0, 0, null, null, null);
    }

    Point(
        Attrs attributes,
        long start,
        long now,
        boolean isDouble,
        long longValue,
        double doubleValue,
        long count,
        double sum,
        double min,
        double max,
        double[] bounds,
        long[] counts,
        List<Exemplar> exemplars) {
      this.attributes = attributes;
      this.startEpochNanos = start;
      this.epochNanos = now;
      this.isDouble = isDouble;
      this.longValue = longValue;
      this.doubleValue = doubleValue;
      this.count = count;
      this.sum = sum;
      this.min = min;
      this.max = max;
      this.bounds = bounds;
      this.counts = counts;
      this.exemplars = exemplars;
    }
  }

  /** A histogram exemplar: a measurement recorded while a sampled span was current. */
  public static final class Exemplar {
    public final double value;
    public final long epochNanos;
    public final String traceId;
    public final String spanId;

    Exemplar(double value, long epochNanos, String traceId, String spanId) {
      this.value = value;
      this.epochNanos = epochNanos;
      this.traceId = traceId;
      this.spanId = spanId;
    }
  }
}
