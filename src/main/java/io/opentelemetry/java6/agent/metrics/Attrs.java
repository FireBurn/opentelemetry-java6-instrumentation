/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.java6.agent.metrics;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * An immutable, canonical (key-sorted) attribute set used as a metric point key. Values keep their
 * OTLP type (String, Long, Double, Boolean).
 */
public final class Attrs {

  public static final Attrs EMPTY = new Attrs(new String[0], new Object[0]);

  final String[] keys;
  final Object[] values;
  private final int hash;

  private Attrs(String[] keys, Object[] values) {
    this.keys = keys;
    this.values = values;
    this.hash = 31 * Arrays.hashCode(keys) + Arrays.hashCode(values);
  }

  /** Builds from alternating key/value pairs; null values are dropped. */
  public static Attrs of(Object... keyValues) {
    Map<String, Object> map = new java.util.TreeMap<String, Object>();
    for (int i = 0; i + 1 < keyValues.length; i += 2) {
      if (keyValues[i] != null && keyValues[i + 1] != null) {
        map.put((String) keyValues[i], keyValues[i + 1]);
      }
    }
    return fromSorted(map);
  }

  /** Builds from a map (any order); null values are dropped. */
  public static Attrs of(Map<String, Object> attributes) {
    Map<String, Object> map = new java.util.TreeMap<String, Object>();
    for (Map.Entry<String, Object> e : attributes.entrySet()) {
      if (e.getKey() != null && e.getValue() != null) {
        map.put(e.getKey(), e.getValue());
      }
    }
    return fromSorted(map);
  }

  private static Attrs fromSorted(Map<String, Object> map) {
    if (map.isEmpty()) {
      return EMPTY;
    }
    String[] k = new String[map.size()];
    Object[] v = new Object[map.size()];
    int i = 0;
    for (Map.Entry<String, Object> e : map.entrySet()) {
      k[i] = e.getKey();
      v[i] = e.getValue();
      i++;
    }
    return new Attrs(k, v);
  }

  public Map<String, Object> asMap() {
    Map<String, Object> map = new LinkedHashMap<String, Object>();
    for (int i = 0; i < keys.length; i++) {
      map.put(keys[i], values[i]);
    }
    return map;
  }

  @Override
  public boolean equals(Object o) {
    if (o == this) {
      return true;
    }
    if (!(o instanceof Attrs)) {
      return false;
    }
    Attrs other = (Attrs) o;
    return hash == other.hash && Arrays.equals(keys, other.keys)
        && Arrays.equals(values, other.values);
  }

  @Override
  public int hashCode() {
    return hash;
  }

  @Override
  public String toString() {
    return asMap().toString();
  }
}
