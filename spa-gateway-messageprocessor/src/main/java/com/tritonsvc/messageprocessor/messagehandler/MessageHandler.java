package com.tritonsvc.messageprocessor.messagehandler;

import com.tritonsvc.spa.communication.proto.Bwg;

/**
 * Created by holow on 2/22/2016.
 */
public interface MessageHandler<T> {

    Class<T> handles();

    void processMessage(final Bwg.Header header, final Bwg.Uplink.UplinkHeader uplinkHeader, final T message);

}
