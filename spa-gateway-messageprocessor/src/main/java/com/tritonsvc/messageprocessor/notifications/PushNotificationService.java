package com.tritonsvc.messageprocessor.notifications;

import com.bwg.iot.model.Alert;
import com.notnoop.apns.APNS;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PreDestroy;
import java.io.IOException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Created by holow on 14.10.2016.
 */
public class PushNotificationService {

    private static final Logger log = LoggerFactory.getLogger(PushNotificationService.class);

    private final ExecutorService es = Executors.newCachedThreadPool();
    private final BlockingQueue<ApnsMessage> apnsQueue = new LinkedBlockingQueue<>();
    private boolean initialized = false;
    private ApnsSenderBuilder apnsSenderBuilder;

    public void pushApnsAlertNotification(final String deviceToken, final Alert alert) {
        init();
        final String payload = APNS.newPayload().alertBody(alert.getShortDescription()).category("SPA ALERT").sound("default").build();
        pushApnsPayload(deviceToken, payload);
    }

    private synchronized void init() {
        if (!initialized && apnsSenderBuilder != null) {
            try {
                for (int i = 0; i < 5; i++) {
                    es.submit(new ApnsMessageQueueConsumer(apnsSenderBuilder.build(), apnsQueue));
                }
                initialized = true;
            } catch (final IOException e) {
                log.error("Apns sender initialization failed", e);
            }
        }
    }

    private void pushApnsPayload(final String deviceTokenId, final String payload) {
        try {
            apnsQueue.put(new ApnsMessage(deviceTokenId, payload));
        } catch (final InterruptedException e) {
            log.error("error while putting apns message to queue");
        }
    }

    public void setApnsSenderBuilder(final ApnsSenderBuilder apnsSenderBuilder) {
        this.apnsSenderBuilder = apnsSenderBuilder;
    }

    @PreDestroy
    public void cleanup() {
        es.shutdownNow();
    }
}
