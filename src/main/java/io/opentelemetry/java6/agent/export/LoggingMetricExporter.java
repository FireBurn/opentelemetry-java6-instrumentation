/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.java6.agent.export;

import io.opentelemetry.java6.agent.metrics.MetricData;
import io.opentelemetry.java6.agent.metrics.MetricExporter;
import java.util.List;
import java.util.logging.Logger;

/**
 * {@code otel.metrics.exporter=logging|console}: logs each metric on the {@code
 * io.opentelemetry.exporter.logging.LoggingMetricExporter} JUL logger, as the main project.
 */
public final class LoggingMetricExporter implements MetricExporter {

  private static final Logger logger =
      Logger.getLogger("io.opentelemetry.exporter.logging.LoggingMetricExporter");

  public void export(List<MetricData> metrics) {
    logger.info("Received a collection of " + metrics.size() + " metrics for export.");
    for (int i = 0; i < metrics.size(); i++) {
      MetricData m = metrics.get(i);
      StringBuilder sb = new StringBuilder("metric: ");
      sb.append(m.name).append(" [").append(m.scope).append("] unit=").append(m.unit)
          .append(" type=").append(m.type == MetricData.HISTOGRAM ? "HISTOGRAM"
              : m.type == MetricData.SUM ? "SUM" : "GAUGE");
      for (MetricData.Point p : m.points) {
        sb.append("\n  ").append(p.attributes).append(' ');
        if (m.type == MetricData.HISTOGRAM) {
          sb.append("count=").append(p.count).append(" sum=").append(p.sum);
        } else {
          sb.append(p.isDouble ? String.valueOf(p.doubleValue) : String.valueOf(p.longValue));
        }
      }
      logger.info(sb.toString());
    }
  }
}
