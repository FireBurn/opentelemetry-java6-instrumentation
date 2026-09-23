/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.java6.agent.context;

import java.util.ArrayList;
import java.util.List;

/**
 * Context propagation configured by {@code otel.propagators} (default {@code tracecontext,baggage},
 * as the standard agent): W3C {@code traceparent}/{@code tracestate}, W3C {@code baggage} (passed
 * through unchanged), B3 single ({@code b3}) and B3 multi ({@code b3multi}). Extraction applies the
 * configured propagators in order, a later one overriding an earlier one's span context.
 *
 * <p>Bootstrap helpers may only use JDK types, so header access is by name: instrumentation reads
 * the values of {@link #EXTRACT_HEADERS} (in that order) from the request itself and hands them
 * over as a {@code String[]}; {@link #inject} returns name/value pairs to set on the request.
 */
public final class Propagator {

  /** Headers read for extraction, in this order. Lookups must be case-insensitive. */
  public static final String[] EXTRACT_HEADERS = {
    "traceparent", "tracestate", "b3", "X-B3-TraceId", "X-B3-SpanId", "X-B3-Sampled", "baggage"
  };

  private static final int TRACEPARENT = 0;
  private static final int TRACESTATE = 1;
  private static final int B3 = 2;
  private static final int B3_TRACE_ID = 3;
  private static final int B3_SPAN_ID = 4;
  private static final int B3_SAMPLED = 5;
  private static final int BAGGAGE = 6;

  private static volatile boolean traceContext = true;
  private static volatile boolean baggage = true;
  private static volatile boolean b3Single;
  private static volatile boolean b3Multi;
  private static volatile List<String> order = defaultOrder();

  private Propagator() {}

  private static List<String> defaultOrder() {
    List<String> list = new ArrayList<String>();
    list.add("tracecontext");
    list.add("baggage");
    return list;
  }

  /** Configures the propagators from {@code otel.propagators}. */
  public static void configure(List<String> propagators) {
    List<String> list = new ArrayList<String>();
    boolean tc = false;
    boolean bg = false;
    boolean b3s = false;
    boolean b3m = false;
    for (int i = 0; i < propagators.size(); i++) {
      String p = propagators.get(i);
      if ("tracecontext".equals(p)) {
        tc = true;
      } else if ("baggage".equals(p)) {
        bg = true;
      } else if ("b3".equals(p)) {
        b3s = true;
      } else if ("b3multi".equals(p)) {
        b3m = true;
      } else {
        continue;
      }
      list.add(p);
    }
    traceContext = tc;
    baggage = bg;
    b3Single = b3s;
    b3Multi = b3m;
    order = list;
  }

  /**
   * Extracts the remote parent and baggage from header values ordered as {@link
   * #EXTRACT_HEADERS}; returns {@code parent} unchanged when nothing is found.
   */
  public static Context extract(Context parent, String[] values) {
    Context result = parent;
    List<String> propagators = order;
    for (int i = 0; i < propagators.size(); i++) {
      String p = propagators.get(i);
      SpanContext remote = null;
      if ("tracecontext".equals(p)) {
        remote = parseTraceparent(values[TRACEPARENT], values[TRACESTATE]);
      } else if ("b3".equals(p)) {
        remote = parseB3Single(values[B3]);
        if (remote == null) {
          remote = parseB3Multi(values[B3_TRACE_ID], values[B3_SPAN_ID], values[B3_SAMPLED]);
        }
      } else if ("b3multi".equals(p)) {
        remote = parseB3Multi(values[B3_TRACE_ID], values[B3_SPAN_ID], values[B3_SAMPLED]);
        if (remote == null) {
          remote = parseB3Single(values[B3]);
        }
      } else if ("baggage".equals(p) && values[BAGGAGE] != null && values[BAGGAGE].length() > 0) {
        result = result.withBaggage(values[BAGGAGE]);
      }
      if (remote != null) {
        result = result.withRemoteParent(remote);
      }
    }
    return result;
  }

