/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.java6.agent.metrics;

import java.lang.management.ClassLoadingMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;
import java.lang.management.OperatingSystemMXBean;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.management.Notification;
import javax.management.NotificationEmitter;
import javax.management.NotificationListener;
import javax.management.openmbean.CompositeData;

/**
 * JVM runtime metrics with the same names, units, descriptions and attributes as the standard
 * agent's {@code io.opentelemetry.runtime-telemetry-java8} scope: memory pools, classes, CPU,
 * threads and GC duration. JVM-specific MXBeans (HotSpot {@code com.sun.management}, IBM J9 {@code
 * com.ibm.lang.management}) are used reflectively, and a metric is left out when the JVM does not
 * provide its source, as in the main project.
 */
public final class JvmMetrics {

  public static final String SCOPE = "io.opentelemetry.runtime-telemetry-java8";
  private static final Logger logger = Logger.getLogger("io.opentelemetry.java6.agent");

  private JvmMetrics() {}

  public static void register(String version) {
    final String v = version + "-alpha";
    registerMemory(v);
    registerClasses(v);
    registerCpu(v);
    registerThreads(v);
    registerGc(v);
  }

  private static Attrs poolAttrs(MemoryPoolMXBean pool) {
    return Attrs.of(
        "jvm.memory.pool.name", pool.getName(),
        "jvm.memory.type", pool.getType() == MemoryType.HEAP ? "heap" : "non_heap");
  }

  private interface UsageGetter {
    long get(MemoryPoolMXBean pool);
  }

  private static void memory(
      String version, String name, String description, final UsageGetter getter) {
    final List<MemoryPoolMXBean> pools = ManagementFactory.getMemoryPoolMXBeans();
    MeterRegistry.asyncUpDownCounter(
        SCOPE, version, name, description, "By",
        new Callback() {
          public void collect(Recorder recorder) {
            for (int i = 0; i < pools.size(); i++) {
              MemoryPoolMXBean pool = pools.get(i);
              long value = getter.get(pool);
              if (value != -1) {
                recorder.record(value, poolAttrs(pool));
              }
            }
          }
        });
  }

  private static void registerMemory(String version) {
    memory(version, "jvm.memory.used", "Measure of memory used.",
        new UsageGetter() {
          public long get(MemoryPoolMXBean pool) {
            MemoryUsage usage = pool.getUsage();
            return usage == null ? -1 : usage.getUsed();
          }
        });
    memory(version, "jvm.memory.committed", "Measure of memory committed.",
        new UsageGetter() {
          public long get(MemoryPoolMXBean pool) {
            MemoryUsage usage = pool.getUsage();
            return usage == null ? -1 : usage.getCommitted();
          }
        });
    memory(version, "jvm.memory.limit", "Measure of max obtainable memory.",
        new UsageGetter() {
          public long get(MemoryPoolMXBean pool) {
            MemoryUsage usage = pool.getUsage();
            return usage == null ? -1 : usage.getMax();
          }
        });
    memory(version, "jvm.memory.used_after_last_gc",
        "Measure of memory used, as measured after the most recent garbage collection event on "
            + "this pool.",
        new UsageGetter() {
          public long get(MemoryPoolMXBean pool) {
            MemoryUsage usage = pool.getCollectionUsage();
            return usage == null ? -1 : usage.getUsed();
          }
        });
  }

  private static void registerClasses(String version) {
    final ClassLoadingMXBean bean = ManagementFactory.getClassLoadingMXBean();
    MeterRegistry.asyncCounter(SCOPE, version, "jvm.class.loaded",
        "Number of classes loaded since JVM start.", "{class}",
        new Callback() {
          public void collect(Recorder recorder) {
            recorder.record(bean.getTotalLoadedClassCount(), Attrs.EMPTY);
          }
        });
    MeterRegistry.asyncCounter(SCOPE, version, "jvm.class.unloaded",
        "Number of classes unloaded since JVM start.", "{class}",
        new Callback() {
          public void collect(Recorder recorder) {
            recorder.record(bean.getUnloadedClassCount(), Attrs.EMPTY);
          }
        });
    MeterRegistry.asyncUpDownCounter(SCOPE, version, "jvm.class.count",
        "Number of classes currently loaded.", "{class}",
        new Callback() {
          public void collect(Recorder recorder) {
            recorder.record((long) bean.getLoadedClassCount(), Attrs.EMPTY);
          }
        });
  }

  /** {@code getProcessCpuTime} from the HotSpot or IBM J9 OperatingSystemMXBean, or null. */
  private static Method osMethod(OperatingSystemMXBean os, String name) {
    String[] beans = {
      "com.sun.management.OperatingSystemMXBean", "com.ibm.lang.management.OperatingSystemMXBean"
    };
    for (int i = 0; i < beans.length; i++) {
      try {
        Class<?> type = Class.forName(beans[i]);
        if (type.isInstance(os)) {
          Method m = type.getMethod(name);
          m.setAccessible(true);
          return m;
        }
      } catch (Throwable t) {
        // not this JVM's bean
      }
    }
    return null;
  }

