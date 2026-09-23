/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.java6.agent.instrumentation.jdbc.internal.parser;

import static io.opentelemetry.java6.agent.instrumentation.jdbc.internal.parser.UrlParsingUtils.buildShortUrl;

import io.opentelemetry.java6.agent.instrumentation.jdbc.internal.dbinfo.DbInfo;
import io.opentelemetry.java6.agent.instrumentation.jdbc.internal.parser.UrlParsingUtils.HostPort;
import io.opentelemetry.java6.agent.instrumentation.jdbc.internal.parser.UrlParsingUtils.UrlParams;
import java.util.Map;
import java.util.Properties;

/**
 * Mutable context for building up connection info during JDBC URL parsing.
 *
 * <p>This class is internal and is hence not for public use. Its APIs are unstable and can change
 * at any time.
 */
public final class ParseContext {

  private final String type;
  private String system;
  private String oldSemconvSystem;
  private String subtype;
  private String host;
  private Integer port;
  private String user;
  private String databaseName;
  private String namespace;
  @Deprecated private String dbName;
  private final Properties props;

  private ParseContext(String type, Properties props) {
    this.type = type;
    this.props = props;
  }

  /** Create a context with the JDBC type and optional properties. */
  public static ParseContext of(String type, Properties props) {
    return new ParseContext(type, props);
  }

  /** The JDBC type (e.g., "mysql", "postgresql"). */
  public String type() {
    return type;
  }

  /** The database system identifier (stable/new value, e.g., "postgresql", "h2database"). */
  public String system() {
    return system;
  }

  /**
   * Set the database system identifier (stable/new value). For systems where old and new values
   * differ, also call {@link #oldSemconvSystem(String)}.
   */
  public void system(String system) {
    this.system = system;
  }

  /** The old semconv database system identifier (e.g., "mssql", "h2"). */
  @Deprecated // to be removed in 3.0
  public String oldSemconvSystem() {
    return oldSemconvSystem;
  }

  /** Set the old semconv database system identifier (only required when different from system). */
  @Deprecated // to be removed in 3.0
  public void oldSemconvSystem(String oldSemconvSystem) {
    this.oldSemconvSystem = oldSemconvSystem;
  }

  /** The optional subtype (e.g., "tcp", "aurora"). */
  public String subtype() {
    return subtype;
  }

  /** Set the subtype value. */
  public void subtype(String subtype) {
    this.subtype = subtype;
  }

  /** The host value accumulated so far. */
  public String host() {
    return host;
  }

  /**
   * Set the host value. Enclosing brackets are removed from literal IPv6 addresses so that {@code
   * server.address} always holds the address alone, regardless of which parser produced it.
   */
  public void host(String host) {
    this.host = host == null ? null : UrlParsingUtils.stripIpv6Brackets(host);
  }

  /** The port value accumulated so far. */
  public Integer port() {
    return port;
  }

  /** Set the port value. */
  public void port(Integer port) {
    this.port = port;
  }

  /** The user value accumulated so far. */
  @Deprecated // to be removed in 3.0
  public String user() {
    return user;
  }

  /** Set the user value. */
  @Deprecated // to be removed in 3.0
  public void user(String user) {
    this.user = user;
  }

  /** The database name value accumulated so far. */
  public String databaseName() {
    return databaseName;
  }

  /** Set the database name value. */
  public void databaseName(String databaseName) {
    this.databaseName = databaseName;
  }

  /** The namespace value accumulated so far. */
  public String namespace() {
    return namespace;
  }

  /** Set the namespace value. */
  public void namespace(String namespace) {
    this.namespace = namespace;
  }

  /**
   * Override for the dbName field in the resulting DbInfo. When set, this value takes precedence
   * over the databaseName-derived value. Used by SQL Server parsers to preserve old behavior where
   * dbName is the instance name when both instance and database are present.
   */
  @Deprecated // to be removed in 3.0
  public String dbName() {
    return dbName;
  }

  @Deprecated // to be removed in 3.0
  public void dbName(String dbName) {
    this.dbName = dbName;
  }

  /** DataSource connection properties. */
  public Properties props() {
    return props;
  }

  /**
   * Apply common parameters from URL parameters to the context.
   *
   * <p>Extracts the same properties as {@link #applyDataSourceProperties()} but using lowercase
   * keys (servername, portnumber, databasename, user) as URL params are typically lowercased.
   *
   * @param jdbcUrl the JDBC URL containing parameters
   * @param startDelimiter the delimiter marking the start of parameters (";" or "?")
   * @param splitSeparator the separator between individual parameters (";" or "&amp;")
   */
  public void applyCommonParams(String jdbcUrl, String startDelimiter, String splitSeparator) {
    applyCommonParams(UrlParsingUtils.extractParams(jdbcUrl, startDelimiter, splitSeparator));
  }

