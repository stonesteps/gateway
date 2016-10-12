package com.tritonsvc.messageprocessor.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Created by holow on 12.10.2016.
 */
public final class Watchdog implements Callable<Void> {

    private static final Logger log = LoggerFactory.getLogger(Watchdog.class);

    private final long watchdogSleepMilliseconds;
    private final long watchdogThresholdMilliseconds;
    private final AtomicLong lastCheckin;
    private final WatchedThreadCreator watchedThreadCreator;

    public Watchdog(final long watchdogSleepMilliseconds, final long watchdogThresholdMilliseconds, final AtomicLong lastCheckin, final WatchedThreadCreator watchedThreadCreator) {
        this.watchdogSleepMilliseconds = watchdogSleepMilliseconds;
        this.watchdogThresholdMilliseconds = watchdogThresholdMilliseconds;
        this.lastCheckin = lastCheckin;
        this.watchedThreadCreator = watchedThreadCreator;
    }

    @Override
    public Void call() throws Exception {
        while (!Thread.currentThread().isInterrupted()) {
            // check periodically if current watched thread is not hung
            Thread.sleep(watchdogSleepMilliseconds);

            if (System.currentTimeMillis() - lastCheckin.get() > watchdogThresholdMilliseconds) {
                log.error("Watched thread reported no activity for over {} milliseconds, recreating it", watchdogThresholdMilliseconds);

                // terminate and create new watched thread
                watchedThreadCreator.recreateThread();
            } else {
                log.info("Watched thread is active, taking no action");
            }
        }
        return null;
    }
}
