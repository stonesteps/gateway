package com.tritonsvc.messageprocessor.notifications;

import com.notnoop.apns.APNS;
import com.notnoop.apns.ApnsService;
import com.notnoop.apns.ApnsServiceBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;

/**
 * Created by holow on 14.10.2016.
 */
public class ApnsSender {

    private static final Logger log = LoggerFactory.getLogger(ApnsSender.class);

    private final ApnsService apnsService;
    private final boolean enabled;
    private final boolean useProductionApns;

    public ApnsSender(final String certPath, final String certPassword, final boolean useProductionApns, final boolean enabled) {
        this.enabled = enabled;
        this.useProductionApns = useProductionApns;
        if (enabled) {
            apnsService = getApnsService(certPath, certPassword, useProductionApns);
        } else {
            apnsService = null;
        }
    }

    public void pushPayload(final String deviceToken, final String payload) {
        log.info("sending apns payload {} to device {}", payload, deviceToken);
        if (enabled && apnsService != null) {
            apnsService.push(deviceToken, payload);
        } else {
            log.info("apns notifications disabled");
        }
    }

    private ApnsService getApnsService(final String certPath, final String certPassword, final boolean useProductionApns) {
        try (final InputStream in = ApnsSender.class.getResourceAsStream(certPath)) {
            final ApnsServiceBuilder builder = APNS.newService()
                    .withCert(in, certPassword);
            if (useProductionApns) builder.withProductionDestination();
            else builder.withSandboxDestination();
            return builder.build();
        } catch (final Throwable e) {
            log.error("error while creating apns client", e);
        }

        return null;
    }
}
