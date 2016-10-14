package com.tritonsvc.messageprocessor.util;

import org.junit.Test;
import org.mockito.Mockito;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Created by holow on 14.10.2016.
 */
public class WatchdogTest {

    /**
     * Very simple test - showing that after given period of time the watchdog calls methods to recreate watched thread.
     *
     * @throws Exception
     */
    @Test
    public void watchdogThreadRecreateTest() throws Exception {
        ExecutorService es = Executors.newCachedThreadPool();

        WatchedThreadCreator mockedWatchedThreadCreator = Mockito.mock(WatchedThreadCreator.class);
        AtomicLong lastCheckin = new AtomicLong(System.currentTimeMillis());

        Watchdog watchdog = new Watchdog(500, 2000, lastCheckin, mockedWatchedThreadCreator);
        es.submit(watchdog);

        Thread.sleep(2100);
        es.shutdownNow();

        Mockito.verify(mockedWatchedThreadCreator, Mockito.times(1)).recreateThread();
    }

    /**
     * If lastChecking param is updated within 4000ms from last update, the recreateThread method will not be called.
     *
     * @throws Exception
     */
    @Test
    public void watchedThreadReportsActiveTest() throws Exception {
        ExecutorService es = Executors.newCachedThreadPool();

        WatchedThreadCreator mockedWatchedThreadCreator = Mockito.mock(WatchedThreadCreator.class);
        AtomicLong lastCheckin = new AtomicLong(System.currentTimeMillis());

        Watchdog watchdog = new Watchdog(500, 2000, lastCheckin, mockedWatchedThreadCreator);
        es.submit(watchdog);

        Thread.sleep(1800);
        lastCheckin.set(System.currentTimeMillis());

        Thread.sleep(1800);
        es.shutdownNow();

        Mockito.verify(mockedWatchedThreadCreator, Mockito.never()).recreateThread();
    }
}
