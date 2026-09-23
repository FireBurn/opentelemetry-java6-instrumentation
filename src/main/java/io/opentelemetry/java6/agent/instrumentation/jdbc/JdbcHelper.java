/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.java6.agent.instrumentation.jdbc;

import io.opentelemetry.java6.agent.context.CallDepth;
import io.opentelemetry.java6.agent.context.Context;
import io.opentelemetry.java6.agent.context.Scope;
import io.opentelemetry.java6.agent.instrumentation.WeakIdentityMap;
import io.opentelemetry.java6.agent.instrumentation.WeakMaps;
import io.opentelemetry.java6.agent.instrumentation.jdbc.internal.JdbcConnectionUrlParser;
import io.opentelemetry.java6.agent.instrumentation.jdbc.internal.dbinfo.DbInfo;
import io.opentelemetry.java6.agent.instrumentation.jdbc.sql.SqlDialect;
import io.opentelemetry.java6.agent.instrumentation.jdbc.sql.SqlQuery;
import io.opentelemetry.java6.agent.instrumentation.jdbc.sql.SqlQueryAnalyzer;
import io.opentelemetry.java6.agent.trace.Span;
import io.opentelemetry.java6.agent.trace.SpanBuilder;
import io.opentelemetry.java6.agent.trace.SpanKind;
import io.opentelemetry.java6.agent.trace.Tracer;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Wrapper;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * JDBC instrumentation logic, a port of the main project's {@code io.opentelemetry.jdbc}
 * ({@code JdbcSingletons}, {@code JdbcAdviceScope}, {@code JdbcData}, {@code JdbcUtils}, {@code
 * DbRequest} and the old-semconv {@code SqlClientAttributesExtractor}/{@code
 * DbClientSpanNameExtractor}):
 *
 * <ul>
 *   <li>wrapper statements (connection pools, WebSphere's {@code WSJdbc*}) are skipped when {@code
 *       unwrap} returns a different object, so the driver statement underneath creates the span;
 *   <li>only the outermost execution on a thread creates a span (call depth);
 *   <li>connection info comes from {@code Driver.connect} (URL and properties, including the user)
 *       or, for DataSource connections, from {@code getMetaData().getURL()} (cached per connection);
 *   <li>SQL is sanitized and analyzed by the vendored upstream sanitizer: span name {@code
 *       <operation> <db.name>.<table>}, {@code db.statement}, {@code db.operation}, {@code
 *       db.sql.table}.
 * </ul>
 */
public final class JdbcHelper {

  public static final String SCOPE_NAME = "io.opentelemetry.jdbc";

  private static final WeakIdentityMap<String> PREPARED_SQL = WeakMaps.create();
  private static final WeakIdentityMap<long[]> PREPARED_BATCH_SIZE = WeakMaps.create();
  private static final WeakIdentityMap<List<String>> STATEMENT_BATCH = WeakMaps.create();
  private static final WeakIdentityMap<DbInfo> CONNECTION_INFO = WeakMaps.create();
  private static final WeakIdentityMap<Boolean> WRAPPER_CLASSES = WeakMaps.create();
  private static final ThreadLocal<String> PREPARING = new ThreadLocal<String>();

  private static volatile boolean sanitize = true;

  private JdbcHelper() {}

  public static void configure(boolean statementSanitizer) {
    sanitize = statementSanitizer;
  }

  // --- wrapper detection ------------------------------------------------------------------

  /** As upstream: a wrapper if {@code isWrapperFor} and {@code unwrap} returns another object. */
  public static boolean isWrapper(Wrapper object, Class<?> iface) {
    Class<?> type = object.getClass();
    Boolean cached = WRAPPER_CLASSES.get(type);
    if (cached != null) {
      return cached.booleanValue();
    }
    boolean wrapper = false;
    try {
      if (object.isWrapperFor(iface)) {
        wrapper = object.unwrap(iface) != object;
      }
    } catch (Throwable t) {
      // SQLException, or AbstractMethodError from a pre-JDBC4 driver
      wrapper = false;
    }
    WRAPPER_CLASSES.put(type, Boolean.valueOf(wrapper));
    return wrapper;
  }

