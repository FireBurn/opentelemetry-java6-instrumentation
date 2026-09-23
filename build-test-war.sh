#!/bin/bash
# Builds build/otel-test.war (src/it/webapp): an in-container test app for Java 6/7 servlet
# containers. Compiled with -source 1.6 -target 1.6 against the servlet 3.0 API (only 2.5 API is
# used, so it also deploys on servlet 2.5 containers such as WebSphere 7 and Tomcat 6).
set -euo pipefail
cd "$(dirname "$0")"
JAVAC="${JAVAC:-javac}"
JAR="${JAR:-jar}"
[ -f lib/javax.servlet-api-3.0.1.jar ] || ./build.sh
rm -rf build/war
mkdir -p build/war/WEB-INF/classes
cp -R src/it/webapp/web/. build/war/
"$JAVAC" -source 1.6 -target 1.6 -encoding UTF-8 -cp lib/javax.servlet-api-3.0.1.jar \
  -d build/war/WEB-INF/classes src/it/webapp/java/*.java
"$JAR" cf build/otel-test.war -C build/war .
ls -l build/otel-test.war
