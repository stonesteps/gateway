package com.tritonsvc.messageprocessor.notifications;

import com.bwg.iot.model.Alert;
import com.notnoop.apns.APNS;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Created by holow on 14.10.2016.
 */
@Service
public class PushNotificationService {

    private static final Logger log = LoggerFactory.getLogger(PushNotificationService.class);

    @Value("${certPath:/ControlMySpa_push.p12}")
    private String certPath;
    @Value("${certPassword:}")
    private String certPassword;
    @Value("${useProduction:false}")
    private boolean useProductionServer;
    @Value("${apnsNotificationsEnabled:true}")
    private boolean apnsNotificationsEnabled;

    private final ExecutorService es = Executors.newCachedThreadPool();
    private final BlockingQueue<ApnsMessage> apnsQueue = new LinkedBlockingQueue<>();

    @PostConstruct
    public void init() {
        for (int i = 0; i < 5; i++) {
            es.submit(new ApnsMessageQueueConsumer(
                    new ApnsSender(certPath, certPassword, useProductionServer, apnsNotificationsEnabled), apnsQueue));
        }
    }

    public void pushApnsAlertNotification(final String deviceToken, final Alert alert) {
        final String payload = APNS.newPayload().alertBody(alert.getShortDescription()).category("SPA ALERT").sound("default").build();
        pushApnsPayload(deviceToken, payload);
    }

    private void pushApnsPayload(final String deviceTokenId, final String payload) {
        try {
            apnsQueue.put(new ApnsMessage(deviceTokenId, payload));
        } catch (final InterruptedException e) {
            log.error("error while putting apns message to queue");
        }
    }

    @PreDestroy
    public void cleanup() {
        es.shutdownNow();
    }
}
