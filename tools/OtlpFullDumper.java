import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.metrics.v1.HistogramDataPoint;
import io.opentelemetry.proto.metrics.v1.Metric;
import io.opentelemetry.proto.metrics.v1.NumberDataPoint;
import io.opentelemetry.proto.metrics.v1.ResourceMetrics;
import io.opentelemetry.proto.metrics.v1.ScopeMetrics;
import io.opentelemetry.proto.resource.v1.Resource;
import io.opentelemetry.proto.trace.v1.ResourceSpans;
import io.opentelemetry.proto.trace.v1.ScopeSpans;
import io.opentelemetry.proto.trace.v1.Span;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.zip.GZIPInputStream;

/**
 * OTLP/HTTP capture server for the parity test: accepts {@code /v1/traces} and {@code /v1/metrics}
 * protobuf posts (optionally gzip-compressed, fixed-length or chunked) and prints one JSON object
 * per resource, span and metric. Attribute values carry their OTLP type ({@code "int:200"}) so a
 * string-vs-int difference shows up in the diff. Run on the build JDK (8+).
 *
 * <p>Usage: {@code OtlpFullDumper <port>}
 */
public final class OtlpFullDumper {

  private static final PrintStream OUT = System.out;

  private OtlpFullDumper() {}

  public static void main(String[] args) throws Exception {
    ServerSocket server = new ServerSocket(Integer.parseInt(args[0]));
    while (true) {
      Socket socket = server.accept();
      try {
        handle(socket);
      } catch (Exception e) {
        OUT.println("{\"t\":\"error\",\"msg\":" + q(String.valueOf(e)) + "}");
      } finally {
        socket.close();
      }
    }
  }

