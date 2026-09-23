/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.java6.agent.metrics;

import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Collects and exports all metrics every {@code otel.metric.export.interval} (default 60s) on a
 * daemon thread, and once more at shutdown, as the main SDK's periodic reader.
 */
public final class PeriodicMetricReader {

  private static final Logger logger = Logger.getLogger("io.opentelemetry.java6.agent");

  private final MetricExporter exporter;
  private final long intervalMs;
  private final Thread worker;
  private volatile boolean running = true;

  public PeriodicMetricReader(MetricExporter exporter, long intervalMs) {
    this.exporter = exporter;
    this.intervalMs = intervalMs;
    this.worker =
        new Thread(
            new Runnable() {
              public void run() {
                loop();
              }
            },
            "otel-java6-metric-reader");
    this.worker.setDaemon(true);
    this.worker.start();
  }

  private void loop() {
    while (running) {
      try {
        Thread.sleep(intervalMs);
      } catch (InterruptedException e) {
        return;
      }
      if (running) {
        exportNow();
      }
    }
  }

  private synchronized void exportNow() {
    try {
      List<MetricData> metrics = MeterRegistry.collect();
      if (!metrics.isEmpty()) {
        exporter.export(metrics);
      }
    } catch (Throwable t) {
      logger.log(Level.WARNING, "metric export failed", t);
    }
  }

  public void shutdown() {
    running = false;
    worker.interrupt();
    exportNow();
  }
}
