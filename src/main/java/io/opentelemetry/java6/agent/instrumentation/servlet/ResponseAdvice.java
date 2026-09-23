/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.java6.agent.instrumentation.servlet;

import io.opentelemetry.java6.agent.context.CallDepth;
import io.opentelemetry.java6.agent.context.Context;
import io.opentelemetry.java6.agent.context.Scope;
import io.opentelemetry.java6.agent.trace.Span;
import io.opentelemetry.java6.agent.trace.Tracer;
import net.bytebuddy.asm.Advice;

/**
 * {@code HttpServletResponse.sendError}/{@code sendRedirect}: an INTERNAL span {@code
 * <Class>.<method>} with {@code code.namespace}/{@code code.function}, when a span is current, as
 * the main project's servlet response instrumentation. Response wrappers delegating to the
 * container response are collapsed by call depth.
 */
public final class ResponseAdvice {

  private ResponseAdvice() {}

  /** Span and scope of one call. Public: referenced from inlined advice. */
  public static final class AdviceScope {
    final Span span;
    final Scope scope;

    AdviceScope(Span span, Scope scope) {
      this.span = span;
      this.scope = scope;
    }
  }

  private static final AdviceScope NESTED = new AdviceScope(null, null);

  public static AdviceScope start(String className, String methodName) {
    if (CallDepth.SERVLET_RESPONSE.getAndIncrement() > 0) {
      return NESTED;
    }
    try {
      Context parent = Context.current();
      if (parent.spanContext() == null) {
        return NESTED;
      }
      String simpleName = className.substring(className.lastIndexOf('.') + 1);
      Span span =
          Tracer.getInstance()
              .spanBuilder(simpleName + "." + methodName, ServletHelper.SCOPE_NAME)
              .setParent(parent)
              .setAttribute("code.namespace", className)
              .setAttribute("code.function", methodName)
              .startSpan();
      return new AdviceScope(span, parent.with(span).makeCurrent());
    } catch (Throwable t) {
      return NESTED;
    }
  }

  public static void end(AdviceScope adviceScope, Throwable thrown) {
    if (CallDepth.SERVLET_RESPONSE.decrementAndGet() > 0 || adviceScope.scope == null) {
      return;
    }
    adviceScope.scope.close();
    if (thrown != null) {
      adviceScope.span.recordException(thrown);
      adviceScope.span.setStatus(Span.STATUS_ERROR, null);
    }
    adviceScope.span.end();
  }

  /** Advice for {@code sendError}/{@code sendRedirect} on response implementations. */
  public static class SendAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AdviceScope onEnter(
        @Advice.Origin("#t") String className, @Advice.Origin("#m") String methodName) {
      return start(className, methodName);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onExit(
        @Advice.Enter AdviceScope adviceScope, @Advice.Thrown Throwable thrown) {
      if (adviceScope != null) {
        end(adviceScope, thrown);
      }
    }
  }
}