  private static void handle(Socket socket) throws Exception {
    InputStream in = socket.getInputStream();
    String head = readHead(in);
    if (head == null) {
      return;
    }
    String lower = head.toLowerCase();
    String requestLine = head.substring(0, head.indexOf('\r'));
    byte[] body;
    if (lower.contains("transfer-encoding: chunked")) {
      body = readChunked(in);
    } else {
      body = readFully(in, header(lower, "content-length:", 0));
    }
    if (lower.contains("content-encoding: gzip")) {
      body = readAll(new GZIPInputStream(new ByteArrayInputStream(body)));
    }
    OutputStream out = socket.getOutputStream();
    out.write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes("US-ASCII"));
    out.flush();
    synchronized (OUT) {
      if (requestLine.contains("/v1/traces")) {
        dumpTraces(ExportTraceServiceRequest.parseFrom(body));
      } else if (requestLine.contains("/v1/metrics")) {
        dumpMetrics(ExportMetricsServiceRequest.parseFrom(body));
      }
      OUT.flush();
    }
  }

  private static void dumpTraces(ExportTraceServiceRequest request) {
    for (ResourceSpans rs : request.getResourceSpansList()) {
      resource(rs.getResource());
      for (ScopeSpans ss : rs.getScopeSpansList()) {
        for (Span span : ss.getSpansList()) {
          StringBuilder sb = new StringBuilder("{\"t\":\"span\"");
          sb.append(",\"scope\":").append(q(ss.getScope().getName()));
          sb.append(",\"scopeVersion\":").append(q(ss.getScope().getVersion()));
          sb.append(",\"name\":").append(q(span.getName()));
          sb.append(",\"kind\":").append(q(span.getKind().name().replace("SPAN_KIND_", "")));
          sb.append(",\"traceId\":").append(q(hex(span.getTraceId())));
          sb.append(",\"spanId\":").append(q(hex(span.getSpanId())));
          sb.append(",\"parent\":").append(q(hex(span.getParentSpanId())));
          sb.append(",\"status\":").append(q(span.getStatus().getCode().name().replace("STATUS_CODE_", "")));
          sb.append(",\"statusMessage\":").append(q(span.getStatus().getMessage()));
          sb.append(",\"durationNanos\":").append(span.getEndTimeUnixNano() - span.getStartTimeUnixNano());
          sb.append(",\"attrs\":").append(attrs(span.getAttributesList()));
          sb.append(",\"events\":[");
          for (int i = 0; i < span.getEventsCount(); i++) {
            Span.Event e = span.getEvents(i);
            sb.append(i == 0 ? "" : ",").append("{\"name\":").append(q(e.getName()));
            sb.append(",\"attrs\":").append(attrs(e.getAttributesList())).append('}');
          }
          sb.append("]}");
          OUT.println(sb);
        }
      }
    }
  }

  private static void dumpMetrics(ExportMetricsServiceRequest request) {
    for (ResourceMetrics rm : request.getResourceMetricsList()) {
      resource(rm.getResource());
      for (ScopeMetrics sm : rm.getScopeMetricsList()) {
        for (Metric m : sm.getMetricsList()) {
          StringBuilder sb = new StringBuilder("{\"t\":\"metric\"");
          sb.append(",\"scope\":").append(q(sm.getScope().getName()));
          sb.append(",\"scopeVersion\":").append(q(sm.getScope().getVersion()));
          sb.append(",\"name\":").append(q(m.getName()));
          sb.append(",\"unit\":").append(q(m.getUnit()));
          sb.append(",\"description\":").append(q(m.getDescription()));
          sb.append(",\"points\":[");
          switch (m.getDataCase()) {
            case GAUGE:
              sb.insert(sb.indexOf(",\"points\""), ",\"type\":\"gauge\"");
              numberPoints(sb, m.getGauge().getDataPointsList());
              break;
            case SUM:
              sb.insert(
                  sb.indexOf(",\"points\""),
                  ",\"type\":\"sum\",\"monotonic\":" + m.getSum().getIsMonotonic()
                      + ",\"temporality\":" + q(m.getSum().getAggregationTemporality().name()));
              numberPoints(sb, m.getSum().getDataPointsList());
              break;
            case HISTOGRAM:
              sb.insert(
                  sb.indexOf(",\"points\""),
                  ",\"type\":\"histogram\",\"temporality\":"
                      + q(m.getHistogram().getAggregationTemporality().name()));
              List<HistogramDataPoint> hp = m.getHistogram().getDataPointsList();
              for (int i = 0; i < hp.size(); i++) {
                HistogramDataPoint p = hp.get(i);
                sb.append(i == 0 ? "" : ",").append("{\"attrs\":").append(attrs(p.getAttributesList()));
                sb.append(",\"count\":").append(p.getCount());
                sb.append(",\"sum\":").append(p.getSum());
                sb.append(",\"bounds\":").append(p.getExplicitBoundsList());
                sb.append(",\"buckets\":").append(p.getBucketCountsCount());
                sb.append(",\"exemplars\":").append(p.getExemplarsCount()).append('}');
              }
              break;
            default:
              sb.insert(sb.indexOf(",\"points\""), ",\"type\":" + q(m.getDataCase().name()));
          }
          sb.append("]}");
          OUT.println(sb);
        }
      }
    }
  }

  private static void numberPoints(StringBuilder sb, List<NumberDataPoint> points) {
    for (int i = 0; i < points.size(); i++) {
      NumberDataPoint p = points.get(i);
      sb.append(i == 0 ? "" : ",").append("{\"attrs\":").append(attrs(p.getAttributesList()));
      sb.append(",\"value\":")
          .append(q(p.getValueCase() == NumberDataPoint.ValueCase.AS_INT
              ? "int:" + p.getAsInt() : "double:" + p.getAsDouble()));
      sb.append('}');
    }
  }

  private static void resource(Resource resource) {
    OUT.println("{\"t\":\"resource\",\"attrs\":" + attrs(resource.getAttributesList()) + "}");
  }

  private static String attrs(List<KeyValue> kvs) {
    StringBuilder sb = new StringBuilder("{");
    for (int i = 0; i < kvs.size(); i++) {
      sb.append(i == 0 ? "" : ",").append(q(kvs.get(i).getKey())).append(':')
          .append(q(value(kvs.get(i).getValue())));
    }
    return sb.append('}').toString();
  }

  private static String value(AnyValue v) {
    switch (v.getValueCase()) {
      case STRING_VALUE:
        return "string:" + v.getStringValue();
      case INT_VALUE:
        return "int:" + v.getIntValue();
      case DOUBLE_VALUE:
        return "double:" + v.getDoubleValue();
      case BOOL_VALUE:
        return "bool:" + v.getBoolValue();
      case ARRAY_VALUE:
        StringBuilder sb = new StringBuilder("array:[");
        for (int i = 0; i < v.getArrayValue().getValuesCount(); i++) {
          sb.append(i == 0 ? "" : ",").append(value(v.getArrayValue().getValues(i)));
        }
        return sb.append(']').toString();
      default:
        return v.getValueCase().name() + ":" + v;
    }
  }

  private static String q(String s) {
    StringBuilder sb = new StringBuilder("\"");
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c == '"' || c == '\\') {
        sb.append('\\').append(c);
      } else if (c < 0x20) {
        sb.append(String.format("\\u%04x", (int) c));
      } else {
        sb.append(c);
      }
    }
    return sb.append('"').toString();
  }

  private static String hex(com.google.protobuf.ByteString bytes) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < bytes.size(); i++) {
      sb.append(String.format("%02x", bytes.byteAt(i)));
    }
    return sb.toString();
  }

  private static String readHead(InputStream in) throws Exception {
    StringBuilder sb = new StringBuilder();
    int b;
    while ((b = in.read()) != -1) {
      sb.append((char) b);
      if (sb.length() >= 4 && sb.substring(sb.length() - 4).equals("\r\n\r\n")) {
        return sb.toString();
      }
    }
    return null;
  }

  private static int header(String lowerHead, String name, int dflt) {
    int i = lowerHead.indexOf(name);
    if (i < 0) {
      return dflt;
    }
    int end = lowerHead.indexOf('\r', i);
    return Integer.parseInt(lowerHead.substring(i + name.length(), end).trim());
  }

  private static byte[] readChunked(InputStream in) throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    while (true) {
      StringBuilder line = new StringBuilder();
      int b;
      while ((b = in.read()) != -1 && b != '\n') {
        if (b != '\r') {
          line.append((char) b);
        }
      }
      int size = Integer.parseInt(line.toString().split(";")[0].trim(), 16);
      if (size == 0) {
        return out.toByteArray();
      }
      out.write(readFully(in, size));
      in.read();
      in.read();
    }
  }

  private static byte[] readFully(InputStream in, int length) throws Exception {
    byte[] body = new byte[length];
    int off = 0;
    while (off < length) {
      int n = in.read(body, off, length - off);
      if (n < 0) {
        break;
      }
      off += n;
    }
    return body;
  }

  private static byte[] readAll(InputStream in) throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] buffer = new byte[8192];
    int n;
    while ((n = in.read(buffer)) > 0) {
      out.write(buffer, 0, n);
    }
    return out.toByteArray();
  }
}