  // --- connection info --------------------------------------------------------------------

  public static void driverConnected(String url, Properties props, Connection connection) {
    if (connection != null) {
      CONNECTION_INFO.put(connection, JdbcConnectionUrlParser.parse(url, props));
    }
  }

  static Connection unwrapConnection(Connection connection) {
    try {
      if (connection.isWrapperFor(Connection.class)) {
        return connection.unwrap(Connection.class);
      }
    } catch (Throwable t) {
      // not unwrappable
    }
    return connection;
  }

  static DbInfo dbInfo(Statement statement) {
    Connection connection;
    try {
      connection = statement.getConnection();
    } catch (Throwable t) {
      return null;
    }
    if (connection == null) {
      return null;
    }
    connection = unwrapConnection(connection);
    if (connection == null) {
      return null;
    }
    DbInfo info = CONNECTION_INFO.get(connection);
    if (info == null) {
      info = computeDbInfo(connection);
      CONNECTION_INFO.put(connection, info);
    }
    return info;
  }

  private static DbInfo computeDbInfo(Connection connection) {
    try {
      DatabaseMetaData metaData = connection.getMetaData();
      String url = metaData.getURL();
      if (url == null) {
        return DbInfo.DEFAULT;
      }
      try {
        return JdbcConnectionUrlParser.parse(url, connection.getClientInfo());
      } catch (Throwable t) {
        return JdbcConnectionUrlParser.parse(url, null);
      }
    } catch (Throwable t) {
      return DbInfo.DEFAULT;
    }
  }

  // --- prepared statements and batches ----------------------------------------------------

  /** Enter of {@code Connection.prepare*}: returns true if this is the outermost prepare. */
  public static boolean prepareEnter(String sql) {
    if (PREPARING.get() != null) {
      return false;
    }
    PREPARING.set(sql);
    return true;
  }

  /** Exit of {@code Connection.prepare*}: remembers the SQL on the (non-wrapper) statement. */
  public static void prepareExit(boolean outermost, Object statement, Throwable error) {
    String sql = PREPARING.get();
    if (outermost) {
      PREPARING.remove();
    }
    if (error == null && sql != null && statement instanceof PreparedStatement
        && !isWrapper((PreparedStatement) statement, PreparedStatement.class)) {
      PREPARED_SQL.put(statement, sql);
    }
  }

  public static void addStatementBatch(Statement statement, String sql) {
    if (statement instanceof PreparedStatement || isWrapper(statement, Statement.class)) {
      return;
    }
    synchronized (STATEMENT_BATCH) {
      List<String> batch = STATEMENT_BATCH.get(statement);
      if (batch == null) {
        batch = new ArrayList<String>();
        STATEMENT_BATCH.put(statement, batch);
      }
      batch.add(sql);
    }
  }

  public static void addPreparedBatch(PreparedStatement statement) {
    synchronized (PREPARED_BATCH_SIZE) {
      long[] size = PREPARED_BATCH_SIZE.get(statement);
      if (size == null) {
        PREPARED_BATCH_SIZE.put(statement, new long[] {1});
      } else {
        size[0]++;
      }
    }
  }

  public static void clearBatch(Object statement) {
    STATEMENT_BATCH.remove(statement);
    PREPARED_BATCH_SIZE.remove(statement);
  }

  public static void close(Object statement) {
    clearBatch(statement);
    PREPARED_SQL.remove(statement);
  }

  // --- spans ------------------------------------------------------------------------------

  /** Span and scope of one execution. Public: referenced from inlined advice. */
  public static final class AdviceScope {
    final Span span;
    final Scope scope;

    AdviceScope(Span span, Scope scope) {
      this.span = span;
      this.scope = scope;
    }
  }

  private static final AdviceScope NESTED = new AdviceScope(null, null);

  /** {@code Statement.execute*(String ...)}. */
  public static AdviceScope startStatement(Statement statement, String sql) {
    if (CallDepth.JDBC_STATEMENT.getAndIncrement() > 0 || isWrapper(statement, Statement.class)) {
      return NESTED;
    }
    return start(statement, Collections.singletonList(sql));
  }

