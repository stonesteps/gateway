package com.tritonsvc.messageprocessor.notifications;

import com.relayrides.pushy.apns.PushNotificationResponse;
import com.relayrides.pushy.apns.util.SimpleApnsPushNotification;
import io.netty.util.concurrent.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;

public class ApnsResponseQueueConsumer implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(ApnsResponseQueueConsumer.class);

    private final BlockingQueue<Future<PushNotificationResponse<SimpleApnsPushNotification>>> queue;

    public ApnsResponseQueueConsumer(final BlockingQueue<Future<PushNotificationResponse<SimpleApnsPushNotification>>> queue) {
        this.queue = queue;
    }

    @Override
    public void run() {
        while (true) {
            if (Thread.currentThread().isInterrupted()) break;
            try {
                final Future<PushNotificationResponse<SimpleApnsPushNotification>> responseFuture = queue.take();
                final PushNotificationResponse<SimpleApnsPushNotification> response = responseFuture.get();
                log.info("apns accepted: {}", response.isAccepted());
                if (!response.isAccepted()) {
                    log.error("apns reject reason {} for device token {}", response.getRejectionReason(), response.getPushNotification().getToken());
                    final String token = response.getPushNotification().getToken();
                    // FIXME set token invalid, remove token from user?
                }
            } catch (final InterruptedException e) {
                log.debug("apns response consumer interrupted");
            } catch (ExecutionException e) {
                log.error("error getting apns response", e);
            }
        }
        log.debug("android message consumer stopped");
    }
}
