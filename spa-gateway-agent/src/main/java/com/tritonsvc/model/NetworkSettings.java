package com.tritonsvc.model;

/**
 * Created by holow on 4/26/2016.
 */
public class NetworkSettings {

    private Wifi wifi;
    private Ethernet ethernet;
    private boolean ethernetPluggedIn;

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

    public boolean isEthernetPluggedIn() {
        return ethernetPluggedIn;
    }

    public void setEthernetPluggedIn(boolean ethernetPluggedIn) {
        this.ethernetPluggedIn = ethernetPluggedIn;
    }
}
