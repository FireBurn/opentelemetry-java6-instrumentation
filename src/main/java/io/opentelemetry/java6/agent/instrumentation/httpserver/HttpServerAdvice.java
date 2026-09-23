/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.java6.agent.instrumentation.httpserver;

import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpsExchange;
import io.opentelemetry.java6.agent.context.Context;
import io.opentelemetry.java6.agent.context.Propagator;
import io.opentelemetry.java6.agent.context.Scope;
import io.opentelemetry.java6.agent.instrumentation.HttpSemconv;
import io.opentelemetry.java6.agent.trace.Span;
import io.opentelemetry.java6.agent.trace.SpanBuilder;
import io.opentelemetry.java6.agent.trace.SpanKind;
import io.opentelemetry.java6.agent.trace.Tracer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import net.bytebuddy.asm.Advice;

/**
 * {@code com.sun.net.httpserver} instrumentation, as the main project's {@code
 * io.opentelemetry.java-http-server}: {@code HttpServer.createContext} gets a telemetry filter that
 * starts one SERVER span per request named {@code <method> <context path>}. Everything here is a
 * JDK type, so the filter can be bootstrap-loaded.
 */
public final class HttpServerAdvice {

  public static final String SCOPE_NAME = "io.opentelemetry.java-http-server";

  private HttpServerAdvice() {}

  /** Advice for {@code HttpServer.createContext}: adds the telemetry filter. */
  public static class BuildAdvice {

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(@Advice.Return Object context) {
      if (context instanceof HttpContext) {
        ((HttpContext) context).getFilters().add(new TelemetryFilter());
      }
    }
  }

  /** The per-request filter. */
  public static final class TelemetryFilter extends Filter {

    public void doFilter(HttpExchange exchange, Filter.Chain chain) throws IOException {
      Context current = Context.current();
      if (current.serverSpan() != null) {
        chain.doFilter(exchange);
        return;
      }
      Span span = null;
      Scope scope = null;
      String method = null;
      String route = null;
      String scheme = null;
      String protocolVersion = null;
      long start = System.nanoTime();
      try {
        method = exchange.getRequestMethod();
        route = exchange.getHttpContext().getPath();
        scheme = exchange instanceof HttpsExchange ? "https" : "http";
        String protocol = exchange.getProtocol();
        protocolVersion =
            protocol != null && protocol.startsWith("HTTP/") ? protocol.substring(5) : null;
        String[] propagation = new String[Propagator.EXTRACT_HEADERS.length];
        for (int i = 0; i < propagation.length; i++) {
          propagation[i] = header(exchange, Propagator.EXTRACT_HEADERS[i]);
        }
        Context parent = Propagator.extract(current, propagation);
        SpanBuilder builder =
            Tracer.getInstance()
                .spanBuilder(HttpSemconv.spanName(method, route), SCOPE_NAME)
                .setParent(parent)
                .setSpanKind(SpanKind.SERVER);
        HttpSemconv.setMethod(builder, method);
        if (route != null) {
          builder.setAttribute("http.route", route);
        }
        java.net.URI uri = exchange.getRequestURI();
        if (uri != null && uri.getRawPath() != null) {
          builder.setAttribute("url.path", uri.getRawPath());
        }
        if (uri != null && uri.getRawQuery() != null) {
          builder.setAttribute("url.query", uri.getRawQuery());
        }
        builder.setAttribute("url.scheme", scheme);
        if (protocolVersion != null) {
          builder.setAttribute("network.protocol.version", protocolVersion);
        }
        Object[] server =
            HttpSemconv.serverAddress(
                headers(exchange, "forwarded"), headers(exchange, "x-forwarded-host"),
                headers(exchange, "host"));
        if (server[0] != null) {
          builder.setAttribute("server.address", (String) server[0]);
          if (server[1] != null) {
            builder.setAttribute("server.port", ((Long) server[1]).longValue());
          }
        }
        InetSocketAddress remote = exchange.getRemoteAddress();
        String remoteAddr =
            remote == null || remote.getAddress() == null
                ? null
                : remote.getAddress().getHostAddress();
        String client =
            HttpSemconv.clientAddress(
                headers(exchange, "forwarded"), headers(exchange, "x-forwarded-for"), remoteAddr);
        if (client != null) {
          builder.setAttribute("client.address", client);
        }
        if (remoteAddr != null) {
          builder.setAttribute("network.peer.address", remoteAddr);
          builder.setAttribute("network.peer.port", (long) remote.getPort());
        }
        String userAgent = header(exchange, "user-agent");
        if (userAgent != null) {
          builder.setAttribute("user_agent.original", userAgent);
        }
        span = builder.startSpan();
        scope = parent.withServerSpan(span).makeCurrent();
      } catch (Throwable t) {
        span = null;
      }
      Throwable thrown = null;
      try {
        chain.doFilter(exchange);
      } catch (IOException e) {
        thrown = e;
        throw e;
      } catch (RuntimeException e) {
        thrown = e;
        throw e;
      } catch (Error e) {
        thrown = e;
        throw e;
      } finally {
        if (scope != null) {
          scope.close();
        }
        if (span != null) {
          int status = exchange.getResponseCode();
          if (thrown != null && status < 400) {
            status = 500;
          }
          String errorType = HttpSemconv.end(span, status, thrown, true);
          span.end();
          HttpSemconv.recordServer(
              SCOPE_NAME, System.nanoTime() - start, method, status, route, protocolVersion,
              scheme, errorType, span.getSpanContext());
        }
      }
    }

    public String description() {
      return "OpenTelemetry";
    }

    private static String[] headers(HttpExchange exchange, String name) {
      Map<String, List<String>> headers = exchange.getRequestHeaders();
      for (Iterator<Map.Entry<String, List<String>>> it = headers.entrySet().iterator();
          it.hasNext(); ) {
        Map.Entry<String, List<String>> e = it.next();
        if (e.getKey() != null && e.getKey().equalsIgnoreCase(name) && e.getValue() != null) {
          return e.getValue().toArray(new String[e.getValue().size()]);
        }
      }
      return null;
    }

    /**
     * The JDK stores header names with the first character capitalized and {@code getFirst} is
     * case-sensitive, so match case-insensitively.
     */
    private static String header(HttpExchange exchange, String name) {
      Map<String, List<String>> headers = exchange.getRequestHeaders();
      for (Iterator<Map.Entry<String, List<String>>> it = headers.entrySet().iterator();
          it.hasNext(); ) {
        Map.Entry<String, List<String>> e = it.next();
        if (e.getKey() != null && e.getKey().equalsIgnoreCase(name)
            && e.getValue() != null && !e.getValue().isEmpty()) {
          return e.getValue().get(0);
        }
      }
      return null;
    }
  }
}
