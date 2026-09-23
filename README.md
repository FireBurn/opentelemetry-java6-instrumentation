# java6-agent — OpenTelemetry agent for Java 6 and Java 7

A Java agent for **Java 6 and 7 JVMs** (in particular IBM J9 Java 6 under WebSphere 8.5) that is a
drop-in replacement for the standard
[opentelemetry-javaagent](https://github.com/open-telemetry/opentelemetry-java-instrumentation),
which needs Java 8+. It accepts the same `otel.*` configuration and exports the same traces and
metrics: its output is diffed against the standard agent (currently **2.31.1**) in CI.

If the JVM is Java 8 or newer, use the standard agent.

Maven coordinates of the published jar: `io.opentelemetry.javaagent:opentelemetry-java6agent`
(this agent's own version, starting at 1.0.0; the standard agent version it mirrors is reported as
`telemetry.distro.version`, currently 2.31.1). The build sets the version with `VERSION=x.y.z
./build.sh` (default 1.0.0); it is in the jar manifest and the startup log line.

- [Usage](#usage)
- [What is instrumented](#what-is-instrumented)
- [Configuration](#configuration)
- [Differences from the standard agent](#differences-from-the-standard-agent)
- [Building](#building)
- [Testing](#testing)
- [How it works](#how-it-works)

## Usage

```
java -javaagent:/path/to/opentelemetry-java6-agent.jar \
     -Dotel.resource.attributes=deployment.environment.name=prd,service.name=my-service \
     -Dotel.exporter.otlp.endpoint=https://collector.example.com \
     -Dotel.exporter.otlp.certificate=/path/to/ca.pem \
     -jar myapp.jar
```

On WebSphere traditional, put the same string in the server's *Generic JVM arguments*
(`server.xml` `jvmEntries/@genericJvmArguments`). Every class in the jar is Java 6 bytecode
(major version 50) and the agent references no JDK API newer than Java 6 (both checked by the
build).

## What is instrumented

The same instrumentations, scopes, span names, attributes (with their OTLP types), status and
events as the standard agent — verified span-for-span by the parity test:

| Instrumentation | Scope | Notes |
| --- | --- | --- |
| Servlet 3.0 (`Servlet.service`, `Filter.doFilter`) | `io.opentelemetry.servlet-3.0` | one SERVER span per request, started by the first filter/servlet; route from the servlet (or filter) mapping, span named `GET /ctx/mapping`; forwards/includes/async dispatches stay in the same span; async requests end via `AsyncListener`; W3C/B3 parent extraction; `Forwarded`/`X-Forwarded-*` handling |
| Servlet response | `io.opentelemetry.servlet-3.0` | INTERNAL `Response.sendError` / `Response.sendRedirect` spans |
| JDBC | `io.opentelemetry.jdbc` | one CLIENT span per execution (statement, prepared, callable, batch); wrapper statements (connection pools, WebSphere `WSJdbc*`) are skipped so the driver statement records; connection info from `Driver.connect` or the connection's metadata URL; SQL sanitized by the main project's own lexer; span named `SELECT db.table` |
| `java.net.HttpURLConnection` | `io.opentelemetry.http-url-connection` | CLIENT span per request, `traceparent`/`tracestate`/`baggage` injected, 4xx/5xx and exceptions as upstream, `url.full` with credentials and signed query parameters redacted |
| `com.sun.net.httpserver` | `io.opentelemetry.java-http-server` | SERVER span per request named by context path |
| JVM runtime metrics | `io.opentelemetry.runtime-telemetry-java8` | `jvm.memory.*`, `jvm.class.*`, `jvm.cpu.*`, `jvm.thread.count`, `jvm.gc.duration` |
| HTTP metrics | (instrumentation scopes) | `http.server.request.duration`, `http.client.request.duration` (explicit bucket histograms with exemplars) |
| SDK self-metrics | `io.opentelemetry.sdk.trace`, `io.opentelemetry.exporters.otlp-http` | `processedSpans`, `queueSize`, `otlp.exporter.seen`, `otlp.exporter.exported` |

Resource: `service.*`, `telemetry.sdk.*`, `telemetry.distro.*` (reported as the standard agent's
distribution and version), `host.*`, `os.*`, `process.*` — identical to the standard agent's.

## Configuration

Every property can be given as a system property or as the equivalent environment variable
(`otel.service.name` / `OTEL_SERVICE_NAME`), as in the standard agent. Durations accept the SDK's
units (`10s`, `500ms`).

| Property | Default |
| --- | --- |
| `otel.javaagent.enabled`, `otel.sdk.disabled` | `true`, `false` |
| `otel.service.name`, `otel.resource.attributes` | `unknown_service:java` |
| `otel.java.disabled.resource.providers` | |
| `otel.traces.exporter` (`otlp`, `zipkin`, `logging`/`console`, `none`) | `otlp` |
| `otel.metrics.exporter` (`otlp`, `logging`/`console`, `none`) | `otlp` |
| `otel.exporter.otlp[.traces\|.metrics].endpoint` | `http://localhost:4318` (`/v1/<signal>` appended) |
| `otel.exporter.otlp[.traces\|.metrics].headers` | |
| `otel.exporter.otlp[.traces\|.metrics].compression` (`gzip`, `none`) | `none` |
| `otel.exporter.otlp[.traces\|.metrics].timeout` | `10s` |
| `otel.exporter.otlp[.traces\|.metrics].certificate` (PEM trusted certificates) | else `javax.net.ssl.trustStore`, else the JVM's `cacerts` (note: WebSphere's own SSL configuration, e.g. a cell `trust.p12`, is not a JVM truststore — pass the collector's CA with this property) |
| `otel.exporter.otlp.metrics.temporality.preference` (`cumulative`, `delta`, `lowmemory`) | `cumulative` |
| `otel.exporter.zipkin.endpoint` | `http://localhost:9411/api/v2/spans` |
| `otel.metric.export.interval` | `60s` |
| `otel.traces.sampler`, `otel.traces.sampler.arg` | `parentbased_always_on` |
| `otel.propagators` (`tracecontext`, `baggage`, `b3`, `b3multi`, `none`) | `tracecontext,baggage` |
| `otel.bsp.schedule.delay`, `.max.queue.size`, `.max.export.batch.size`, `.export.timeout` | `5s`, `2048`, `512`, `30s` |
| `otel.instrumentation.<name>.enabled`, `otel.instrumentation.common.default-enabled` | `true` — names: `jdbc`, `servlet`, `http-url-connection`, `java-http-server`, `runtime-telemetry` |
| `otel.instrumentation.http.known-methods` | the standard methods |
| `otel.instrumentation.http.{server,client}.capture-{request,response}-headers` | |
| `otel.instrumentation.jdbc.statement-sanitizer.enabled` / `otel.instrumentation.common.db-statement-sanitizer.enabled` | `true` |

Properties the standard agent supports and this one does not (`otel.logs.exporter` other than
`none`, `otel.exporter.otlp.protocol=grpc`, `otel.javaagent.extensions`, client TLS
certificates, declarative config files) produce a startup warning, never a failure.

## Differences from the standard agent

- **Scope of instrumentation**: the instrumentations above only (the Java 6-era essentials).
  No logs signal, no extensions, no declarative configuration, old (default) database semconv only.
- **IBM J9 Java 6** has no GC notification API and no process CPU load, so `jvm.gc.duration` and
  `jvm.cpu.recent_utilization` are absent there (the standard agent also omits a metric when the
  JVM lacks its source). J9 reports `getProcessCpuTime` in 100 ns units on Java 6; the agent
  scales it (`jvm.cpu.time` is seconds, as upstream).
- **OTLP** is always `http/protobuf` (the standard agent's default).
- On JSPs served by WebSphere, the route is unknown (WebSphere does not register them as servlet
  mappings), so the span is named by method only.

## Building

The build uses a plain `javac` and requires **JDK 8** (the last `javac` that accepts `-source 1.6`):

```bash
JAVAC=/path/to/jdk8/bin/javac JAR=/path/to/jdk8/bin/jar ./build.sh
```

It downloads and shades its dependencies (all Java 5/6 bytecode), compiles with
`-source 1.6 -target 1.6` and verifies that no class in `build/opentelemetry-java6-agent.jar` is
newer than Java 6.

| Dependency | Version | Why |
| --- | --- | --- |
| `byte-buddy` | 1.18.5 | last line with a Java 5 baseline |
| `bcprov`/`bcutil`/`bctls` `-jdk15to18` | 1.77 | TLS 1.3/1.2 for `https` exports (Java 5 baseline; 1.78+ is Java 8) |
| `protobuf-java` | 3.5.1 | last runtime with a Java 6 baseline |

Vendored, generated sources (regenerated by scripts in `tools/`, not by the build):

- `src/protoc/java` — OTLP trace and metrics messages, protoc 3.5.1
  (`tools/generate-metrics-protos.sh`)
- `jdbc/sql/AutoSqlSanitizer.java` — the main project's JFlex SQL lexer
  (`src/main/jflex/SqlSanitizer.jflex`, `tools/generate-sql-sanitizer.sh`)

Also vendored from the main project (v2.31.1), lowered to Java 6 source: the JDBC URL parsers
(`jdbc/internal`), the URL sanitizers and the servlet `MappingResolver`.

## Testing

```bash
./run-test.sh          # ported upstream unit tests + smoke test (logging exporter)
./check-java6-api.sh   # animal-sniffer: no JDK API newer than Java 6
./run-parity-test.sh   # traces + metrics diffed against the standard agent
./run-tls-test.sh      # TLS export: trusted CA, untrusted CA, host name mismatch
```

All scripts use `java` from `PATH` (JDK 8), and `run-test.sh`, `run-parity-test.sh` and
`run-tls-test.sh` also run on a second JVM given as `JAVA6_JVM` — e.g. the J9 Java 6 of a
WebSphere install:

```bash
JAVA6_JVM=/opt/IBM/WebSphere/AppServer/java/jre/bin/java ./run-parity-test.sh
```

**Unit tests** — the main project's own tests for the code vendored from it
(`JdbcConnectionUrlParserTest`, `SqlQueryAnalyzerTest`, `UrlSanitizerTest`,
`UrlQuerySanitizerTest`, and the Forwarded-header extractor tests), 616 cases, run by
`MiniJupiter` — a small JUnit 5-compatible runner plus AssertJ-style assertions for Java 6 in
`src/test/java`, so the ported tests keep their annotations and assertions (Java 8 `Stream`s become
`List`s). They pass on HotSpot 8 and IBM J9 6.

**Parity test** — `ParityApp` runs an embedded Jetty 8 (servlet 3.0, Java 6) with a filter in
front, commons-dbcp pooling over H2, and the scenarios of the main project's
`AbstractHttpServerTest`, `AbstractHttpClientTest` and JDBC tests (success, redirect, error,
exception, not found, query, wildcard route, client call inside a request, JDBC inside a request,
forward, include, remote parent, POST, connection refused, statement/prepared/callable/batch,
pooled and direct). It runs under the standard agent and under this agent, captures both OTLP
streams (`tools/OtlpFullDumper`) and compares them (`tools/parity-diff.py`): span **trees** (a span
that loses its parent is a difference), typed attributes, events, status, resource, and every
metric's definition and data-point attribute sets. Jetty's own instrumentation is disabled in the
standard agent's run, so its server spans come from the servlet instrumentation, as on WebSphere.
Result: identical resource, 46/46 spans, 18/18 metrics — on HotSpot 8, and on IBM J9 6 (with
`--cross-jvm`, which normalizes JVM-specific values and allows the metrics J9 cannot provide).

**WebSphere** — `src/it/webapp` (`./build-test-war.sh`) is a WAR exercising servlets, JSP, a
container-managed DataSource, an outgoing HTTP call, forward, errors and 404, for testing inside a
real container. It was verified on WebSphere 8.5.5.13 with IBM J9 Java 6 SR8 FP75: JDBC spans
through WebSphere's `WSJdbc*` wrappers, context propagation across in-server HTTP calls, W3C
parent continuation, error and `sendError` spans, HTTP and JVM metrics; agent startup cost ≈5 s.

## How it works

- **Bootstrap class path**: the agent jar is appended to the bootstrap class loader
  (`Instrumentation.appendToBootstrapClassLoaderSearch`, Java 6). Inlined advice executes inside
  the instrumented class, so its helpers must be resolvable from any class loader — including
  WebSphere's OSGi bundle loaders and JDK classes.
- **Helpers only see JDK types**: a bootstrap-loaded helper cannot reference `javax.servlet`, so
  every servlet API call lives in the inlined advice methods and plain values are handed over.
  JDBC (`java.sql`) is a JDK API, so its logic lives in helpers. The async-servlet listener is a
  dynamic proxy of the application's own `AsyncListener` interface.
- **Inline advice only** (no `invokedynamic`), with `suppress = Throwable.class`: instrumentation
  failures never reach the application. Call-depth counters make sure only the outermost of nested
  calls (wrapper → driver, filter → servlet, wrapper response → container response) records.
- **Per-object state** uses weak, identity-keyed maps (the main agent's virtual fields): JDBC
  wrappers override `equals` to delegate to the wrapped object, so an equality-keyed map would
  confuse a pooled connection with the physical one.
- **Type matching** walks each loaded class's hierarchy once (memoized across matchers) through a
  weak-keyed type pool cache; agent, ByteBuddy, Bouncy Castle, protobuf and reflection-generated
  classes are ignored.
- **TLS** uses Bouncy Castle's low-level TLS client with its lightweight crypto (`BcTlsCrypto`):
  a Java 6 JSSE cannot negotiate TLS 1.2/1.3, and IBM J9's JCE refuses unsigned JCA providers (the
  shaded Bouncy Castle cannot stay signed), so no JCA/JSSE provider is involved at all. The server
  chain is validated with the JVM's own PKIX validator, and the host name against the certificate.
  Nothing is registered with `java.security.Security`.
- **Context** is a thread-local immutable context carrying the current span, the request's server
  span (so no nested SERVER spans), W3C baggage and trace state (propagated unchanged).
- **Own traffic**: the export threads (and the shutdown hook) are marked, so the agent's own OTLP
  requests over `HttpURLConnection` are never traced.
