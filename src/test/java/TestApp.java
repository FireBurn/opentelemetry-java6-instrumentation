/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URL;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * Smoke test application for the java6 agent. Exercises the HTTP client, JDBC and servlet
 * instrumentations. Compiled with -source 1.6 -target 1.6.
 */
public class TestApp {

  public static void main(String[] args) throws Exception {
    // --- 1. local HTTP server (com.sun.net.httpserver exists since Java 6) ---
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/hello",
        new HttpHandler() {
          public void handle(HttpExchange exchange) throws IOException {
            byte[] body = "hello java6".getBytes("UTF-8");
            exchange.sendResponseHeaders(200, body.length);
            OutputStream out = exchange.getResponseBody();
            out.write(body);
            out.close();
            exchange.close();
          }
        });
    server.createContext(
        "/not-found",
        new HttpHandler() {
          public void handle(HttpExchange exchange) throws IOException {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
          }
        });
    server.createContext(
        "/server-error",
        new HttpHandler() {
          public void handle(HttpExchange exchange) throws IOException {
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
          }
        });
    server.start();
    int port = server.getAddress().getPort();
    System.out.println("test server listening on port " + port);

    // --- 2. HTTP client spans (scenarios from the main project's AbstractHttpClientTest) ---
    URL url = new URL("http://127.0.0.1:" + port + "/hello");
    HttpURLConnection connection = (HttpURLConnection) url.openConnection();
    connection.setRequestMethod("GET");
    InputStream in = connection.getInputStream();
    byte[] buffer = new byte[64];
    in.read(buffer);
    in.close();
    connection.disconnect();

    // 404: client error, span carries the status but no error status
    URL notFound = new URL("http://127.0.0.1:" + port + "/not-found");
    HttpURLConnection nfConnection = (HttpURLConnection) notFound.openConnection();
    try {
      nfConnection.getResponseCode();
    } catch (IOException expected) {
      // 404 has no input stream
    }
    nfConnection.disconnect();

    // 500: server error, span gets an error status
    URL serverError = new URL("http://127.0.0.1:" + port + "/server-error");
    HttpURLConnection seConnection = (HttpURLConnection) serverError.openConnection();
    try {
      seConnection.getResponseCode();
    } catch (IOException expected) {
      // 500 has no input stream
    }
    seConnection.disconnect();

    // connection refused: the request throws, span gets the exception
    ServerSocket closed = new ServerSocket(0);
    int deadPort = closed.getLocalPort();
    closed.close();
    try {
      URL refused = new URL("http://127.0.0.1:" + deadPort + "/refused");
      HttpURLConnection rConnection = (HttpURLConnection) refused.openConnection();
      rConnection.getResponseCode();
    } catch (IOException expected) {
      // expected: connection refused
    }
    System.out.println("http requests done");

    // --- 3. JDBC spans ---
    Class.forName("org.h2.Driver");
    Connection db = DriverManager.getConnection("jdbc:h2:mem:test;DB_CLOSE_DELAY=-1", "sa", "");
    try {
      Statement statement = db.createStatement();
      ResultSet rs = statement.executeQuery("SELECT 1");
      rs.next();
      rs.close();
      statement.close();

      PreparedStatement prepared = db.prepareStatement("SELECT ? + ?");
      prepared.setInt(1, 40);
      prepared.setInt(2, 2);
      rs = prepared.executeQuery();
      rs.next();
      rs.close();
      prepared.close();
    } finally {
      db.close();
    }
    System.out.println("jdbc queries done");

    // --- 4. servlet span, with a remote parent from the traceparent header ---
    TestServlet servlet = new TestServlet();
    Map<String, String> headers = new HashMap<String, String>();
    headers.put("traceparent", "00-11111111111111111111111111111111-2222222222222222-01");
    HttpServletRequest request =
        (HttpServletRequest) newProxy(HttpServletRequest.class, headers);
    HttpServletResponse response =
        (HttpServletResponse) newProxy(HttpServletResponse.class, null);
    servlet.service(request, response);
    System.out.println("servlet request done");

    server.stop(0);
    // give the daemon export worker time to flush (remote TLS exports take longer than the
    // app's own runtime)
    Thread.sleep(8000);
    System.out.println("TEST APP DONE");
  }

  /** A servlet that only implements doGet/doPost, so the inherited service() is exercised. */
  public static class TestServlet extends HttpServlet {
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
        throws IOException, javax.servlet.ServletException {
      // intentionally empty
    }

    protected void doPost(HttpServletRequest req, HttpServletResponse resp)
        throws IOException, javax.servlet.ServletException {
      // intentionally empty
    }
  }

  /** Builds a dynamic proxy that behaves like a servlet request/response for the advice. */
  private static Object newProxy(Class<?> iface, final Map<String, String> headers) {
    return Proxy.newProxyInstance(
        TestApp.class.getClassLoader(),
        new Class<?>[] {iface},
        new InvocationHandler() {
          public Object invoke(Object proxy, Method method, Object[] margs) {
            String name = method.getName();
            if (name.equals("getHeader") && margs != null && margs.length == 1) {
              return headers == null ? null : headers.get((String) margs[0]);
            }
            if (name.equals("getMethod")) {
              return "POST";
            }
            if (name.equals("getRequestURI")) {
              return "/test/servlet";
            }
            if (name.equals("getRemoteAddr")) {
              return "127.0.0.1";
            }
            if (name.equals("toString")) {
              return "FakeServletRequest";
            }
            if (name.equals("hashCode")) {
              return Integer.valueOf(System.identityHashCode(proxy));
            }
            if (name.equals("equals")) {
              return Boolean.valueOf(proxy == margs[0]);
            }
            if (name.equals("getClass")) {
              return proxy.getClass();
            }
            Class<?> returnType = method.getReturnType();
            if (returnType == int.class) {
              return Integer.valueOf(200);
            }
            if (returnType == long.class) {
              return Long.valueOf(0L);
            }
            if (returnType == boolean.class) {
              return Boolean.FALSE;
            }
            if (returnType == double.class) {
              return Double.valueOf(0d);
            }
            if (returnType == float.class) {
              return Float.valueOf(0f);
            }
            if (returnType == short.class) {
              return Short.valueOf((short) 0);
            }
            if (returnType == byte.class) {
              return Byte.valueOf((byte) 0);
            }
            if (returnType == char.class) {
              return Character.valueOf((char) 0);
            }
            return null;
          }
        });
  }
}
