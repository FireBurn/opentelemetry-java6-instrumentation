/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.java6.agent.context;

/**
 * Restores the previously attached context when {@link #close()} is called. Not {@code
 * AutoCloseable} because that interface does not exist before Java 7.
 */
public final class Scope {

  private final Context previous;
  private boolean closed;

  Scope(Context previous) {
    this.previous = previous;
  }

  public void close() {
    if (closed) {
      return;
    }
    closed = true;
    ContextStorage.INSTANCE.attach(previous);
  }
}
