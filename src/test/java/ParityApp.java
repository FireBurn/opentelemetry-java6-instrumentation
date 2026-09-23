/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.EnumSet;
import javax.servlet.DispatcherType;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.apache.commons.dbcp.BasicDataSource;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;

/**
 * Parity application: exercises the instrumentations the way a real Java 6 container does, and is
 * run under both the standard agent and the java6 agent so their OTLP output can be diffed.
 *
 * <p>Scenarios are ported from the main project's shared test harnesses:
 *
 * <ul>
 *   <li>{@code AbstractHttpServerTest} ({@code ServerEndpoint}): SUCCESS, REDIRECT, ERROR,
 *       EXCEPTION, NOT_FOUND, QUERY_PARAM - served by an embedded Jetty 8 (servlet 3.0, Java 6)
 *       with a pass-through filter in front, like most real web apps
 *   <li>{@code AbstractHttpClientTest}: GET 200/404/500, POST with a body, connection refused,
 *       and a client call made inside a server request (parent linkage)
 *   <li>{@code JdbcInstrumentationTest}: Statement query/update, PreparedStatement, CallableStatement,
 *       batch, all through a commons-dbcp pool (wrapper statements, as in WebSphere) and directly
 *   <li>request dispatch: forward and include must stay a single server span
 * </ul>
 *
 * Ports are fixed (18080 app, 18081 refused) so both runs produce identical attributes. Compiled
 * with -source 1.6 -target 1.6.
 */
public class ParityApp {

  static final int PORT = Integer.getInteger("parity.port", 18080);
  static final int DEAD_PORT = Integer.getInteger("parity.deadPort", 18081);
  static BasicDataSource dataSource;

  public static void main(String[] args) throws Exception {
    dataSource = new BasicDataSource();
    dataSource.setDriverClassName("org.h2.Driver");
    dataSource.setUrl("jdbc:h2:mem:parity;DB_CLOSE_DELAY=-1");
    dataSource.setUsername("sa");
    dataSource.setPassword("");
    // warm the pool and create the schema outside any traced request
    Connection setup = dataSource.getConnection();
    Statement ddl = setup.createStatement();
    ddl.execute("CREATE TABLE parity (id INT PRIMARY KEY, name VARCHAR(20))");
    ddl.execute("INSERT INTO parity VALUES (1, 'one'), (2, 'two')");
    ddl.close();
    setup.close();

    Server server = new Server(PORT);
    ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
    context.setContextPath("/app");
    ServletHolder holder = new ServletHolder(new TestServlet());
    String[] exact = {
      "/success", "/redirect", "/error-status", "/exception", "/query", "/jdbc", "/client",
      "/forward", "/include"
    };
    for (int i = 0; i < exact.length; i++) {
      context.addServlet(holder, exact[i]);
    }
    context.addServlet(holder, "/wild/*");
    context.addFilter(
        new FilterHolder(new PassThroughFilter()), "/*", EnumSet.of(DispatcherType.REQUEST));
    server.setHandler(context);
    server.start();

    String base = "http://127.0.0.1:" + PORT + "/app";
    // --- AbstractHttpServerTest endpoints ---
    get(base + "/success");
    get(base + "/redirect");
    get(base + "/error-status");
    get(base + "/exception");
    get(base + "/notFound");
    get(base + "/query?some=query");
    get(base + "/wild/anything");
    // --- nesting: client call, JDBC and dispatch inside a server request ---
    get(base + "/client");
    get(base + "/jdbc");
    get(base + "/forward");
    get(base + "/include");
    // --- remote parent (W3C traceparent) ---
    HttpURLConnection remote = (HttpURLConnection) new URL(base + "/success").openConnection();
    remote.setRequestProperty(
        "traceparent", "00-11111111111111111111111111111111-2222222222222222-01");
    drain(remote);
    // --- AbstractHttpClientTest: POST with body, connection refused ---
    HttpURLConnection post = (HttpURLConnection) new URL(base + "/success").openConnection();
    post.setRequestMethod("POST");
    post.setDoOutput(true);
    OutputStream out = post.getOutputStream();
    out.write("body".getBytes("UTF-8"));
    out.close();
    drain(post);
    try {
      get("http://127.0.0.1:" + DEAD_PORT + "/refused");
    } catch (IOException expected) {
      // connection refused
    }
    // --- JdbcInstrumentationTest, outside any request, pooled and direct ---
    jdbc(dataSource.getConnection());
    Connection direct = java.sql.DriverManager.getConnection("jdbc:h2:mem:parity", "sa", "");
    jdbc(direct);

    server.stop();
    // deterministic GC activity (minor and major collections) for jvm.gc.duration
    long retained = 0;
    for (int i = 0; i < 200000; i++) {
      byte[] garbage = new byte[4096];
      retained += garbage.length;
    }
    System.gc();
    System.out.println("allocated " + retained + " bytes");
    // let both agents flush traces and at least one metrics export
    Thread.sleep(Long.getLong("parity.linger", 16000L).longValue());
    System.out.println("PARITY APP DONE");
    System.exit(0);
  }

