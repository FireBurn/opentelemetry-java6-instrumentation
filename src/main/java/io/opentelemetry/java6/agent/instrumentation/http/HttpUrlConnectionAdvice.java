/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.java6.agent.instrumentation.http;

import io.opentelemetry.java6.agent.context.CallDepth;
import io.opentelemetry.java6.agent.context.Context;
import io.opentelemetry.java6.agent.context.Propagator;
import io.opentelemetry.java6.agent.context.Scope;
import io.opentelemetry.java6.agent.export.ExportGuard;
import io.opentelemetry.java6.agent.instrumentation.HttpSemconv;
import io.opentelemetry.java6.agent.instrumentation.WeakIdentityMap;
import io.opentelemetry.java6.agent.instrumentation.WeakMaps;
import io.opentelemetry.java6.agent.trace.Span;
import io.opentelemetry.java6.agent.trace.SpanBuilder;
import io.opentelemetry.java6.agent.trace.SpanKind;
import io.opentelemetry.java6.agent.trace.Tracer;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.bytebuddy.asm.Advice;

/**
 * {@code java.net.HttpURLConnection} instrumentation, a port of the main project's {@code
 * io.opentelemetry.http-url-connection}: advice on {@code connect}/{@code getOutputStream}/{@code
 * getInputStream} (and IBM's protected {@code plainConnect}) starts one CLIENT span per connection
 * and makes it current; only the outermost call on a thread is handled (call depth). The span ends
 * when {@code getInputStream} returns with a response code, or when a call throws - a 4xx/5xx
 * {@code IOException} ends it with the status (no exception event, as upstream), anything else as
 * a failure. {@code getResponseCode} records the status for that path.
 *
 * <p>The advice is inlined into JDK classes, so it only references JDK types and this
 * (bootstrap-loaded) agent.
 */
public final class HttpUrlConnectionAdvice {

  public static final String SCOPE_NAME = "io.opentelemetry.http-url-connection";

  private static final Set<String> SENSITIVE_QUERY_PARAMETERS =
      new HashSet<String>(
          Arrays.asList(
              "AWSAccessKeyId", "Signature", "X-Amz-Signature", "X-Amz-Credential",
              "X-Amz-Security-Token", "sig", "X-Goog-Signature"));

  /** Per-connection state (the main project's virtual field), keyed by the connection. */
  private static final WeakIdentityMap<State> STATES = WeakMaps.create();

  private HttpUrlConnectionAdvice() {}

  /** State of one connection's request. Public: referenced from inlined advice. */
  public static final class State {
    final Context context;
    final Span span;
    final long startNanos;
    final String method;
    final String serverAddress;
    final Long serverPort;
    volatile int statusCode;
    volatile boolean finished;

    State(Context context, Span span, String method, String serverAddress, Long serverPort) {
      this.context = context;
      this.span = span;
      this.startNanos = System.nanoTime();
      this.method = method;
      this.serverAddress = serverAddress;
      this.serverPort = serverPort;
    }
  }

  /** Enter/exit bookkeeping for one advised call. Public: referenced from inlined advice. */
  public static final class AdviceScope {
    final State state;
    final Scope scope;

    AdviceScope(State state, Scope scope) {
      this.state = state;
      this.scope = scope;
    }
  }

  private static final AdviceScope NESTED = new AdviceScope(null, null);

  public static AdviceScope start(HttpURLConnection connection) {
    if (CallDepth.HTTP_URL_CONNECTION.getAndIncrement() > 0 || ExportGuard.isExporting()) {
      return NESTED;
    }
    try {
      return startTopLevel(connection);
    } catch (Throwable t) {
      // never break the application; the exit advice still restores the call depth
      return NESTED;
    }
  }

