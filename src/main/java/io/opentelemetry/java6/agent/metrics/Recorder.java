/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.java6.agent.metrics;

/** Receives the measurements of an asynchronous instrument's callback. */
public interface Recorder {

  void record(long value, Attrs attributes);

  void record(double value, Attrs attributes);
}
