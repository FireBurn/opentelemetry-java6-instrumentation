/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.java6.agent.instrumentation;

import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Forwarded/X-Forwarded-* parsing, with the test vectors of the main project's (v2.31.1) {@code
 * ForwardedHostAddressAndPortExtractorTest}, {@code HttpServerAddressAndPortExtractorTest} and
 * {@code ForwardedUrlSchemeProviderTest} copied verbatim; the Mockito request getter is replaced
 * by passing the header values directly. Runs on Java 6 via MiniJupiter.
 */
class ForwardedHeadersTest {

  private static String[] values(List<String> headers) {
    return headers.toArray(new String[headers.size()]);
  }

  private static void assertServer(Object[] sink, String address, Integer port) {
    assertThat(sink[0]).isEqualTo(address);
    assertThat(sink[1] == null ? null : Integer.valueOf(((Long) sink[1]).intValue()))
        .isEqualTo(port);
  }

  @ParameterizedTest
  @MethodSource("forwardedArgs")
  void shouldParseForwarded(List<String> headers, String expectedAddress, Integer expectedPort) {
    assertServer(
        HttpSemconv.serverAddress(values(headers), null, null), expectedAddress, expectedPort);
  }

  @ParameterizedTest
  @MethodSource("hostArgs")
  void shouldParseForwardedHost(List<String> headers, String expectedAddress, Integer expectedPort) {
    assertServer(
        HttpSemconv.serverAddress(null, values(headers), null), expectedAddress, expectedPort);
  }

  @ParameterizedTest
  @MethodSource("hostArgs")
  void shouldParseHost(List<String> headers, String expectedAddress, Integer expectedPort) {
    assertServer(
        HttpSemconv.serverAddress(null, null, values(headers)), expectedAddress, expectedPort);
  }

  @ParameterizedTest
  @MethodSource("clientForwardedArgs")
  void shouldParseForwardedFor(List<String> headers, String expectedAddress) {
    assertThat(HttpSemconv.clientAddress(values(headers), null, null)).isEqualTo(expectedAddress);
  }

  @ParameterizedTest
  @MethodSource("clientForwardedForArgs")
  void shouldParseXForwardedFor(List<String> headers, String expectedAddress) {
    assertThat(HttpSemconv.clientAddress(null, values(headers), null)).isEqualTo(expectedAddress);
  }

  @Test
  void noHeaders() {
    assertThat(HttpSemconv.forwardedScheme(null, null)).isNull();
  }

  @ParameterizedTest
  @MethodSource("schemeForwardedArgs")
  void parseForwardedHeader(List<String> values, String expectedScheme) {
    assertThat(HttpSemconv.forwardedScheme(values(values), null)).isEqualTo(expectedScheme);
  }

  @ParameterizedTest
  @MethodSource("schemeForwardedProtoArgs")
  void parseForwardedProtoHeader(List<String> values, String expectedScheme) {
    assertThat(HttpSemconv.forwardedScheme(null, values(values))).isEqualTo(expectedScheme);
  }

  private static List<Arguments> forwardedArgs() {
    return asList(
        // empty/invalid headers
        arguments(singletonList(""), null, null),
        arguments(singletonList("host="), null, null),
        arguments(singletonList("host=;"), null, null),
        arguments(singletonList("host=\""), null, null),
        arguments(singletonList("host=\"\""), null, null),
        arguments(singletonList("host=\"example.com"), null, null),
        arguments(singletonList("by=1.2.3.4, test=abc"), null, null),
        arguments(singletonList("host=example.com"), "example.com", null),
        arguments(singletonList("host=\"example.com\""), "example.com", null),
        arguments(singletonList("host=example.com; test=abc:1234"), "example.com", null),
        arguments(singletonList("host=\"example.com\"; test=abc:1234"), "example.com", null),
        arguments(singletonList("host=example.com:port"), "example.com", null),
        arguments(singletonList("host=\"example.com:port\""), "example.com", null),
        arguments(singletonList("host=example.com:42"), "example.com", 42),
        arguments(singletonList("host=\"example.com:42\""), "example.com", 42),
        arguments(singletonList("host=example.com:42; test=abc:1234"), "example.com", 42),
        arguments(singletonList("host=\"example.com:42\"; test=abc:1234"), "example.com", 42),

        // multiple headers
        arguments(
            asList("proto=https", "host=example.com", "host=github.com:1234"),
            "example.com",
            null));
  }

  private static List<Arguments> hostArgs() {
    return asList(
        // empty/invalid headers
        arguments(singletonList(""), null, null),
        arguments(singletonList("\""), null, null),
        arguments(singletonList("\"\""), null, null),
        arguments(singletonList("example.com"), "example.com", null),
        arguments(singletonList("example.com:port"), "example.com", null),
        arguments(singletonList("example.com:42"), "example.com", 42),
        arguments(singletonList("\"example.com\""), "example.com", null),
        arguments(singletonList("\"example.com:port\""), "example.com", null),
        arguments(singletonList("\"example.com:42\""), "example.com", 42),

        // multiple headers
        arguments(asList("example.com", "github.com:1234"), "example.com", null));
  }

