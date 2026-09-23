/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.java6.agent.export;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.CertPath;
import java.security.cert.CertPathValidator;
import java.security.cert.CertificateFactory;
import java.security.cert.PKIXParameters;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Vector;
import java.util.regex.Pattern;
import org.bouncycastle.tls.AlertDescription;
import org.bouncycastle.tls.DefaultTlsClient;
import org.bouncycastle.tls.NameType;
import org.bouncycastle.tls.ProtocolVersion;
import org.bouncycastle.tls.ServerName;
import org.bouncycastle.tls.ServerOnlyTlsAuthentication;
import org.bouncycastle.tls.TlsAuthentication;
import org.bouncycastle.tls.TlsFatalAlert;
import org.bouncycastle.tls.TlsServerCertificate;
import org.bouncycastle.tls.crypto.TlsCertificate;
import org.bouncycastle.tls.crypto.impl.bc.BcTlsCrypto;

/**
 * TLS 1.3/1.2 client on Bouncy Castle's low-level TLS API with its lightweight crypto ({@link
 * BcTlsCrypto}). No JCA/JCE provider is involved: IBM J9's JCE refuses unsigned providers ("JCE
 * cannot authenticate the provider BC") and the shaded Bouncy Castle classes cannot keep their
 * signatures. The server certificate chain is validated with the JVM's own PKIX validator against
 * the configured trust, and the host name is checked against the certificate's subject
 * alternative names (or CN), as {@code HttpsURLConnection} would.
 */
final class TlsClient extends DefaultTlsClient {

  private static final Pattern IP_LITERAL = Pattern.compile("^[0-9.]+$|^\\[?[0-9a-fA-F:]+\\]?$");

  private final String host;
  private final KeyStore trust;

  TlsClient(String host, KeyStore trust) {
    super(new BcTlsCrypto(new SecureRandom()));
    this.host = host;
    this.trust = trust;
  }

  @Override
  protected ProtocolVersion[] getSupportedVersions() {
    return ProtocolVersion.TLSv13.downTo(ProtocolVersion.TLSv12);
  }

  @Override
  @SuppressWarnings({"rawtypes", "unchecked"})
  protected Vector getSNIServerNames() {
    if (IP_LITERAL.matcher(host).matches()) {
      return null;
    }
    Vector names = new Vector(1);
    try {
      names.add(new ServerName(NameType.host_name, host.getBytes("ASCII")));
    } catch (java.io.UnsupportedEncodingException e) {
      return null;
    }
    return names;
  }

  public TlsAuthentication getAuthentication() {
    return new ServerOnlyTlsAuthentication() {
      public void notifyServerCertificate(TlsServerCertificate serverCertificate)
          throws IOException {
        verify(serverCertificate.getCertificate().getCertificateList());
      }
    };
  }

  private void verify(TlsCertificate[] chain) throws IOException {
    if (chain == null || chain.length == 0) {
      throw new TlsFatalAlert(AlertDescription.bad_certificate);
    }
    try {
      CertificateFactory factory = CertificateFactory.getInstance("X.509");
      List<X509Certificate> path = new ArrayList<X509Certificate>(chain.length);
      for (int i = 0; i < chain.length; i++) {
        X509Certificate cert =
            (X509Certificate)
                factory.generateCertificate(new ByteArrayInputStream(chain[i].getEncoded()));
        // a self-signed root sent by the server is the trust anchor, not part of the path
        if (i > 0 && cert.getSubjectX500Principal().equals(cert.getIssuerX500Principal())) {
          break;
        }
        path.add(cert);
      }
      CertPath certPath = factory.generateCertPath(path);
      PKIXParameters parameters = new PKIXParameters(trust);
      parameters.setRevocationEnabled(false);
      CertPathValidator.getInstance("PKIX").validate(certPath, parameters);
      if (!hostMatches(path.get(0), host)) {
        throw new IOException("certificate does not match host " + host);
      }
    } catch (IOException e) {
      throw e;
    } catch (Exception e) {
      TlsFatalAlert alert = new TlsFatalAlert(AlertDescription.bad_certificate, e);
      throw alert;
    }
  }

  static boolean hostMatches(X509Certificate cert, String host) throws Exception {
    String h = host.toLowerCase(Locale.ROOT);
    if (h.startsWith("[") && h.endsWith("]")) {
      h = h.substring(1, h.length() - 1);
    }
    boolean ip = IP_LITERAL.matcher(h).matches();
    Collection<List<?>> names = cert.getSubjectAlternativeNames();
    boolean hasDns = false;
    if (names != null) {
      for (Iterator<List<?>> it = names.iterator(); it.hasNext(); ) {
        List<?> entry = it.next();
        int type = ((Integer) entry.get(0)).intValue();
        String value = String.valueOf(entry.get(1)).toLowerCase(Locale.ROOT);
        if (ip && type == 7
            && InetAddress.getByName(value).equals(InetAddress.getByName(h))) {
          return true;
        }
        if (!ip && type == 2) {
          hasDns = true;
          if (dnsMatches(value, h)) {
            return true;
          }
        }
      }
    }
    if (ip || hasDns) {
      return false;
    }
    // no DNS subject alternative names: fall back to the most specific CN
    String subject = cert.getSubjectX500Principal().getName();
    for (String part : subject.split(",")) {
      String p = part.trim();
      if (p.regionMatches(true, 0, "CN=", 0, 3)) {
        return dnsMatches(p.substring(3).toLowerCase(Locale.ROOT), h);
      }
    }
    return false;
  }

  private static boolean dnsMatches(String pattern, String host) {
    if (pattern.startsWith("*.")) {
      int dot = host.indexOf('.');
      return dot > 0 && host.substring(dot).equals(pattern.substring(1));
    }
    return pattern.equals(host);
  }
}