  /** {@code PreparedStatement.execute*()}; no span if the SQL was not captured at prepare time. */
  public static AdviceScope startPrepared(PreparedStatement statement) {
    if (CallDepth.JDBC_STATEMENT.getAndIncrement() > 0) {
      return NESTED;
    }
    String sql = PREPARED_SQL.get(statement);
    if (sql == null || isWrapper(statement, PreparedStatement.class)) {
      return NESTED;
    }
    return start(statement, Collections.singletonList(sql));
  }

  /** {@code Statement.executeBatch()} (plain or prepared). */
  public static AdviceScope startBatch(Statement statement) {
    if (CallDepth.JDBC_STATEMENT.getAndIncrement() > 0 || isWrapper(statement, Statement.class)) {
      return NESTED;
    }
    List<String> texts;
    if (statement instanceof PreparedStatement) {
      String sql = PREPARED_SQL.get(statement);
      if (sql == null) {
        return NESTED;
      }
      texts = Collections.singletonList(sql);
    } else {
      List<String> batch = STATEMENT_BATCH.get(statement);
      texts = batch == null ? Collections.<String>emptyList() : new ArrayList<String>(batch);
    }
    return start(statement, texts);
  }

  private static AdviceScope start(Statement statement, List<String> queryTexts) {
    try {
      DbInfo info = dbInfo(statement);
      if (info == null) {
        return NESTED;
      }
      String dbName = info.getDbName();
      String operation = null;
      String table = null;
      String procedure = null;
      String queryText = null;
      boolean single = queryTexts.size() == 1;
      if (single) {
        String raw = queryTexts.get(0);
        SqlQuery query =
            SqlQueryAnalyzer.analyze(raw, SqlDialect.fromDbSystemName(info.getDbSystemName()));
        operation = query.getOperationName();
        table = query.getCollectionName();
        procedure = query.getStoredProcedureName();
        queryText = sanitize ? query.getQueryText() : raw;
      }
      SpanBuilder builder =
          Tracer.getInstance()
              .spanBuilder(spanName(dbName, operation, table, procedure), SCOPE_NAME)
              .setSpanKind(SpanKind.CLIENT);
      put(builder, "db.system", info.getDbSystem());
      put(builder, "db.user", info.getDbUser());
      put(builder, "db.name", dbName);
      put(builder, "db.connection_string", info.getDbConnectionString());
      if (single) {
        put(builder, "db.statement", queryText);
        put(builder, "db.operation", operation);
        put(builder, "db.sql.table", table);
      }
      put(builder, "server.address", info.getServerAddress());
      if (info.getServerAddress() != null && info.getServerPort() != null) {
        builder.setAttribute("server.port", info.getServerPort().longValue());
      }
      Span span = builder.startSpan();
      return new AdviceScope(span, Context.current().with(span).makeCurrent());
    } catch (Throwable t) {
      return NESTED;
    }
  }

  private static void put(SpanBuilder builder, String key, String value) {
    if (value != null) {
      builder.setAttribute(key, value);
    }
  }

  /** The main project's old-semconv {@code DbClientSpanNameExtractor.computeSpanName}. */
  static String spanName(String namespace, String operation, String collection, String procedure) {
    if (operation == null) {
      return namespace == null ? "DB Query" : namespace;
    }
    StringBuilder name = new StringBuilder(operation);
    String main = collection != null ? collection : procedure;
    if (namespace != null || main != null) {
      name.append(' ');
    }
    if (namespace != null && (main == null || main.indexOf('.') == -1)) {
      name.append(namespace);
      if (main != null) {
        name.append('.');
      }
    }
    if (main != null) {
      name.append(main);
    }
    return name.toString();
  }

  public static void end(AdviceScope adviceScope, Throwable thrown) {
    if (CallDepth.JDBC_STATEMENT.decrementAndGet() > 0 || adviceScope.scope == null) {
      return;
    }
    adviceScope.scope.close();
    if (thrown != null) {
      adviceScope.span.recordException(thrown);
      adviceScope.span.setStatus(Span.STATUS_ERROR, null);
    }
    adviceScope.span.end();
  }
}
