package com.tritonsvc.httpd.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.tritonsvc.httpd.NetworkSettingsHolder;
import com.tritonsvc.model.NetworkSettings;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Handle network settings http requests
 */
public class NetworkSettingsHandler implements HttpHandler {

    private static final Logger log = LoggerFactory.getLogger(NetworkSettingsHandler.class);

    private final ObjectMapper mapper = new ObjectMapper();
    private final NetworkSettingsHolder networkSettingsHolder;
    private long lastActivity;

    /**
     * Constructor
     *
     * @param networkSettingsHolder
     */
    public NetworkSettingsHandler(NetworkSettingsHolder networkSettingsHolder) {
        this.networkSettingsHolder = networkSettingsHolder;
    }

    /**
     * retrieve the last time the handler was invoked
     * @return
     */
    public long getLastActivity() {
        return lastActivity;
    }

    @Override
    public void handle(final HttpExchange httpExchange) throws IOException {
        // GET, POST

        final String requestMethod = httpExchange.getRequestMethod();
        lastActivity = System.currentTimeMillis();

        if ("post".equalsIgnoreCase(requestMethod)) {
            handlePost(httpExchange);
        } else if ("get".equalsIgnoreCase(requestMethod)) {
            handleGet(httpExchange);
        } else {
            log.error("Bad request, not supported request method: {}", requestMethod);
            httpExchange.sendResponseHeaders(400, 0); // bad request
        }
        httpExchange.close();
    }

    private void handlePost(final HttpExchange httpExchange) throws IOException {
        log.debug("Handling network settings post");

        try (final InputStream in = httpExchange.getRequestBody()) {
            final ByteArrayOutputStream bos = new ByteArrayOutputStream();
            IOUtils.copy(in, bos);
            final String body = bos.toString();
            log.debug("Got in request: {}", body);

            final NetworkSettings networkSettings = mapper.readValue(body, NetworkSettings.class);

            try {
                this.networkSettingsHolder.setNetworkSettings(networkSettings);
                httpExchange.sendResponseHeaders(200, 0); // OK
                lastActivity = -1;
            } catch (Exception ex) {
                log.error("ap mode client submitted invalid network credentials", ex);
                httpExchange.sendResponseHeaders(400, 0); // BAD_REQUEST
            }
        }
    }

    private void handleGet(final HttpExchange httpExchange) throws IOException {
        log.debug("Handling network settings get");

        final String response = mapper.writeValueAsString(this.networkSettingsHolder.getNetworkSettings());

        final Headers responseHeaders = httpExchange.getResponseHeaders();
        responseHeaders.set("Content-Type", "application/json");
        byte[] json = response.getBytes();
        httpExchange.sendResponseHeaders(200, json.length);
        try (final OutputStream os = httpExchange.getResponseBody()) {
            os.write(json);
        }
    }
}
