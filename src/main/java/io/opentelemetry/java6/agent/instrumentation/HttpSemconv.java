/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.java6.agent.instrumentation;

import io.opentelemetry.java6.agent.AgentConfig;
import io.opentelemetry.java6.agent.context.SpanContext;
import io.opentelemetry.java6.agent.metrics.Attrs;
import io.opentelemetry.java6.agent.metrics.DoubleHistogram;
import io.opentelemetry.java6.agent.metrics.MeterRegistry;
import io.opentelemetry.java6.agent.trace.Span;
import io.opentelemetry.java6.agent.trace.SpanBuilder;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * HTTP semantic conventions shared by the HTTP instrumentations, following the main project's
 * {@code HttpCommonAttributesExtractor}/{@code HttpServerAttributesExtractor}/{@code
 * HttpClientAttributesExtractor}: known-method normalization ({@code _OTHER}), {@code error.type},
 * captured headers ({@code otel.instrumentation.http.*.capture-*-headers}), the {@code Forwarded}/
 * {@code X-Forwarded-*} parsing of server and client addresses, and the {@code
 * http.server.request.duration}/{@code http.client.request.duration} histograms.
 *
 * <p>Only JDK types cross into this class: it is bootstrap-loaded and shared by all class loaders.
 */
public final class HttpSemconv {

  public static final String OTHER = "_OTHER";

  private static volatile Set<String> knownMethods = defaultKnownMethods();
  private static volatile String[] serverRequestHeaders = new String[0];
  private static volatile String[] serverResponseHeaders = new String[0];
  private static volatile String[] clientRequestHeaders = new String[0];
  private static volatile String[] clientResponseHeaders = new String[0];

  private static final Map<String, DoubleHistogram> SERVER = new HashMap<String, DoubleHistogram>();
  private static final Map<String, DoubleHistogram> CLIENT = new HashMap<String, DoubleHistogram>();

  private HttpSemconv() {}

  private static Set<String> defaultKnownMethods() {
    Set<String> set = new HashSet<String>();
    String[] methods = {"CONNECT", "DELETE", "GET", "HEAD", "OPTIONS", "PATCH", "POST", "PUT", "TRACE"};
    for (int i = 0; i < methods.length; i++) {
      set.add(methods[i]);
    }
    return set;
  }

  public static void configure(AgentConfig config) {
    knownMethods = new HashSet<String>(config.knownMethods);
    serverRequestHeaders = lower(config.serverRequestHeaders);
    serverResponseHeaders = lower(config.serverResponseHeaders);
    clientRequestHeaders = lower(config.clientRequestHeaders);
    clientResponseHeaders = lower(config.clientResponseHeaders);
  }

  private static String[] lower(List<String> names) {
    String[] out = new String[names.size()];
    for (int i = 0; i < out.length; i++) {
      out[i] = names.get(i).toLowerCase(Locale.ROOT);
    }
    return out;
  }

  public static String[] serverRequestHeaders() {
    return serverRequestHeaders;
  }

  public static String[] serverResponseHeaders() {
    return serverResponseHeaders;
  }

  public static String[] clientRequestHeaders() {
    return clientRequestHeaders;
  }

  public static String[] clientResponseHeaders() {
    return clientResponseHeaders;
  }

  /** {@code http.request.method}: the method if known, else {@code _OTHER}. */
  public static String method(String method) {
    return method == null || knownMethods.contains(method) ? method : OTHER;
  }

  /** The span name for a request: {@code <method> <route>}, or {@code HTTP} for unknown methods. */
  public static String spanName(String method, String route) {
    String m = method(method);
    String name = m == null || OTHER.equals(m) ? "HTTP" : m;
    return route == null ? name : name + " " + route;
  }

  /** Sets {@code http.request.method} (and {@code http.request.method_original} for unknowns). */
  public static void setMethod(SpanBuilder builder, String method) {
    String m = method(method);
    if (m != null) {
      builder.setAttribute("http.request.method", m);
      if (OTHER.equals(m)) {
        builder.setAttribute("http.request.method_original", method);
      }
    }
  }

