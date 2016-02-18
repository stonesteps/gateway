package com.tritonsvc.gateway.wsn;

/**
 * WSN key value pair
 */
public class WsnValue {
    private String deviceName;
    private Double value;

    public String getDeviceName() {return deviceName;}
    public void setDeviceName(String deviceName) {this.deviceName = deviceName;}

    public Double getValue() {return value;}
    public void setValue(Double value) {this.value = value;}
}