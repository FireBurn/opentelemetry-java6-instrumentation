#!/bin/bash
# Unit tests + smoke test.
#
#  1. the unit tests ported from the main project (JDBC URL parser, SQL sanitizer, URL sanitizers,
#     Forwarded/X-Forwarded-* parsing), run by MiniJupiter - a Java 6 stand-in for JUnit 5
#  2. a smoke run of TestApp with the logging exporter, checking the expected spans
#
# Environment: JAVA (default: java on PATH), JAVA6_JVM (optional: also run both on this JVM, e.g.
# an IBM J9 Java 6).
set -euo pipefail
cd "$(dirname "$0")"
JAVA="${JAVA:-java}"

[ -f build/opentelemetry-java6-agent.jar ] || ./build.sh
./compile-tests.sh

TESTS=$(cd build/test-classes && find . -name '*Test.class' | sed 's|^\./||; s|\.class$||; s|/|.|g' | sort)
APP_CP="build/test-classes:$(ls test-deps/*.jar | tr '\n' ':')"

run_on() {
  local jvm="$1"
  echo "=== $("$jvm" -version 2>&1 | sed -n 2p)"
  "$jvm" -cp build/test-classes:build/classes io.opentelemetry.java6.testing.MiniJupiter $TESTS

  local out=build/smoke-output.log
  "$jvm" -javaagent:build/opentelemetry-java6-agent.jar \
    -Dotel.service.name=java6-test-app -Dotel.traces.exporter=logging \
    -Dotel.metrics.exporter=none -Dotel.bsp.schedule.delay=200 \
    -cp "$APP_CP" TestApp > "$out" 2>&1 || { cat "$out"; echo "SMOKE APP FAILED"; exit 1; }
  local fail=0
  check() {
    if grep -q -- "$1" "$out"; then echo "PASS: $2"; else echo "FAIL: $2 (pattern: $1)"; fail=1; fi
  }
  check "'GET' : [0-9a-f]* [0-9a-f]* CLIENT \[tracer: io.opentelemetry.http-url-connection:" "http client span"
  check "'GET /hello' : [0-9a-f]* [0-9a-f]* SERVER \[tracer: io.opentelemetry.java-http-server:" "java-http-server span named by route"
  check "http.response.status_code=404" "404 status"
  check "error.type=500" "500 error.type"
  check "error.type=java.net.ConnectException" "connection refused"
  check "'SELECT test' : .* CLIENT \[tracer: io.opentelemetry.jdbc:" "jdbc span named <operation> <db.name>"
  check "db.statement=SELECT ?" "sanitized statement"
  check "'POST' : 11111111111111111111111111111111 [0-9a-f]* SERVER \[tracer: io.opentelemetry.servlet-3.0:.* parent=2222222222222222" "servlet span continues the remote trace"
  [ "$fail" -eq 0 ] || { echo "SMOKE TEST FAILED"; exit 1; }
  echo "SMOKE TEST OK"
}

run_on "$JAVA"
if [ -n "${JAVA6_JVM:-}" ]; then
  run_on "$JAVA6_JVM"
fi
