package com.tritonsvc.messageprocessor;

import org.springframework.beans.factory.annotation.Autowired;

/**
 * Created by shawn on 2/18/16.
 */
public class UplinkProcessor implements Runnable{
    @Autowired
    SpaCommandRepository spaCommandRepository;

    @Autowired
    SpaRepository spaRepository;

    @Override
    public void run() {
        //TODO - establish a loop that subscribes to MQTT
        //       the subscription should only block X configurable seconds for messages
        //       if none are received re-subscribe. Log all elapsed time events and received messages
        //       log output from this process is very important need to see log output that
        //       demonstrates this thread is active and never dead or locked up.
        // ...
    }
}
