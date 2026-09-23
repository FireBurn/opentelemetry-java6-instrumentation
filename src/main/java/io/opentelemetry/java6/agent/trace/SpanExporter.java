/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.java6.agent.trace;

import java.util.List;

/** Exports finished spans. Implementations must not throw. */
public interface SpanExporter {

  void export(List<SpanData> spans);

  void shutdown();
}
