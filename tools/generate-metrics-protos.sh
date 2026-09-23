#!/bin/bash
# Regenerates the vendored OTLP metrics message classes in src/protoc/java with protoc 3.5.1 (the
# protobuf-java runtime line with a Java 6 baseline). protoc 3.5.1 predates proto3 `optional`, so
# those fields are rewritten to the equivalent single-field oneof (identical wire format; this is
# exactly how newer protoc desugars them).
set -euo pipefail
cd "$(dirname "$0")/.."
OTLP_VERSION=1.3.2
w=build/proto
mkdir -p "$w"
[ -x "$w/protoc" ] || { curl -sfSL -o "$w/protoc" \
  "https://repo1.maven.org/maven2/com/google/protobuf/protoc/3.5.1/protoc-3.5.1-linux-x86_64.exe"; chmod +x "$w/protoc"; }
curl -sfSL "https://github.com/open-telemetry/opentelemetry-proto/archive/refs/tags/v${OTLP_VERSION}.tar.gz" | tar xz -C "$w"
src="$w/opentelemetry-proto-${OTLP_VERSION}"
sed -i -E 's/^(\s*)optional (double|int64|uint64) ([a-z_]+) = ([0-9]+);/\1oneof \3_oneof { \2 \3 = \4; }/' \
  "$src/opentelemetry/proto/metrics/v1/metrics.proto"
rm -rf "$w/gen" && mkdir -p "$w/gen"
"$w/protoc" -I"$src" --java_out="$w/gen" \
  opentelemetry/proto/metrics/v1/metrics.proto \
  opentelemetry/proto/collector/metrics/v1/metrics_service.proto
rm -rf src/protoc/java/io/opentelemetry/proto/metrics src/protoc/java/io/opentelemetry/proto/collector/metrics
cp -R "$w/gen/io/opentelemetry/proto/metrics" src/protoc/java/io/opentelemetry/proto/
cp -R "$w/gen/io/opentelemetry/proto/collector/metrics" src/protoc/java/io/opentelemetry/proto/collector/
echo "generated metrics protos from opentelemetry-proto v${OTLP_VERSION}"
