package com.tritonsvc.httpd.model;

/**
 * Created by holow on 4/8/2016.
 */
public class Ethernet {
    private String ipAddress;
    private String netmask;
    private String gateway;
    private String dnsServer;
    private boolean dhcp;

    public String getIpAddress() {
        return ipAddress;
    }

    public void setIpAddress(String ipAddress) {
        this.ipAddress = ipAddress;
    }

    public String getNetmask() {
        return netmask;
    }

    public void setNetmask(String netmask) {
        this.netmask = netmask;
    }

    public String getGateway() {
        return gateway;
    }

    public void setGateway(String gateway) {
        this.gateway = gateway;
    }

    public String getDnsServer() {
        return dnsServer;
    }

    public void setDnsServer(String dnsServer) {
        this.dnsServer = dnsServer;
    }

    public boolean isDhcp() {
        return dhcp;
    }

    public void setDhcp(boolean dhcp) {
        this.dhcp = dhcp;
    }
}
