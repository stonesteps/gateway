package com.tritonsvc.messageprocessor.notifications;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.BlockingQueue;

public class ApnsMessageQueueConsumer implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(ApnsMessageQueueConsumer.class);

    private final ApnsSender sender;
    private final BlockingQueue<ApnsMessage> queue;

    public ApnsMessageQueueConsumer(final ApnsSender sender, final BlockingQueue<ApnsMessage> queue) {
        this.sender = sender;
        this.queue = queue;
    }

    @Override
    public void run() {
        while (true) {
            if (Thread.currentThread().isInterrupted()) break;
            try {
                final ApnsMessage message = queue.take();
                sender.pushPayload(message.getDevice(), message.getPayload());
            } catch (final InterruptedException e) {
                log.debug("apns message consumer interrupted");
            }
        }
        log.debug("apns message consumer stopped");
        sender.cleanup();
    }
}
