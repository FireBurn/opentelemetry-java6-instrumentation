#!/bin/bash
# TLS export test. Without arguments it is self-contained: it creates a CA and server
# certificates, runs local TLS 1.2+ OTLP receivers (python3 + openssl) and checks that
#   - export over TLS works with the CA trusted via otel.exporter.otlp.certificate,
#   - a server certificate from an untrusted CA is rejected,
#   - a server certificate for another host name is rejected.
#
# With arguments it exports TestApp's spans to a real receiver (use a non-production endpoint):
#   ./run-tls-test.sh <https-base-url> <ca-pem-file>
#
# Environment: JAVA (default: java on PATH), JAVA6_JVM (optional: also test on this JVM).
set -euo pipefail
cd "$(dirname "$0")"
JAVA="${JAVA:-java}"
[ -f build/opentelemetry-java6-agent.jar ] || ./build.sh
./compile-tests.sh >/dev/null
APP_CP="build/test-classes:$(ls test-deps/*.jar | tr '\n' ':')"

export_to() {  # jvm endpoint ca-pem log
  "$1" -javaagent:build/opentelemetry-java6-agent.jar -Dotel.service.name=java6-tls-test \
    -Dotel.exporter.otlp.endpoint="$2" -Dotel.exporter.otlp.certificate="$3" \
    -Dotel.exporter.otlp.compression=gzip -Dotel.metric.export.interval=3000 \
    -cp "$APP_CP" TestApp > "$4" 2>&1
}

if [ $# -ge 2 ]; then
  export_to "$JAVA" "$1" "$2" build/tls-test.log
  if grep -q "export to .* failed" build/tls-test.log; then
    grep "export to .* failed" build/tls-test.log; echo "TLS TEST FAILED"; exit 1
  fi
  echo "TLS TEST OK (exported to $1)"
  exit 0
fi

w=build/tls
rm -rf "$w" && mkdir -p "$w"
openssl req -x509 -newkey rsa:2048 -nodes -keyout "$w/ca.key" -out "$w/ca.pem" -days 1 -subj "/CN=otel-java6-test-ca" 2>/dev/null
openssl req -x509 -newkey rsa:2048 -nodes -keyout "$w/other.key" -out "$w/other-ca.pem" -days 1 -subj "/CN=other-ca" 2>/dev/null
server_cert() {  # name san
  openssl req -newkey rsa:2048 -nodes -keyout "$w/$1.key" -out "$w/$1.csr" -subj "/CN=$1" 2>/dev/null
  printf "subjectAltName=%s\n" "$2" > "$w/$1.ext"
  openssl x509 -req -in "$w/$1.csr" -CA "$w/ca.pem" -CAkey "$w/ca.key" -CAcreateserial \
    -out "$w/$1.pem" -days 1 -extfile "$w/$1.ext" 2>/dev/null
}
server_cert localhost "DNS:localhost,IP:127.0.0.1"
server_cert otherhost "DNS:otherhost"
cat > "$w/receiver.py" <<'PY'
import http.server, ssl, sys
class H(http.server.BaseHTTPRequestHandler):
    def do_POST(self):
        self.rfile.read(int(self.headers.get('Content-Length', 0)))
        print('POST', self.path, self.request.version(), self.request.cipher()[0], flush=True)
        self.send_response(200); self.send_header('Content-Length', '0'); self.end_headers()
    def log_message(self, *a): pass
ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER); ctx.minimum_version = ssl.TLSVersion.TLSv1_2
ctx.load_cert_chain(sys.argv[2] + '.pem', sys.argv[2] + '.key')
s = http.server.HTTPServer(('127.0.0.1', int(sys.argv[1])), H)
s.socket = ctx.wrap_socket(s.socket, server_side=True); s.serve_forever()
PY
python3 "$w/receiver.py" 18443 "$w/localhost" > "$w/good.log" 2>&1 & GOOD=$!
python3 "$w/receiver.py" 18444 "$w/otherhost" > "$w/wrong.log" 2>&1 & WRONG=$!
trap 'kill $GOOD $WRONG 2>/dev/null || true' EXIT
sleep 1

fail=0
run_on() {
  local jvm="$1"
  echo "=== $("$jvm" -version 2>&1 | sed -n 2p)"
  local before; before=$(grep -c POST "$w/good.log" || true)
  export_to "$jvm" https://localhost:18443 "$w/ca.pem" "$w/app.log"
  local posts; posts=$(( $(grep -c POST "$w/good.log" || true) - before ))
  if [ "$posts" -gt 0 ] && ! grep -q "export to .* failed" "$w/app.log"; then
    echo "PASS: trusted receiver ($posts exports, $(grep POST "$w/good.log" | tail -1 | cut -d' ' -f3-))"
  else
    echo "FAIL: trusted receiver"; grep "failed" "$w/app.log" | head -2; fail=1
  fi
  export_to "$jvm" https://localhost:18443 "$w/other-ca.pem" "$w/app.log"
  if grep -q "bad_certificate" "$w/app.log"; then echo "PASS: untrusted CA rejected"; else echo "FAIL: untrusted CA accepted"; fail=1; fi
  export_to "$jvm" https://localhost:18444 "$w/ca.pem" "$w/app.log"
  if grep -q "certificate does not match host" "$w/app.log"; then echo "PASS: host name mismatch rejected"; else echo "FAIL: host name mismatch accepted"; fail=1; fi
}
run_on "$JAVA"
if [ -n "${JAVA6_JVM:-}" ]; then
  run_on "$JAVA6_JVM"
fi
[ "$fail" -eq 0 ] && echo "TLS TEST OK" || { echo "TLS TEST FAILED"; exit 1; }
