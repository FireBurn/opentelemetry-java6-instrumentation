#!/bin/bash
# Compiles src/test/java (TestApp, ParityApp, unit tests, capture server) with -source 1.6
# -target 1.6 into build/test-classes, downloading the test-only dependencies (all Java 5/6
# bytecode) and verifying that the test classes are Java 6 bytecode too.
set -euo pipefail
cd "$(dirname "$0")"
JAVAC="${JAVAC:-javac}"
[ -d build/classes ] || ./build.sh
mkdir -p test-deps
fetch() {
  [ -f "test-deps/$(basename "$1")" ] || curl -sfSL -o "test-deps/$(basename "$1")" "https://repo1.maven.org/maven2/$1"
}
fetch com/h2database/h2/1.2.140/h2-1.2.140.jar
fetch javax/servlet/javax.servlet-api/3.0.1/javax.servlet-api-3.0.1.jar
fetch org/eclipse/jetty/aggregate/jetty-all/8.1.16.v20140903/jetty-all-8.1.16.v20140903.jar
fetch commons-dbcp/commons-dbcp/1.4/commons-dbcp-1.4.jar
fetch commons-pool/commons-pool/1.6/commons-pool-1.6.jar

rm -rf build/test-classes
mkdir -p build/test-classes
find src/test/java -name '*.java' > build/test-sources.txt
rc=0
"$JAVAC" -nowarn -source 1.6 -target 1.6 -encoding UTF-8 \
  -cp "build/classes:lib/protobuf-java-3.5.1.jar:$(ls test-deps/*.jar | tr '\n' ':')" \
  -d build/test-classes @build/test-sources.txt > build/test-javac.log 2>&1 || rc=$?
grep -v '^warning: \[options\]\|^1 warning\|^Note: ' build/test-javac.log || true
[ "$rc" -eq 0 ] || { echo "FAIL: test compilation failed"; exit 1; }
max=0
for c in $(find build/test-classes -name '*.class'); do
  read b1 b2 < <(od -An -j6 -N2 -t u1 "$c" | tr -s ' ')
  v=$((b1 * 256 + b2))
  if [ "$v" -gt "$max" ]; then max=$v; fi
done
if [ "$max" -gt 50 ]; then
  echo "FAIL: test classes newer than Java 6 (max $max)"
  exit 1
fi
echo "test classes compiled (class file version $max)"