  static int get(String url) throws IOException {
    return drain((HttpURLConnection) new URL(url).openConnection());
  }

  static int drain(HttpURLConnection connection) throws IOException {
    connection.setInstanceFollowRedirects(false);
    int code = connection.getResponseCode();
    InputStream in = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
    if (in != null) {
      byte[] buffer = new byte[1024];
      while (in.read(buffer) >= 0) {
        // discard
      }
      in.close();
    }
    connection.disconnect();
    return code;
  }

  static void jdbc(Connection c) throws SQLException {
    try {
      Statement s = c.createStatement();
      ResultSet rs = s.executeQuery("SELECT name FROM parity WHERE id = 1");
      rs.next();
      rs.close();
      s.executeUpdate("UPDATE parity SET name = 'uno' WHERE id = 1");
      s.addBatch("UPDATE parity SET name = 'one' WHERE id = 1");
      s.addBatch("UPDATE parity SET name = 'two' WHERE id = 2");
      s.executeBatch();
      s.close();
      PreparedStatement ps = c.prepareStatement("SELECT name FROM parity WHERE id = ?");
      ps.setInt(1, 2);
      rs = ps.executeQuery();
      rs.next();
      rs.close();
      ps.close();
      CallableStatement cs = c.prepareCall("CALL 1 + 1");
      cs.execute();
      cs.close();
    } finally {
      c.close();
    }
  }

  /** A filter in front of every request, as in most real applications. */
  public static class PassThroughFilter implements Filter {
    public void init(FilterConfig config) {}

    public void doFilter(ServletRequest req, ServletResponse resp, FilterChain chain)
        throws IOException, ServletException {
      chain.doFilter(req, resp);
    }

    public void destroy() {}
  }

  public static class TestServlet extends HttpServlet {
    protected void service(HttpServletRequest req, HttpServletResponse resp)
        throws ServletException, IOException {
      // during an include the request keeps the including servlet's path
      String path = (String) req.getAttribute("javax.servlet.include.servlet_path");
      if (path == null) {
        path = req.getServletPath();
      }
      resp.setContentType("text/plain");
      if (path.equals("/success") || path.equals("/wild")) {
        resp.getWriter().print("success");
      } else if (path.equals("/redirect")) {
        resp.sendRedirect(req.getContextPath() + "/success");
      } else if (path.equals("/error-status")) {
        resp.setStatus(500);
        resp.getWriter().print("controller error");
      } else if (path.equals("/exception")) {
        throw new ServletException("controller exception");
      } else if (path.equals("/query")) {
        resp.getWriter().print("some=" + req.getParameter("some"));
      } else if (path.equals("/client")) {
        int code = get("http://127.0.0.1:" + PORT + req.getContextPath() + "/success");
        resp.getWriter().print("downstream " + code);
      } else if (path.equals("/jdbc")) {
        try {
          jdbc(dataSource.getConnection());
        } catch (SQLException e) {
          throw new ServletException(e);
        }
        resp.getWriter().print("jdbc");
      } else if (path.equals("/forward")) {
        req.getRequestDispatcher("/success").forward(req, resp);
      } else if (path.equals("/include")) {
        req.getRequestDispatcher("/success").include(req, resp);
      } else {
        resp.sendError(404);
      }
    }
  }
}
