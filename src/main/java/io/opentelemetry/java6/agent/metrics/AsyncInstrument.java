/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.java6.agent.metrics;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Asynchronous gauge or sum: values are observed by a callback at collection time. For a delta
 * monotonic sum the previous observation is subtracted.
 */
final class AsyncInstrument extends Instrument {

  private static final Logger logger = Logger.getLogger("io.opentelemetry.java6.agent");

  private final Callback callback;
  private final Map<Attrs, Number> previous = new HashMap<Attrs, Number>();

  AsyncInstrument(
      String scope, String scopeVersion, String name, String description, String unit, int type,
      boolean monotonic, Callback callback) {
    super(scope, scopeVersion, name, description, unit, type, monotonic,
        type == MetricData.SUM && monotonic);
    this.callback = callback;
  }

  List<MetricData.Point> collect(
      final boolean delta, final long start, final long now) {
    final List<MetricData.Point> points = new ArrayList<MetricData.Point>();
    final long pointStart = type == MetricData.GAUGE ? 0 : start;
    try {
      callback.collect(
          new Recorder() {
            public void record(long value, Attrs attributes) {
              long v = value;
              if (delta) {
                Number prev = previous.put(attributes, Long.valueOf(value));
                v = prev == null ? value : value - prev.longValue();
              }
              points.add(new MetricData.Point(attributes, pointStart, now, v));
            }

            public void record(double value, Attrs attributes) {
              double v = value;
              if (delta) {
                Number prev = previous.put(attributes, Double.valueOf(value));
                v = prev == null ? value : value - prev.doubleValue();
              }
              points.add(new MetricData.Point(attributes, pointStart, now, v));
            }
          });
    } catch (Throwable t) {
      logger.log(Level.FINE, "metric callback failed for " + name, t);
    }
    return points;
  }
}