  private static void registerCpu(String version) {
    final OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
    final Method cpuTime = osMethod(os, "getProcessCpuTime");
    final double cpuTimeScale = cpuTimeNanosPerUnit(os, cpuTime);
    if (cpuTime != null) {
      MeterRegistry.asyncCounter(SCOPE, version, "jvm.cpu.time",
          "CPU time used by the process as reported by the JVM.", "s",
          new Callback() {
            public void collect(Recorder recorder) {
              try {
                long t = ((Number) cpuTime.invoke(os)).longValue();
                if (t >= 0) {
                  recorder.record(t * cpuTimeScale / 1e9, Attrs.EMPTY);
                }
              } catch (Throwable ignored) {
                // unavailable
              }
            }
          });
    }
    MeterRegistry.asyncUpDownCounter(SCOPE, version, "jvm.cpu.count",
        "Number of processors available to the Java virtual machine.", "{cpu}",
        new Callback() {
          public void collect(Recorder recorder) {
            recorder.record((long) Runtime.getRuntime().availableProcessors(), Attrs.EMPTY);
          }
        });
    final Method cpuLoad = osMethod(os, "getProcessCpuLoad");
    if (cpuLoad != null) {
      MeterRegistry.gauge(SCOPE, version, "jvm.cpu.recent_utilization",
          "Recent CPU utilization for the process as reported by the JVM.", "1",
          new Callback() {
            public void collect(Recorder recorder) {
              try {
                double load = ((Number) cpuLoad.invoke(os)).doubleValue();
                if (load >= 0) {
                  recorder.record(load, Attrs.EMPTY);
                }
              } catch (Throwable ignored) {
                // unavailable
              }
            }
          });
    }
  }

  /**
   * IBM Java 6 reports {@code getProcessCpuTime} in 100ns units (IBM Java 7+ and HotSpot report
   * nanoseconds; IBM Java 7 SR3+ can also be switched with {@code
   * -Dcom.ibm.lang.management.OperatingSystemMXBean.isCpuTime100ns}).
   */
  static double cpuTimeNanosPerUnit(OperatingSystemMXBean os, Method cpuTime) {
    if (cpuTime == null
        || !cpuTime.getDeclaringClass().getName().startsWith("com.ibm.")) {
      return 1.0;
    }
    String flag = System.getProperty("com.ibm.lang.management.OperatingSystemMXBean.isCpuTime100ns");
    if (flag != null) {
      return Boolean.valueOf(flag).booleanValue() ? 100.0 : 1.0;
    }
    return System.getProperty("java.specification.version", "").equals("1.6") ? 100.0 : 1.0;
  }

  private static void registerThreads(String version) {
    MeterRegistry.asyncUpDownCounter(SCOPE, version, "jvm.thread.count",
        "Number of executing platform threads.", "{thread}",
        new Callback() {
          public void collect(Recorder recorder) {
            // as the main project on Java 8: enumerate the root thread group
            ThreadGroup group = Thread.currentThread().getThreadGroup();
            while (group.getParent() != null) {
              group = group.getParent();
            }
            Thread[] threads = new Thread[group.activeCount() + 10];
            int n = group.enumerate(threads);
            Map<Attrs, long[]> counts = new HashMap<Attrs, long[]>();
            for (int i = 0; i < n; i++) {
              Thread t = threads[i];
              Attrs a =
                  Attrs.of(
                      "jvm.thread.daemon", Boolean.valueOf(t.isDaemon()),
                      "jvm.thread.state", t.getState().name().toLowerCase(Locale.ROOT));
              long[] c = counts.get(a);
              if (c == null) {
                counts.put(a, new long[] {1});
              } else {
                c[0]++;
              }
            }
            for (Map.Entry<Attrs, long[]> e : counts.entrySet()) {
              recorder.record(e.getValue()[0], e.getKey());
            }
          }
        });
  }

  private static void registerGc(String version) {
    String notificationType = null;
    Method from = null;
    for (String prefix : new String[] {"com.sun.management", "com.ibm.lang.management"}) {
      try {
        Class<?> info = Class.forName(prefix + ".GarbageCollectionNotificationInfo");
        from = info.getMethod("from", CompositeData.class);
        notificationType =
            (String) info.getField("GARBAGE_COLLECTION_NOTIFICATION").get(null);
        break;
      } catch (Throwable t) {
        // not available on this JVM
      }
    }
    if (from == null) {
      logger.fine("GC notifications unavailable on this JVM: jvm.gc.duration not recorded");
      return;
    }
    final DoubleHistogram histogram =
        MeterRegistry.histogram(SCOPE, version, "jvm.gc.duration",
            "Duration of JVM garbage collection actions.", "s",
            new double[] {0.01, 0.1, 1, 10});
    final Method fromMethod = from;
    final String type = notificationType;
    NotificationListener listener =
        new NotificationListener() {
          public void handleNotification(Notification notification, Object handback) {
            if (!type.equals(notification.getType())) {
              return;
            }
            try {
              Object info = fromMethod.invoke(null, notification.getUserData());
              String name = (String) info.getClass().getMethod("getGcName").invoke(info);
              String action = (String) info.getClass().getMethod("getGcAction").invoke(info);
              Object gcInfo = info.getClass().getMethod("getGcInfo").invoke(info);
              long durationMs =
                  ((Number) gcInfo.getClass().getMethod("getDuration").invoke(gcInfo)).longValue();
              histogram.record(
                  durationMs / 1000.0, Attrs.of("jvm.gc.name", name, "jvm.gc.action", action),
                  null);
            } catch (Throwable t) {
              logger.log(Level.FINE, "gc notification failed", t);
            }
          }
        };
    List<GarbageCollectorMXBean> beans = ManagementFactory.getGarbageCollectorMXBeans();
    for (int i = 0; i < beans.size(); i++) {
      if (beans.get(i) instanceof NotificationEmitter) {
        ((NotificationEmitter) beans.get(i)).addNotificationListener(listener, null, null);
      }
    }
  }
}
