package com.tritonsvc.httpd;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import com.tritonsvc.httpd.handler.NetworkSettingsHandler;
import com.tritonsvc.httpd.handler.RegisterUserToSpaHandler;
import com.tritonsvc.httpd.model.NetworkSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.net.InetSocketAddress;
import java.security.KeyStore;
import java.util.Properties;

/**
 * Created by holow on 4/6/2016.
 */
public class WebServer {

    private static final Logger LOGGER = LoggerFactory.getLogger(WebServer.class);

    private static final int DEFAULT_PORT = 8000;
    private static final boolean DEFAULT_SSL_ENABLED = true;
    private HttpServer server;

    private final RegistrationInfoHolder registrationInfoHolder;

    private final NetworkSettingsHandler networkSettingsHandler;
    private final RegisterUserToSpaHandler registerUserToSpaHandler;

    public WebServer(final Properties properties, final RegistrationInfoHolder registrationInfoHolder) throws Exception {
        this.registrationInfoHolder = registrationInfoHolder;

        this.networkSettingsHandler = new NetworkSettingsHandler();
        this.registerUserToSpaHandler = new RegisterUserToSpaHandler(this.registrationInfoHolder);

        final int port = getInt(properties, "webServer.port", DEFAULT_PORT);
        final boolean ssl = getBoolean(properties, "webServer.ssl", DEFAULT_SSL_ENABLED);

        server = HttpsServer.create(new InetSocketAddress(port), 0);
        if (ssl) {
            ((HttpsServer) server).setHttpsConfigurator(getHttpsConfigurator());
        }

        server.createContext("/networkSettings", networkSettingsHandler);
        server.createContext("/registerUserToSpa", registerUserToSpaHandler);
        server.setExecutor(null);
    }

    private HttpsConfigurator getHttpsConfigurator() throws Exception {
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

    public void start() {
        server.start();
    }

    public void stop() {
        server.stop(0);
    }

    private int getInt(final Properties properties, final String key, int defaultValue) {
        int val = defaultValue;
        final String valStr = properties.getProperty(key);
        if (valStr != null && valStr.length() > 0) {
            try {
                val = Integer.parseInt(valStr);
            } catch (final NumberFormatException e) {
                // ignore
            }
        }
        return val;
    }

    private boolean getBoolean(final Properties properties, final String key, boolean defaultValue) {
        boolean val = defaultValue;
        final String valStr = properties.getProperty(key);
        if (valStr != null && valStr.length() > 0) {
            val = Boolean.parseBoolean(valStr);
        }
        return val;
    }

    public NetworkSettings getNetworkSettings() {
        return this.networkSettingsHandler.getNetworkSettings();
    }

    public void setNetworkSettings(final NetworkSettings networkSettings) {
        this.networkSettingsHandler.setNetworkSettings(networkSettings);
    }

    public static void main(String... args) throws Exception {
        WebServer ws = new WebServer(new Properties(), null);
        ws.start();
    }
}
