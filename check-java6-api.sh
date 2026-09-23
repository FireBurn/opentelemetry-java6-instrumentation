#!/bin/bash
# Checks that nothing in the agent jar references a JDK API newer than Java 6, using
# animal-sniffer with the java16 signature. This is the same tool the main build uses (it checks
# against the Android API 23 signature); the class-file-version check in build.sh only proves the
# bytecode level, not the API surface, so this is the complementary check.
set -euo pipefail
cd "$(dirname "$0")"

fetch() {
  local file="$1" url="$2"
  if [ ! -f "$file" ]; then
    echo "downloading $file"
    curl -sfSL -o "$file" "$url"
  fi
}
fetch lib/animal-sniffer-1.23.jar \
  "https://repo1.maven.org/maven2/org/codehaus/mojo/animal-sniffer/1.23/animal-sniffer-1.23.jar"
fetch lib/asm-9.7.1.jar \
  "https://repo1.maven.org/maven2/org/ow2/asm/asm/9.7.1/asm-9.7.1.jar"
fetch lib/java16-1.0.signature \
  "https://repo1.maven.org/maven2/org/codehaus/mojo/signature/java16/1.0/java16-1.0.signature"

if [ ! -f tools/Java6ApiChecker.class ]; then
  javac -cp lib/animal-sniffer-1.23.jar -d tools tools/Java6ApiChecker.java
fi

[ -d build/classes ] || ./build.sh

# check the classes this repo compiles; references to third-party APIs are ignored (the shaded
# libraries are published artifacts already built for Java 5/6, verified in build.sh; the
# servlet API is a spec API provided by the container, not part of the JDK). com.sun.net.httpserver
# is a standard JDK API since Java 6 (present on every Java 6/7 JVM, including IBM J9) but is not
# included in the animal-sniffer java16 signature file, so it is ignored here too.
java -cp "tools:lib/animal-sniffer-1.23.jar:lib/asm-9.7.1.jar" \
  Java6ApiChecker lib/java16-1.0.signature build/classes \
    'io.opentelemetry.java6.agent.*' \
    'net.bytebuddy.*' \
    'org.bouncycastle.*' \
    'com.google.protobuf.*' \
    'io.opentelemetry.proto.*' \
    'javax.servlet.*' \
    'com.sun.net.httpserver.*'
