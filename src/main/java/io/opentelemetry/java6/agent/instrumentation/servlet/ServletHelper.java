/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.java6.agent.instrumentation.servlet;

import io.opentelemetry.java6.agent.context.CallDepth;
import io.opentelemetry.java6.agent.context.Context;
import io.opentelemetry.java6.agent.context.Propagator;
import io.opentelemetry.java6.agent.context.Scope;
import io.opentelemetry.java6.agent.instrumentation.HttpSemconv;
import io.opentelemetry.java6.agent.instrumentation.WeakIdentityMap;
import io.opentelemetry.java6.agent.instrumentation.WeakMaps;
import io.opentelemetry.java6.agent.trace.Span;
import io.opentelemetry.java6.agent.trace.SpanBuilder;
import io.opentelemetry.java6.agent.trace.SpanKind;
import io.opentelemetry.java6.agent.trace.Tracer;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Map;

/**
 * Servlet request handling, a port of the main project's {@code servlet-3.0} instrumentation
 * logic ({@code Servlet3Advice}/{@code ServletHelper}/{@code HttpServerRoute}):
 *
 * <ul>
 *   <li>the first {@code Filter.doFilter} or {@code Servlet.service} of a request starts the SERVER
 *       span and attaches its context to the request (attribute {@link #CONTEXT_ATTRIBUTE}), so
 *       nested filters, servlets, forwards, includes and async dispatches never start another one;
 *   <li>every filter/servlet on the way updates {@code http.route} and the span name from its
 *       mappings, a servlet mapping beating a filter mapping (route sources, as upstream);
 *   <li>the span ends when the starting call returns, or - for async requests - when the {@code
 *       AsyncListener} sees the request complete.
 * </ul>
 *
 * <p>This class is bootstrap-loaded, so it only sees JDK types: the inlined advice reads every
 * servlet API value and hands them over as strings.
 */
public final class ServletHelper {

  public static final String SCOPE_NAME = "io.opentelemetry.servlet-3.0";
  public static final String CONTEXT_ATTRIBUTE =
      "io.opentelemetry.java6.agent.instrumentation.servlet.Context";

  private static final int SOURCE_FILTER = 1;
  private static final int SOURCE_SERVLET = 2;

  /** Mapping resolvers by servlet/filter instance; {@link #NO_MAPPINGS} if there are none. */
  private static final WeakIdentityMap<Object> RESOLVERS = WeakMaps.create();

  private static final Object NO_MAPPINGS = new Object();

  private ServletHelper() {}

  /** Per-request state, carried in the request's context. */
  static final class RequestState {
    final Span span;
    final String method;
    final String contextPath;
    final String scheme;
    final String protocolVersion;
    final long startNanos;
    volatile int routeSource;
    volatile String route;
    volatile boolean ended;

    RequestState(
        Span span, String method, String contextPath, String scheme, String protocolVersion) {
      this.span = span;
      this.method = method;
      this.contextPath = contextPath;
      this.scheme = scheme;
      this.protocolVersion = protocolVersion;
      this.startNanos = System.nanoTime();
    }
  }

  /** Enter/exit bookkeeping for one advised call. Public: referenced from inlined advice. */
  public static final class AdviceScope {
    final boolean topLevel;
    final boolean servlet;
    final Object resolver;
    final String servletPath;
    final String pathInfo;
    Context context;
    Scope scope;
    RequestState state;
    boolean started;

    AdviceScope(
        boolean topLevel, boolean servlet, Object resolver, String servletPath, String pathInfo) {
      this.topLevel = topLevel;
      this.servlet = servlet;
      this.resolver = resolver;
      this.servletPath = servletPath;
      this.pathInfo = pathInfo;
    }

    /** Whether the advice must collect the request data and call {@link ServletHelper#start}. */
    public boolean needsStart() {
      return context == null;
    }

    /** Whether this call started the span (and so must end it, or hand it to async). */
    public boolean endsRequest() {
      return started;
    }

    /** The context to attach to the request after {@link ServletHelper#start}. */
    public Object context() {
      return context;
    }
  }

  // --- mappings -----------------------------------------------------------------------------

  /** The cached resolver for a servlet/filter instance, or null if not computed yet. */
  public static Object resolver(Object servletOrFilter) {
    return RESOLVERS.get(servletOrFilter);
  }

