package com.tritonsvc.httpd.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.*;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.X509Certificate;

/**
 * Can disable ssl certificate and hostname checking.
 */
public final class SSLUtil {

    private static final Logger log = LoggerFactory.getLogger(SSLUtil.class);

    private static final HostnameVerifier ALLOW_ALL_HOSTNAMES = new HostnameVerifier() {
        @Override
        public boolean verify(final String hostname, final SSLSession session) {
            return true;
        }
    };

    private static final TrustManager[] UNQUESTIONING_TRUST_MANAGER = new TrustManager[]{new X509TrustManager() {
        @Override
        public java.security.cert.X509Certificate[] getAcceptedIssuers() {
            return null;
        }

        @Override
        public void checkClientTrusted(final X509Certificate[] certs, final String authType) {
        }

        @Override
        public void checkServerTrusted(final X509Certificate[] certs, final String authType) {
        }
    }};

    private SSLUtil() {
    }

    public static void turnOffSslVerification() {
        HttpsURLConnection.setDefaultHostnameVerifier(ALLOW_ALL_HOSTNAMES);
        try {
            final SSLContext sc = SSLContext.getInstance("SSL");
            sc.init(null, UNQUESTIONING_TRUST_MANAGER, null);
            HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
        } catch (final KeyManagementException e) {
            log.error("Error turning off ssl verification", e);
        } catch (final NoSuchAlgorithmException e) {
            log.error("Error turning off ssl verification", e);
        }
    }
}