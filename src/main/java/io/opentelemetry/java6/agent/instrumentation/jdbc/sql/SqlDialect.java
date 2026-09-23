/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.java6.agent.instrumentation.jdbc.sql;

/**
 * Whether double-quoted fragments are identifiers or string literals, as the main project's {@code
 * SqlDialect}; {@link #fromDbSystemName} mirrors its {@code SqlDialectUtil}.
 */
public final class SqlDialect {

  /** Double-quoted fragments are string literals and are sanitized (the safe default). */
  public static final SqlDialect DOUBLE_QUOTES_ARE_STRING_LITERALS = new SqlDialect(false);

  /** Double-quoted fragments are identifiers and are kept. */
  public static final SqlDialect DOUBLE_QUOTES_ARE_IDENTIFIERS = new SqlDialect(true);

  private final boolean doubleQuotesAreIdentifiers;

  private SqlDialect(boolean doubleQuotesAreIdentifiers) {
    this.doubleQuotesAreIdentifiers = doubleQuotesAreIdentifiers;
  }

  boolean doubleQuotesAreIdentifiers() {
    return doubleQuotesAreIdentifiers;
  }

  /** Databases where double quotes are exclusively identifiers (stable db.system.name values). */
  public static SqlDialect fromDbSystemName(String dbSystemName) {
    if ("postgresql".equals(dbSystemName)
        || "oracle.db".equals(dbSystemName)
        || "ibm.db2".equals(dbSystemName)
        || "derby".equals(dbSystemName)
        || "hsqldb".equals(dbSystemName)
        || "sap.hana".equals(dbSystemName)
        || "clickhouse".equals(dbSystemName)
        || "polardb".equals(dbSystemName)) {
      return DOUBLE_QUOTES_ARE_IDENTIFIERS;
    }
    return DOUBLE_QUOTES_ARE_STRING_LITERALS;
  }
}
