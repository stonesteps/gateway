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
            log.error("Error saving network settings to properties file", e);
        }

        return networkSettings;
    }

    private static void fillNetworkSettings(final NetworkSettings networkSettings, final Properties props) {
        final Wifi wifi = new Wifi();
        final String ssid = props.getProperty("wifi.ssid");
        if (ssid != null) {
            wifi.setSsid(ssid);
        }
        final String security = props.getProperty("wifi.security");
        if (security != null) {
            wifi.setSecurity(WifiSecurity.valueOf(security));
        }
        final String password = props.getProperty("wifi.password");
        if (password != null) {
            wifi.setPassword(password);
        }
        networkSettings.setWifi(wifi);

        final Ethernet ethernet = new Ethernet();
        final String ip = props.getProperty("ethernet.ip");
        if (ip != null) {
            ethernet.setIpAddress(ip);
        }
        final String netmask = props.getProperty("ethernet.netmask");
        if (netmask != null) {
            ethernet.setNetmask(netmask);
        }
        final String gateway = props.getProperty("ethernet.gateway");
        if (gateway != null) {
            ethernet.setGateway(gateway);
        }
        final String dns = props.getProperty("ethernet.dns");
        if (dns != null) {
            ethernet.setDnsServer(dns);
        }
        final String dhcp = props.getProperty("ethernet.dhcp");
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
                    props.setProperty("wifi.ssid", networkSettings.getWifi().getSsid());
                }
                if (networkSettings.getWifi().getSecurity() != null) {
                    props.setProperty("wifi.security", networkSettings.getWifi().getSecurity().toString());
                }
                if (networkSettings.getWifi().getPassword() != null) {
                    props.setProperty("wifi.password", networkSettings.getWifi().getPassword());
                }
            }
            if (networkSettings.getEthernet() != null) {
                if (networkSettings.getEthernet().getIpAddress() != null) {
                    props.setProperty("ethernet.ip", networkSettings.getEthernet().getIpAddress());
                }
                if (networkSettings.getEthernet().getNetmask() != null) {
                    props.setProperty("ethernet.netmask", networkSettings.getEthernet().getNetmask());
                }
                if (networkSettings.getEthernet().getGateway() != null) {
                    props.setProperty("ethernet.gateway", networkSettings.getEthernet().getGateway());
                }
                if (networkSettings.getEthernet().getDnsServer() != null) {
                    props.setProperty("ethernet.dns", networkSettings.getEthernet().getDnsServer());
                }
                props.setProperty("ethernet.dhcp", String.valueOf(networkSettings.getEthernet().isDhcp()));
            }

            props.store(out, "Network settings");
        } catch (Exception e) {
            log.error("Error saving network settings to properties file", e);
        }
    }
}
