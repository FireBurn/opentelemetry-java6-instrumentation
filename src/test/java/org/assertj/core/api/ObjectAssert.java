/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.assertj.core.api;

import java.util.Arrays;
import java.util.Collection;
import java.util.Map;

/** The subset of AssertJ's fluent assertions used by the ported tests (Java 6). */
public class ObjectAssert {
  private final Object actual;
  private String description = "";

  ObjectAssert(Object actual) {
    this.actual = actual;
  }

  public ObjectAssert as(String description) {
    this.description = "[" + description + "] ";
    return this;
  }

  public ObjectAssert describedAs(String description) {
    return as(description);
  }

  private AssertionError fail(String message) {
    return new AssertionError(description + message);
  }

  private static boolean eq(Object a, Object b) {
    if (a instanceof Object[] && b instanceof Object[]) {
      return Arrays.deepEquals((Object[]) a, (Object[]) b);
    }
    if (a instanceof Number && b instanceof Number && a.getClass() != b.getClass()) {
      return ((Number) a).longValue() == ((Number) b).longValue()
          && ((Number) a).doubleValue() == ((Number) b).doubleValue();
    }
    return a == null ? b == null : a.equals(b);
  }

  public ObjectAssert isEqualTo(Object expected) {
    if (!eq(actual, expected)) {
      throw fail("expected: <" + expected + "> but was: <" + actual + ">");
    }
    return this;
  }

  public ObjectAssert isNotEqualTo(Object other) {
    if (eq(actual, other)) {
      throw fail("expected not to be: <" + other + ">");
    }
    return this;
  }

  public ObjectAssert isSameAs(Object expected) {
    if (actual != expected) {
      throw fail("expected same instance as: <" + expected + "> but was: <" + actual + ">");
    }
    return this;
  }

  public ObjectAssert isNull() {
    if (actual != null) {
      throw fail("expected null but was: <" + actual + ">");
    }
    return this;
  }

  public ObjectAssert isNotNull() {
    if (actual == null) {
      throw fail("expected non-null");
    }
    return this;
  }

  public ObjectAssert isTrue() {
    return isEqualTo(Boolean.TRUE);
  }

  public ObjectAssert isFalse() {
    return isEqualTo(Boolean.FALSE);
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private int compare(Object other) {
    return ((Comparable) actual).compareTo(other);
  }

  public ObjectAssert isLessThanOrEqualTo(Object other) {
    if (compare(other) > 0) {
      throw fail("expected <" + actual + "> <= <" + other + ">");
    }
    return this;
  }

  public ObjectAssert isGreaterThanOrEqualTo(Object other) {
    if (compare(other) < 0) {
      throw fail("expected <" + actual + "> >= <" + other + ">");
    }
    return this;
  }

  public ObjectAssert isLessThan(Object other) {
    if (compare(other) >= 0) {
      throw fail("expected <" + actual + "> < <" + other + ">");
    }
    return this;
  }

  public ObjectAssert isGreaterThan(Object other) {
    if (compare(other) <= 0) {
      throw fail("expected <" + actual + "> > <" + other + ">");
    }
    return this;
  }

  public ObjectAssert isEmpty() {
    if (size() != 0) {
      throw fail("expected empty but was: <" + actual + ">");
    }
    return this;
  }

  public ObjectAssert isNotEmpty() {
    if (size() == 0) {
      throw fail("expected non-empty");
    }
    return this;
  }

  public ObjectAssert hasSize(int expected) {
    if (size() != expected) {
      throw fail("expected size " + expected + " but was " + size() + ": <" + actual + ">");
    }
    return this;
  }

  private int size() {
    if (actual instanceof CharSequence) {
      return ((CharSequence) actual).length();
    }
    if (actual instanceof Collection) {
      return ((Collection<?>) actual).size();
    }
    if (actual instanceof Map) {
      return ((Map<?, ?>) actual).size();
    }
    if (actual instanceof Object[]) {
      return ((Object[]) actual).length;
    }
    throw fail("no size for <" + actual + ">");
  }

  public ObjectAssert contains(Object... values) {
    for (int i = 0; i < values.length; i++) {
      boolean found;
      if (actual instanceof String) {
        found = ((String) actual).contains(String.valueOf(values[i]));
      } else if (actual instanceof Collection) {
        found = ((Collection<?>) actual).contains(values[i]);
      } else if (actual instanceof Object[]) {
        found = Arrays.asList((Object[]) actual).contains(values[i]);
      } else {
        throw fail("contains() not supported for <" + actual + ">");
      }
      if (!found) {
        throw fail("expected <" + actual + "> to contain <" + values[i] + ">");
      }
    }
    return this;
  }

  public ObjectAssert doesNotContain(Object... values) {
    for (int i = 0; i < values.length; i++) {
      boolean found =
          actual instanceof String
              ? ((String) actual).contains(String.valueOf(values[i]))
              : ((Collection<?>) actual).contains(values[i]);
      if (found) {
        throw fail("expected <" + actual + "> not to contain <" + values[i] + ">");
      }
    }
    return this;
  }

  public ObjectAssert containsExactly(Object... values) {
    Object[] a =
        actual instanceof Collection ? ((Collection<?>) actual).toArray() : (Object[]) actual;
    if (!Arrays.deepEquals(a, values)) {
      throw fail("expected exactly " + Arrays.toString(values) + " but was " + Arrays.toString(a));
    }
    return this;
  }

  public ObjectAssert startsWith(String prefix) {
    if (!String.valueOf(actual).startsWith(prefix)) {
      throw fail("expected <" + actual + "> to start with <" + prefix + ">");
    }
    return this;
  }

  public ObjectAssert endsWith(String suffix) {
    if (!String.valueOf(actual).endsWith(suffix)) {
      throw fail("expected <" + actual + "> to end with <" + suffix + ">");
    }
    return this;
  }

  public ObjectAssert containsEntry(Object key, Object value) {
    Map<?, ?> map = (Map<?, ?>) actual;
    if (!map.containsKey(key) || !eq(map.get(key), value)) {
      throw fail("expected <" + actual + "> to contain entry " + key + "=" + value);
    }
    return this;
  }

  public ObjectAssert isInstanceOf(Class<?> type) {
    if (!type.isInstance(actual)) {
      throw fail("expected instance of " + type.getName() + " but was <" + actual + ">");
    }
    return this;
  }
}
