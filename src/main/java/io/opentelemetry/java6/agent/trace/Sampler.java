/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.java6.agent.trace;

import io.opentelemetry.java6.agent.context.SpanContext;

/**
 * The standard samplers, configured by {@code otel.traces.sampler} and {@code
 * otel.traces.sampler.arg}: {@code always_on}, {@code always_off}, {@code traceidratio}, {@code
 * parentbased_always_on} (default), {@code parentbased_always_off}, {@code
 * parentbased_traceidratio}. The ratio decision uses the same trace-id bound as the main SDK's
 * {@code TraceIdRatioBasedSampler}, so both agents sample the same traces.
 */
public final class Sampler {

  private static final int ALWAYS_ON = 0;
  private static final int ALWAYS_OFF = 1;
  private static final int RATIO = 2;

  private final boolean parentBased;
  private final int root;
  private final long idUpperBound;

  private Sampler(boolean parentBased, int root, double ratio) {
    this.parentBased = parentBased;
    this.root = root;
    if (ratio <= 0.0) {
      idUpperBound = Long.MIN_VALUE;
    } else if (ratio >= 1.0) {
      idUpperBound = Long.MAX_VALUE;
    } else {
      idUpperBound = (long) (ratio * Long.MAX_VALUE);
    }
  }

  /** Builds the sampler; unknown names fall back to the default {@code parentbased_always_on}. */
  public static Sampler create(String name, String arg) {
    double ratio = 1.0;
    if (arg != null && arg.length() > 0) {
      try {
        ratio = Double.parseDouble(arg.trim());
      } catch (NumberFormatException e) {
        ratio = 1.0;
      }
    }
    if ("always_on".equals(name)) {
      return new Sampler(false, ALWAYS_ON, 1.0);
    }
    if ("always_off".equals(name)) {
      return new Sampler(false, ALWAYS_OFF, 0.0);
    }
    if ("traceidratio".equals(name)) {
      return new Sampler(false, RATIO, ratio);
    }
    if ("parentbased_always_off".equals(name)) {
      return new Sampler(true, ALWAYS_OFF, 0.0);
    }
    if ("parentbased_traceidratio".equals(name)) {
      return new Sampler(true, RATIO, ratio);
    }
    return new Sampler(true, ALWAYS_ON, 1.0);
  }

  /** Whether a new span in trace {@code traceId} with the given parent is sampled. */
  boolean shouldSample(SpanContext parent, String traceId) {
    if (parentBased && parent != null) {
      // parent based: follow the parent's decision (remote or local)
      return parent.sampled;
    }
    switch (root) {
      case ALWAYS_OFF:
        return false;
      case RATIO:
        return Math.abs(IdGenerator.lowerLong(traceId)) < idUpperBound;
      default:
        return true;
    }
  }
}