  /**
   * Apply common parameters from a pre-parsed parameter map. Equivalent to {@link
   * #applyCommonParams(String, String, String)} but avoids re-parsing the URL when the caller has
   * already extracted the parameters.
   *
   * @param params the parameter map (keys must be lowercase)
   */
  public void applyCommonParams(Map<String, String> params) {
    if (params.isEmpty()) {
      return;
    }
    if (params.containsKey("servername")) {
      host(params.get("servername"));
    }
    Integer port = UrlParsingUtils.parsePort(params.get("portnumber"));
    if (port != null) {
      this.port = port;
    }
    String databaseName = params.get("databasename");
    if (databaseName != null && !databaseName.isEmpty()) {
      this.databaseName = databaseName;
    }
    if (params.containsKey("user")) {
      this.user = params.get("user");
    }
  }

  /**
   * Apply common DataSource properties to this context. These properties are defined by the JDBC
   * specification (JSR 221, Section 9.4.1).
   *
   * <p>Extracts serverName, portNumber, databaseName, and user from the properties if present.
   */
  public void applyDataSourceProperties() {
    if (props == null) {
      return;
    }

    String serverName = props.getProperty("serverName");
    if (serverName != null && !serverName.isEmpty()) {
      host(serverName);
    }

    Integer parsedPort = UrlParsingUtils.parsePort(props.getProperty("portNumber"));
    if (parsedPort != null) {
      this.port = parsedPort;
    }

    String databaseName = props.getProperty("databaseName");
    if (databaseName != null && !databaseName.isEmpty()) {
      this.databaseName = databaseName;
    }

    String propsUser = props.getProperty("user");
    if (propsUser != null && !propsUser.isEmpty()) {
      this.user = propsUser;
    }
  }

  /**
   * Apply only the user property from DataSource properties. Use this for drivers that don't
   * support the standard serverName/portNumber/databaseName DataSource properties (e.g., SAP HANA,
   * H2, HSQLDB, Derby).
   *
   * <p>TODO: Currently delegates to {@link #applyDataSourceProperties()} to avoid a behavioral
   * change in this refactoring. In the future, this will be changed to only apply the user
   * property.
   */
  public void applyUserProperty() {
    // TODO: change to only apply user property
    applyDataSourceProperties();
  }

  /**
   * Parse a URL-style JDBC connection string that uses semicolons for properties. Updates this
   * context with extracted values (user, host, port, path).
   *
   * <p>If the URL contains a database path, it is applied to this context and overrides any
   * previously set database name.
   *
   * @param jdbcUrl the JDBC URL to parse
   */
  public void parseUrl(String jdbcUrl) {
    // Split off semicolon-delimited parameters
    String[] split = jdbcUrl.split(";", 2);
    String urlPart = split[0];
    if (split.length > 1) {
      UrlParams params = UrlParams.fromSemicolon(split[1]);
      if (params.get("user") != null) {
        this.user = params.get("user");
      }
    }

    int hostIndex = urlPart.indexOf("://");
    if (hostIndex <= 0) {
      return;
    }

    // Parse URL host/port
    String serverName = urlPart.substring(hostIndex + 3);
    if (serverName.isEmpty()) {
      return;
    }

    // Extract database path from URL
    int pathLoc = serverName.indexOf("/");
    if (pathLoc > 0) {
      String databaseName = serverName.substring(pathLoc + 1);
      if (!databaseName.isEmpty()) {
        this.databaseName = databaseName;
      }
      serverName = serverName.substring(0, pathLoc);
    }

    // Handle IPv6 addresses and extract host:port
    HostPort hostPort = UrlParsingUtils.extractHostPort(serverName);
    if (hostPort.port() != null) {
      this.port = hostPort.port();
    }
    if (!hostPort.host().isEmpty()) {
      host(hostPort.host());
    }
  }

  /**
   * Build the final DbInfo from the accumulated context values.
   *
   * @return the complete DbInfo
   */
  public DbInfo toDbInfo() {
    // oldSemconvSystem falls back to system when not explicitly set (i.e., when both are the same)
    String oldSystem = oldSemconvSystem != null ? oldSemconvSystem : system;
    DbInfo.Builder builder = DbInfo.builder().dbSystemName(system).dbSystem(oldSystem);
    if (host != null) {
      builder.serverAddress(host);
    }
    if (port != null) {
      builder.serverPort(port);
    }
    if (user != null) {
      builder.dbUser(user);
    }
    if (namespace != null) {
      builder.dbNamespace(namespace);
    } else if (databaseName != null) {
      builder.dbNamespace(databaseName);
    }
    if (dbName != null) {
      builder.dbName(dbName);
    } else if (databaseName != null) {
      builder.dbName(databaseName);
    } else if (namespace != null) {
      builder.dbName(namespace);
    }
    builder.dbConnectionString(buildShortUrl(type, subtype, host, port));
    return builder.build();
  }
}