  private static AdviceScope startTopLevel(HttpURLConnection connection) {
    State state = STATES.get(connection);
    if (state != null) {
      return new AdviceScope(state, state.finished ? null : state.context.makeCurrent());
    }
    Context parent = Context.current();
    String method = connection.getRequestMethod();
    URL url = connection.getURL();
    String host = url.getHost();
    int port = url.getPort();
    if (port <= 0) {
      port = url.getDefaultPort();
    }
    Long serverPort = port > 0 ? Long.valueOf(port) : null;
    SpanBuilder builder =
        Tracer.getInstance()
            .spanBuilder(HttpSemconv.spanName(method, null), SCOPE_NAME)
            .setParent(parent)
            .setSpanKind(SpanKind.CLIENT);
    HttpSemconv.setMethod(builder, method);
    builder.setAttribute(
        "url.full", UrlSanitizer.sanitizeUrl(url.toExternalForm(), SENSITIVE_QUERY_PARAMETERS));
    if (host != null && host.length() > 0) {
      builder.setAttribute("server.address", host);
    }
    if (serverPort != null) {
      builder.setAttribute("server.port", serverPort.longValue());
    }
    // HttpURLConnection always speaks HTTP/1.1, as the main project records
    builder.setAttribute("network.protocol.version", "1.1");
    String[] captured = HttpSemconv.clientRequestHeaders();
    for (int i = 0; i < captured.length; i++) {
      List<String> values = connection.getRequestProperties().get(captured[i]);
      if (values != null && !values.isEmpty()) {
        builder.setAttribute(
            HttpSemconv.headerKey(true, captured[i]), values.toArray(new String[values.size()]));
      }
    }
    Span span = builder.startSpan();
    Context context = parent.with(span);
    String[] headers = Propagator.inject(context);
    for (int i = 0; i + 1 < headers.length; i += 2) {
      try {
        connection.setRequestProperty(headers[i], headers[i + 1]);
      } catch (IllegalStateException alreadyConnected) {
        // headers can no longer be set
      }
    }
    state = new State(context, span, method, host, serverPort);
    STATES.put(connection, state);
    return new AdviceScope(state, context.makeCurrent());
  }

  public static void end(
      AdviceScope adviceScope,
      HttpURLConnection connection,
      int responseCode,
      Throwable thrown,
      String methodName) {
    if (CallDepth.HTTP_URL_CONNECTION.decrementAndGet() > 0 || adviceScope.scope == null) {
      return;
    }
    // reading response headers can re-enter getInputStream; keep those calls nested
    CallDepth.HTTP_URL_CONNECTION.getAndIncrement();
    try {
      adviceScope.scope.close();
      State state = adviceScope.state;
      if (thrown != null) {
        if (responseCode >= 400) {
          // HttpURLConnection throws for error responses; like other clients, this is a response,
          // not an exception
          finish(state, connection, responseCode, null);
        } else {
          finish(state, connection, responseCode > 0 ? responseCode : state.statusCode, thrown);
        }
      } else if ("getInputStream".equals(methodName) && responseCode > 0) {
        finish(state, connection, responseCode, null);
      }
    } finally {
      CallDepth.HTTP_URL_CONNECTION.decrementAndGet();
    }
  }

  private static void finish(State state, HttpURLConnection connection, int status, Throwable t) {
    if (state.finished) {
      return;
    }
    state.finished = true;
    String[] captured = HttpSemconv.clientResponseHeaders();
    for (int i = 0; i < captured.length && status > 0; i++) {
      List<String> values = connection.getHeaderFields().get(captured[i]);
      if (values != null && !values.isEmpty()) {
        state.span.setAttribute(
            HttpSemconv.headerKey(false, captured[i]), values.toArray(new String[values.size()]));
      }
    }
    String errorType = HttpSemconv.end(state.span, status, t, false);
    state.span.end();
    HttpSemconv.recordClient(
        SCOPE_NAME, System.nanoTime() - state.startNanos, state.method, status,
        state.serverAddress, state.serverPort, "1.1", errorType, state.span.getSpanContext());
  }

  public static void recordStatus(HttpURLConnection connection, int status) {
    State state = STATES.get(connection);
    if (state != null) {
      state.statusCode = status;
    }
  }

  /** Advice for {@code connect}, {@code getOutputStream}, {@code getInputStream}, {@code plainConnect}. */
  public static class ConnectionAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AdviceScope onEnter(@Advice.This HttpURLConnection connection) {
      return start(connection);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onExit(
        @Advice.This HttpURLConnection connection,
        @Advice.FieldValue("responseCode") int responseCode,
        @Advice.Thrown Throwable thrown,
        @Advice.Origin("#m") String methodName,
        @Advice.Enter AdviceScope adviceScope) {
      if (adviceScope != null) {
        end(adviceScope, connection, responseCode, thrown, methodName);
      }
    }
  }

  /** Advice for {@code getResponseCode}: remembers the status. */
  public static class ResponseCodeAdvice {

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(
        @Advice.This HttpURLConnection connection, @Advice.Return int status) {
      recordStatus(connection, status);
    }
  }
}
