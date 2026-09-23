/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.java6.agent.trace;

import io.opentelemetry.java6.agent.metrics.Attrs;
import io.opentelemetry.java6.agent.metrics.Callback;
import io.opentelemetry.java6.agent.metrics.LongCounter;
import io.opentelemetry.java6.agent.metrics.MeterRegistry;
import io.opentelemetry.java6.agent.metrics.Recorder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Batches finished spans and exports them on a daemon worker thread when a batch is full or every
 * {@code otel.bsp.schedule.delay}, as the main SDK's {@code BatchSpanProcessor}, including its
 * {@code processedSpans}/{@code queueSize} self-metrics.
 */
public final class BatchSpanProcessor {

  private static final Logger logger = Logger.getLogger("io.opentelemetry.java6.agent");
  private static final String SELF_SCOPE = "io.opentelemetry.sdk.trace";
  private static final Attrs PROCESSED =
      Attrs.of("processorType", "BatchSpanProcessor", "dropped", Boolean.FALSE);
  private static final Attrs DROPPED =
      Attrs.of("processorType", "BatchSpanProcessor", "dropped", Boolean.TRUE);

  private final SpanExporter exporter;
  private final long scheduleDelayMs;
  private final int maxBatchSize;
  private final BlockingQueue<SpanData> queue;
  private final Thread worker;
  private final Object signal = new Object();
  private final LongCounter processedSpans;
  private volatile boolean running = true;
  private boolean warnedDrop;

  /**
   * @param scheduleDelayMs {@code otel.bsp.schedule.delay}
   * @param maxQueueSize {@code otel.bsp.max-queue-size}
   * @param maxBatchSize {@code otel.bsp.max-export-batch-size}
   */
  public BatchSpanProcessor(
      SpanExporter exporter, long scheduleDelayMs, int maxQueueSize, int maxBatchSize) {
    this.exporter = exporter;
    this.scheduleDelayMs = scheduleDelayMs;
    this.maxBatchSize = maxBatchSize;
    this.queue = new ArrayBlockingQueue<SpanData>(maxQueueSize);
    this.processedSpans =
        MeterRegistry.counter(
            SELF_SCOPE, null, "processedSpans",
            "The number of spans processed by the BatchSpanProcessor. [dropped=true if they were"
                + " dropped due to high throughput]",
            "1");
    MeterRegistry.gauge(
        SELF_SCOPE, null, "queueSize", "The number of items queued", "1",
        new Callback() {
          public void collect(Recorder recorder) {
            recorder.record((long) queue.size(), Attrs.of("processorType", "BatchSpanProcessor"));
          }
        });
    this.worker =
        new Thread(
            new Runnable() {
              public void run() {
                loop();
              }
            },
            "otel-java6-span-processor");
    this.worker.setDaemon(true);
    this.worker.start();
  }

  public void onEnd(SpanData spanData) {
    if (!queue.offer(spanData)) {
      processedSpans.add(1, DROPPED);
      if (!warnedDrop) {
        warnedDrop = true;
        logger.warning("span queue full (otel.bsp.max-queue-size), dropping spans");
      }
      return;
    }
    if (queue.size() >= maxBatchSize) {
      synchronized (signal) {
        signal.notify();
      }
    }
  }

  private void loop() {
    long nextExport = System.currentTimeMillis() + scheduleDelayMs;
    List<SpanData> batch = new ArrayList<SpanData>(maxBatchSize);
    while (running) {
      long wait = nextExport - System.currentTimeMillis();
      if (queue.size() < maxBatchSize && wait > 0) {
        try {
          synchronized (signal) {
            signal.wait(wait);
          }
        } catch (InterruptedException e) {
          return;
        }
      }
      queue.drainTo(batch, maxBatchSize);
      if (!batch.isEmpty()) {
        exportBatch(batch);
        batch.clear();
      }
      if (queue.size() < maxBatchSize) {
        nextExport = System.currentTimeMillis() + scheduleDelayMs;
      }
    }
  }

  private void exportBatch(List<SpanData> batch) {
    try {
      exporter.export(new ArrayList<SpanData>(batch));
      processedSpans.add(batch.size(), PROCESSED);
    } catch (Throwable t) {
      logger.log(Level.WARNING, "span export failed, dropping " + batch.size() + " spans", t);
    }
  }

  /** Stops the worker and exports what is still queued (bounded by {@code maxWaitMs}). */
  public void shutdown(long maxWaitMs) {
    running = false;
    worker.interrupt();
    try {
      worker.join(maxWaitMs);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    List<SpanData> remaining = new ArrayList<SpanData>();
    queue.drainTo(remaining);
    for (int from = 0; from < remaining.size(); from += maxBatchSize) {
      exportBatch(remaining.subList(from, Math.min(remaining.size(), from + maxBatchSize)));
    }
    try {
      exporter.shutdown();
    } catch (Throwable t) {
      logger.log(Level.FINE, "exporter shutdown failed", t);
    }
  }
}
