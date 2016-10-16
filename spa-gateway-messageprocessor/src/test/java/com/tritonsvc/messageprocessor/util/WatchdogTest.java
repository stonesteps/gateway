package com.tritonsvc.messageprocessor.util;

import org.junit.Test;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

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

        final CountDownLatch cdl = new CountDownLatch(1);
        doAnswer(invocation -> {
            cdl.countDown();
            return null;
        }).when(mockedWatchedThreadCreator).recreateThread();

        AtomicLong lastCheckin = new AtomicLong(System.currentTimeMillis());

        Watchdog watchdog = new Watchdog(500, 2000, lastCheckin, mockedWatchedThreadCreator);
        es.submit(watchdog);

        cdl.await(5, TimeUnit.SECONDS);
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

        final CountDownLatch cdl = new CountDownLatch(1);
        doAnswer(invocation -> {
            cdl.countDown();
            return null;
        }).when(mockedWatchedThreadCreator).recreateThread();


        Watchdog watchdog = new Watchdog(500, 2000, lastCheckin, mockedWatchedThreadCreator);
        es.submit(watchdog);

        Thread.sleep(1800);
        lastCheckin.set(System.currentTimeMillis());

        cdl.await(2, TimeUnit.SECONDS);
        es.shutdownNow();

        Mockito.verify(mockedWatchedThreadCreator, Mockito.never()).recreateThread();
    }
}