  /** Caches the mappings of a servlet (lazily) or a filter (at {@code init}). */
  public static Object cacheResolver(Object servletOrFilter, String[] mappings) {
    Object resolver =
        mappings == null || mappings.length == 0
            ? NO_MAPPINGS
            : MappingResolver.build(Arrays.asList(mappings));
    RESOLVERS.put(servletOrFilter, resolver);
    return resolver;
  }

  // --- request lifecycle --------------------------------------------------------------------

  /**
   * Called on every advised {@code service}/{@code doFilter}. When the request already has a
   * server context (nested call, dispatch, or the same request on another thread), makes it
   * current and updates the route; otherwise the advice must call {@link #start}.
   */
  public static AdviceScope enter(
      Object attachedContext,
      boolean servlet,
      Object resolver,
      String servletPath,
      String pathInfo) {
    boolean topLevel = CallDepth.SERVLET.getAndIncrement() == 0;
    AdviceScope scope = new AdviceScope(topLevel, servlet, resolver, servletPath, pathInfo);
    Context current = Context.current();
    if (attachedContext instanceof Context) {
      Context attached = (Context) attachedContext;
      scope.context = attached;
      scope.state = (RequestState) attached.requestState();
      // same request continuing on another thread (async dispatch): re-attach its context
      if (current.serverSpan() != attached.serverSpan()) {
        scope.scope = attached.makeCurrent();
      }
      updateRoute(scope);
    } else if (current.serverSpan() != null) {
      // a server span is already active (another server instrumentation): only update its route
      scope.context = current;
      scope.state =
          current.requestState() instanceof RequestState
              ? (RequestState) current.requestState()
              : null;
      updateRoute(scope);
    }
    return scope;
  }

  /** Starts the SERVER span for the request; values are read from the request by the advice. */
  public static void start(
      AdviceScope scope,
      String method,
      String scheme,
      String requestUri,
      String queryString,
      String protocol,
      String contextPath,
      String remoteAddr,
      int remotePort,
      String host,
      String[] forwarded,
      String[] forwardedHost,
      String[] forwardedProto,
      String[] forwardedFor,
      String userAgent,
      String[] propagationHeaders,
      String[][] capturedHeaders) {
    Context parent = Propagator.extract(Context.current(), propagationHeaders);
    String urlScheme = HttpSemconv.forwardedScheme(forwarded, forwardedProto);
    if (urlScheme == null) {
      urlScheme = scheme;
    }
    String protocolVersion =
        protocol != null && protocol.startsWith("HTTP/") ? protocol.substring(5) : null;

    SpanBuilder builder =
        Tracer.getInstance()
            .spanBuilder(HttpSemconv.spanName(method, null), SCOPE_NAME)
            .setParent(parent)
            .setSpanKind(SpanKind.SERVER);
    HttpSemconv.setMethod(builder, method);
    if (requestUri != null) {
      builder.setAttribute("url.path", requestUri);
    }
    if (queryString != null && queryString.length() > 0) {
      builder.setAttribute("url.query", queryString);
    }
    if (urlScheme != null) {
      builder.setAttribute("url.scheme", urlScheme);
    }
    Object[] server =
        HttpSemconv.serverAddress(
            forwarded, forwardedHost, host == null ? null : new String[] {host});
    if (server[0] != null) {
      builder.setAttribute("server.address", (String) server[0]);
      if (server[1] != null) {
        builder.setAttribute("server.port", ((Long) server[1]).longValue());
      }
    }
    String client = HttpSemconv.clientAddress(forwarded, forwardedFor, remoteAddr);
    if (client != null) {
      builder.setAttribute("client.address", client);
    }
    if (remoteAddr != null) {
      builder.setAttribute("network.peer.address", remoteAddr);
      if (remotePort > 0) {
        builder.setAttribute("network.peer.port", (long) remotePort);
      }
    }
    if (protocolVersion != null) {
      builder.setAttribute("network.protocol.version", protocolVersion);
    }
    if (userAgent != null) {
      builder.setAttribute("user_agent.original", userAgent);
    }
    String[] names = HttpSemconv.serverRequestHeaders();
    for (int i = 0; i < names.length && capturedHeaders != null; i++) {
      if (capturedHeaders[i] != null) {
        builder.setAttribute(HttpSemconv.headerKey(true, names[i]), capturedHeaders[i]);
      }
    }
    Span span = builder.startSpan();
    RequestState state = new RequestState(span, method, contextPath, urlScheme, protocolVersion);
    Context context = parent.withServerSpan(span).withRequestState(state);
    scope.context = context;
    scope.state = state;
    scope.started = true;
    scope.scope = context.makeCurrent();
    updateRoute(scope);
  }

