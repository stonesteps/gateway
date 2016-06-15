package com.tritonsvc.httpd;

import com.google.common.primitives.Ints;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import com.tritonsvc.agent.AgentConfiguration;
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
 * Publish an HTTP service that is meant for the mobile device to access directly over AP Wifi mode
 * of the gateway. this allows onboarding Wifi client credentials and user self reg.
 */
public class WebServer {

    private static final Logger LOGGER = LoggerFactory.getLogger(WebServer.class);

    private static final int DEFAULT_PORT = 8080;
    private static final boolean DEFAULT_SSL_ENABLED = false;

    private HttpServer server = null;
    private final int port;
    private final boolean ssl;

    private final RegistrationInfoHolder registrationInfoHolder;
    private final NetworkSettingsHolder networkSettingsHolder;

    private NetworkSettingsHandler networkSettingsHandler;
    private RegisterUserToSpaHandler registerUserToSpaHandler;
    private long lastActivity;
    private long lastActivtyForDelayedShutdown;
    private long timeoutMs;

    /**
     * Constructor
     *
     * @param properties
     * @param registrationInfoHolder
     * @param networkSettingsHolder
     */
    public WebServer(final Properties properties, final RegistrationInfoHolder registrationInfoHolder, final NetworkSettingsHolder networkSettingsHolder, long timeoutMs) {
        this.registrationInfoHolder = registrationInfoHolder;
        this.networkSettingsHolder = networkSettingsHolder;
        this.timeoutMs = timeoutMs;
        this.port = getInt(properties, AgentConfiguration.AP_MODE_WEB_SERVER_PORT, DEFAULT_PORT);
        this.ssl = getBoolean(properties, AgentConfiguration.AP_MODE_WEB_SERVER_SSLENABLED, DEFAULT_SSL_ENABLED);
    }

    /**
     * Start the web server
     *
     * @throws CertificateException
     * @throws UnrecoverableKeyException
     * @throws NoSuchAlgorithmException
     * @throws IOException
     * @throws KeyManagementException
     * @throws KeyStoreException
     */
    public void start() throws CertificateException, UnrecoverableKeyException, NoSuchAlgorithmException, IOException, KeyManagementException, KeyStoreException {
        if (server == null) {
            init();
            server.start();
            lastActivity = System.currentTimeMillis();
            lastActivtyForDelayedShutdown = 0;
        }
    }

    /**
     * Stop the web server
     */
    public void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    /**
     * get the timestamp of last time an http request was received.
     *
     * @return
     */
    public long getLastActivity() {
        if (lastActivtyForDelayedShutdown > 0) {
            return lastActivtyForDelayedShutdown;
        }
        if (networkSettingsHandler.getLastActivity() < 0 || registerUserToSpaHandler.getLastActivity() < 0) {
            // if negative, user saved network settings, turn off ap mode and trigger a network restart
            lastActivtyForDelayedShutdown =  System.currentTimeMillis() - (timeoutMs - 15000);
            return lastActivtyForDelayedShutdown;
        }
        return Math.max(lastActivity, Math.max(networkSettingsHandler.getLastActivity(), registerUserToSpaHandler.getLastActivity()));
    }

    /**
     * indicate whether the network settings have been updated
     * @return
     */
    public boolean updatedNetwork() {
        return networkSettingsHandler.getLastActivity() < 0;
    }

    private void init() throws CertificateException, UnrecoverableKeyException, NoSuchAlgorithmException, IOException, KeyManagementException, KeyStoreException {
        if (ssl) {
            server = HttpsServer.create(new InetSocketAddress(port), 0);
            ((HttpsServer) server).setHttpsConfigurator(getHttpsConfigurator());
        } else {
            server = HttpServer.create(new InetSocketAddress(port), 0);
        }

        this.networkSettingsHandler = new NetworkSettingsHandler(this.networkSettingsHolder);
        this.registerUserToSpaHandler = new RegisterUserToSpaHandler(this.registrationInfoHolder);
        server.createContext("/networkSettings", networkSettingsHandler);
        server.createContext("/registerUserToSpa", registerUserToSpaHandler);
        server.setExecutor(null);

        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run() {
                WebServer.this.stop();
            }
        });
        LOGGER.info("agent web server started on port {}", port);
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
