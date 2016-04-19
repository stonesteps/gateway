package com.tritonsvc.httpd.model;

/**
 * Created by holow on 4/8/2016.
 */
public class Wifi {
    private String ssid;
    private String password;
    private WifiSecurity security;

    public String getSsid() {
        return ssid;
    }

    public void setSsid(String ssid) {
        this.ssid = ssid;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public WifiSecurity getSecurity() {
        return security;
    }

    public void setSecurity(WifiSecurity security) {
        this.security = security;
    }
}