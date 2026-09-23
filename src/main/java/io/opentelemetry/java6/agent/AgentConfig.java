/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.java6.agent;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Agent configuration from the standard OpenTelemetry properties, so the agent can be dropped in
 * for the standard javaagent with the same command line. As in the standard agent, every property
 * can be given as a system property ({@code -Dotel.service.name=x}) or as an environment variable
 * ({@code OTEL_SERVICE_NAME=x}); the system property wins.
 *
 * <p>Properties the standard agent understands but this agent does not are reported as startup
 * warnings instead of failing (see {@link #unsupportedProperties}).
 */
public final class AgentConfig {

  /** The standard agent version this agent mirrors (telemetry.distro.version, scope versions). */
  public static final String AGENT_VERSION = "2.31.1";
  /** This agent's own version (build.sh {@code VERSION}), for logging. */
  public static final String JAVA6_AGENT_VERSION = ownVersion();
  /** The SDK version the standard agent bundles (telemetry.sdk.version). */
  public static final String SDK_VERSION = "1.65.0";

  public final boolean enabled;
  public final String serviceName;
  public final Map<String, String> resourceAttributes;
  public final List<String> disabledResourceProviders;

  public final String tracesExporter;
  public final String metricsExporter;
  public final String tracesEndpoint;
  public final String metricsEndpoint;
  public final String zipkinEndpoint;
  public final Map<String, String> tracesHeaders;
  public final Map<String, String> metricsHeaders;
  public final String tracesCompression;
  public final String metricsCompression;
  public final int tracesTimeoutMs;
  public final int metricsTimeoutMs;
  public final String tracesCertificate;
  public final String metricsCertificate;
  public final String trustStore;
  public final String trustStorePassword;
  public final String temporalityPreference;
  public final long metricExportIntervalMs;

  public final String sampler;
  public final String samplerArg;
  public final List<String> propagators;

  public final long bspScheduleDelayMs;
  public final int bspMaxQueueSize;
  public final int bspMaxExportBatchSize;
  public final long bspExportTimeoutMs;

  public final List<String> knownMethods;
  public final List<String> serverRequestHeaders;
  public final List<String> serverResponseHeaders;
  public final List<String> clientRequestHeaders;
  public final List<String> clientResponseHeaders;
  public final boolean statementSanitizer;

  public final List<String> unsupportedProperties = new ArrayList<String>();

  private final boolean defaultEnabled;
  private final List<String> legacyDisabled;

  private AgentConfig() {
    enabled =
        !"false".equalsIgnoreCase(get("otel.javaagent.enabled", "true"))
            && !"true".equalsIgnoreCase(get("otel.sdk.disabled", "false"));

    resourceAttributes = parseKeyValues(get("otel.resource.attributes", ""), true);
    String name = get("otel.service.name", null);
    if (name == null || name.length() == 0) {
      name = resourceAttributes.get("service.name");
    }
    serviceName = name == null || name.length() == 0 ? "unknown_service:java" : name;
    disabledResourceProviders = list(get("otel.java.disabled.resource.providers", ""));

    tracesExporter = firstExporter("otel.traces.exporter");
    metricsExporter = firstExporter("otel.metrics.exporter");
    String logsExporter = get("otel.logs.exporter", "otlp");
    if (!"none".equals(logsExporter)) {
      // the standard agent exports logs by default; this agent has no log bridge
      if (get("otel.logs.exporter", null) != null) {
        unsupportedProperties.add("otel.logs.exporter=" + logsExporter + " (no log export)");
      }
    }

    String protocol = get("otel.exporter.otlp.protocol", "http/protobuf");
    if (!"http/protobuf".equals(protocol)) {
      unsupportedProperties.add(
          "otel.exporter.otlp.protocol=" + protocol + " (only http/protobuf; using it)");
    }
    String base = get("otel.exporter.otlp.endpoint", "http://localhost:4318");
    tracesEndpoint = signalEndpoint("traces", base);
    metricsEndpoint = signalEndpoint("metrics", base);
    zipkinEndpoint =
        get("otel.exporter.zipkin.endpoint",
            get("otel.zipkin.endpoint", "http://localhost:9411/api/v2/spans"));
    Map<String, String> headers = parseKeyValues(get("otel.exporter.otlp.headers", ""), true);
    tracesHeaders = merged(headers, parseKeyValues(get("otel.exporter.otlp.traces.headers", ""), true));
    metricsHeaders =
        merged(headers, parseKeyValues(get("otel.exporter.otlp.metrics.headers", ""), true));
    String compression = get("otel.exporter.otlp.compression", "none");
    tracesCompression = get("otel.exporter.otlp.traces.compression", compression);
    metricsCompression = get("otel.exporter.otlp.metrics.compression", compression);
    int timeout = (int) durationMs(get("otel.exporter.otlp.timeout", null), 10000L);
    tracesTimeoutMs = (int) durationMs(get("otel.exporter.otlp.traces.timeout", null), timeout);
    metricsTimeoutMs = (int) durationMs(get("otel.exporter.otlp.metrics.timeout", null), timeout);
    String certificate = get("otel.exporter.otlp.certificate", null);
    tracesCertificate = get("otel.exporter.otlp.traces.certificate", certificate);
    metricsCertificate = get("otel.exporter.otlp.metrics.certificate", certificate);
    trustStore = System.getProperty("javax.net.ssl.trustStore");
    trustStorePassword = System.getProperty("javax.net.ssl.trustStorePassword");
    if (get("otel.exporter.otlp.client.key", null) != null
        || get("otel.exporter.otlp.client.certificate", null) != null) {
      unsupportedProperties.add("otel.exporter.otlp.client.key/certificate (no client TLS auth)");
    }
    temporalityPreference =
        get("otel.exporter.otlp.metrics.temporality.preference", "cumulative");
    metricExportIntervalMs = durationMs(get("otel.metric.export.interval", null), 60000L);

    sampler = get("otel.traces.sampler", "parentbased_always_on");
    samplerArg = get("otel.traces.sampler.arg", null);
    propagators = list(get("otel.propagators", "tracecontext,baggage"));
    for (int i = 0; i < propagators.size(); i++) {
      String p = propagators.get(i);
      if (!"tracecontext".equals(p) && !"baggage".equals(p) && !"b3".equals(p)
          && !"b3multi".equals(p) && !"none".equals(p)) {
        unsupportedProperties.add("otel.propagators entry '" + p + "'");
      }
    }

    bspScheduleDelayMs = durationMs(get("otel.bsp.schedule.delay", null), 5000L);
    bspMaxQueueSize = intValue(get("otel.bsp.max.queue.size", get("otel.bsp.max-queue-size", null)), 2048);
    bspMaxExportBatchSize =
        intValue(get("otel.bsp.max.export.batch.size", get("otel.bsp.max-export-batch-size", null)), 512);
    bspExportTimeoutMs = durationMs(get("otel.bsp.export.timeout", null), 30000L);

    knownMethods =
        list(get("otel.instrumentation.http.known-methods",
            "CONNECT,DELETE,GET,HEAD,OPTIONS,PATCH,POST,PUT,TRACE"));
    serverRequestHeaders = list(get("otel.instrumentation.http.server.capture-request-headers", ""));
    serverResponseHeaders =
        list(get("otel.instrumentation.http.server.capture-response-headers", ""));
    clientRequestHeaders = list(get("otel.instrumentation.http.client.capture-request-headers", ""));
    clientResponseHeaders =
        list(get("otel.instrumentation.http.client.capture-response-headers", ""));
    statementSanitizer =
        !"false".equalsIgnoreCase(
            get("otel.instrumentation.jdbc.statement-sanitizer.enabled",
                get("otel.instrumentation.common.db-statement-sanitizer.enabled", "true")));

    defaultEnabled =
        !"false".equalsIgnoreCase(get("otel.instrumentation.common.default-enabled", "true"));
    legacyDisabled = list(get("otel.javaagent.disabled-instrumentations", ""));

    String extensions = get("otel.javaagent.extensions", null);
    if (extensions != null && extensions.length() > 0) {
      unsupportedProperties.add("otel.javaagent.extensions (extensions are not supported)");
    }
    if (get("otel.config.file", null) != null || get("otel.experimental.config.file", null) != null) {
      unsupportedProperties.add("otel.config.file (declarative configuration is not supported)");
    }
  }

  private static String ownVersion() {
    java.io.InputStream in =
        AgentConfig.class.getResourceAsStream("/io/opentelemetry/java6/agent/version.properties");
    if (in == null) {
      return "unknown";
    }
    try {
      java.util.Properties p = new java.util.Properties();
      p.load(in);
      return p.getProperty("version", "unknown");
    } catch (java.io.IOException e) {
      return "unknown";
    } finally {
      try {
        in.close();
      } catch (java.io.IOException ignored) {
        // nothing to do
      }
    }
  }

  public static AgentConfig fromSystemProperties() {
    return new AgentConfig();
  }

  /**
   * Whether instrumentation {@code name} is enabled: {@code otel.instrumentation.<name>.enabled},
   * defaulting to {@code otel.instrumentation.common.default-enabled}. Any alias (the standard
   * agent accepts several names per instrumentation) disables it.
   */
  public boolean isInstrumentationEnabled(String... names) {
    boolean enabledByDefault = defaultEnabled;
    for (int i = 0; i < names.length; i++) {
      if (legacyDisabled.contains(names[i]) || legacyDisabled.contains("io.opentelemetry." + names[i])) {
        return false;
      }
      String value = get("otel.instrumentation." + names[i] + ".enabled", null);
      if (value != null) {
        return !"false".equalsIgnoreCase(value.trim());
      }
    }
    return enabledByDefault;
  }

  /** A property from system properties, else the equivalent {@code OTEL_...} environment variable. */
  public static String get(String name, String dflt) {
    String value = System.getProperty(name);
    if (value == null) {
      try {
        value = System.getenv(name.toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_'));
      } catch (SecurityException e) {
        value = null;
      }
    }
    return value == null ? dflt : value.trim();
  }

  private String firstExporter(String property) {
    List<String> exporters = list(get(property, "otlp"));
    String first = exporters.isEmpty() ? "none" : exporters.get(0);
    if ("console".equals(first) || "logging-otlp".equals(first)) {
      first = "logging";
    }
    if (exporters.size() > 1) {
      unsupportedProperties.add(property + "=" + exporters + " (only the first exporter is used)");
    }
    if (!"otlp".equals(first) && !"logging".equals(first) && !"none".equals(first)
        && !("zipkin".equals(first) && property.contains("traces"))) {
      unsupportedProperties.add(property + "=" + first + " (using otlp)");
      first = "otlp";
    }
    return first;
  }

  private static String signalEndpoint(String signal, String base) {
    String specific = get("otel.exporter.otlp." + signal + ".endpoint", null);
    if (specific != null && specific.length() > 0) {
      return specific;
    }
    return (base.endsWith("/") ? base.substring(0, base.length() - 1) : base) + "/v1/" + signal;
  }

  private static Map<String, String> merged(Map<String, String> a, Map<String, String> b) {
    Map<String, String> result = new LinkedHashMap<String, String>(a);
    result.putAll(b);
    return result;
  }

  /** Parses {@code k1=v1,k2=v2} (W3C baggage-style percent-encoding decoded, as the SDK). */
  static Map<String, String> parseKeyValues(String raw, boolean decode) {
    Map<String, String> result = new LinkedHashMap<String, String>();
    if (raw == null || raw.length() == 0) {
      return result;
    }
    String[] pairs = raw.split(",");
    for (int i = 0; i < pairs.length; i++) {
      int eq = pairs[i].indexOf('=');
      if (eq > 0) {
        String key = pairs[i].substring(0, eq).trim();
        String value = pairs[i].substring(eq + 1).trim();
        if (decode) {
          try {
            value = URLDecoder.decode(value.replace("+", "%2B"), "UTF-8");
          } catch (UnsupportedEncodingException e) {
            // UTF-8 always exists
          } catch (IllegalArgumentException e) {
            // not percent-encoded; keep as is
          }
        }
        result.put(key, value);
      }
    }
    return result;
  }

  static List<String> list(String raw) {
    List<String> result = new ArrayList<String>();
    if (raw == null) {
      return result;
    }
    String[] parts = raw.split(",");
    for (int i = 0; i < parts.length; i++) {
      String p = parts[i].trim();
      if (p.length() > 0) {
        result.add(p);
      }
    }
    return result;
  }

  private static int intValue(String raw, int dflt) {
    try {
      return raw == null || raw.length() == 0 ? dflt : Integer.parseInt(raw.trim());
    } catch (NumberFormatException e) {
      return dflt;
    }
  }

  /** A duration as the SDK accepts it: a number of ms, or with a ms/s/m/h/d unit suffix. */
  static long durationMs(String raw, long dflt) {
    if (raw == null || raw.length() == 0) {
      return dflt;
    }
    String s = raw.trim().toLowerCase(Locale.ROOT);
    long multiplier = 1;
    if (s.endsWith("ms")) {
      s = s.substring(0, s.length() - 2);
    } else if (s.endsWith("s")) {
      multiplier = 1000L;
      s = s.substring(0, s.length() - 1);
    } else if (s.endsWith("m")) {
      multiplier = 60000L;
      s = s.substring(0, s.length() - 1);
    } else if (s.endsWith("h")) {
      multiplier = 3600000L;
      s = s.substring(0, s.length() - 1);
    } else if (s.endsWith("d")) {
      multiplier = 86400000L;
      s = s.substring(0, s.length() - 1);
    }
    try {
      return Long.parseLong(s.trim()) * multiplier;
    } catch (NumberFormatException e) {
      return dflt;
    }
  }
}
