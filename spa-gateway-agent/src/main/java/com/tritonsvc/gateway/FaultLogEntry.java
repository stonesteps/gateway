package com.tritonsvc.gateway;

/**
 * In-memory fault log entry representation.
 */
public final class FaultLogEntry {

    private final int number;
    private final int code;
    private final long timestamp;

    private final int targetTemp;
    private final int sensorATemp;
    private final int sensorBTemp;
    private final boolean celcius;

    private boolean sentToUplik = false;

    public FaultLogEntry(int number, int code, long timestamp, int targetTemp, int sensorATemp, int sensorBTemp, boolean celcius) {
        this.number = number;
        this.code = code;
        this.timestamp = timestamp;
        this.targetTemp = targetTemp;
        this.sensorATemp = sensorATemp;
        this.sensorBTemp = sensorBTemp;
        this.celcius = celcius;
    }

    public int getNumber() {
        return number;
    }

    public int getCode() {
        return code;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public int getTargetTemp() {
        return targetTemp;
    }

    public int getSensorATemp() {
        return sensorATemp;
    }

    public int getSensorBTemp() {
        return sensorBTemp;
    }

    public boolean isCelcius() {
        return celcius;
    }

    public boolean isSentToUplik() {
        return sentToUplik;
    }

    public void setSentToUplik(boolean sentToUplik) {
        this.sentToUplik = sentToUplik;
    }
}
