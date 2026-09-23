#!/bin/bash
# Parity test: runs ParityApp (embedded Jetty 8 + commons-dbcp + H2, scenarios ported from the main
# project's AbstractHttpServerTest / AbstractHttpClientTest / JdbcInstrumentationTest) under BOTH
# the standard OpenTelemetry Java agent and this agent, captures the OTLP traces and metrics each
# one exports, and diffs them (span trees, typed attributes, metric definitions).
#
# Usage: ./run-parity-test.sh [official-agent-version]      (default 2.31.1)
#
# Environment:
#   JAVA        JVM for both runs (default: java on PATH; must be 8+ for the standard agent)
#   JAVA6_JVM   optional: ALSO run the java6 agent on this JVM (e.g. an IBM J9 Java 6) and diff it
#               against the standard agent's HotSpot run with JVM-specific values normalized
set -euo pipefail
cd "$(dirname "$0")"

VERSION="${1:-2.31.1}"
JAVA="${JAVA:-java}"
OFFICIAL_JAR="build/otel-official-agent-${VERSION}.jar"
if [ ! -f "$OFFICIAL_JAR" ]; then
  echo "downloading standard agent ${VERSION}"
  mkdir -p build
  curl -sfSL -o "$OFFICIAL_JAR" \
    "https://repo1.maven.org/maven2/io/opentelemetry/javaagent/opentelemetry-javaagent/${VERSION}/opentelemetry-javaagent-${VERSION}.jar"
fi

[ -f build/opentelemetry-java6-agent.jar ] || ./build.sh
./compile-tests.sh >/dev/null

javac -nowarn -cp "build/classes:lib/protobuf-java-3.5.1.jar" -d tools tools/OtlpFullDumper.java

APP_CP="build/test-classes:$(ls test-deps/*.jar | tr '\n' ':')"
# the standard agent's defaults, plus a short metric interval so the run sees an export. Jetty's
# own server instrumentation is disabled so the servlet instrumentation creates the server span,
# as it does on WebSphere traditional (which has no container-specific instrumentation).
PROPS="-Dotel.service.name=parity-app -Dotel.instrumentation.jetty.enabled=false -Dotel.metric.export.interval=5000 -Dotel.logs.exporter=none"

run_agent() {
  local jvm="$1" agent_jar="$2" dump="$3" port="$4"
  "$JAVA" -cp "tools:build/classes:lib/protobuf-java-3.5.1.jar" OtlpFullDumper "$port" > "$dump" 2>&1 &
  local dumper=$!
  sleep 1
  "$jvm" -javaagent:"$agent_jar" $PROPS -Dotel.exporter.otlp.endpoint="http://127.0.0.1:$port" \
    -cp "$APP_CP" ParityApp > "${dump%.jsonl}.log" 2>&1 || { tail -30 "${dump%.jsonl}.log"; exit 1; }
  sleep 1
  kill "$dumper" 2>/dev/null || true
  wait "$dumper" 2>/dev/null || true
}

echo "running the standard agent ${VERSION}..."
run_agent "$JAVA" "$OFFICIAL_JAR" build/parity-official.jsonl 4318
echo "running the java6 agent..."
run_agent "$JAVA" build/opentelemetry-java6-agent.jar build/parity-java6.jsonl 4319

rc=0
python3 tools/parity-diff.py build/parity-official.jsonl build/parity-java6.jsonl || rc=1

if [ -n "${JAVA6_JVM:-}" ]; then
  echo
  echo "running the java6 agent on $("$JAVA6_JVM" -version 2>&1 | sed -n 2p)..."
  run_agent "$JAVA6_JVM" build/opentelemetry-java6-agent.jar build/parity-java6-jvm.jsonl 4320
  python3 tools/parity-diff.py --cross-jvm build/parity-official.jsonl build/parity-java6-jvm.jsonl || rc=1
fi
exit $rc