  /** Name/value pairs (flattened) to add to an outgoing request for {@code context}. */
  public static String[] inject(Context context) {
    SpanContext sc = context.spanContext();
    List<String> out = new ArrayList<String>(8);
    if (sc != null) {
      if (traceContext) {
        out.add("traceparent");
        out.add(toTraceparent(sc));
        if (sc.traceState != null && sc.traceState.length() > 0) {
          out.add("tracestate");
          out.add(sc.traceState);
        }
      }
      if (b3Single) {
        out.add("b3");
        out.add(sc.traceId + "-" + sc.spanId + "-" + (sc.sampled ? "1" : "0"));
      }
      if (b3Multi) {
        out.add("X-B3-TraceId");
        out.add(sc.traceId);
        out.add("X-B3-SpanId");
        out.add(sc.spanId);
        out.add("X-B3-Sampled");
        out.add(sc.sampled ? "1" : "0");
      }
    }
    if (baggage && context.baggage() != null) {
      out.add("baggage");
      out.add(context.baggage());
    }
    return out.toArray(new String[out.size()]);
  }

  /**
   * Parses a W3C {@code traceparent}: {@code 00-<32 hex>-<16 hex>-<2 hex>}; later versions are
   * accepted when at least as long as version 00 (extra fields ignored), version ff never.
   */
  public static SpanContext parseTraceparent(String value, String traceState) {
    if (value == null) {
      return null;
    }
    value = value.trim();
    if (value.length() < 55) {
      return null;
    }
    String version = value.substring(0, 2);
    if (!isHex(version) || "ff".equals(version)) {
      return null;
    }
    if ("00".equals(version) && value.length() != 55) {
      return null;
    }
    if (value.length() > 55 && value.charAt(55) != '-') {
      return null;
    }
    if (value.charAt(2) != '-' || value.charAt(35) != '-' || value.charAt(52) != '-') {
      return null;
    }
    String traceId = value.substring(3, 35);
    String spanId = value.substring(36, 52);
    String flags = value.substring(53, 55);
    if (!isLowerHex(traceId) || !isLowerHex(spanId) || !isHex(flags)) {
      return null;
    }
    if (isAllZero(traceId) || isAllZero(spanId)) {
      return null;
    }
    boolean sampled = (Character.digit(flags.charAt(1), 16) & 1) == 1;
    return new SpanContext(traceId, spanId, sampled, true, traceState);
  }

  /** Backwards-compatible single-argument form. */
  public static SpanContext parseTraceparent(String value) {
    return parseTraceparent(value, null);
  }

  /** B3 single header: {@code <32|16 hex>-<16 hex>[-<0|1|d>[-<parent>]]}. */
  public static SpanContext parseB3Single(String value) {
    if (value == null) {
      return null;
    }
    String[] parts = value.trim().split("-");
    if (parts.length < 2 || parts.length > 4) {
      return null;
    }
    boolean sampled = true;
    if (parts.length >= 3) {
      String flag = parts[2];
      if ("0".equals(flag)) {
        sampled = false;
      } else if (!"1".equals(flag) && !"d".equals(flag)) {
        return null;
      }
    }
    return b3Context(parts[0], parts[1], sampled);
  }

  /** B3 multi headers ({@code X-B3-TraceId}, {@code X-B3-SpanId}, {@code X-B3-Sampled}). */
  public static SpanContext parseB3Multi(String traceId, String spanId, String sampledValue) {
    if (traceId == null || spanId == null) {
      return null;
    }
    boolean sampled = !"0".equals(sampledValue) && !"false".equalsIgnoreCase(sampledValue);
    return b3Context(traceId.trim(), spanId.trim(), sampled);
  }

  private static SpanContext b3Context(String traceId, String spanId, boolean sampled) {
    if ((traceId.length() != 32 && traceId.length() != 16) || spanId.length() != 16) {
      return null;
    }
    traceId = traceId.toLowerCase(java.util.Locale.ROOT);
    spanId = spanId.toLowerCase(java.util.Locale.ROOT);
    if (!isLowerHex(traceId) || !isLowerHex(spanId) || isAllZero(traceId) || isAllZero(spanId)) {
      return null;
    }
    if (traceId.length() == 16) {
      traceId = "0000000000000000" + traceId;
    }
    return new SpanContext(traceId, spanId, sampled, true, null);
  }

  /** Renders a W3C {@code traceparent} header value. */
  public static String toTraceparent(SpanContext context) {
    return "00-" + context.traceId + "-" + context.spanId + "-" + (context.sampled ? "01" : "00");
  }

  private static boolean isHex(String s) {
    for (int i = 0; i < s.length(); i++) {
      if (Character.digit(s.charAt(i), 16) < 0) {
        return false;
      }
    }
    return true;
  }

  private static boolean isLowerHex(String s) {
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) {
        return false;
      }
    }
    return true;
  }

  private static boolean isAllZero(String s) {
    for (int i = 0; i < s.length(); i++) {
      if (s.charAt(i) != '0') {
        return false;
      }
    }
    return true;
  }
}
