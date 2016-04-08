package com.tritonsvc.httpd.handler;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.tritonsvc.httpd.model.NetworkSettings;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Created by holow on 4/6/2016.
 */
public class NetworkSettingsHandler implements HttpHandler {

    private static final Logger log = LoggerFactory.getLogger(NetworkSettingsHandler.class);

    private NetworkSettings networkSettings = new NetworkSettings();
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public void handle(final HttpExchange httpExchange) throws IOException {
        // GET, POST

        final String requestMethod = httpExchange.getRequestMethod();

        if ("post".equalsIgnoreCase(requestMethod)) {
            handlePost(httpExchange);
        } else if ("get".equalsIgnoreCase(requestMethod)) {
            handleGet(httpExchange);
        } else {
            log.error("Bad request, not supported request method: {}", requestMethod);
            httpExchange.sendResponseHeaders(400, 0); // bad request
        }
    }

    private void handlePost(final HttpExchange httpExchange) throws IOException {
        log.debug("Handling network settings post");

        try (final InputStream in = httpExchange.getRequestBody()) {
            final ByteArrayOutputStream bos = new ByteArrayOutputStream();
            IOUtils.copy(in, bos);
            final String body = bos.toString();
            log.debug("Got in request: {}", body);

            final NetworkSettings networkSettings = mapper.readValue(body, NetworkSettings.class);
            this.networkSettings = networkSettings;

            httpExchange.sendResponseHeaders(200, 0); // OK
        }
    }

    private void handleGet(final HttpExchange httpExchange) throws IOException {
        log.debug("Handling network settings get");

        final String response = mapper.writeValueAsString(networkSettings);

        httpExchange.sendResponseHeaders(200, response.length());
        try (final OutputStream os = httpExchange.getResponseBody()) {
            os.write(response.getBytes());
        }
    }

    public void setNetworkSettings(NetworkSettings networkSettings) {
        this.networkSettings = networkSettings;
    }

    public NetworkSettings getNetworkSettings() {
        return networkSettings;
    }
}
