package com.tritonsvc.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.zeromq.ZMQ.Socket;

/**
 * WSN data collection thread, keeps the thread running/fresh at all times unless interrupted
 */
public class WSNDataHarvester implements Runnable {
    private static Logger LOGGER = LoggerFactory.getLogger(WSNDataHarvester.class);
    private BWGProcessor processor;

    /**
     * Constructor
     *
     * @param processor
     */
    public WSNDataHarvester (BWGProcessor processor) {
        this.processor = processor;
    }

    @Override
    public void run() {
        Socket subscriber = null;
        while(processor.stillRunning()) {
            try {
                if (subscriber == null) {
                    subscriber = processor.createWSNSubscriberSocket();
                }

                while(true) {
                    String data = processor.waitForWSNData(subscriber, 10000);
                    if (data != null) {
                        processor.sendWSNDataToCloud(data);
                        continue;
                    }
                    break;
                }
            }
            catch (Throwable ex) {
                LOGGER.info("harvest data listener got exception " + ex.getMessage());
                if (subscriber != null) {
                    subscriber.close();
                    subscriber = null;
                }
                try {Thread.sleep(10000);} catch (InterruptedException ex2){}
            }
        }
        if (subscriber != null) {
            subscriber.close();
        }
    }
}
