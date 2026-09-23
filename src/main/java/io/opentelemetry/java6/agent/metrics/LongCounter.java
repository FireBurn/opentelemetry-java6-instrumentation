/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.java6.agent.metrics;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/** Synchronous long sum (monotonic counter or up-down counter). */
public final class LongCounter extends Instrument {

  private final Map<Attrs, long[]> values = new HashMap<Attrs, long[]>();

  LongCounter(
      String scope, String scopeVersion, String name, String description, String unit,
      boolean monotonic) {
    super(scope, scopeVersion, name, description, unit, MetricData.SUM, monotonic, monotonic);
  }

  public void add(long value, Attrs attributes) {
    if (monotonic && value < 0) {
      return;
    }
    synchronized (values) {
      long[] v = values.get(attributes);
      if (v == null) {
        v = new long[1];
        values.put(attributes, v);
      }
      v[0] += value;
    }
  }

  List<MetricData.Point> collect(boolean delta, long start, long now) {
    List<MetricData.Point> points = new ArrayList<MetricData.Point>();
    synchronized (values) {
      for (Iterator<Map.Entry<Attrs, long[]>> it = values.entrySet().iterator(); it.hasNext(); ) {
        Map.Entry<Attrs, long[]> e = it.next();
        points.add(new MetricData.Point(e.getKey(), start, now, e.getValue()[0]));
        if (delta) {
          it.remove();
        }
      }
    }
    return points;
  }
}
