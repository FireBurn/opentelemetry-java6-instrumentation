#!/bin/bash
# Builds the java6 agent: compiles with -source 1.6 -target 1.6, shades byte-buddy into the
# agent jar, and verifies that every class file in the jar is Java 6 compatible (major <= 50).
#
# Requires a JDK whose javac still accepts -source 1.6 (i.e. JDK 8).
set -euo pipefail
cd "$(dirname "$0")"

# this agent's own version (Maven: io.opentelemetry.javaagent:opentelemetry-java6agent)
VERSION="${VERSION:-1.0.0}"
BB_VERSION=1.18.5
BC_VERSION=1.77
PB_VERSION=3.5.1
SERVLET_API_VERSION=3.0.1
JAVAC="${JAVAC:-javac}"
JAR="${JAR:-jar}"

echo "using javac: $($JAVAC -version 2>&1)"

mkdir -p lib
if [ ! -f "lib/byte-buddy-${BB_VERSION}.jar" ]; then
  echo "downloading byte-buddy ${BB_VERSION} (Java 5 baseline)"
  curl -sfSL -o "lib/byte-buddy-${BB_VERSION}.jar" \
    "https://repo1.maven.org/maven2/net/bytebuddy/byte-buddy/${BB_VERSION}/byte-buddy-${BB_VERSION}.jar"
fi
# compile-time only: the agent references the servlet API from advice code
if [ ! -f "lib/javax.servlet-api-${SERVLET_API_VERSION}.jar" ]; then
  echo "downloading javax.servlet-api ${SERVLET_API_VERSION} (compile-time only)"
  curl -sfSL -o "lib/javax.servlet-api-${SERVLET_API_VERSION}.jar" \
    "https://repo1.maven.org/maven2/javax/servlet/javax.servlet-api/${SERVLET_API_VERSION}/javax.servlet-api-${SERVLET_API_VERSION}.jar"
fi
# Bouncy Castle jdk15to18 line: Java 5 bytecode, provides TLS 1.2/1.3 for https endpoints
# (a Java 6 JVM's built-in JSSE cannot negotiate with modern TLS-only receivers).
# bctls needs bcprov + bcutil (bcutil carries the old-package ASN.1 delegates bctls references).
for a in bcprov bcutil bctls; do
  if [ ! -f "lib/${a}-jdk15to18-${BC_VERSION}.jar" ]; then
    echo "downloading ${a}-jdk15to18 ${BC_VERSION} (Java 5 baseline)"
    curl -sfSL -o "lib/${a}-jdk15to18-${BC_VERSION}.jar" \
      "https://repo1.maven.org/maven2/org/bouncycastle/${a}-jdk15to18/${BC_VERSION}/${a}-jdk15to18-${BC_VERSION}.jar"
  fi
done
# protobuf-java 3.5.1: the last protobuf runtime line with a Java 6 baseline, used by the
# OTLP protobuf exporter (message classes in src/protoc/java are generated with protoc 3.5.1)
if [ ! -f "lib/protobuf-java-${PB_VERSION}.jar" ]; then
  echo "downloading protobuf-java ${PB_VERSION} (Java 6 baseline)"
  curl -sfSL -o "lib/protobuf-java-${PB_VERSION}.jar" \
    "https://repo1.maven.org/maven2/com/google/protobuf/protobuf-java/${PB_VERSION}/protobuf-java-${PB_VERSION}.jar"
fi

# keep build/test-classes (compiled by run-test.sh) across agent rebuilds
rm -rf build/classes build/shaded build/sources.txt build/MANIFEST.MF build/opentelemetry-java6-agent.jar
mkdir -p build/classes build/shaded

find src/main/java src/protoc/java -name '*.java' > build/sources.txt
echo "compiling $(wc -l < build/sources.txt | tr -d ' ') sources with -source 1.6 -target 1.6"
"$JAVAC" -source 1.6 -target 1.6 -encoding UTF-8 \
  -cp "lib/byte-buddy-${BB_VERSION}.jar:lib/javax.servlet-api-${SERVLET_API_VERSION}.jar:lib/bcprov-jdk15to18-${BC_VERSION}.jar:lib/bctls-jdk15to18-${BC_VERSION}.jar:lib/protobuf-java-${PB_VERSION}.jar" \
  -d build/classes @build/sources.txt

# shade byte-buddy + Bouncy Castle + protobuf into the agent jar (original package names;
# the whole jar goes to bootstrap)
(cd build/shaded && unzip -q -o "../../lib/byte-buddy-${BB_VERSION}.jar" && \
  unzip -q -o "../../lib/bcprov-jdk15to18-${BC_VERSION}.jar" && \
  unzip -q -o "../../lib/bcutil-jdk15to18-${BC_VERSION}.jar" && \
  unzip -q -o "../../lib/bctls-jdk15to18-${BC_VERSION}.jar" && \
  unzip -q -o "../../lib/protobuf-java-${PB_VERSION}.jar")
# Bouncy Castle jars are signed; the signature files must go once the jar is merged
rm -f build/shaded/META-INF/*.SF build/shaded/META-INF/*.RSA build/shaded/META-INF/*.DSA
cp -R build/classes/. build/shaded/

# the version, readable by the (bootstrap-loaded) agent at runtime
printf 'version=%s\n' "$VERSION" > build/shaded/io/opentelemetry/java6/agent/version.properties

cat > build/MANIFEST.MF <<EOF
Manifest-Version: 1.0
Premain-Class: io.opentelemetry.java6.agent.OpenTelemetryAgent
Implementation-Title: opentelemetry-java6agent
Implementation-Version: ${VERSION}
Can-Redefine-Classes: true
Can-Retransform-Classes: true

EOF

"$JAR" cfm build/opentelemetry-java6-agent.jar build/MANIFEST.MF -C build/shaded .

# verify: no class file in the agent jar may be newer than Java 6 (major version 50).
# META-INF/versions/* are multi-release entries (Java 9+ manifest attribute); older JVMs
# ignore the attribute and never load those classes, so they are excluded here.
echo "verifying class file versions in the agent jar..."
tmp=$(mktemp -d)
unzip -q -o build/opentelemetry-java6-agent.jar -d "$tmp" '*.class'
max=0
count=0
for c in $(find "$tmp" -name '*.class' | grep -v 'META-INF/versions'); do
  read b1 b2 < <(od -An -j6 -N2 -t u1 "$c" | tr -s ' ')
  v=$((b1 * 256 + b2))
  count=$((count + 1))
  if [ "$v" -gt "$max" ]; then max=$v; fi
done
rm -rf "$tmp"
echo "checked $count classes, max class file version: $max (50 = Java 6)"
if [ "$max" -gt 50 ]; then
  echo "FAIL: agent jar contains classes newer than Java 6"
  exit 1
fi

ls -l build/opentelemetry-java6-agent.jar
echo "BUILD OK"
