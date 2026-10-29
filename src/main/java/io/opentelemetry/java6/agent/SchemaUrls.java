/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.java6.agent;

/**
 * The semantic-conventions schema URLs the standard agent puts on its telemetry. They are set by
 * the instrumenters (HTTP 1.41.0, legacy database 1.24.0, servlet response code attributes 1.37.0),
 * the runtime metrics (1.44.0) and the SDK's default resource (1.40.0 in SDK 1.67.0), so they are
 * mapped here by instrumentation scope.
 */
public final class SchemaUrls {

  public static final String V1_24_0 = "https://opentelemetry.io/schemas/1.24.0";
  public static final String V1_37_0 = "https://opentelemetry.io/schemas/1.37.0";
  public static final String V1_40_0 = "https://opentelemetry.io/schemas/1.40.0";
  public static final String V1_41_0 = "https://opentelemetry.io/schemas/1.41.0";
  public static final String V1_44_0 = "https://opentelemetry.io/schemas/1.44.0";

  /** Schema URL of the resource (from the SDK's default resource). */
  public static final String RESOURCE = V1_40_0;

  /** The schema URL for an instrumentation scope's telemetry, or {@code ""} when it has none. */
  public static String forScope(String scopeName) {
    if ("io.opentelemetry.jdbc".equals(scopeName)) {
      return V1_24_0;
    }
    if ("io.opentelemetry.servlet-3.0".equals(scopeName)
        || "io.opentelemetry.http-url-connection".equals(scopeName)
        || "io.opentelemetry.java-http-server".equals(scopeName)) {
      return V1_41_0;
    }
    if ("io.opentelemetry.runtime-telemetry-java8".equals(scopeName)) {
      return V1_44_0;
    }
    return "";
  }

  private SchemaUrls() {}
}
