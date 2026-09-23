/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.java6.agent;

import java.util.logging.Level;
import java.util.logging.Logger;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.utility.JavaModule;

/** Logs instrumentation activity and makes sure failures are reported, never thrown. */
public final class EngineListener implements AgentBuilder.Listener {

  private static final Logger logger = Logger.getLogger("io.opentelemetry.java6.agent");

  public void onDiscovery(String typeName, ClassLoader classLoader, JavaModule module, boolean loaded) {}

  public void onTransformation(
      TypeDescription typeDescription,
      ClassLoader classLoader,
      JavaModule module,
      boolean loaded,
      DynamicType dynamicType) {
    logger.fine(
        "instrumented "
            + typeDescription.getTypeName()
            + " ("
            + dynamicType.getAllTypeDescriptions().size()
            + " types)");
  }

  public void onIgnored(
      TypeDescription typeDescription, ClassLoader classLoader, JavaModule module, boolean loaded) {}

  public void onError(
      String typeName, ClassLoader classLoader, JavaModule module, boolean loaded, Throwable throwable) {
    logger.log(Level.WARNING, "failed to instrument " + typeName, throwable);
  }

  public void onComplete(String typeName, ClassLoader classLoader, JavaModule module, boolean loaded) {}
}