  /**
   * The {@code error.type} for a finished request: the status code when it is an error (5xx for
   * servers, 4xx and 5xx for clients), else the exception class, else {@code _OTHER} when there is
   * no status at all.
   */
  public static String errorType(int status, Throwable error, boolean server) {
    if (status > 0) {
      return status >= 500 || (!server && status >= 400) ? String.valueOf(status) : null;
    }
    return error != null ? error.getClass().getName() : OTHER;
  }

  /** Ends an HTTP span: status code, {@code error.type}, error status, exception event. */
  public static String end(Span span, int status, Throwable error, boolean server) {
    if (status > 0) {
      span.setAttribute("http.response.status_code", (long) status);
    }
    String errorType = errorType(status, error, server);
    if (errorType != null) {
      span.setAttribute("error.type", errorType);
      span.setStatus(Span.STATUS_ERROR, null);
    }
    if (error != null) {
      span.recordException(error);
    }
    return errorType;
  }

  /** Captured header attribute key, as the main project: {@code http.request.header.<name>}. */
  public static String headerKey(boolean request, String lowerName) {
    return (request ? "http.request.header." : "http.response.header.") + lowerName;
  }

  private static DoubleHistogram histogram(
      Map<String, DoubleHistogram> map, String scope, boolean server) {
    synchronized (map) {
      DoubleHistogram h = map.get(scope);
      if (h == null) {
        h =
            MeterRegistry.histogram(
                scope,
                AgentConfig.AGENT_VERSION + "-alpha",
                server ? "http.server.request.duration" : "http.client.request.duration",
                server ? "Duration of HTTP server requests." : "Duration of HTTP client requests.",
                "s",
                MeterRegistry.HTTP_DURATION_BUCKETS);
        map.put(scope, h);
      }
      return h;
    }
  }

  /** Records {@code http.server.request.duration} with the main project's attribute set. */
  public static void recordServer(
      String scope, long durationNanos, String method, int status, String route,
      String protocolVersion, String scheme, String errorType, SpanContext exemplar) {
    histogram(SERVER, scope, true)
        .record(
            durationNanos / 1e9,
            Attrs.of(
                "http.request.method", method(method),
                "http.response.status_code", status > 0 ? Long.valueOf(status) : null,
                "http.route", route,
                "network.protocol.version", protocolVersion,
                "url.scheme", scheme,
                "error.type", errorType),
            exemplar);
  }

  /** Records {@code http.client.request.duration} with the main project's attribute set. */
  public static void recordClient(
      String scope, long durationNanos, String method, int status, String serverAddress,
      Long serverPort, String protocolVersion, String errorType, SpanContext exemplar) {
    histogram(CLIENT, scope, false)
        .record(
            durationNanos / 1e9,
            Attrs.of(
                "http.request.method", method(method),
                "http.response.status_code", status > 0 ? Long.valueOf(status) : null,
                "server.address", serverAddress,
                "server.port", serverPort,
                "network.protocol.version", protocolVersion,
                "error.type", errorType),
            exemplar);
  }

  // --- Forwarded / X-Forwarded-* parsing, ported from the main project ---

  /**
   * Host and port from {@code Forwarded host=}, {@code X-Forwarded-Host} or {@code Host}: every
   * value of each header in turn, as the main project's {@code
   * ForwardedHostAddressAndPortExtractor}. Returns {address, port} (either may be null).
   */
  public static Object[] serverAddress(String[] forwarded, String[] forwardedHost, String[] host) {
    Object[] sink = new Object[2];
    for (int i = 0; forwarded != null && i < forwarded.length; i++) {
      if (forwarded[i] != null && forwardedHostField(sink, forwarded[i])) {
        return sink;
      }
    }
    for (int i = 0; forwardedHost != null && i < forwardedHost.length; i++) {
      String v = forwardedHost[i];
      if (v != null && hostPort(sink, v, 0, v.length())) {
        return sink;
      }
    }
    for (int i = 0; host != null && i < host.length; i++) {
      String v = host[i];
      if (v != null && hostPort(sink, v, 0, v.length())) {
        return sink;
      }
    }
    return sink;
  }

