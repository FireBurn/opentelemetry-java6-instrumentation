#!/bin/bash
# Regenerates the vendored SQL sanitizer (src/main/java/.../jdbc/sql/AutoSqlSanitizer.java) from
# src/main/jflex/SqlSanitizer.jflex - the main project's instrumentation-api-incubator lexer with
# only the package changed - using the same JFlex version as the main project (1.9.1).
set -euo pipefail
cd "$(dirname "$0")/.."
JAVA="${JAVA:-java}"
w=build/jflex
mkdir -p "$w"
[ -f "$w/jflex.jar" ] || curl -sfSL -o "$w/jflex.jar" \
  https://repo1.maven.org/maven2/de/jflex/jflex/1.9.1/jflex-1.9.1.jar
[ -f "$w/cup.jar" ] || curl -sfSL -o "$w/cup.jar" \
  https://repo1.maven.org/maven2/com/github/vbmacher/java-cup-runtime/11b-20160615-1/java-cup-runtime-11b-20160615-1.jar
"$JAVA" -cp "$w/jflex.jar:$w/cup.jar" jflex.Main -q --nobak \
  -d src/main/java/io/opentelemetry/java6/agent/instrumentation/jdbc/sql src/main/jflex/SqlSanitizer.jflex
echo "generated AutoSqlSanitizer.java"
