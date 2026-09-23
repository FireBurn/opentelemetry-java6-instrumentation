/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.java6.agent.trace;

/**
 * Entry point for span creation. Initialized once by the agent installer; before that (and with
 * tracing disabled) {@link #getInstance()} returns a tracer whose spans are non-recording but
 * still propagate context.
 */
public final class Tracer {

  private static volatile Tracer instance;
  private static final Tracer NOOP = new Tracer(null, Sampler.create("always_off", null));

  final BatchSpanProcessor processor;
  final Sampler sampler;

  private Tracer(BatchSpanProcessor processor, Sampler sampler) {
    this.processor = processor;
    this.sampler = sampler;
  }

  public static void init(BatchSpanProcessor processor, Sampler sampler) {
    instance = new Tracer(processor, sampler);
  }

  public static Tracer getInstance() {
    Tracer tracer = instance;
    return tracer == null ? NOOP : tracer;
  }

  public SpanBuilder spanBuilder(String name, String scopeName) {
    return new SpanBuilderImpl(name, this).setScopeName(scopeName);
  }
}
