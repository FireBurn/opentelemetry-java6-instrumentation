/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.java6.agent.trace;

import java.util.Random;

/**
 * Random W3C trace and span ids. Uses a per-thread {@link Random} seeded from {@code nanoTime}
 * and the thread id, as the main SDK uses {@code ThreadLocalRandom} (Java 7+): {@code
 * SecureRandom} is synchronized and can block on {@code /dev/random} on IBM J9.
 */
public final class IdGenerator {

  private static final char[] HEX = "0123456789abcdef".toCharArray();

  private static final ThreadLocal<Random> RANDOM =
      new ThreadLocal<Random>() {
        @Override
        protected Random initialValue() {
          return new Random(System.nanoTime() ^ (Thread.currentThread().getId() << 32));
        }
      };

  private IdGenerator() {}

  /** A random, valid (non-zero) 128-bit trace id as 32 lowercase hex characters. */
  public static String traceId() {
    Random random = RANDOM.get();
    long high;
    long low;
    do {
      high = random.nextLong();
      low = random.nextLong();
    } while (high == 0 && low == 0);
    return hex(high) + hex(low);
  }

  /** A random, valid (non-zero) 64-bit span id as 16 lowercase hex characters. */
  public static String spanId() {
    Random random = RANDOM.get();
    long id;
    do {
      id = random.nextLong();
    } while (id == 0);
    return hex(id);
  }

  /** Lowest 8 bytes of the trace id as a long, used by the ratio sampler. */
  static long lowerLong(String traceId) {
    long value = 0;
    for (int i = 16; i < 32; i++) {
      value = (value << 4) | Character.digit(traceId.charAt(i), 16);
    }
    return value;
  }

  private static String hex(long value) {
    char[] out = new char[16];
    for (int i = 15; i >= 0; i--) {
      out[i] = HEX[(int) (value & 0xf)];
      value >>>= 4;
    }
    return new String(out);
  }
}
