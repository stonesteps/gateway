package com.tritonsvc.messageprocessor.mqtt;

/**
 * Listener that allows to listen to subscribed messages.
 */
public interface MessageListener {

    void processMessage(final byte[] payload);

}
