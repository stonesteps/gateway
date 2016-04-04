package com.tritonsvc.gateway;

import java.util.Date;
import java.util.Map;

import static com.google.common.collect.Maps.newHashMap;

/**
 * The state of device registration attempt
 */
public class DeviceRegistration {
    private String hardwareId;
    private long lastTime;
    private Map<String, String> meta = newHashMap();

    /**
     * default constructor
     */
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
    public Map<String, String> getMeta() {
        return meta;
    }
}
