/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.java6.agent.export;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.logging.Logger;
import java.util.zip.GZIPOutputStream;
import org.bouncycastle.tls.TlsClientProtocol;

/**
 * OTLP/HTTP POST transport.
 *
 * <p>Plain {@code http} uses the platform's {@link HttpURLConnection} (with {@link ExportGuard}
 * set, so the agent does not trace its own exports). {@code https} uses Bouncy Castle's TLS 1.3/1.2
 * client ({@link TlsClient}): a Java 6 JVM's built-in JSSE cannot negotiate TLS 1.2/1.3 or the
 * cipher suites modern receivers require. Nothing is registered with {@code java.security.Security},
 * so the application's JCA/JSSE configuration is untouched.
 *
 * <p>Trust, as the standard exporter: {@code otel.exporter.otlp[.traces|.metrics].certificate}
 * (PEM file of trusted certificates) if set, else {@code javax.net.ssl.trustStore}, else the JVM's
 * default {@code cacerts}.
 *
 * <p>Retries follow the main SDK's default policy: up to 5 attempts on transient network failures
 * and HTTP 429/502/503/504, backing off from 1s by x1.5 up to 5s.
 */
public final class HttpSender {

  private static final Logger logger = Logger.getLogger("io.opentelemetry.java6.agent");
  private static final int CONNECT_TIMEOUT_MS = 10000;
  private static final int MAX_ATTEMPTS = 5;

  private final URL url;
  private final String contentType;
  private final Map<String, String> headers;
  private final boolean gzip;
  private final int timeoutMs;
  private final String certificatePath;
  private final String trustStore;
  private final String trustStorePassword;
  private volatile KeyStore trustStoreCache;

  public HttpSender(
      String endpoint,
      String contentType,
      Map<String, String> headers,
      String compression,
      int timeoutMs,
      String certificatePath,
      String trustStore,
      String trustStorePassword)
      throws IOException {
    this.url = new URL(endpoint);
    if (!"http".equals(url.getProtocol()) && !"https".equals(url.getProtocol())) {
      throw new IOException("unsupported OTLP endpoint protocol: " + endpoint);
    }
    this.contentType = contentType;
    this.headers = headers;
    this.gzip = "gzip".equalsIgnoreCase(compression);
    this.timeoutMs = timeoutMs;
    this.certificatePath = certificatePath;
    this.trustStore = trustStore;
    this.trustStorePassword = trustStorePassword;
  }

  public String endpoint() {
    return url.toExternalForm();
  }

  /** Posts the body with retries; returns true on a 2xx response. */
  public boolean post(byte[] body) {
    boolean previous = ExportGuard.enter();
    try {
      byte[] payload = gzip ? gzip(body) : body;
      long backoffMs = 1000;
      for (int attempt = 1; ; attempt++) {
        int status;
        try {
          status = "https".equals(url.getProtocol()) ? postTls(payload) : postPlain(payload);
        } catch (IOException e) {
          if (attempt >= MAX_ATTEMPTS || !isRetryable(e)) {
            logger.warning("OTLP export to " + url + " failed: " + e);
            return false;
          }
          status = -1;
        }
        if (status / 100 == 2) {
          return true;
        }
        boolean retryable = status == -1 || status == 429 || status == 502 || status == 503
            || status == 504;
        if (!retryable || attempt >= MAX_ATTEMPTS) {
          if (status != -1) {
            logger.warning("OTLP export to " + url + " rejected with HTTP " + status);
          }
          return false;
        }
        try {
          Thread.sleep((long) (Math.random() * backoffMs));
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          return false;
        }
        backoffMs = Math.min((long) (backoffMs * 1.5), 5000L);
      }
    } catch (Throwable t) {
      logger.log(java.util.logging.Level.WARNING, "OTLP export to " + url + " failed", t);
      return false;
    } finally {
      ExportGuard.exit(previous);
    }
  }