  private static boolean forwardedHostField(Object[] sink, String forwarded) {
    int start = forwarded.toLowerCase(Locale.ROOT).indexOf("host=");
    if (start < 0) {
      return false;
    }
    start += "host=".length();
    if (start >= forwarded.length() - 1) {
      return false;
    }
    int end = forwarded.indexOf(';', start);
    if (end < 0) {
      end = forwarded.length();
    }
    return hostPort(sink, forwarded, start, end);
  }

  private static boolean hostPort(Object[] sink, String host, int start, int end) {
    if (start >= end) {
      return false;
    }
    if (host.charAt(start) == '"') {
      int quoteEnd = host.indexOf('"', start + 1);
      if (quoteEnd < 0 || quoteEnd >= end) {
        return false;
      }
      return hostPort(sink, host, start + 1, quoteEnd);
    }
    int separator = host.indexOf(':', start);
    if (separator < 0 || separator >= end) {
      sink[0] = host.substring(start, end);
    } else {
      sink[0] = host.substring(start, separator);
      if (separator + 1 < end) {
        try {
          sink[1] = Long.valueOf(Integer.parseInt(host.substring(separator + 1, end)));
        } catch (NumberFormatException ignored) {
          // no port
        }
      }
    }
    return true;
  }

  /** {@code url.scheme} from {@code Forwarded proto=} or {@code X-Forwarded-Proto}, else null. */
  public static String forwardedScheme(String[] forwarded, String[] forwardedProto) {
    for (int i = 0; forwarded != null && i < forwarded.length; i++) {
      String f = forwarded[i];
      if (f == null) {
        continue;
      }
      int start = f.toLowerCase(Locale.ROOT).indexOf("proto=");
      if (start >= 0) {
        start += 6;
        if (start < f.length() - 1) {
          String proto = proto(f, start);
          if (proto != null) {
            return proto;
          }
        }
      }
    }
    for (int i = 0; forwardedProto != null && i < forwardedProto.length; i++) {
      String proto = forwardedProto[i] == null ? null : proto(forwardedProto[i], 0);
      if (proto != null) {
        return proto;
      }
    }
    return null;
  }

  private static String proto(String value, int start) {
    if (value.length() == start) {
      return null;
    }
    if (value.charAt(start) == '"') {
      return proto(value, start + 1);
    }
    for (int i = start; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c == ',' || c == ';' || c == '"') {
        return i == start ? null : value.substring(start, i);
      }
    }
    return value.substring(start);
  }

  /** {@code client.address} from {@code Forwarded for=} or {@code X-Forwarded-For}, else peer. */
  public static String clientAddress(String[] forwarded, String[] forwardedFor, String peer) {
    for (int i = 0; forwarded != null && i < forwarded.length; i++) {
      String f = forwarded[i];
      if (f == null) {
        continue;
      }
      int start = f.toLowerCase(Locale.ROOT).indexOf("for=");
      if (start >= 0) {
        start += "for=".length();
        if (start < f.length() - 1) {
          int end = f.indexOf(';', start);
          String address = clientInfo(f, start, end < 0 ? f.length() : end);
          if (address != null) {
            return address;
          }
        }
      }
    }
    for (int i = 0; forwardedFor != null && i < forwardedFor.length; i++) {
      String v = forwardedFor[i];
      String address = v == null ? null : clientInfo(v, 0, v.length());
      if (address != null) {
        return address;
      }
    }
    return peer;
  }

  private static String clientInfo(String value, int start, int end) {
    if (start >= end) {
      return null;
    }
    if (value.charAt(start) == '"') {
      int quoteEnd = value.indexOf('"', start + 1);
      if (quoteEnd < 0 || quoteEnd >= end) {
        return null;
      }
      return clientInfo(value, start + 1, quoteEnd);
    }
    if (value.charAt(start) == '[') {
      int ipv6End = value.indexOf(']', start + 1);
      if (ipv6End < 0 || ipv6End >= end) {
        return null;
      }
      return value.substring(start + 1, ipv6End);
    }
    boolean inIpv4 = false;
    for (int i = start; i < end; ++i) {
      char c = value.charAt(i);
      if (c == '.') {
        inIpv4 = true;
      }
      if (c == ',' || c == ';' || c == '"' || (inIpv4 && c == ':')) {
        return i == start ? null : value.substring(start, i);
      }
    }
    return value.substring(start, end);
  }
}
