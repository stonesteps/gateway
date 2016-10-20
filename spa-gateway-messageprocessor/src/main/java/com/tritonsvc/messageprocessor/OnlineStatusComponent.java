package com.tritonsvc.messageprocessor;

import com.bwg.iot.model.Spa;
import com.mongodb.WriteResult;
import com.tritonsvc.messageprocessor.mongo.repository.SpaRepository;
import com.tritonsvc.messageprocessor.util.Watchdog;
import com.tritonsvc.messageprocessor.util.WatchedThreadCreator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.Date;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

import static org.springframework.data.mongodb.core.query.Criteria.where;
import static org.springframework.data.mongodb.core.query.Query.query;

@Component
public class OnlineStatusComponent implements WatchedThreadCreator {

    private static final Logger log = LoggerFactory.getLogger(UplinkProcessor.class);

    private static final long ONLINE_STATUS_THREAD_SLEEP_MILLISECONDS = 60000;
    private static final long WATCHDOG_SLEEP_MILLISECONDS = 120000;
    private static final long WATCHDOG_THRESHOLD_MILLISECONDS = ONLINE_STATUS_THREAD_SLEEP_MILLISECONDS + 30000;

    @Autowired
    private SpaRepository spaRepository;

    @Autowired
    private MongoOperations mongoOps;

    private final ExecutorService es = Executors.newCachedThreadPool();
    private Future<Void> currentOnlineStatusThread;
    private Future<Void> watchdog;
    private AtomicLong lastCheckin;

    @PostConstruct
    public void init() {
        log.info("Initializing online status thread");
        lastCheckin = new AtomicLong(System.currentTimeMillis());
        final OnlineStatusThread onlineStatusThread = new OnlineStatusThread();
        currentOnlineStatusThread = es.submit(onlineStatusThread);
        watchdog = es.submit(new Watchdog(WATCHDOG_SLEEP_MILLISECONDS, WATCHDOG_THRESHOLD_MILLISECONDS, lastCheckin, this));
    }

    @PreDestroy
    public void cleanup() {
        log.info("Cleaning up online status reporter");
        es.shutdown();
        if (currentOnlineStatusThread != null) {
            currentOnlineStatusThread.cancel(true);
        }
        if (watchdog != null) {
            watchdog.cancel(true);
        }
    }

    @Override
    public void recreateThread() {
        if (currentOnlineStatusThread != null) {
            currentOnlineStatusThread.cancel(true);
        }

        currentOnlineStatusThread = es.submit(new OnlineStatusThread());
    }

    private void reportOnline() throws Exception {
        log.debug("OnlineStatusThread is running online status check");
        Date now = new Date();

        WriteResult wr = mongoOps.updateMulti(
                query(where("currentState.staleTimestamp").lt(now).andOperator(Criteria.where("currentState.online").is(Boolean.TRUE))),
                new Update().set("currentState.online", Boolean.FALSE),
                Spa.class);
        if (wr.getN() > 0) {
            log.info(wr.getN() + " spas set offline");
        }

        log.debug("OnlineStatusThread has completed online status check");
    }

    private final class OnlineStatusThread implements Callable<Void> {

        @Override
        public Void call() throws Exception {
            while (!Thread.currentThread().isInterrupted()) {
                Thread.sleep(ONLINE_STATUS_THREAD_SLEEP_MILLISECONDS);
                try {
                    reportOnline();
                    lastCheckin.set(System.currentTimeMillis());
                } catch (final InterruptedException e) {
                    log.info("OnlineStatusThread stopped");
                    // exit thread normally
                    break;
                } catch (final Exception e) {
                    log.error("Error while reporting online status", e);
                }
            }
            return null;
        }
    }
}
