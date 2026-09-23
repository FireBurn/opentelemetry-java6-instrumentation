/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.java6.agent.instrumentation.jdbc.sql;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Sanitizes SQL with the vendored {@link AutoSqlSanitizer}, caching results for up to 1000
 * distinct statements (queries over 10 KiB are not cached), as the main project's {@code
 * SqlQueryAnalyzer} does.
 */
public final class SqlQueryAnalyzer {

  private static final int CACHE_SIZE = 1000;
  private static final int LARGE_QUERY_THRESHOLD = 10 * 1024;

  private static final Map<String, SqlQuery> CACHE =
      new LinkedHashMap<String, SqlQuery>(64, 0.75f, true) {
        private static final long serialVersionUID = 1L;

        @Override
        protected boolean removeEldestEntry(Map.Entry<String, SqlQuery> eldest) {
          return size() > CACHE_SIZE;
        }
      };

  private SqlQueryAnalyzer() {}

  public static SqlQuery analyze(String query, SqlDialect dialect) {
    if (query == null) {
      return SqlQuery.create(null, null, null);
    }
    if (query.length() > LARGE_QUERY_THRESHOLD) {
      return AutoSqlSanitizer.sanitize(query, dialect);
    }
    String key = (dialect.doubleQuotesAreIdentifiers() ? '1' : '0') + query;
    synchronized (CACHE) {
      SqlQuery cached = CACHE.get(key);
      if (cached != null) {
        return cached;
      }
    }
    SqlQuery result = AutoSqlSanitizer.sanitize(query, dialect);
    synchronized (CACHE) {
      CACHE.put(key, result);
    }
    return result;
  }

  static boolean isCached(String query, SqlDialect dialect) {
    synchronized (CACHE) {
      return CACHE.containsKey((dialect.doubleQuotesAreIdentifiers() ? '1' : '0') + query);
    }
  }
}
