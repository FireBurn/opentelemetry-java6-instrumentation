/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.java6.agent.metrics;

/** An asynchronous instrument callback, invoked on each collection. */
public interface Callback {

  void collect(Recorder recorder);
}