  /**
   * As the main SDK's default retry policy: transient network failures (connection refused or
   * reset, timeouts) are retried; TLS and other failures are not.
   */
  static boolean isRetryable(IOException e) {
    if (e instanceof java.net.SocketTimeoutException || e instanceof java.net.ConnectException
        || e instanceof java.net.NoRouteToHostException) {
      return true;
    }
    String message = e.getMessage();
    return e instanceof java.net.SocketException && message != null
        && message.toLowerCase(java.util.Locale.ROOT).contains("reset");
  }

  private static byte[] gzip(byte[] body) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream(body.length / 2 + 64);
    GZIPOutputStream out = new GZIPOutputStream(bytes);
    out.write(body);
    out.close();
    return bytes.toByteArray();
  }

  private int postPlain(byte[] payload) throws IOException {
    HttpURLConnection connection = (HttpURLConnection) url.openConnection();
    try {
      connection.setRequestMethod("POST");
      connection.setConnectTimeout(Math.min(CONNECT_TIMEOUT_MS, timeoutMs));
      connection.setReadTimeout(timeoutMs);
      connection.setDoOutput(true);
      connection.setFixedLengthStreamingMode(payload.length);
      connection.setRequestProperty("Content-Type", contentType);
      if (gzip) {
        connection.setRequestProperty("Content-Encoding", "gzip");
      }
      for (Map.Entry<String, String> h : headers.entrySet()) {
        connection.setRequestProperty(h.getKey(), h.getValue());
      }
      OutputStream out = connection.getOutputStream();
      try {
        out.write(payload);
      } finally {
        out.close();
      }
      int status = connection.getResponseCode();
      drain(status >= 400 ? connection.getErrorStream() : connection.getInputStream());
      return status;
    } finally {
      connection.disconnect();
    }
  }

  private static void drain(InputStream in) {
    if (in == null) {
      return;
    }
    try {
      byte[] buffer = new byte[1024];
      while (in.read(buffer) >= 0) {
        // discard the response body
      }
      in.close();
    } catch (IOException ignored) {
      // best effort
    }
  }

  private int postTls(byte[] payload) throws IOException {
    KeyStore trust;
    try {
      trust = trust();
    } catch (IOException e) {
      throw e;
    } catch (Exception e) {
      IOException io = new IOException("cannot load TLS trust for " + url + ": " + e);
      io.initCause(e);
      throw io;
    }
    int port = url.getPort() == -1 ? 443 : url.getPort();
    Socket socket = new Socket();
    try {
      socket.setSoTimeout(timeoutMs);
      socket.connect(
          new InetSocketAddress(url.getHost(), port), Math.min(CONNECT_TIMEOUT_MS, timeoutMs));
      TlsClientProtocol tls =
          new TlsClientProtocol(socket.getInputStream(), socket.getOutputStream());
      tls.connect(new TlsClient(url.getHost(), trust));

      StringBuilder request = new StringBuilder(256);
      String path = url.getFile().length() == 0 ? "/" : url.getFile();
      request.append("POST ").append(path).append(" HTTP/1.1\r\n");
      request.append("Host: ").append(url.getHost()).append(port == 443 ? "" : ":" + port)
          .append("\r\n");
      request.append("Content-Type: ").append(contentType).append("\r\n");
      if (gzip) {
        request.append("Content-Encoding: gzip\r\n");
      }
      for (Map.Entry<String, String> h : headers.entrySet()) {
        request.append(h.getKey()).append(": ").append(h.getValue()).append("\r\n");
      }
      request.append("Content-Length: ").append(payload.length).append("\r\n");
      request.append("Connection: close\r\n\r\n");
      OutputStream out = tls.getOutputStream();
      out.write(request.toString().getBytes("ISO-8859-1"));
      out.write(payload);
      out.flush();

      InputStream in = new BufferedInputStream(tls.getInputStream());
      String statusLine = readLine(in);
      if (statusLine == null) {
        throw new IOException("empty response from " + url);
      }
      String line;
      while ((line = readLine(in)) != null && line.length() > 0) {
        // skip the response headers
      }
      int space = statusLine.indexOf(' ');
      if (space < 0 || statusLine.length() < space + 4) {
        throw new IOException("unparseable status line from " + url + ": " + statusLine);
      }
      try {
        tls.close();
      } catch (IOException ignored) {
        // the server may already have closed the connection
      }
      return Integer.parseInt(statusLine.substring(space + 1, space + 4));
    } finally {
      socket.close();
    }
  }

  private static String readLine(InputStream in) throws IOException {
    StringBuilder sb = new StringBuilder();
    int b;
    while ((b = in.read()) != -1) {
      if (b == '\n') {
        break;
      }
      if (b != '\r') {
        sb.append((char) b);
      }
    }
    return b == -1 && sb.length() == 0 ? null : sb.toString();
  }

  private KeyStore trust() throws Exception {
    KeyStore store = trustStoreCache;
    if (store == null) {
      synchronized (this) {
        if (trustStoreCache == null) {
          trustStoreCache = loadTrust();
        }
        store = trustStoreCache;
      }
    }
    return store;
  }

  private KeyStore loadTrust() throws Exception {
    if (certificatePath != null && certificatePath.length() > 0) {
      KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
      keyStore.load(null, null);
      InputStream in = new FileInputStream(certificatePath);
      try {
        Collection<? extends Certificate> certs =
            CertificateFactory.getInstance("X.509").generateCertificates(pemOrDer(in));
        int i = 0;
        for (Iterator<? extends Certificate> it = certs.iterator(); it.hasNext(); ) {
          keyStore.setCertificateEntry("otlp-" + (i++), it.next());
        }
        if (i == 0) {
          throw new IOException("no certificates found in " + certificatePath);
        }
      } finally {
        in.close();
      }
      logger.fine("OTLP TLS trust: " + certificatePath);
      return keyStore;
    }
    String path = trustStore;
    char[] password = trustStorePassword == null ? null : trustStorePassword.toCharArray();
    if (path == null || path.length() == 0) {
      path =
          System.getProperty("java.home") + File.separator + "lib" + File.separator + "security"
              + File.separator + "cacerts";
      if (password == null) {
        password = "changeit".toCharArray();
      }
    }
    String lower = path.toLowerCase(java.util.Locale.ROOT);
    KeyStore keyStore =
        KeyStore.getInstance(lower.endsWith(".p12") || lower.endsWith(".pfx")
            || lower.endsWith(".pkcs12") ? "PKCS12" : "JKS");
    InputStream in = new FileInputStream(path);
    try {
      keyStore.load(in, password);
    } finally {
      in.close();
    }
    logger.fine("OTLP TLS trust: " + path);
    return keyStore;
  }

  /** CertificateFactory on older JVMs is strict about text around PEM blocks; normalize it. */
  private static InputStream pemOrDer(InputStream in) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    byte[] buffer = new byte[4096];
    int n;
    while ((n = in.read(buffer)) > 0) {
      bytes.write(buffer, 0, n);
    }
    byte[] data = bytes.toByteArray();
    String text = new String(data, "ISO-8859-1");
    if (!text.contains("-----BEGIN CERTIFICATE-----")) {
      return new ByteArrayInputStream(data);
    }
    StringBuilder pem = new StringBuilder();
    int from = 0;
    while (true) {
      int begin = text.indexOf("-----BEGIN CERTIFICATE-----", from);
      if (begin < 0) {
        break;
      }
      int end = text.indexOf("-----END CERTIFICATE-----", begin);
      if (end < 0) {
        break;
      }
      end += "-----END CERTIFICATE-----".length();
      pem.append(text, begin, end).append('\n');
      from = end;
    }
    return new ByteArrayInputStream(pem.toString().getBytes("ISO-8859-1"));
  }
}