  private static void updateRoute(AdviceScope scope) {
    RequestState state = scope.state;
    if (state == null || !(scope.resolver instanceof MappingResolver)) {
      return;
    }
    int source = scope.servlet ? SOURCE_SERVLET : SOURCE_FILTER;
    // filters: a later filter's mapping wins if it is more specific (longer); servlets: first wins
    boolean onlyIfBetter = !scope.servlet && source == state.routeSource;
    if (source <= state.routeSource && !onlyIfBetter) {
      return;
    }
    String mapping = ((MappingResolver) scope.resolver).resolve(scope.servletPath, scope.pathInfo);
    if (mapping == null) {
      return;
    }
    String route = prependContextPath(state.contextPath, mapping);
    if (route.length() == 0
        || (onlyIfBetter && route.length() <= (state.route == null ? 0 : state.route.length()))) {
      return;
    }
    state.route = route;
    state.routeSource = source;
    state.span.updateName(HttpSemconv.spanName(state.method, route));
  }

  private static String prependContextPath(String contextPath, String mapping) {
    if (contextPath == null) {
      return mapping;
    }
    if (mapping.length() == 0) {
      return contextPath;
    }
    return contextPath + (mapping.startsWith("/") ? mapping : "/" + mapping);
  }

  /**
   * Called on every advised call's exit. For the call that started the span (and no async
   * processing), ends it with the response status; an uncaught exception is a 500 unless the
   * response already has an error status (as the servlet container will answer).
   */
  public static void exit(
      AdviceScope scope, Throwable thrown, int status, String[][] responseHeaders, boolean async) {
    CallDepth.SERVLET.decrementAndGet();
    if (scope.scope != null) {
      scope.scope.close();
    }
    if (!scope.started || async) {
      return;
    }
    end(scope.state, thrown, status, responseHeaders);
  }

  static void end(RequestState state, Throwable thrown, int status, String[][] responseHeaders) {
    if (state == null || state.ended) {
      return;
    }
    state.ended = true;
    if (thrown != null && status < 400) {
      status = 500;
    }
    String[] names = HttpSemconv.serverResponseHeaders();
    for (int i = 0; i < names.length && responseHeaders != null; i++) {
      if (responseHeaders[i] != null) {
        state.span.setAttribute(HttpSemconv.headerKey(false, names[i]), responseHeaders[i]);
      }
    }
    if (state.route != null) {
      state.span.setAttribute("http.route", state.route);
    }
    String errorType = HttpSemconv.end(state.span, status, thrown, true);
    state.span.end();
    HttpSemconv.recordServer(
        SCOPE_NAME, System.nanoTime() - state.startNanos, state.method, status, state.route,
        state.protocolVersion, state.scheme, errorType, state.span.getSpanContext());
  }

  /**
   * An {@code AsyncListener} for an async request, as a dynamic proxy of the application's own
   * listener interface (this bootstrap class cannot implement it): ends the span when the request
   * completes, times out or fails.
   */
  public static Object asyncListener(AdviceScope scope, Class<?> listenerInterface) {
    final RequestState state = scope.state;
    return Proxy.newProxyInstance(
        listenerInterface.getClassLoader(),
        new Class<?>[] {listenerInterface},
        new InvocationHandler() {
          private Throwable failure;

          public Object invoke(Object proxy, Method method, Object[] args) {
            String name = method.getName();
            if (name.equals("onError") && args != null && args.length == 1) {
              failure = (Throwable) call(args[0], "getThrowable");
            } else if (name.equals("onTimeout")) {
              end(state, failure, 500, null);
            } else if (name.equals("onComplete") && args != null && args.length == 1) {
              Object response = call(args[0], "getSuppliedResponse");
              Object status = response == null ? null : call(response, "getStatus");
              end(state, failure, status instanceof Integer ? ((Integer) status).intValue() : -1,
                  null);
            } else if (name.equals("hashCode")) {
              return Integer.valueOf(System.identityHashCode(proxy));
            } else if (name.equals("equals")) {
              return Boolean.valueOf(args != null && proxy == args[0]);
            } else if (name.equals("toString")) {
              return "otel-java6-async-listener";
            }
            return null;
          }
        });
  }

  private static Object call(Object target, String method) {
    try {
      Method m = target.getClass().getMethod(method);
      m.setAccessible(true);
      return m.invoke(target);
    } catch (Throwable t) {
      return null;
    }
  }
}
