package com.tritonsvc.gateway;

/**
 * tracks the port and duration of a filter cycle request, duration of 0 means turn off.
 */
public class FilterCycleRequest {
    int port;
    int durationMinutes;
    byte address;
    String originatorId;
    String hardwareId;

    /**
     * constructor
     *
     * @param port
     * @param durationMinutes
     * @param address
     * @param originatorId
     * @param hardwareId
     */
    public FilterCycleRequest(int port, int durationMinutes, byte address, String originatorId, String hardwareId) {
        this.port = port;
        this.durationMinutes = durationMinutes;
        this.address = address;
        this.originatorId = originatorId;
        this.hardwareId = hardwareId;
    }

    public int getPort() {
        return port;
    }

    public int getDurationMinutes() {
        return durationMinutes;
    }

    public byte getAddress() {
        return address;
    }

    public String getOriginatorId() {
        return originatorId;
    }

    public String getHardwareId() {
        return hardwareId;
    }
}
