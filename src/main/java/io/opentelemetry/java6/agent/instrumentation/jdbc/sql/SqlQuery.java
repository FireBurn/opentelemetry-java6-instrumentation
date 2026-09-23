/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.java6.agent.instrumentation.jdbc.sql;

/**
 * Result of analyzing a SQL statement: the sanitized text, the operation and its target. A
 * hand-written equivalent of the main project's AutoValue {@code SqlQuery} (old-semconv subset),
 * used by the vendored {@link AutoSqlSanitizer}.
 */
public final class SqlQuery {

  private static final String SQL_CALL = "CALL";

  private final String queryText;
  private final String operationName;
  private final String collectionName;
  private final String storedProcedureName;

  private SqlQuery(
      String queryText, String operationName, String collectionName, String storedProcedureName) {
    this.queryText = queryText;
    this.operationName = operationName;
    this.collectionName = collectionName;
    this.storedProcedureName = storedProcedureName;
  }

  /** As the main project's {@code SqlQuery.create}: CALL/EXECUTE targets are procedure names. */
  public static SqlQuery create(String queryText, String operationName, String target) {
    boolean isStoredProcedure = SQL_CALL.equals(operationName) || "EXECUTE".equals(operationName);
    return new SqlQuery(
        queryText, operationName, isStoredProcedure ? null : target, isStoredProcedure ? target : null);
  }

  public String getQueryText() {
    return queryText;
  }

  public String getOperationName() {
    return operationName;
  }

  /** The table/collection name, or null for CALL operations. */
  public String getCollectionName() {
    return collectionName;
  }

  /** The stored procedure name for CALL operations, or null. */
  public String getStoredProcedureName() {
    return storedProcedureName;
  }
}
