/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.java6.agent.metrics;

import java.util.List;

/** Exports collected metrics. Implementations must not throw. */
public interface MetricExporter {

  void export(List<MetricData> metrics);
}
