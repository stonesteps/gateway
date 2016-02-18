package com.tritonsvc.agent;


import com.google.protobuf.AbstractMessageLite;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.UplinkCommandType;

/**
 * Interface for messages that can be sent to cloud
 *
 */
public interface GatewayEventDispatcher {

    /**
     * send a message to the cloud
     *
     * @param hardwareId
     * @param originator
     * @param uplinkCommandType
     * @param msg
     */
    void sendUplink(String hardwareId,
                              String originator,
                              UplinkCommandType uplinkCommandType,
                              AbstractMessageLite msg);

}