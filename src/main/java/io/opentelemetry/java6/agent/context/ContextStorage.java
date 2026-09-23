/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.java6.agent.context;

/**
 * Thread-local context storage. A single bootstrap-loaded copy of this class is shared by all
 * class loaders, which is what makes context propagate across class loader boundaries.
 */
final class ContextStorage {

  static final ContextStorage INSTANCE = new ContextStorage();

  private final ThreadLocal<Context> storage = new ThreadLocal<Context>();

  Context current() {
    return storage.get();
  }

  /** Attaches the given context and returns the previously attached one. */
  Context attach(Context context) {
    Context previous = storage.get();
    storage.set(context);
    return previous;
  }
}
