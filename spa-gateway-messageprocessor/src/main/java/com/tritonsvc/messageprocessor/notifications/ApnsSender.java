package com.tritonsvc.messageprocessor.notifications;

/**
 * Created by holow on 14.10.2016.
 */
public interface ApnsSender {

    /**
     * Pushed given payload to device specified by token
     *
     * @param deviceToken
     * @param payload
     */
    void pushPayload(final String deviceToken, final String payload);

}
