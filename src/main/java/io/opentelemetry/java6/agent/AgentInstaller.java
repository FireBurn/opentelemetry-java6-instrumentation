/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.java6.agent;

import static net.bytebuddy.matcher.ElementMatchers.isProtected;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.isSynthetic;
import static net.bytebuddy.matcher.ElementMatchers.nameStartsWith;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.namedOneOf;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;
import static net.bytebuddy.matcher.ElementMatchers.takesNoArguments;

import io.opentelemetry.java6.agent.context.Propagator;
import io.opentelemetry.java6.agent.export.HttpSender;
import io.opentelemetry.java6.agent.export.LoggingMetricExporter;
import io.opentelemetry.java6.agent.export.OtlpMetricExporter;
import io.opentelemetry.java6.agent.export.OtlpSpanExporter;
import io.opentelemetry.java6.agent.export.ZipkinSpanExporter;
import io.opentelemetry.java6.agent.instrumentation.HttpSemconv;
import io.opentelemetry.java6.agent.instrumentation.http.HttpUrlConnectionAdvice;
import io.opentelemetry.java6.agent.instrumentation.httpserver.HttpServerAdvice;
import io.opentelemetry.java6.agent.instrumentation.jdbc.JdbcAdvice;
import io.opentelemetry.java6.agent.instrumentation.jdbc.JdbcHelper;
import io.opentelemetry.java6.agent.instrumentation.servlet.ResponseAdvice;
import io.opentelemetry.java6.agent.instrumentation.servlet.ServletAdvice;
import io.opentelemetry.java6.agent.metrics.JvmMetrics;
import io.opentelemetry.java6.agent.metrics.MeterRegistry;
import io.opentelemetry.java6.agent.metrics.MetricExporter;
import io.opentelemetry.java6.agent.metrics.PeriodicMetricReader;
import io.opentelemetry.java6.agent.trace.BatchSpanProcessor;
import io.opentelemetry.java6.agent.trace.LoggingSpanExporter;
import io.opentelemetry.java6.agent.trace.NoopSpanExporter;
import io.opentelemetry.java6.agent.trace.Sampler;
import io.opentelemetry.java6.agent.trace.SpanExporter;
import io.opentelemetry.java6.agent.trace.Tracer;
import java.io.File;
import java.lang.instrument.Instrumentation;
import java.util.AbstractMap;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Logger;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.pool.TypePool;

/**
 * Wires up the telemetry pipeline and installs the ByteBuddy transformer.
 *
 * <p>Advice is inlined (no {@code invokedynamic}), the only mode that works on Java 6. The advice
 * classes live in the agent jar on the bootstrap class path, so inlined code in any class loader
 * can resolve them.
 */
public final class AgentInstaller {

  private static final Logger logger = Logger.getLogger("io.opentelemetry.java6.agent");

  private AgentInstaller() {}

