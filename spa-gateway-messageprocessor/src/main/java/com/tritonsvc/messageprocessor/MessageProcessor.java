package com.tritonsvc.messageprocessor;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Service;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Starter class for message processing, launches uplink and downlink
 * thread based processors separately and then should act as watchdog
 * making both threads are running all the time and not dead or locked
 */
@Service
public class MessageProcessor implements CommandLineRunner {

    @Autowired
    DownlinkProcessor downlinkProcessor;

    @Autowired
    UplinkProcessor uplinkProcessor;

    @Override
    public void run(String... args) throws Exception {
        ScheduledExecutorService scheduledExecutorService =  new ScheduledThreadPoolExecutor(2);

        // this isn't great, would be nice to refactor this to use Spring injected task executor/scheduler
        // or something that encapsulates shutdown stuff for us
        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run() {
                scheduledExecutorService.shutdownNow();
                try {scheduledExecutorService.awaitTermination(10, TimeUnit.SECONDS);} catch (InterruptedException ex){}
            }
        });

        scheduledExecutorService.execute(downlinkProcessor);
        scheduledExecutorService.execute(uplinkProcessor);
    }
}
