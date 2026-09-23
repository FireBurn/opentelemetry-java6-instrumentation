/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.java6.agent.instrumentation.jdbc;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.Properties;
import net.bytebuddy.asm.Advice;

/** JDBC advice; the logic lives in {@link JdbcHelper} (java.sql is a JDK API). */
public final class JdbcAdvice {

  private JdbcAdvice() {}

  /** {@code Statement.execute*(String ...)}. */
  public static class StatementAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static JdbcHelper.AdviceScope onEnter(
        @Advice.This Object statement, @Advice.Argument(0) String sql) {
      return statement instanceof Statement
          ? JdbcHelper.startStatement((Statement) statement, sql)
          : null;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onExit(
        @Advice.Enter JdbcHelper.AdviceScope scope, @Advice.Thrown Throwable thrown) {
      if (scope != null) {
        JdbcHelper.end(scope, thrown);
      }
    }
  }

  /** {@code PreparedStatement.execute()/executeQuery()/executeUpdate()/executeLargeUpdate()}. */
  public static class PreparedStatementAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static JdbcHelper.AdviceScope onEnter(@Advice.This Object statement) {
      return statement instanceof PreparedStatement
          ? JdbcHelper.startPrepared((PreparedStatement) statement)
          : null;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onExit(
        @Advice.Enter JdbcHelper.AdviceScope scope, @Advice.Thrown Throwable thrown) {
      if (scope != null) {
        JdbcHelper.end(scope, thrown);
      }
    }
  }

  /** {@code Statement.executeBatch()/executeLargeBatch()}. */
  public static class ExecuteBatchAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static JdbcHelper.AdviceScope onEnter(@Advice.This Object statement) {
      return statement instanceof Statement ? JdbcHelper.startBatch((Statement) statement) : null;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onExit(
        @Advice.This Object statement,
        @Advice.Enter JdbcHelper.AdviceScope scope,
        @Advice.Thrown Throwable thrown) {
      try {
        if (scope != null) {
          JdbcHelper.end(scope, thrown);
        }
      } finally {
        JdbcHelper.clearBatch(statement);
      }
    }
  }

  /** {@code Statement.addBatch(String)}. */
  public static class AddBatchAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(@Advice.This Object statement, @Advice.Argument(0) String sql) {
      if (statement instanceof Statement) {
        JdbcHelper.addStatementBatch((Statement) statement, sql);
      }
    }
  }

  /** {@code PreparedStatement.addBatch()}. */
  public static class AddPreparedBatchAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(@Advice.This Object statement) {
      if (statement instanceof PreparedStatement) {
        JdbcHelper.addPreparedBatch((PreparedStatement) statement);
      }
    }
  }

  /** {@code Statement.clearBatch()}. */
  public static class ClearBatchAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(@Advice.This Object statement) {
      JdbcHelper.clearBatch(statement);
    }
  }

  /** {@code Statement.close()}. */
  public static class CloseAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(@Advice.This Object statement) {
      JdbcHelper.close(statement);
    }
  }

  /** {@code Connection.prepare*(String ...)}: remembers the SQL on the returned statement. */
  public static class PrepareAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static boolean onEnter(@Advice.Argument(0) String sql) {
      return JdbcHelper.prepareEnter(sql);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onExit(
        @Advice.Enter boolean outermost,
        @Advice.Return Object statement,
        @Advice.Thrown Throwable thrown) {
      JdbcHelper.prepareExit(outermost, statement, thrown);
    }
  }

  /** {@code Driver.connect(String, Properties)}: remembers the parsed URL (and user). */
  public static class DriverConnectAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(
        @Advice.Argument(0) String url,
        @Advice.Argument(1) Properties props,
        @Advice.Return Object connection) {
      if (connection instanceof Connection) {
        JdbcHelper.driverConnected(url, props, (Connection) connection);
      }
    }
  }
}
