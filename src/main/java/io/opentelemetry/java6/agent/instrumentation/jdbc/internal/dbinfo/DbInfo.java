/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.java6.agent.instrumentation.jdbc.internal.dbinfo;

import io.opentelemetry.java6.agent.instrumentation.jdbc.internal.target.DbServerTarget;

/**
 * Parsed JDBC connection information. A hand-written equivalent of the main project's AutoValue
 * {@code io.opentelemetry.instrumentation.jdbc.internal.dbinfo.DbInfo} (same accessors, builder
 * and value semantics), so the ported URL parsers compile unchanged on Java 6.
 */
public final class DbInfo {

  public static final DbInfo DEFAULT = builder().build();

  private final String dbSystemName;
  private final String dbSystem;
  private final String subtype;
  private final String dbConnectionString;
  private final String dbUser;
  private final String dbName;
  private final String dbNamespace;
  private final String legacyServerAddress;
  private final Integer legacyServerPort;
  private final DbServerTarget configuredServerTarget;

  private DbInfo(Builder b) {
    this.dbSystemName = b.dbSystemName;
    this.dbSystem = b.dbSystem;
    this.subtype = b.subtype;
    this.dbConnectionString = b.dbConnectionString;
    this.dbUser = b.dbUser;
    this.dbName = b.dbName;
    this.dbNamespace = b.dbNamespace;
    this.legacyServerAddress = b.legacyServerAddress;
    this.legacyServerPort = b.legacyServerPort;
    this.configuredServerTarget = b.configuredServerTarget;
  }

  public static Builder builder() {
    return new Builder();
  }

  /** The stable {@code db.system.name} value (e.g. "h2database", "oracle.db"). */
  public String getDbSystemName() {
    return dbSystemName;
  }

  /** The old-semconv {@code db.system} value (e.g. "h2", "oracle"). */
  public String getDbSystem() {
    return dbSystem;
  }

  public String getSubtype() {
    return subtype;
  }

  /** {@code type:[subtype:]//host:port}. */
  public String getDbConnectionString() {
    return dbConnectionString;
  }

  public String getDbUser() {
    return dbUser;
  }

  public String getDbName() {
    return dbName;
  }

  public String getDbNamespace() {
    return dbNamespace;
  }

  /** The parser's single host, including defaults, used for legacy telemetry and pool names. */
  public String getLegacyServerAddress() {
    return legacyServerAddress;
  }

  /** The parser's single port, including defaults, used for legacy telemetry and pool names. */
  public Integer getLegacyServerPort() {
    return legacyServerPort;
  }

  /**
   * The logical configured target for stable telemetry, or {@code null} when unavailable. This is
   * not the endpoint that handled an individual query.
   */
  public DbServerTarget getConfiguredServerTarget() {
    return configuredServerTarget;
  }

  public String getSystem() {
    return dbSystem != null ? dbSystem : dbSystemName;
  }

  public String getShortUrl() {
    return dbConnectionString;
  }

  public String getUser() {
    return dbUser;
  }

  public String getName() {
    return dbName != null ? dbName : dbNamespace;
  }

  public String getHost() {
    return legacyServerAddress;
  }

  public Integer getPort() {
    return legacyServerPort;
  }

  public Builder toBuilder() {
    return builder()
        .dbSystemName(dbSystemName)
        .dbSystem(dbSystem)
        .subtype(subtype)
        .dbConnectionString(dbConnectionString)
        .dbUser(dbUser)
        .dbName(dbName)
        .dbNamespace(dbNamespace)
        .legacyServerAddress(legacyServerAddress)
        .legacyServerPort(legacyServerPort)
        .configuredServerTarget(configuredServerTarget);
  }

  @Override
  public boolean equals(Object o) {
    if (o == this) {
      return true;
    }
    if (!(o instanceof DbInfo)) {
      return false;
    }
    DbInfo that = (DbInfo) o;
    return eq(dbSystemName, that.dbSystemName)
        && eq(dbSystem, that.dbSystem)
        && eq(subtype, that.subtype)
        && eq(dbConnectionString, that.dbConnectionString)
        && eq(dbUser, that.dbUser)
        && eq(dbName, that.dbName)
        && eq(dbNamespace, that.dbNamespace)
        && eq(legacyServerAddress, that.legacyServerAddress)
        && eq(legacyServerPort, that.legacyServerPort)
        && eq(configuredServerTarget, that.configuredServerTarget);
  }

  @Override
  public int hashCode() {
    Object[] fields = {
      dbSystemName, dbSystem, subtype, dbConnectionString, dbUser, dbName, dbNamespace,
      legacyServerAddress, legacyServerPort, configuredServerTarget
    };
    return java.util.Arrays.hashCode(fields);
  }

  @Override
  public String toString() {
    return "DbInfo{system=" + dbSystem + ", subtype=" + subtype + ", connectionString="
        + dbConnectionString + ", user=" + dbUser + ", name=" + dbName + ", host="
        + legacyServerAddress + ", port=" + legacyServerPort + ", target=" + configuredServerTarget + "}";
  }

  private static boolean eq(Object a, Object b) {
    return a == null ? b == null : a.equals(b);
  }

  /** Builder, matching the main project's AutoValue builder. */
  public static final class Builder {
    private String dbSystemName;
    private String dbSystem;
    private String subtype;
    private String dbConnectionString;
    private String dbUser;
    private String dbName;
    private String dbNamespace;
    private String legacyServerAddress;
    private Integer legacyServerPort;
    private DbServerTarget configuredServerTarget;

    Builder() {}

    public Builder dbSystemName(String dbSystemName) {
      this.dbSystemName = dbSystemName;
      return this;
    }

    public Builder dbSystem(String dbSystem) {
      this.dbSystem = dbSystem;
      return this;
    }

    public Builder subtype(String subtype) {
      this.subtype = subtype;
      return this;
    }

    public Builder dbConnectionString(String dbConnectionString) {
      this.dbConnectionString = dbConnectionString;
      return this;
    }

    public Builder dbUser(String dbUser) {
      this.dbUser = dbUser;
      return this;
    }

    public Builder dbName(String dbName) {
      this.dbName = dbName;
      return this;
    }

    public Builder dbNamespace(String dbNamespace) {
      this.dbNamespace = dbNamespace;
      return this;
    }

    public Builder legacyServerAddress(String legacyServerAddress) {
      this.legacyServerAddress = legacyServerAddress;
      return this;
    }

    public Builder legacyServerPort(Integer legacyServerPort) {
      this.legacyServerPort = legacyServerPort;
      return this;
    }

    public Builder system(String system) {
      return dbSystemName(system).dbSystem(system);
    }

    public Builder shortUrl(String shortUrl) {
      return dbConnectionString(shortUrl);
    }

    public Builder user(String user) {
      return dbUser(user);
    }

    public Builder name(String name) {
      return dbNamespace(name).dbName(name);
    }

    public Builder configuredServerTarget(DbServerTarget configuredServerTarget) {
      this.configuredServerTarget = configuredServerTarget;
      return this;
    }

    public Builder host(String host) {
      return legacyServerAddress(host);
    }

    public Builder port(Integer port) {
      return legacyServerPort(port);
    }

    public DbInfo build() {
      return new DbInfo(this);
    }
  }
}
