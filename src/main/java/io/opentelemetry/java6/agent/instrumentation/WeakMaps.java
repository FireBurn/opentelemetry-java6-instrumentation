/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.java6.agent.instrumentation;

/** Factory for the per-object state maps (see {@link WeakIdentityMap}). */
public final class WeakMaps {

  private WeakMaps() {}

  public static <V> WeakIdentityMap<V> create() {
    return new WeakIdentityMap<V>();
  }
}
