package com.tritonsvc.messageprocessor;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * this class is entry point responsible for performing all Downlink processing
 * namely scanning the mongodb Requests collection for any unprocessed
 * messages and transforming that document into a gateway-idl message(protobufs) and
 * publishing the serialized protobufs byte array to MQTT broker
 */
@Component
public class DownlinkProcessor implements Runnable {

    @Autowired
    SpaCommandRepository spaCommandRepository;

    @Autowired
    SpaRepository spaRepository;

    @Override
    public void run() {
        //TODO - establish a loop that polls the mongodb Requests collection
        //       every X configurable seconds, should query collection for any
        //       documents that don't have a processedTimestamp attribute.
        //
        //       transform mongodb document into idl request message(spa-gateway-idl),
        //       populate with values and then serialize to byte array and publish to MQTT
        //
        //       log output from this process is very important need to see log output that
        //       demonstrates this thread is active and never dead or locked up.
        // ....
    }


}
