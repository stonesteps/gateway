package com.tritonsvc.agent;


import com.google.protobuf.AbstractMessageLite;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.UplinkCommandType;

/**
 * Interface for messages that can be sent to cloud
 *
 */
public interface GatewayEventDispatcher {

    /**
     * processes a message to be an uplink message and send it to the cloud
     *
     * @param hardwareId
     * @param originator
     * @param uplinkCommandType
     * @param msg
     * @param retry
     */
    void sendUplink(String hardwareId,
                              String originator,
                              UplinkCommandType uplinkCommandType,
                              AbstractMessageLite msg,
                              boolean retry);

    /**
     * pushes uplink message to the cloud
     *
     * @param uplink
     * @param retryOnFailure
     */
    void sendMessage(QueuedUplink uplink,
                     boolean retryOnFailure);

    /**
     * utility to run off inner thread executor
     * @param runner
     */
    void executeRunnable(Runnable runner);
}