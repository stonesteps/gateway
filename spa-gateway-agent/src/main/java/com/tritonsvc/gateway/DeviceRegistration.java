package com.tritonsvc.gateway;

import java.util.Date;

/**
 * The state of device registration attempt
 */
public class DeviceRegistration {
    private String hardwareId;
    private long lastTime;

    public DeviceRegistration() {
        lastTime = new Date().getTime();
    }

    public long getLastTime() {
        return lastTime;
    }
    public void setLastTime(long lastTime) {
        this.lastTime = lastTime;
    }
    public String getHardwareId() {
        return hardwareId;
    }
    public void setHardwareId(String hardwareId) {
        this.hardwareId = hardwareId;
    }
}
