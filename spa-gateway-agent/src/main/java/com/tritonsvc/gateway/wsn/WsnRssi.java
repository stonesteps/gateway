package com.tritonsvc.gateway.wsn;

/**
 * The RSSI of WSN message
 */
public class WsnRssi {
    private double quality;
    private double uplink;
    private double downlink;

    public double getQuality() {
        return quality;
    }
    public void setQuality(double quality) {
        this.quality = quality;
    }

    public double getUplink() {
        return uplink;
    }
    public void setUplink(double uplink) {
        this.uplink = uplink;
    }

    public double getDownlink() {
        return downlink;
    }
    public void setDownlink(double downlink) {
        this.downlink = downlink;
    }
}

