/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.java6.agent.instrumentation;

/** Port of the main project's StringUtils.truncate: never splits a surrogate pair. */
public final class StringUtils {

  /**
   * Truncates {@code value} so that its length does not exceed {@code maxLength}. If the limit
   * would split a surrogate pair, the pair is omitted.
   */
  public static void truncate(StringBuilder value, int maxLength) {
    if (value.length() > maxLength) {
      value.setLength(truncationLength(value, maxLength));
    }
  }

  private static int truncationLength(CharSequence value, int maxLength) {
    if (maxLength > 0
        && Character.isHighSurrogate(value.charAt(maxLength - 1))
        && Character.isLowSurrogate(value.charAt(maxLength))) {
      return maxLength - 1;
    }
    return maxLength;
  }

  private StringUtils() {}
}
