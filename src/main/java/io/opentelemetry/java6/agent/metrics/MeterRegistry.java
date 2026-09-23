/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.java6.agent.metrics;

import java.util.ArrayList;
import java.util.List;

/**
 * Holds every instrument and collects them. Temporality follows {@code
 * otel.exporter.otlp.metrics.temporality.preference} as in the main SDK: {@code cumulative}
 * (default); {@code delta} (counters, histograms and observable counters delta); {@code lowmemory}
 * (synchronous counters and histograms delta). Up-down counters and gauges are always cumulative.
 *
 * <p>Before the agent enables metrics every instrument is still usable; nothing is collected.
 */
public final class MeterRegistry {

  /** Explicit bucket boundaries of the HTTP duration histograms (seconds), as the main agent. */
  public static final double[] HTTP_DURATION_BUCKETS = {
    0.005, 0.01, 0.025, 0.05, 0.075, 0.1, 0.25, 0.5, 0.75, 1, 2.5, 5, 7.5, 10
  };

  private static final long EPOCH_OFFSET_NANOS =
      System.currentTimeMillis() * 1000000L - System.nanoTime();

  private static final List<Instrument> INSTRUMENTS = new ArrayList<Instrument>();
  private static final long START = nowEpochNanos();
  private static volatile String temporality = "cumulative";
  private static long lastCollect = START;

  private MeterRegistry() {}

  static long nowEpochNanos() {
    return System.nanoTime() + EPOCH_OFFSET_NANOS;
  }

  public static void setTemporalityPreference(String preference) {
    if (preference != null) {
      temporality = preference.trim().toLowerCase(java.util.Locale.ROOT);
    }
  }

  private static <T extends Instrument> T add(T instrument) {
    synchronized (INSTRUMENTS) {
      INSTRUMENTS.add(instrument);
    }
    return instrument;
  }

  public static DoubleHistogram histogram(
      String scope, String scopeVersion, String name, String description, String unit,
      double[] bounds) {
    return add(new DoubleHistogram(scope, scopeVersion, name, description, unit, bounds));
  }

  /** A monotonic counter; the same scope and name return the same instrument, as in the SDK. */
  public static LongCounter counter(
      String scope, String scopeVersion, String name, String description, String unit) {
    synchronized (INSTRUMENTS) {
      for (int i = 0; i < INSTRUMENTS.size(); i++) {
        Instrument existing = INSTRUMENTS.get(i);
        if (existing instanceof LongCounter && existing.name.equals(name)
            && existing.scope.equals(scope)) {
          return (LongCounter) existing;
        }
      }
      return add(new LongCounter(scope, scopeVersion, name, description, unit, true));
    }
  }

  /**
   * Removes a scope's instruments. The runtime metrics are closed before the final shutdown
   * collection, as the standard agent closes its runtime-telemetry observers first.
   */
  public static void removeScope(String scope) {
    synchronized (INSTRUMENTS) {
      for (java.util.Iterator<Instrument> it = INSTRUMENTS.iterator(); it.hasNext(); ) {
        if (it.next().scope.equals(scope)) {
          it.remove();
        }
      }
    }
  }

  public static void gauge(
      String scope, String scopeVersion, String name, String description, String unit,
      Callback callback) {
    add(new AsyncInstrument(scope, scopeVersion, name, description, unit, MetricData.GAUGE, false,
        callback));
  }

  public static void asyncCounter(
      String scope, String scopeVersion, String name, String description, String unit,
      Callback callback) {
    add(new AsyncInstrument(scope, scopeVersion, name, description, unit, MetricData.SUM, true,
        callback));
  }

  public static void asyncUpDownCounter(
      String scope, String scopeVersion, String name, String description, String unit,
      Callback callback) {
    add(new AsyncInstrument(scope, scopeVersion, name, description, unit, MetricData.SUM, false,
        callback));
  }

  /** Collects every instrument; instruments without points are left out. */
  public static synchronized List<MetricData> collect() {
    long now = nowEpochNanos();
    long deltaStart = lastCollect;
    lastCollect = now;
    List<Instrument> snapshot;
    synchronized (INSTRUMENTS) {
      snapshot = new ArrayList<Instrument>(INSTRUMENTS);
    }
    String pref = temporality;
    List<MetricData> result = new ArrayList<MetricData>(snapshot.size());
    for (int i = 0; i < snapshot.size(); i++) {
      Instrument instrument = snapshot.get(i);
      boolean delta = false;
      if (instrument.deltaEligible) {
        if ("delta".equals(pref)) {
          delta = true;
        } else if ("lowmemory".equals(pref)) {
          delta = !(instrument instanceof AsyncInstrument);
        }
      }
      List<MetricData.Point> points = instrument.collect(delta, delta ? deltaStart : START, now);
      if (!points.isEmpty()) {
        result.add(new MetricData(instrument, delta, points));
      }
    }
    return result;
  }
}
