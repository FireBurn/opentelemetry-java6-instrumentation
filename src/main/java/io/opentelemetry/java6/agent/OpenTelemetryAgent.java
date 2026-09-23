/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.java6.agent;

import java.io.File;
import java.lang.instrument.Instrumentation;
import java.util.jar.JarFile;
import java.security.CodeSource;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Entry point of the Java 6 OpenTelemetry agent spike.
 *
 * <p>The whole agent (engine, telemetry core and advice helpers) is appended to the bootstrap
 * class loader search path so that inlined advice in application classes can resolve the helper
 * classes from any class loader, and so that context storage is a single shared copy.
 *
 * <p>This class is loaded by the system class loader (the JVM loads the premain class that way),
 * while the rest of the agent is loaded by the bootstrap class loader. Only JDK types ({@link
 * File}, {@link Instrumentation}) may therefore cross that boundary; passing a custom type would
 * cause a {@code LinkageError}.
 */
public final class OpenTelemetryAgent {

  private static final Logger logger = Logger.getLogger("io.opentelemetry.java6.agent");

  private OpenTelemetryAgent() {}

  public static void premain(String agentArgs, Instrumentation instrumentation) {
    try {
      File agentJar = locateAgentJar();
      instrumentation.appendToBootstrapClassLoaderSearch(new JarFile(agentJar));
      AgentInstaller.install(agentJar, instrumentation);
    } catch (Throwable t) {
      // never break the application
      logger.log(Level.SEVERE, "otel-java6 agent failed to start", t);
    }
  }

  private static File locateAgentJar() throws Exception {
    CodeSource codeSource = OpenTelemetryAgent.class.getProtectionDomain().getCodeSource();
    if (codeSource == null || codeSource.getLocation() == null) {
      throw new IllegalStateException("cannot determine agent jar location");
    }
    File jar = new File(codeSource.getLocation().toURI());
    if (!jar.isFile()) {
      throw new IllegalStateException("agent location is not a jar file: " + jar);
    }
    return jar;
  }
}
