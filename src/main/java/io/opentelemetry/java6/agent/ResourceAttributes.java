/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.java6.agent;

import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Builds the resource attributes, matching the standard agent's default resource providers
 * (service, sdk, distro, host, os, process). User-supplied {@code otel.resource.attributes}
 * override the built-in values, as in the standard agent. Providers can be disabled by listing
 * their class name in {@code otel.java.disabled.resource.providers}, using the standard class
 * names (for example {@code io.opentelemetry.instrumentation.resources.ProcessResourceProvider}).
 */
public final class ResourceAttributes {

  private ResourceAttributes() {}

  /**
   * The resource: built-in attributes, then {@code otel.resource.attributes}, then {@code
   * otel.service.name} (highest precedence), as in the standard agent. Values keep their OTLP type.
   */
  public static Map<String, Object> build(AgentConfig config) {
    Map<String, Object> result = compute(config);
    result.putAll(config.resourceAttributes);
    result.put("service.name", config.serviceName);
    return result;
  }

  private static Map<String, Object> compute(AgentConfig config) {
    Map<String, Object> m = new LinkedHashMap<String, Object>();
    List<String> disabled = config.disabledResourceProviders;

    // service
    if (!disabledContains(disabled, "ServiceResourceProvider")) {
      m.put("service.name", config.serviceName);
      m.put("service.instance.id", UUID.randomUUID().toString());
    }

    // sdk + distro (no provider class for these in the standard agent; always on)
    m.put("telemetry.sdk.name", "opentelemetry");
    m.put("telemetry.sdk.language", "java");
    m.put("telemetry.sdk.version", AgentConfig.SDK_VERSION);
    m.put("telemetry.distro.name", "opentelemetry-java-instrumentation");
    m.put("telemetry.distro.version", AgentConfig.AGENT_VERSION);

    // host
    if (!disabledContains(disabled, "HostResourceProvider")) {
      String hostName = null;
      try {
        hostName = InetAddress.getLocalHost().getHostName();
      } catch (Throwable t) {
        // leave unset
      }
      if (hostName != null) {
        m.put("host.name", hostName);
      }
      m.put("host.arch", System.getProperty("os.arch", "unknown"));
    }

    // os
    if (!disabledContains(disabled, "OsResourceProvider")) {
      String osName = System.getProperty("os.name", "");
      String osVersion = System.getProperty("os.version", "");
      String osType = osType(osName);
      if (osType != null) {
        m.put("os.type", osType);
      }
      if (osVersion.length() > 0) {
        m.put("os.version", osVersion);
      }
      if (osName.length() > 0) {
        m.put("os.description", osVersion.length() > 0 ? osName + " " + osVersion : osName);
      }
    }

    // process
    if (!disabledContains(disabled, "ProcessResourceProvider")) {
      Long pid = processPid();
      if (pid != null) {
        m.put("process.pid", pid);
      }
      String javaHome = System.getProperty("java.home");
      if (javaHome != null) {
        m.put("process.executable.path", javaHome + java.io.File.separator + "bin" + java.io.File.separator + "java");
      }
      // the standard agent uses the java.runtime.* system properties for name/version
      m.put("process.runtime.name", System.getProperty("java.runtime.name", "unknown"));
      m.put("process.runtime.version", System.getProperty("java.runtime.version", "unknown"));
      m.put(
          "process.runtime.description",
          System.getProperty("java.vm.vendor", "")
              + " "
              + System.getProperty("java.vm.name", "")
              + " "
              + System.getProperty("java.vm.version", ""));
      String commandLine = processCommandLine();
      if (commandLine != null) {
        m.put("process.command_line", commandLine);
      }
    }
    return m;
  }

  private static String osType(String osName) {
    String lower = osName.toLowerCase();
    if (lower.startsWith("windows")) {
      return "windows";
    }
    if (lower.startsWith("mac") || lower.startsWith("darwin")) {
      return "darwin";
    }
    if (lower.startsWith("linux")) {
      return "linux";
    }
    if (lower.startsWith("sunos") || lower.startsWith("solaris")) {
      return "solaris";
    }
    if (lower.startsWith("aix")) {
      return "aix";
    }
    if (lower.startsWith("hp-ux")) {
      return "hpux";
    }
    if (lower.startsWith("freebsd")) {
      return "freebsd";
    }
    return null;
  }

  /** {@code RuntimeMXBean.getName()} is {@code <pid>@<host>} since Java 5. */
  private static Long processPid() {
    try {
      String name = ManagementFactory.getRuntimeMXBean().getName();
      int at = name.indexOf('@');
      if (at > 0) {
        return Long.valueOf(name.substring(0, at));
      }
    } catch (Throwable t) {
      // leave unset
    }
    return null;
  }

  /**
   * Builds {@code process.command_line} the same way the standard agent does on Java 8 and
   * earlier (it uses {@code ProcessHandle} on Java 9+): the java executable, the JVM input
   * arguments ({@code RuntimeMXBean.getInputArguments()}, Java 5+) and {@code sun.java.command}
   * (the main class or script, added back with {@code -jar} when a jar is run directly).
   * Arguments whose property name contains "password" or "secret" are scrubbed, as in the
   * standard agent.
   */
  private static String processCommandLine() {
    try {
      StringBuilder sb = new StringBuilder();
      String javaHome = System.getProperty("java.home");
      if (javaHome != null) {
        sb.append(javaHome)
            .append(java.io.File.separator)
            .append("bin")
            .append(java.io.File.separator)
            .append("java");
      }
      java.util.List<String> inputArgs = ManagementFactory.getRuntimeMXBean().getInputArguments();
      for (int i = 0; i < inputArgs.size(); i++) {
        sb.append(' ').append(scrub(inputArgs.get(i)));
      }
      String javaCommand = System.getProperty("sun.java.command");
      if (javaCommand != null) {
        if (JAR_FILE_PATTERN.matcher(javaCommand).matches()) {
          sb.append(" -jar");
        }
        sb.append(' ').append(scrubCommandLine(javaCommand));
      }
      if (sb.length() > 0) {
        return sb.toString();
      }
    } catch (Throwable t) {
      // not available on this JVM
    }
    return null;
  }

  private static final java.util.regex.Pattern JAR_FILE_PATTERN =
      java.util.regex.Pattern.compile("^\\S+\\.(jar|war)", java.util.regex.Pattern.CASE_INSENSITIVE);

  private static final java.util.regex.Pattern SCRUB_PATTERN =
      java.util.regex.Pattern.compile(
          "(-D.*(password|secret).*=).*",
          java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.DOTALL);

  private static final java.util.regex.Pattern SCRUB_COMMAND_LINE_PATTERN =
      java.util.regex.Pattern.compile(
          "(-D[^=]*(password|secret)[^=]*=).*",
          java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.DOTALL);

  private static String scrub(String argument) {
    return SCRUB_PATTERN.matcher(argument).replaceFirst("$1***");
  }

  private static String scrubCommandLine(String commandLine) {
    return SCRUB_COMMAND_LINE_PATTERN.matcher(commandLine).replaceFirst("$1***");
  }

  private static boolean disabledContains(List<String> disabled, String simpleName) {
    for (int i = 0; i < disabled.size(); i++) {
      String d = disabled.get(i);
      int dot = d.lastIndexOf('.');
      if (d.equals(simpleName) || (dot >= 0 && d.substring(dot + 1).equals(simpleName))) {
        return true;
      }
    }
    return false;
  }
}
