package com.tritonsvc.httpd.util;

import com.tritonsvc.httpd.model.Ethernet;
import com.tritonsvc.httpd.model.NetworkSettings;
import com.tritonsvc.httpd.model.Wifi;
import com.tritonsvc.httpd.model.WifiSecurity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.Properties;

/**
 * Created by holow on 4/14/2016.
 */
public final class SettingsPersister {

    private static final Logger log = LoggerFactory.getLogger(SettingsPersister.class);

    private static final String ETHERNET_DNS = "ethernet.dns";
    private static final String WIFI_SSID = "wifi.ssid";
    private static final String WIFI_SECURITY = "wifi.security";
    private static final String WIFI_PASSWORD = "wifi.password";
    private static final String ETHERNET_IP = "ethernet.ip";
    private static final String ETHERNET_NETMASK = "ethernet.netmask";
    private static final String ETHERNET_GATEWAY = "ethernet.gateway";
    private static final String ETHERNET_DHCP = "ethernet.dhcp";

    private SettingsPersister() {
        // utility class
    }

    public static NetworkSettings load(final File file) {
        final NetworkSettings networkSettings = new NetworkSettings();
        final Properties props = new Properties();
        try (final InputStream in = new FileInputStream(file)) {
            props.load(in);
            fillNetworkSettings(networkSettings, props);
        } catch (final IOException e) {
            log.error("Error loading network settings to properties file", e);
        }

        return networkSettings;
    }

    private static void fillNetworkSettings(final NetworkSettings networkSettings, final Properties props) {
        final Wifi wifi = new Wifi();
        final String ssid = props.getProperty(WIFI_SSID);
        if (ssid != null) {
            wifi.setSsid(ssid);
        }
        final String security = props.getProperty(WIFI_SECURITY);
        if (security != null) {
            wifi.setSecurity(WifiSecurity.valueOf(security));
        }
        final String password = props.getProperty(WIFI_PASSWORD);
        if (password != null) {
            wifi.setPassword(password);
        }
        networkSettings.setWifi(wifi);

        final Ethernet ethernet = new Ethernet();
        final String ip = props.getProperty(ETHERNET_IP);
        if (ip != null) {
            ethernet.setIpAddress(ip);
        }
        final String netmask = props.getProperty(ETHERNET_NETMASK);
        if (netmask != null) {
            ethernet.setNetmask(netmask);
        }
        final String gateway = props.getProperty(ETHERNET_GATEWAY);
        if (gateway != null) {
            ethernet.setGateway(gateway);
        }
        final String dns = props.getProperty(ETHERNET_DNS);
        if (dns != null) {
            ethernet.setDnsServer(dns);
        }
        final String dhcp = props.getProperty(ETHERNET_DHCP);
        if (dhcp != null) {
            ethernet.setDhcp(Boolean.parseBoolean(dhcp));
        }
        networkSettings.setEthernet(ethernet);
    }

    public static void save(final File file, final NetworkSettings networkSettings) {
        if (networkSettings == null) return;

        try (final OutputStream out = new FileOutputStream(file)) {
            final Properties props = new Properties();
            if (networkSettings.getWifi() != null) {
                if (networkSettings.getWifi().getSsid() != null) {
                    props.setProperty(WIFI_SSID, networkSettings.getWifi().getSsid());
                }
                if (networkSettings.getWifi().getSecurity() != null) {
                    props.setProperty(WIFI_SECURITY, networkSettings.getWifi().getSecurity().toString());
                }
                if (networkSettings.getWifi().getPassword() != null) {
                    props.setProperty(WIFI_PASSWORD, networkSettings.getWifi().getPassword());
                }
            }
            if (networkSettings.getEthernet() != null) {
                if (networkSettings.getEthernet().getIpAddress() != null) {
                    props.setProperty(ETHERNET_IP, networkSettings.getEthernet().getIpAddress());
                }
                if (networkSettings.getEthernet().getNetmask() != null) {
                    props.setProperty(ETHERNET_NETMASK, networkSettings.getEthernet().getNetmask());
                }
                if (networkSettings.getEthernet().getGateway() != null) {
                    props.setProperty(ETHERNET_GATEWAY, networkSettings.getEthernet().getGateway());
                }
                if (networkSettings.getEthernet().getDnsServer() != null) {
                    props.setProperty(ETHERNET_DNS, networkSettings.getEthernet().getDnsServer());
                }
                props.setProperty(ETHERNET_DHCP, String.valueOf(networkSettings.getEthernet().isDhcp()));
            }

            props.store(out, "Network settings");
        } catch (Exception e) {
            log.error("Error saving network settings to properties file", e);
        }
    }
}
