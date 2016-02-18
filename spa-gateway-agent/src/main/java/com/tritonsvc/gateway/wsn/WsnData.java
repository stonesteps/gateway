package com.tritonsvc.gateway.wsn;

import java.util.List;

/**
 * WSN data message
 */
public class WsnData {
    private String mac;
    private List<WsnValue> values;
    private Long recordedUnixTimestamp;
    private long receivedUnixTimestamp;
    private WsnRssi rssi;

    public String getMac() {
        return mac;
    }

    public void setMac(String mac) {
        this.mac = mac;
    }

    public List<WsnValue> getValues() {
        return values;
    }

    public void setValues(List<WsnValue> values) {
        this.values = values;
    }

    public Long getRecordedUnixTimestamp() {
        return recordedUnixTimestamp;
    }

    public void setRecordedUnixTimestamp(Long timestamp) {
        this.recordedUnixTimestamp = timestamp;
    }

    public long getReceivedUnixTimestamp() {
        return receivedUnixTimestamp;
    }

    public void setReceivedUnixTimestamp(long timestamp) {
        this.receivedUnixTimestamp = timestamp;
    }

    public WsnRssi getRssi() {
        return rssi;
    }

    public void setRssi(WsnRssi rssi) {
        this.rssi = rssi;
    }
}
