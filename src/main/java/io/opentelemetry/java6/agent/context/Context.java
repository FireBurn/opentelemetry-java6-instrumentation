/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.java6.agent.context;

import io.opentelemetry.java6.agent.trace.Span;

/**
 * Immutable context: the current span (live, or a remote parent extracted from propagation
 * headers), the local server span of the request being handled (so nested server instrumentation
 * does not start a second SERVER span, as the main agent's {@code SpanKey.HTTP_SERVER}), the
 * incoming W3C baggage (propagated unchanged) and one extra slot for instrumentation state.
 */
public final class Context {

  private static final Context ROOT = new Context(null, null, null, null, null);

  private final SpanContext spanContext;
  private final Span span;
  private final Span serverSpan;
  private final String baggage;
  private final Object requestState;

  private Context(
      SpanContext spanContext, Span span, Span serverSpan, String baggage, Object requestState) {
    this.spanContext = spanContext;
    this.span = span;
    this.serverSpan = serverSpan;
    this.baggage = baggage;
    this.requestState = requestState;
  }

  public static Context root() {
    return ROOT;
  }

  public static Context current() {
    Context context = ContextStorage.INSTANCE.current();
    return context == null ? ROOT : context;
  }

  /** This context with {@code span} as the current span. */
  public Context with(Span span) {
    return new Context(span.getSpanContext(), span, serverSpan, baggage, requestState);
  }

  /** This context with {@code span} as the current span and the request's server span. */
  public Context withServerSpan(Span span) {
    return new Context(span.getSpanContext(), span, span, baggage, requestState);
  }

  /** This context with a remote parent (no live span), as extracted from request headers. */
  public Context withRemoteParent(SpanContext remote) {
    return new Context(remote, null, serverSpan, baggage, requestState);
  }

  /** This context with the raw W3C {@code baggage} header value to propagate downstream. */
  public Context withBaggage(String baggage) {
    return new Context(spanContext, span, serverSpan, baggage, requestState);
  }

  /** This context with instrumentation state for the current request (e.g. servlet route). */
  public Context withRequestState(Object requestState) {
    return new Context(spanContext, span, serverSpan, baggage, requestState);
  }

  /** The live span, or {@code null} for a remote parent or the root. */
  public Span span() {
    return span;
  }

  /** The current span context (local or remote), or {@code null} for the root. */
  public SpanContext spanContext() {
    return spanContext;
  }

  /** The local SERVER span of the request being handled on this thread, or {@code null}. */
  public Span serverSpan() {
    return serverSpan;
  }

  public String baggage() {
    return baggage;
  }

  public Object requestState() {
    return requestState;
  }

  /** Attaches this context to the current thread; the scope restores the previous one. */
  public Scope makeCurrent() {
    Context previous = ContextStorage.INSTANCE.attach(this);
    return new Scope(previous);
  }
}