  public static void install(File agentJar, Instrumentation instrumentation) throws Exception {
    AgentConfig config = AgentConfig.fromSystemProperties();
    if (!config.enabled) {
      logger.info("otel-java6 agent disabled");
      return;
    }
    for (int i = 0; i < config.unsupportedProperties.size(); i++) {
      logger.warning("otel-java6 agent: unsupported: " + config.unsupportedProperties.get(i));
    }
    Propagator.configure(config.propagators);
    HttpSemconv.configure(config);
    JdbcHelper.configure(config.statementSanitizer);
    MeterRegistry.setTemporalityPreference(config.temporalityPreference);
    Map<String, Object> resource = ResourceAttributes.build(config);

    final BatchSpanProcessor processor =
        new BatchSpanProcessor(
            spanExporter(config, resource),
            config.bspScheduleDelayMs,
            config.bspMaxQueueSize,
            config.bspMaxExportBatchSize);
    Tracer.init(processor, Sampler.create(config.sampler, config.samplerArg));

    MetricExporter metricExporter = metricExporter(config, resource);
    final PeriodicMetricReader metricReader =
        metricExporter == null
            ? null
            : new PeriodicMetricReader(metricExporter, config.metricExportIntervalMs);
    if (metricReader != null
        && config.isInstrumentationEnabled("runtime-telemetry", "runtime-telemetry-java8")) {
      JvmMetrics.register(AgentConfig.AGENT_VERSION);
    }
    final long shutdownTimeout = config.bspExportTimeoutMs;
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                new Runnable() {
                  public void run() {
                    processor.shutdown(shutdownTimeout);
                    if (metricReader != null) {
                      MeterRegistry.removeScope(JvmMetrics.SCOPE);
                      metricReader.shutdown();
                    }
                  }
                },
                "otel-java6-shutdown"));

    installInstrumentation(config, agentJar, instrumentation);
    logger.info(
        "opentelemetry-java6agent " + AgentConfig.JAVA6_AGENT_VERSION + " (standard agent "
            + AgentConfig.AGENT_VERSION + " compatible) installed (service="
            + config.serviceName + ", traces=" + config.tracesExporter + ", metrics="
            + config.metricsExporter + ")");
  }

  private static SpanExporter spanExporter(AgentConfig config, Map<String, Object> resource)
      throws Exception {
    if ("otlp".equals(config.tracesExporter)) {
      return new OtlpSpanExporter(
          new HttpSender(
              config.tracesEndpoint, "application/x-protobuf", config.tracesHeaders,
              config.tracesCompression, config.tracesTimeoutMs, config.tracesCertificate,
              config.trustStore, config.trustStorePassword),
          resource,
          AgentConfig.AGENT_VERSION);
    }
    if ("zipkin".equals(config.tracesExporter)) {
      return new ZipkinSpanExporter(
          new HttpSender(
              config.zipkinEndpoint, "application/json", Collections.<String, String>emptyMap(),
              "none", config.tracesTimeoutMs, config.tracesCertificate, config.trustStore,
              config.trustStorePassword),
          config.serviceName,
          AgentConfig.AGENT_VERSION);
    }
    if ("logging".equals(config.tracesExporter)) {
      return new LoggingSpanExporter(AgentConfig.AGENT_VERSION);
    }
    return new NoopSpanExporter();
  }

  private static MetricExporter metricExporter(AgentConfig config, Map<String, Object> resource)
      throws Exception {
    if ("otlp".equals(config.metricsExporter)) {
      return new OtlpMetricExporter(
          new HttpSender(
              config.metricsEndpoint, "application/x-protobuf", config.metricsHeaders,
              config.metricsCompression, config.metricsTimeoutMs, config.metricsCertificate,
              config.trustStore, config.trustStorePassword),
          resource);
    }
    if ("logging".equals(config.metricsExporter)) {
      return new LoggingMetricExporter();
    }
    return null;
  }

  private static AgentBuilder.Transformer.ForAdvice advice(ClassFileLocator locator) {
    return new AgentBuilder.Transformer.ForAdvice().include(locator);
  }

  private static void installInstrumentation(
      AgentConfig config, File agentJar, Instrumentation instrumentation) throws Exception {
    // where ByteBuddy reads the advice bytecode from (the default locator cannot see bootstrap)
    ClassFileLocator locator = ClassFileLocator.ForJarFile.of(agentJar);

    ElementMatcher.Junction<TypeDescription> ignored =
        nameStartsWith("io.opentelemetry.java6.")
            .or(nameStartsWith("io.opentelemetry.proto."))
            .or(nameStartsWith("net.bytebuddy."))
            .or(nameStartsWith("org.bouncycastle."))
            .or(nameStartsWith("com.google.protobuf."))
            .or(nameStartsWith("sun.reflect."))
            .or(nameStartsWith("jdk.internal.reflect."))
            .or(nameStartsWith("com.ibm.oti.reflect."))
            .or(nameStartsWith("java.lang.invoke."))
            .<TypeDescription>or(isSynthetic());

    AgentBuilder builder =
        new AgentBuilder.Default()
            .disableClassFormatChanges()
            .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
            .with(new AgentBuilder.PoolStrategy.WithTypePoolCache.Simple(
                TypePool.Default.ReaderMode.FAST, new WeakConcurrentMap()))
            .with(new EngineListener())
            .ignore(ignored);

    if (config.isInstrumentationEnabled("jdbc")) {
      builder =
          builder
              .type(TypeMatchers.implementsInterface("java.sql.Driver"))
              .transform(
                  advice(locator)
                      .advice(
                          named("connect")
                              .and(takesArguments(2))
                              .and(takesArgument(0, String.class))
                              .and(takesArgument(1, java.util.Properties.class)),
                          JdbcAdvice.DriverConnectAdvice.class.getName()))
              .type(TypeMatchers.implementsInterface("java.sql.Connection"))
              .transform(
                  advice(locator)
                      .advice(
                          nameStartsWith("prepare").and(takesArgument(0, String.class)),
                          JdbcAdvice.PrepareAdvice.class.getName()))
              .type(TypeMatchers.implementsInterface("java.sql.Statement"))
              .transform(
                  advice(locator)
                      .advice(
                          nameStartsWith("execute").and(takesArgument(0, String.class))
                              .and(isPublic()),
                          JdbcAdvice.StatementAdvice.class.getName())
                      .advice(
                          named("addBatch").and(takesArguments(1))
                              .and(takesArgument(0, String.class)).and(isPublic()),
                          JdbcAdvice.AddBatchAdvice.class.getName())
                      .advice(
                          named("clearBatch").and(takesNoArguments()).and(isPublic()),
                          JdbcAdvice.ClearBatchAdvice.class.getName())
                      .advice(
                          namedOneOf("executeBatch", "executeLargeBatch").and(takesNoArguments())
                              .and(isPublic()),
                          JdbcAdvice.ExecuteBatchAdvice.class.getName())
                      .advice(
                          named("close").and(takesNoArguments()).and(isPublic()),
                          JdbcAdvice.CloseAdvice.class.getName()))
              .type(TypeMatchers.implementsInterface("java.sql.PreparedStatement"))
              .transform(
                  advice(locator)
                      .advice(
                          nameStartsWith("execute")
                              .and(not(namedOneOf("executeBatch", "executeLargeBatch")))
                              .and(takesNoArguments())
                              .and(isPublic()),
                          JdbcAdvice.PreparedStatementAdvice.class.getName())
                      .advice(
                          named("addBatch").and(takesNoArguments()).and(isPublic()),
                          JdbcAdvice.AddPreparedBatchAdvice.class.getName()));
    }
    if (config.isInstrumentationEnabled("http-url-connection")) {
      builder =
          builder
              .type(
                  nameStartsWith("java.net.")
                      .or(nameStartsWith("sun.net"))
                      .or(named("weblogic.net.http.HttpURLConnection"))
                      .and(not(named("sun.net.www.protocol.https.HttpsURLConnectionImpl")))
                      .and(TypeMatchers.extendsClass("java.net.HttpURLConnection")))
              .transform(
                  advice(locator)
                      .advice(
                          isPublic()
                              .and(namedOneOf("connect", "getOutputStream", "getInputStream"))
                              .or(isProtected().and(named("plainConnect"))),
                          HttpUrlConnectionAdvice.ConnectionAdvice.class.getName())
                      .advice(
                          isPublic().and(named("getResponseCode")),
                          HttpUrlConnectionAdvice.ResponseCodeAdvice.class.getName()));
    }
    if (config.isInstrumentationEnabled("java-http-server")) {
      builder =
          builder
              .type(TypeMatchers.extendsClass("com.sun.net.httpserver.HttpServer"))
              .transform(
                  advice(locator)
                      .advice(
                          named("createContext").and(takesArgument(0, String.class))
                              .and(isPublic()),
                          HttpServerAdvice.BuildAdvice.class.getName()));
    }
    if (config.isInstrumentationEnabled("servlet", "servlet-3.0")) {
      builder =
          builder
              .type(
                  TypeMatchers.implementsInterface("javax.servlet.Servlet")
                      .or(TypeMatchers.implementsInterface("javax.servlet.Filter")))
              .transform(
                  advice(locator)
                      .advice(
                          named("service")
                              .and(takesArguments(2))
                              .and(takesArgument(0, named("javax.servlet.ServletRequest")))
                              .and(takesArgument(1, named("javax.servlet.ServletResponse")))
                              .and(isPublic()),
                          ServletAdvice.ServiceAdvice.class.getName())
                      .advice(
                          named("doFilter")
                              .and(takesArguments(3))
                              .and(takesArgument(0, named("javax.servlet.ServletRequest")))
                              .and(takesArgument(1, named("javax.servlet.ServletResponse")))
                              .and(isPublic()),
                          ServletAdvice.ServiceAdvice.class.getName())
                      .advice(
                          named("init")
                              .and(takesArguments(1))
                              .and(takesArgument(0, named("javax.servlet.FilterConfig"))),
                          ServletAdvice.FilterInitAdvice.class.getName()))
              .type(TypeMatchers.implementsInterface("javax.servlet.http.HttpServletResponse"))
              .transform(
                  advice(locator)
                      .advice(
                          namedOneOf("sendError", "sendRedirect"),
                          ResponseAdvice.SendAdvice.class.getName()));
    }
    builder.installOn(instrumentation);
  }

  /**
   * A weak-keyed map for ByteBuddy's per-class-loader type pool caches, so redeployed
   * applications' class loaders can be collected.
   */
  static final class WeakConcurrentMap extends AbstractMap<Object, TypePool.CacheProvider>
      implements ConcurrentMap<Object, TypePool.CacheProvider> {

    private final Map<Object, TypePool.CacheProvider> map =
        Collections.synchronizedMap(new WeakHashMap<Object, TypePool.CacheProvider>());

    @Override
    public Set<Map.Entry<Object, TypePool.CacheProvider>> entrySet() {
      return map.entrySet();
    }

    @Override
    public TypePool.CacheProvider get(Object key) {
      return map.get(key);
    }

    @Override
    public TypePool.CacheProvider put(Object key, TypePool.CacheProvider value) {
      return map.put(key, value);
    }

    public TypePool.CacheProvider putIfAbsent(Object key, TypePool.CacheProvider value) {
      synchronized (map) {
        TypePool.CacheProvider existing = map.get(key);
        if (existing == null) {
          map.put(key, value);
        }
        return existing;
      }
    }

    public boolean remove(Object key, Object value) {
      synchronized (map) {
        if (value != null && value.equals(map.get(key))) {
          map.remove(key);
          return true;
        }
        return false;
      }
    }

    public boolean replace(
        Object key, TypePool.CacheProvider oldValue, TypePool.CacheProvider newValue) {
      synchronized (map) {
        if (oldValue != null && oldValue.equals(map.get(key))) {
          map.put(key, newValue);
          return true;
        }
        return false;
      }
    }

    public TypePool.CacheProvider replace(Object key, TypePool.CacheProvider value) {
      synchronized (map) {
        return map.containsKey(key) ? map.put(key, value) : null;
      }
    }
  }
}
