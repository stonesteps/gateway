package com.tritonsvc.agent;

import com.google.protobuf.AbstractMessageLite;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.UplinkCommandType;

/**
 * Pojo to stored uplinks that initially failed to retry again later
 */
public class QueuedUplink {
    private String hardwareId;
    private String originator;
    private UplinkCommandType uplinkCommandType;
    private AbstractMessageLite msg;
    private int attempts;
    private boolean cached;

    /**
     * Constructor
     *
     * @param hardwareId
     * @param originator
     * @param uplinkCommandType
     * @param msg
     */
    public QueuedUplink(String hardwareId, String originator, UplinkCommandType uplinkCommandType, AbstractMessageLite msg) {
        this.hardwareId = hardwareId;
        this.originator = originator;
        this.uplinkCommandType = uplinkCommandType;
        this.msg = msg;
    }

    public String getHardwareId() {
        return hardwareId;
    }

    public String getOriginator() {
        return originator;
    }

    public UplinkCommandType getUplinkCommandType() {
        return uplinkCommandType;
    }

    public AbstractMessageLite getMsg() {
        return msg;
    }

    public void incrementAttempts() {
        attempts++;
    }

    public int getAttempts() {
        return attempts;
    }

    public boolean isCached() {
        return cached;
    }

    public void setCached() {
        cached = true;
    }
}