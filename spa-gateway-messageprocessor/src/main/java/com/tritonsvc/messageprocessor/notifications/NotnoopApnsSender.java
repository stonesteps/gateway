package com.tritonsvc.messageprocessor.notifications;

import com.notnoop.apns.APNS;
import com.notnoop.apns.ApnsService;
import com.notnoop.apns.ApnsServiceBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.Date;
import java.util.Map;

public class NotnoopApnsSender implements ApnsSender {

    private static final Logger log = LoggerFactory.getLogger(ApnsSender.class);

    private final ApnsService apnsService;
    private final boolean useProductionApns;

    public NotnoopApnsSender(final String certPath, final String certPassword, final boolean useProductionApns) throws IOException {
        this.useProductionApns = useProductionApns;
        this.apnsService = initApnsService(certPath, certPassword, useProductionApns);
    }

    public void pushPayload(final String deviceToken, final String payload) {
        log.info("sending apns payload {} to device {}", payload, deviceToken);
        apnsService.push(deviceToken, payload);
    }

    private ApnsService initApnsService(final String certPath, final String certPassword, final boolean useProductionApns) throws IOException {
        log.info("initializing apns service, certPath={}, production={}", certPath, useProductionApns);
        try (final InputStream in = ApnsSender.class.getResourceAsStream(certPath)) {
            final ApnsServiceBuilder builder = APNS.newService()
                    .withCert(in, certPassword);
            if (useProductionApns) builder.withProductionDestination();
            else builder.withSandboxDestination();
            return builder.build();
        }
    }

    public Map<String, Date> getInactiveDevices() {
        return apnsService.getInactiveDevices();
    }
}

