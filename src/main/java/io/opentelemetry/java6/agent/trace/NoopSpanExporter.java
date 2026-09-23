/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.java6.agent.trace;

import java.util.List;

/** Discards all spans. */
public final class NoopSpanExporter implements SpanExporter {

  public void export(List<SpanData> spans) {}

  public void shutdown() {}
}
