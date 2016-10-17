package com.tritonsvc.messageprocessor.notifications;

import com.relayrides.pushy.apns.ApnsClient;
import com.relayrides.pushy.apns.ApnsClientBuilder;
import com.relayrides.pushy.apns.DeliveryPriority;
import com.relayrides.pushy.apns.PushNotificationResponse;
import com.relayrides.pushy.apns.util.SimpleApnsPushNotification;
import com.relayrides.pushy.apns.util.TokenUtil;
import io.netty.util.concurrent.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Created by holow on 14.10.2016.
 */
public class ApnsSender {

    private static final Logger log = LoggerFactory.getLogger(ApnsSender.class);

    private final ApnsClient apnsClient;
    private final boolean enabled;
    private final boolean useProductionApns;
    private final BlockingQueue<Future<PushNotificationResponse<SimpleApnsPushNotification>>> apnsResponseQueue;

    public ApnsSender(final String certPath, final String certPassword, final boolean useProductionApns, final boolean enabled, final BlockingQueue<Future<PushNotificationResponse<SimpleApnsPushNotification>>> apnsResponseQueue) {
        this.enabled = enabled;
        this.useProductionApns = useProductionApns;
        if (enabled) {
            apnsClient = getApnsClient(certPath, certPassword);
        } else {
            apnsClient = null;
        }
        this.apnsResponseQueue = apnsResponseQueue;
    }

    public void pushPayload(final String deviceToken, final String payload) {
        log.info("sending apns payload {} to device {}", payload, deviceToken);
        if (enabled && apnsClient != null) {
            connect();

            final String token = TokenUtil.sanitizeTokenString(deviceToken);
            final SimpleApnsPushNotification pushNotification = new SimpleApnsPushNotification(token, "com.digduck.digduck", payload, null, DeliveryPriority.IMMEDIATE);
            final Future<PushNotificationResponse<SimpleApnsPushNotification>> sendNotificationFuture = apnsClient.sendNotification(pushNotification);

            final boolean sentToProcess = apnsResponseQueue.offer(sendNotificationFuture);
            if (!sentToProcess) {
                log.error("apns response queue does not accept any more items");
            }
        } else {
            log.info("apns notifications disabled");
        }
    }

    private ApnsClient getApnsClient(final String certPath, final String certPassword) {
        ApnsClient apnsClient = null;
        try (final InputStream in = ApnsSender.class.getResourceAsStream(certPath)) {
            apnsClient = new ApnsClientBuilder().setClientCredentials(in, certPassword).setConnectionTimeout(30, TimeUnit.SECONDS).setWriteTimeout(30000, TimeUnit.SECONDS).build();
        } catch (final Throwable e) {
            log.error("error while creating apns client", e);
        }

        return apnsClient;
    }

    public void connect() {
        if (enabled && apnsClient != null && !apnsClient.isConnected()) {
            try {
                log.debug("apns client connecting...");
                final Future<Void> connectFuture = apnsClient.connect(useProductionApns ? ApnsClient.PRODUCTION_APNS_HOST : ApnsClient.DEVELOPMENT_APNS_HOST);
                connectFuture.await();
                log.debug("apns client connected");
            } catch (final Exception e) {
                log.error("error connecting to apns host", e);
            }
        }
    }

    public void cleanup() {
        if (apnsClient != null && apnsClient.isConnected()) {
            try {
                final Future<Void> disconnect = apnsClient.disconnect();
                disconnect.await();
            } catch (final InterruptedException e) {
                log.error("error while disconnecting apns pushy client", e);
            }
        }
    }
}
