/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.java6.agent.instrumentation;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;

/**
 * A thread-safe map with weakly referenced keys compared by identity - the main agent's "virtual
 * fields". Identity matters: JDBC wrappers (commons-dbcp, WebSphere) override {@code equals} to
 * delegate to the wrapped object, so an equality-based {@code WeakHashMap} would mix up a wrapper
 * and the connection or statement it wraps.
 */
public final class WeakIdentityMap<V> {

  private final Map<Key, V> map = new HashMap<Key, V>();
  private final ReferenceQueue<Object> queue = new ReferenceQueue<Object>();

  private static final class Key extends WeakReference<Object> {
    private final int hash;

    Key(Object referent, ReferenceQueue<Object> queue) {
      super(referent, queue);
      this.hash = System.identityHashCode(referent);
    }

    @Override
    public int hashCode() {
      return hash;
    }

    @Override
    public boolean equals(Object o) {
      if (o == this) {
        return true;
      }
      if (o instanceof Key) {
        Object referent = get();
        return referent != null && referent == ((Key) o).get();
      }
      if (o instanceof LookupKey) {
        Object referent = get();
        return referent != null && referent == ((LookupKey) o).referent;
      }
      return false;
    }
  }

  /** A strong, allocation-cheap key for lookups; equal to a {@link Key} of the same referent. */
  private static final class LookupKey {
    final Object referent;

    LookupKey(Object referent) {
      this.referent = referent;
    }

    @Override
    public int hashCode() {
      return System.identityHashCode(referent);
    }

    @Override
    public boolean equals(Object o) {
      return o instanceof Key ? o.equals(this) : o instanceof LookupKey
          && ((LookupKey) o).referent == referent;
    }
  }

  private void expunge() {
    Reference<?> ref;
    while ((ref = queue.poll()) != null) {
      map.remove(ref);
    }
  }

  public V get(Object key) {
    if (key == null) {
      return null;
    }
    synchronized (map) {
      return map.get(new LookupKey(key));
    }
  }

  public V put(Object key, V value) {
    if (key == null) {
      return null;
    }
    synchronized (map) {
      expunge();
      return map.put(new Key(key, queue), value);
    }
  }

  public V remove(Object key) {
    if (key == null) {
      return null;
    }
    synchronized (map) {
      expunge();
      return map.remove(new LookupKey(key));
    }
  }

  public int size() {
    synchronized (map) {
      expunge();
      return map.size();
    }
  }
}
