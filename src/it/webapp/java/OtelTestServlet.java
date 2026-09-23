/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import javax.naming.InitialContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.sql.DataSource;

/**
 * In-container test servlet for the java6 agent. One servlet, dispatched on the path info, so the
 * web.xml stays trivial on every Java 6/7 container (WebSphere 7/8/8.5, Tomcat 6/7, JBoss 5/6).
 *
 * <ul>
 *   <li>{@code /hello} - plain 200
 *   <li>{@code /jdbc} - Statement + PreparedStatement through a container-managed DataSource
 *       ({@code otel.test.datasource} system property, default {@code
 *       jdbc/DefaultEJBTimerDataSource}); the query runs against table {@code otel_test}
 *   <li>{@code /client} - HttpURLConnection back to {@code /hello} on this server (tests context
 *       propagation from the server span to the client span and on to the downstream server span)
 *   <li>{@code /forward} - RequestDispatcher.forward to {@code /hello} (must stay one server span)
 *   <li>{@code /error} - throws, container answers 500
 * </ul>
 *
 * Compiled with -source 1.6 -target 1.6.
 */
public class OtelTestServlet extends HttpServlet {

  protected void doGet(HttpServletRequest req, HttpServletResponse resp)
      throws ServletException, IOException {
    String path = req.getPathInfo() == null ? "/" : req.getPathInfo();
    resp.setContentType("text/plain");
    if (path.equals("/hello")) {
      resp.getWriter().println("hello");
    } else if (path.equals("/jdbc")) {
      jdbc(resp.getWriter());
    } else if (path.equals("/client")) {
      client(req, resp.getWriter());
    } else if (path.equals("/forward")) {
      req.getRequestDispatcher("/test/hello").forward(req, resp);
    } else if (path.equals("/error")) {
      throw new ServletException("intentional test failure");
    } else {
      resp.sendError(404);
    }
  }

  private void jdbc(PrintWriter out) throws ServletException {
    String name = System.getProperty("otel.test.datasource", "jdbc/DefaultEJBTimerDataSource");
    try {
      DataSource ds = (DataSource) new InitialContext().lookup(name);
      Connection c = ds.getConnection();
      try {
        Statement s = c.createStatement();
        ResultSet rs = s.executeQuery("SELECT name FROM otel_test WHERE id = 1");
        rs.next();
        out.println("statement: " + rs.getString(1));
        rs.close();
        s.close();

        PreparedStatement ps = c.prepareStatement("SELECT name FROM otel_test WHERE id = ?");
        ps.setInt(1, 2);
        rs = ps.executeQuery();
        rs.next();
        out.println("prepared: " + rs.getString(1));
        rs.close();
        ps.close();
        out.println("connection class: " + c.getClass().getName());
      } finally {
        c.close();
      }
    } catch (Exception e) {
      throw new ServletException("jdbc test failed", e);
    }
  }

  private void client(HttpServletRequest req, PrintWriter out) throws IOException {
    URL url =
        new URL(
            "http://127.0.0.1:" + req.getLocalPort() + req.getContextPath() + "/test/hello");
    HttpURLConnection connection = (HttpURLConnection) url.openConnection();
    int code = connection.getResponseCode();
    InputStream in = connection.getInputStream();
    byte[] buffer = new byte[256];
    int n = in.read(buffer);
    in.close();
    connection.disconnect();
    out.println("downstream " + code + ": " + (n > 0 ? new String(buffer, 0, n, "UTF-8") : ""));
  }
}
