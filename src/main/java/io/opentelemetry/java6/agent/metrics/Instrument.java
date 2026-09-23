/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.java6.agent.metrics;

import java.util.List;

/** Base of all instruments. */
abstract class Instrument {

  final String scope;
  final String scopeVersion;
  final String name;
  final String description;
  final String unit;
  final int type;
  final boolean monotonic;
  /** Whether a delta temporality preference applies to this instrument. */
  final boolean deltaEligible;

  Instrument(
      String scope,
      String scopeVersion,
      String name,
      String description,
      String unit,
      int type,
      boolean monotonic,
      boolean deltaEligible) {
    this.scope = scope;
    this.scopeVersion = scopeVersion;
    this.name = name;
    this.description = description;
    this.unit = unit;
    this.type = type;
    this.monotonic = monotonic;
    this.deltaEligible = deltaEligible;
  }

  /** Collects the points; {@code start} is the cumulative or delta interval start. */
  abstract List<MetricData.Point> collect(boolean delta, long start, long now);
}
