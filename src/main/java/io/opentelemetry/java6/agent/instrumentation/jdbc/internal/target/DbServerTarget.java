/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.java6.agent.instrumentation.jdbc.internal.target;

/**
 * The logical configured database target. A hand-written equivalent of the main project's
 * AutoValue {@code DbServerTarget} (same factories, accessors and value semantics).
 */
public final class DbServerTarget {

  private final String address;
  private final Integer port;

  private DbServerTarget(String address, Integer port) {
    if (address == null) {
      throw new NullPointerException("Null address");
    }
    this.address = address;
    this.port = port;
  }

  public static DbServerTarget create(String address, Integer port) {
    return new DbServerTarget(address, port);
  }

  public static DbServerTargetBuilder builder(int defaultPort) {
    return new DbServerTargetBuilder(Integer.valueOf(defaultPort));
  }

  public static DbServerTargetBuilder builder() {
    return new DbServerTargetBuilder(null);
  }

  public static DbServerTarget unixSocket(String path) {
    if (path == null
        || path.length() <= 1
        || path.charAt(0) != '/'
        || path.indexOf(',') >= 0
        || path.indexOf('=') >= 0
        || path.indexOf('%') >= 0
        || path.indexOf('@') >= 0
        || path.indexOf('?') >= 0
        || path.indexOf('#') >= 0) {
      return null;
    }
    return create(path, null);
  }

  public String getAddress() {
    return address;
  }

  public Integer getPort() {
    return port;
  }

  @Override
  public boolean equals(Object o) {
    if (o == this) {
      return true;
    }
    if (!(o instanceof DbServerTarget)) {
      return false;
    }
    DbServerTarget that = (DbServerTarget) o;
    return address.equals(that.address) && (port == null ? that.port == null : port.equals(that.port));
  }

  @Override
  public int hashCode() {
    return address.hashCode() * 1000003 ^ (port == null ? 0 : port.hashCode());
  }

  @Override
  public String toString() {
    return "DbServerTarget{address=" + address + ", port=" + port + "}";
  }
}
