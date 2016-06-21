package com.tritonsvc.gateway.wsn;

import com.tritonsvc.spa.communication.proto.Bwg.Uplink.Model.Measurement.DataType;

/**
 * WSN data message
 */
public class WsnData {
    private String mac;
    private Long recordedUnixTimestamp;
    private long receivedUnixTimestamp;
    private WsnRssi rssi;
    private String deviceName;
    private Double value;
    private DataType dataType;
    private String uom;

    public String getUom() {
        return uom;
    }

    public void setUom(String uom) {
        this.uom = uom;
    }

    public DataType getDataType() {
        return dataType;
    }

    public void setDataType(DataType dataType) {
        this.dataType = dataType;
    }

    public String getDeviceName() {return deviceName;}
    public void setDeviceName(String deviceName) {this.deviceName = deviceName;}

    public Double getValue() {return value;}
    public void setValue(Double value) {this.value = value;}

    public String getMac() {
        return mac;
    }

    public void setMac(String mac) {
        this.mac = mac;
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
