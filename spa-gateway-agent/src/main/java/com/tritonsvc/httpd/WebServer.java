package com.tritonsvc.httpd;

import com.google.common.primitives.Ints;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import com.tritonsvc.httpd.handler.NetworkSettingsHandler;
import com.tritonsvc.httpd.handler.RegisterUserToSpaHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.security.*;
import java.security.cert.CertificateException;
import java.util.Properties;

/**
 * Created by holow on 4/6/2016.
 */
public class WebServer {

    private static final Logger LOGGER = LoggerFactory.getLogger(WebServer.class);

    private static final int DEFAULT_PORT = 8000;
    private static final boolean DEFAULT_SSL_ENABLED = true;

    private HttpServer server = null;
    private final int port;
    private final boolean ssl;

    private final RegistrationInfoHolder registrationInfoHolder;
    private final NetworkSettingsHolder networkSettingsHolder;

    private final NetworkSettingsHandler networkSettingsHandler;
    private final RegisterUserToSpaHandler registerUserToSpaHandler;

    public WebServer(final Properties properties, final RegistrationInfoHolder registrationInfoHolder, final NetworkSettingsHolder networkSettingsHolder) {
        this.registrationInfoHolder = registrationInfoHolder;
        this.networkSettingsHolder = networkSettingsHolder;

        this.networkSettingsHandler = new NetworkSettingsHandler(this.networkSettingsHolder);
        this.registerUserToSpaHandler = new RegisterUserToSpaHandler(this.registrationInfoHolder);

        this.port = getInt(properties, "webServer.port", DEFAULT_PORT);
        this.ssl = getBoolean(properties, "webServer.ssl", DEFAULT_SSL_ENABLED);
    }

    public void start() throws CertificateException, UnrecoverableKeyException, NoSuchAlgorithmException, IOException, KeyManagementException, KeyStoreException {
        if (server == null) {
            init();
            server.start();
        }
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    private void init() throws CertificateException, UnrecoverableKeyException, NoSuchAlgorithmException, IOException, KeyManagementException, KeyStoreException {
        server = HttpsServer.create(new InetSocketAddress(port), 0);
        if (ssl) {
            ((HttpsServer) server).setHttpsConfigurator(getHttpsConfigurator());
        }

        server.createContext("/networkSettings", networkSettingsHandler);
        server.createContext("/registerUserToSpa", registerUserToSpaHandler);
        server.setExecutor(null);

        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run() {
                WebServer.this.stop();
            }
        });
    }

    private HttpsConfigurator getHttpsConfigurator() throws CertificateException, UnrecoverableKeyException, NoSuchAlgorithmException, IOException, KeyManagementException, KeyStoreException {
        final SSLContext sslContext = SSLContext.getInstance("TLS");
        char[] keystorePassword = "bwg123".toCharArray();
        KeyStore ks = KeyStore.getInstance("JKS");
        ks.load(WebServer.class.getResourceAsStream("/bwg.jks"), keystorePassword);
        KeyManagerFactory kmf = KeyManagerFactory.getInstance("SunX509");
        kmf.init(ks, keystorePassword);
        sslContext.init(kmf.getKeyManagers(), null, null);

        final HttpsConfigurator configurator = new HttpsConfigurator(sslContext);
        return configurator;
    }

    private int getInt(final Properties properties, final String key, int defaultValue) {
        Integer val = null;
        final String valStr = properties.getProperty(key);
        if (valStr != null && valStr.length() > 0) {
            val = Ints.tryParse(valStr);
        }
        return val != null ? val.intValue() : defaultValue;
    }

    private boolean getBoolean(final Properties properties, final String key, boolean defaultValue) {
        boolean val = defaultValue;
        final String valStr = properties.getProperty(key);
        if (valStr != null && valStr.length() > 0) {
            val = Boolean.parseBoolean(valStr);
        }
        return val;
    }
}
