package com.tritonsvc.messageprocessor.messagehandler;

import com.tritonsvc.messageprocessor.UplinkProcessor;
import com.tritonsvc.spa.communication.proto.Bwg;
import org.springframework.beans.factory.annotation.Autowired;

import javax.annotation.PostConstruct;

/**
 * Created by holow on 2/22/2016.
 */
public abstract class AbstractMessageHandler<T> implements MessageHandler<T> {

    @Autowired
    private UplinkProcessor uplinkProcessor;

    @PostConstruct
    public void registerInUplinkProcessor() {
        uplinkProcessor.registerHandler(this.handles(), this);
    }
}
