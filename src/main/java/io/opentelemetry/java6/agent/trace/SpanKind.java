/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.java6.agent.trace;

/** Span kind, mirroring the OpenTelemetry span kinds. */
public enum SpanKind {
  CLIENT("CLIENT"),
  SERVER("SERVER"),
  INTERNAL("INTERNAL"),
  PRODUCER("PRODUCER"),
  CONSUMER("CONSUMER");

  private final String zipkinName;

  SpanKind(String zipkinName) {
    this.zipkinName = zipkinName;
  }

  /** Name used in the Zipkin v2 JSON format. */
  public String zipkinName() {
    return zipkinName;
  }
}
