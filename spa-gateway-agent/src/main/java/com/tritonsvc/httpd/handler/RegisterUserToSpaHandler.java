package com.tritonsvc.httpd.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.tritonsvc.httpd.RegistrationInfoHolder;
import com.tritonsvc.model.RegisterUserResponse;

import java.io.IOException;
import java.io.OutputStream;

/**
 * Created by holow on 4/6/2016.
 */
public class RegisterUserToSpaHandler implements HttpHandler {

    private final ObjectMapper mapper = new ObjectMapper();
    private final RegistrationInfoHolder registrationInfoHolder;

    public RegisterUserToSpaHandler(final RegistrationInfoHolder registrationInfoHolder) {
        this.registrationInfoHolder = registrationInfoHolder;
    }

    @Override
    public void handle(final HttpExchange httpExchange) throws IOException {

        final String regKey = registrationInfoHolder != null ? registrationInfoHolder.getRegKey() : null;
        final String regUserId = registrationInfoHolder != null ? registrationInfoHolder.getRegUserId() : null;
        final String spaId = registrationInfoHolder != null ? registrationInfoHolder.getSpaId() : null;

        if (regUserId != null) {
            error(httpExchange, "Spa already registered to User");
        } else if (spaId == null) {
            error(httpExchange, "Spa not registered to cloud");
        } else {
            ok(httpExchange, regKey, spaId);
        }
    }

    private void error(final HttpExchange httpExchange, final String errorMessage) throws IOException {
        final RegisterUserResponse response = new RegisterUserResponse();
        response.setError(true);
        response.setErrorMessage(errorMessage);

        writeResponse(httpExchange, response);
    }

    private void ok(final HttpExchange httpExchange, final String regKey, final String spaId) throws IOException {
        final RegisterUserResponse response = new RegisterUserResponse();
        response.setError(false);
        response.setRegKey(regKey);
        response.setSpaId(spaId);

        writeResponse(httpExchange, response);
    }

    private void writeResponse(final HttpExchange httpExchange, final RegisterUserResponse response) throws IOException {
        final String responseStr = mapper.writeValueAsString(response);

        final Headers responseHeaders = httpExchange.getResponseHeaders();
        responseHeaders.set("Content-Type", "application/json");
        httpExchange.sendResponseHeaders(200, responseStr.length());
        try (final OutputStream os = httpExchange.getResponseBody()) {
            os.write(responseStr.getBytes());
        }
    }
}