  private static List<Arguments> clientForwardedArgs() {
    return asList(
        // empty/invalid headers
        arguments(singletonList(""), null),
        arguments(singletonList("for="), null),
        arguments(singletonList("for=;"), null),
        arguments(singletonList("for=\""), null),
        arguments(singletonList("for=\"\""), null),
        arguments(singletonList("for=\"1.2.3.4"), null),
        arguments(singletonList("for=\"[::1]"), null),
        arguments(singletonList("for=[::1"), null),
        arguments(singletonList("for=\"[::1\""), null),
        arguments(singletonList("for=\"[::1\"]"), null),
        arguments(singletonList("by=1.2.3.4, test=abc"), null),

        // ipv6
        arguments(singletonList("for=[::1]"), "::1"),
        arguments(singletonList("For=[::1]"), "::1"),
        arguments(singletonList("for=\"[::1]\":42"), "::1"),
        arguments(singletonList("for=[::1]:42"), "::1"),
        arguments(singletonList("for=\"[::1]:42\""), "::1"),
        arguments(singletonList("for=[::1], for=1.2.3.4"), "::1"),
        arguments(singletonList("for=[::1]; for=1.2.3.4:42"), "::1"),
        arguments(singletonList("for=[::1]:42abc"), "::1"),
        arguments(singletonList("for=[::1]:abc"), "::1"),

        // ipv4
        arguments(singletonList("for=1.2.3.4"), "1.2.3.4"),
        arguments(singletonList("FOR=1.2.3.4"), "1.2.3.4"),
        arguments(singletonList("for=1.2.3.4, :42"), "1.2.3.4"),
        arguments(singletonList("for=1.2.3.4;proto=https;by=4.3.2.1"), "1.2.3.4"),
        arguments(singletonList("for=1.2.3.4:42"), "1.2.3.4"),
        arguments(singletonList("for=1.2.3.4:42abc"), "1.2.3.4"),
        arguments(singletonList("for=1.2.3.4:abc"), "1.2.3.4"),
        arguments(singletonList("for=1.2.3.4; for=4.3.2.1:42"), "1.2.3.4"),

        // multiple headers
        arguments(asList("proto=https", "for=1.2.3.4", "for=[::1]:42"), "1.2.3.4"));
  }

  private static List<Arguments> clientForwardedForArgs() {
    return asList(
        // empty/invalid headers
        arguments(singletonList(""), null),
        arguments(singletonList(";"), null),
        arguments(singletonList("\""), null),
        arguments(singletonList("\"\""), null),
        arguments(singletonList("\"1.2.3.4"), null),
        arguments(singletonList("\"[::1]"), null),
        arguments(singletonList("[::1"), null),
        arguments(singletonList("\"[::1\""), null),
        arguments(singletonList("\"[::1\"]"), null),

        // ipv6
        arguments(singletonList("[::1]"), "::1"),
        arguments(singletonList("\"[::1]\":42"), "::1"),
        arguments(singletonList("[::1]:42"), "::1"),
        arguments(singletonList("\"[::1]:42\""), "::1"),
        arguments(singletonList("[::1],1.2.3.4"), "::1"),
        arguments(singletonList("[::1];1.2.3.4:42"), "::1"),
        arguments(singletonList("[::1]:42abc"), "::1"),
        arguments(singletonList("[::1]:abc"), "::1"),

        // ipv4
        arguments(singletonList("1.2.3.4"), "1.2.3.4"),
        arguments(singletonList("1.2.3.4, :42"), "1.2.3.4"),
        arguments(singletonList("1.2.3.4,4.3.2.1"), "1.2.3.4"),
        arguments(singletonList("1.2.3.4:42"), "1.2.3.4"),
        arguments(singletonList("1.2.3.4:42abc"), "1.2.3.4"),
        arguments(singletonList("1.2.3.4:abc"), "1.2.3.4"),

        // ipv6 without brackets
        arguments(singletonList("::1"), "::1"),
        arguments(singletonList("::1,::2,1.2.3.4"), "::1"),
        arguments(singletonList("::1;::2;1.2.3.4"), "::1"),

        // multiple headers
        arguments(asList("1.2.3.4", "::1"), "1.2.3.4"));
  }

  private static List<Arguments> schemeForwardedArgs() {
    return asList(
        arguments(singletonList("for=1.1.1.1;proto=xyz"), "xyz"),
        arguments(singletonList("for=1.1.1.1;proto=xyz;"), "xyz"),
        arguments(singletonList("for=1.1.1.1;proto=xyz,"), "xyz"),
        arguments(singletonList("for=1.1.1.1;proto="), null),
        arguments(singletonList("for=1.1.1.1;proto=;"), null),
        arguments(singletonList("for=1.1.1.1;proto=,"), null),
        arguments(singletonList("for=1.1.1.1;proto=\"xyz\""), "xyz"),
        arguments(singletonList("for=1.1.1.1;proto=\"xyz\";"), "xyz"),
        arguments(singletonList("for=1.1.1.1;proto=\"xyz\","), "xyz"),
        arguments(singletonList("for=1.1.1.1;proto=\""), null),
        arguments(singletonList("for=1.1.1.1;proto=\"\""), null),
        arguments(singletonList("for=1.1.1.1;proto=\"\";"), null),
        arguments(singletonList("for=1.1.1.1;proto=\"\","), null),
        arguments(asList("for=1.1.1.1", "proto=xyz", "proto=abc"), "xyz"));
  }

  private static List<Arguments> schemeForwardedProtoArgs() {
    return asList(
        arguments(singletonList("xyz"), "xyz"),
        arguments(singletonList("\"xyz\""), "xyz"),
        arguments(singletonList("\""), null),
        arguments(asList("xyz", "abc"), "xyz"));
  }
}
