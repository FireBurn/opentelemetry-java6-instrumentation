/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.java6.agent.instrumentation.servlet;

import io.opentelemetry.java6.agent.context.Propagator;
import io.opentelemetry.java6.agent.instrumentation.HttpSemconv;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.List;
import javax.servlet.AsyncListener;
import javax.servlet.FilterConfig;
import javax.servlet.FilterRegistration;
import javax.servlet.Servlet;
import javax.servlet.ServletConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletRegistration;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import net.bytebuddy.asm.Advice;

/**
 * Servlet advice (see {@link ServletHelper}). The advice methods are inlined into the application's
 * servlets and filters, so every servlet API call lives here and resolves through the application
 * class loader; the bootstrap-loaded helpers only receive JDK types. Servlet 3.0 API calls
 * ({@code getStatus}, {@code isAsyncStarted}, registrations) fail with {@code NoSuchMethodError} on
 * 2.5 containers and are caught: those requests get no route and a status of -1.
 */
public final class ServletAdvice {

  private ServletAdvice() {}

  /** {@code Servlet.service(ServletRequest, ServletResponse)} and {@code Filter.doFilter(...)}. */
  public static class ServiceAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static ServletHelper.AdviceScope onEnter(
        @Advice.This Object self,
        @Advice.Argument(0) Object request,
        @Advice.Argument(1) Object response) {
      if (!(request instanceof HttpServletRequest) || !(response instanceof HttpServletResponse)) {
        return null;
      }
      HttpServletRequest req = (HttpServletRequest) request;
      boolean servlet = self instanceof Servlet;
      Object resolver = ServletHelper.resolver(self);
      if (resolver == null && servlet) {
        String[] mappings = null;
        try {
          ServletConfig config = ((Servlet) self).getServletConfig();
          ServletRegistration registration =
              config.getServletContext().getServletRegistration(config.getServletName());
          if (registration != null) {
            Collection<String> m = registration.getMappings();
            mappings = m.toArray(new String[m.size()]);
          }
        } catch (Throwable t) {
          mappings = null;
        }
        resolver = ServletHelper.cacheResolver(self, mappings);
      }
      ServletHelper.AdviceScope scope =
          ServletHelper.enter(
              req.getAttribute(ServletHelper.CONTEXT_ATTRIBUTE),
              servlet,
              resolver,
              req.getServletPath(),
              req.getPathInfo());
      if (scope.needsStart()) {
        // enter() counted this call: never let a failure here skip the exit advice
        try {
          String[] propagation = new String[Propagator.EXTRACT_HEADERS.length];
          for (int i = 0; i < propagation.length; i++) {
            propagation[i] = req.getHeader(Propagator.EXTRACT_HEADERS[i]);
          }
          // every value of the forwarding headers, as the main project reads them
          String[][] forwarding = new String[4][];
          String[] forwardingNames = {
            "forwarded", "x-forwarded-host", "x-forwarded-proto", "x-forwarded-for"
          };
          for (int i = 0; i < forwardingNames.length; i++) {
            Enumeration<?> values = req.getHeaders(forwardingNames[i]);
            List<String> list = new ArrayList<String>(1);
            while (values != null && values.hasMoreElements()) {
              list.add(String.valueOf(values.nextElement()));
            }
            forwarding[i] = list.isEmpty() ? null : list.toArray(new String[list.size()]);
          }
          String[] names = HttpSemconv.serverRequestHeaders();
          String[][] captured = new String[names.length][];
          for (int i = 0; i < names.length; i++) {
            Enumeration<?> values = req.getHeaders(names[i]);
            List<String> list = new ArrayList<String>();
            while (values != null && values.hasMoreElements()) {
              list.add(String.valueOf(values.nextElement()));
            }
            captured[i] = list.isEmpty() ? null : list.toArray(new String[list.size()]);
          }
          ServletHelper.start(
              scope,
              req.getMethod(),
              req.getScheme(),
              req.getRequestURI(),
              req.getQueryString(),
              req.getProtocol(),
              req.getContextPath(),
              req.getRemoteAddr(),
              req.getRemotePort(),
              req.getHeader("host"),
              forwarding[0],
              forwarding[1],
              forwarding[2],
              forwarding[3],
              req.getHeader("user-agent"),
              propagation,
              captured);
          req.setAttribute(ServletHelper.CONTEXT_ATTRIBUTE, scope.context());
        } catch (Throwable t) {
          // no span for this request
        }
      }
      return scope;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onExit(
        @Advice.Enter ServletHelper.AdviceScope scope,
        @Advice.Thrown Throwable thrown,
        @Advice.Argument(0) Object request,
        @Advice.Argument(1) Object response) {
      if (scope == null) {
        return;
      }
      if (!scope.endsRequest()) {
        ServletHelper.exit(scope, thrown, -1, null, false);
        return;
      }
      HttpServletRequest req = (HttpServletRequest) request;
      HttpServletResponse resp = (HttpServletResponse) response;
      boolean async = false;
      if (thrown == null) {
        try {
          if (req.isAsyncStarted()) {
            req.getAsyncContext()
                .addListener(
                    (AsyncListener) ServletHelper.asyncListener(scope, AsyncListener.class),
                    req,
                    resp);
            async = true;
          }
        } catch (Throwable t) {
          async = false;
        }
      }
      int status = -1;
      String[][] headers = null;
      if (!async) {
        try {
          status = resp.getStatus();
        } catch (Throwable t) {
          status = -1;
        }
        String[] names = HttpSemconv.serverResponseHeaders();
        headers = new String[names.length][];
        for (int i = 0; i < names.length; i++) {
          try {
            Collection<String> values = resp.getHeaders(names[i]);
            headers[i] =
                values == null || values.isEmpty() ? null : values.toArray(new String[values.size()]);
          } catch (Throwable t) {
            headers[i] = null;
          }
        }
      }
      ServletHelper.exit(scope, thrown, status, headers, async);
    }
  }

  /** {@code Filter.init(FilterConfig)}: caches the filter's URL and servlet-name mappings. */
  public static class FilterInitAdvice {

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(@Advice.This Object filter, @Advice.Argument(0) Object config) {
      if (!(config instanceof FilterConfig)) {
        return;
      }
      List<String> mappings = new ArrayList<String>();
      try {
        FilterConfig filterConfig = (FilterConfig) config;
        ServletContext context = filterConfig.getServletContext();
        FilterRegistration registration =
            context.getFilterRegistration(filterConfig.getFilterName());
        if (registration != null) {
          mappings.addAll(registration.getUrlPatternMappings());
          for (String servletName : registration.getServletNameMappings()) {
            ServletRegistration servlet = context.getServletRegistration(servletName);
            if (servlet != null) {
              mappings.addAll(servlet.getMappings());
            }
          }
        }
      } catch (Throwable t) {
        mappings.clear();
      }
      ServletHelper.cacheResolver(filter, mappings.toArray(new String[mappings.size()]));
    }
  }
}
