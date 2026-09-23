/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.java6.agent;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/**
 * Type hierarchy matchers, as the main agent's {@code implementsInterface}/{@code extendsClass}.
 * ByteBuddy evaluates every type matcher against the same {@link TypeDescription} in turn, so the
 * supertype names of the type are computed once and memoized per thread for the following
 * matchers. Resolution failures yield "no match" instead of throwing.
 */
public final class TypeMatchers {

  private static final ThreadLocal<Object[]> LAST = new ThreadLocal<Object[]>();

  private TypeMatchers() {}

  /** A type implementing the interface, directly or indirectly (the interface itself matches). */
  public static ElementMatcher.Junction<TypeDescription> implementsInterface(final String name) {
    return new ElementMatcher.Junction.AbstractBase<TypeDescription>() {
      public boolean matches(TypeDescription target) {
        return hierarchy(target).contains(name);
      }
    };
  }

  /** A type extending the class, directly or indirectly (the class itself matches). */
  public static ElementMatcher.Junction<TypeDescription> extendsClass(final String name) {
    return new ElementMatcher.Junction.AbstractBase<TypeDescription>() {
      public boolean matches(TypeDescription target) {
        return hierarchy(target).contains(name);
      }
    };
  }

  static Set<String> hierarchy(TypeDescription target) {
    Object[] last = LAST.get();
    if (last != null && last[0] == target) {
      @SuppressWarnings("unchecked")
      Set<String> cached = (Set<String>) last[1];
      return cached;
    }
    Set<String> names = new HashSet<String>();
    Deque<TypeDescription.Generic> queue = new ArrayDeque<TypeDescription.Generic>();
    names.add(target.getName());
    try {
      TypeDescription.Generic superClass = target.getSuperClass();
      if (superClass != null) {
        queue.add(superClass);
      }
      for (TypeDescription.Generic iface : target.getInterfaces()) {
        queue.add(iface);
      }
    } catch (Throwable t) {
      // unresolvable supertypes
    }
    while (!queue.isEmpty()) {
      TypeDescription.Generic type = queue.removeFirst();
      try {
        TypeDescription raw = type.asErasure();
        if (!names.add(raw.getName())) {
          continue;
        }
        TypeDescription.Generic superClass = raw.getSuperClass();
        if (superClass != null) {
          queue.add(superClass);
        }
        for (TypeDescription.Generic iface : raw.getInterfaces()) {
          queue.add(iface);
        }
      } catch (Throwable t) {
        // unresolvable supertype: ignore this branch
      }
    }
    LAST.set(new Object[] {target, names});
    return names;
  }
}
