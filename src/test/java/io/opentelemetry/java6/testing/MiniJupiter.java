/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.java6.testing;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * A minimal JUnit 5 compatible runner for Java 6, so tests ported from the main project run
 * unchanged apart from {@code Stream} (Java 8) being replaced by a {@code List}. Supports {@code
 * @Test}, {@code @ParameterizedTest} with {@code @MethodSource} (static method returning an
 * {@code Iterable} or array of {@code Arguments} or plain values) and {@code @ValueSource}, and
 * {@code Assumptions.assumeTrue}.
 *
 * <p>Usage: {@code MiniJupiter <test class>...}; exits non-zero if any test fails.
 */
public final class MiniJupiter {

  private int passed;
  private int failed;
  private int skipped;

  private MiniJupiter() {}

  public static void main(String[] args) throws Exception {
    MiniJupiter runner = new MiniJupiter();
    for (int i = 0; i < args.length; i++) {
      runner.run(Class.forName(args[i]));
    }
    System.out.println(
        "TESTS: " + runner.passed + " passed, " + runner.failed + " failed, " + runner.skipped
            + " skipped");
    System.exit(runner.failed == 0 ? 0 : 1);
  }

  private void run(Class<?> testClass) throws Exception {
    Method[] methods = testClass.getDeclaredMethods();
    for (int i = 0; i < methods.length; i++) {
      Method m = methods[i];
      if (m.isAnnotationPresent(Test.class)) {
        invoke(testClass, m, new Object[0], m.getName());
      } else if (m.isAnnotationPresent(ParameterizedTest.class)) {
        List<Object[]> cases = cases(testClass, m);
        for (int c = 0; c < cases.size(); c++) {
          invoke(testClass, m, cases.get(c), m.getName() + "[" + c + "] " + describe(cases.get(c)));
        }
      }
    }
  }

  private static String describe(Object[] args) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < args.length; i++) {
      sb.append(i == 0 ? "" : ", ").append(args[i]);
    }
    return sb.toString();
  }

  private static List<Object[]> cases(Class<?> testClass, Method m) throws Exception {
    List<Object[]> result = new ArrayList<Object[]>();
    ValueSource values = m.getAnnotation(ValueSource.class);
    if (values != null) {
      for (int i = 0; i < values.strings().length; i++) {
        result.add(new Object[] {values.strings()[i]});
      }
      for (int i = 0; i < values.ints().length; i++) {
        result.add(new Object[] {Integer.valueOf(values.ints()[i])});
      }
    }
    CsvSource csv = m.getAnnotation(CsvSource.class);
    if (csv != null) {
      for (int i = 0; i < csv.value().length; i++) {
        result.add(csvLine(csv.value()[i]));
      }
    }
    MethodSource source = m.getAnnotation(MethodSource.class);
    if (source != null) {
      String[] names = source.value().length == 0 ? new String[] {m.getName()} : source.value();
      for (int n = 0; n < names.length; n++) {
        Method provider = testClass.getDeclaredMethod(names[n]);
        provider.setAccessible(true);
        Object provided = provider.invoke(null);
        Iterable<?> items =
            provided instanceof Object[]
                ? java.util.Arrays.asList((Object[]) provided)
                : (Iterable<?>) provided;
        for (Object item : items) {
          result.add(item instanceof Arguments ? ((Arguments) item).get() : new Object[] {item});
        }
      }
    }
    return result;
  }

  private void invoke(Class<?> testClass, Method m, Object[] args, String name) throws Exception {
    Object instance = null;
    if (!Modifier.isStatic(m.getModifiers())) {
      java.lang.reflect.Constructor<?> ctor = testClass.getDeclaredConstructor();
      ctor.setAccessible(true);
      instance = ctor.newInstance();
    }
    m.setAccessible(true);
    try {
      m.invoke(instance, args);
      passed++;
    } catch (InvocationTargetException e) {
      Throwable cause = e.getCause();
      if (cause instanceof Assumptions.Aborted) {
        skipped++;
      } else {
        failed++;
        System.out.println("FAIL " + testClass.getSimpleName() + "." + name + ": " + cause);
        if (!(cause instanceof AssertionError)) {
          cause.printStackTrace(System.out);
        }
      }
    }
  }

  /** JUnit's CSV rules: comma separated, trimmed, '...' quotes, empty unquoted value is null. */
  static Object[] csvLine(String line) {
    List<Object> values = new ArrayList<Object>();
    StringBuilder current = new StringBuilder();
    boolean quoted = false;
    boolean wasQuoted = false;
    for (int i = 0; i < line.length(); i++) {
      char c = line.charAt(i);
      if (c == '\'') {
        quoted = !quoted;
        wasQuoted = true;
      } else if (c == ',' && !quoted) {
        values.add(csvValue(current, wasQuoted));
        current.setLength(0);
        wasQuoted = false;
      } else {
        current.append(c);
      }
    }
    values.add(csvValue(current, wasQuoted));
    return values.toArray();
  }

  private static Object csvValue(StringBuilder raw, boolean quoted) {
    String v = raw.toString().trim();
    return v.length() == 0 && !quoted ? null : v;
  }

  /** Converts a collection to a list, for ported {@code Stream} factory methods. */
  public static <T> List<T> list(Collection<T> c) {
    return new ArrayList<T>(c);
  }
}
