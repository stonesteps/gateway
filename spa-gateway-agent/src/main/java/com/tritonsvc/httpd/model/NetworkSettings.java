package com.tritonsvc.httpd.model;

/**
 * Created by holow on 4/8/2016.
 */
public class NetworkSettings {
    private Wifi wifi;
    private Ethernet ethernet;

    public Wifi getWifi() {
        return wifi;
    }

    public void setWifi(Wifi wifi) {
        this.wifi = wifi;
    }

    public Ethernet getEthernet() {
        return ethernet;
    }

    public void setEthernet(Ethernet ethernet) {
        this.ethernet = ethernet;
    }
}
