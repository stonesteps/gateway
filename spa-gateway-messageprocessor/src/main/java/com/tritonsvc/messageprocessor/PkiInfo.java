package com.tritonsvc.messageprocessor;

import javax.net.ssl.SSLContext;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;

/**
 * data class to carry crypto object for performing ssl handshake with mqtt broker
 */
public class PkiInfo {
    private static final int DEFAULT_MQTTS_PORT = 8883;
    private SSLContext sslContext;
    private X509Certificate caRoot;
    private X509Certificate clientPublic;
    private PrivateKey clientPrivate;

    public X509Certificate getClientPublic() {
        return clientPublic;
    }

    public void setClientPublic(X509Certificate clientPublic) {
        this.clientPublic = clientPublic;
    }

    public SSLContext getSslContext() {
        return sslContext;
    }

    public void setSslContext(SSLContext sslContext) {
        this.sslContext = sslContext;
    }

    public X509Certificate getCaRoot() {
        return caRoot;
    }

    public void setCaRoot(X509Certificate caRoot) {
        this.caRoot = caRoot;
    }

    public PrivateKey getClientPrivate() {
        return clientPrivate;
    }

    public void setClientPrivate(PrivateKey clientPrivate) {
        this.clientPrivate = clientPrivate;
    }

    public String getProtocolPrefix() {
        return (sslContext != null ? "tls://" : "tcp://");
    }

    public int getPort() {
        return DEFAULT_MQTTS_PORT;
    }
}