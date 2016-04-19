package com.tritonsvc.httpd.util;

import com.tritonsvc.httpd.model.Ethernet;
import com.tritonsvc.httpd.model.NetworkSettings;
import com.tritonsvc.httpd.model.Wifi;
import org.apache.commons.beanutils.BeanUtilsBean;
import org.apache.commons.beanutils.ConvertUtilsBean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Created by holow on 4/14/2016.
 */
public final class SettingsPersister {

    private static final Logger log = LoggerFactory.getLogger(SettingsPersister.class);

    private static final BeanUtilsBean beanUtilsBean = new BeanUtilsBean(new ConvertUtilsBean() {
        @Override
        public Object convert(String value, Class clazz) {
            if (clazz.isEnum()) {
                try {
                    return Enum.valueOf(clazz, value);
                } catch (Exception e) {
                    // ignore
                }
                return null;
            } else {
                return super.convert(value, clazz);
            }
        }
    });

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

        final Map<String, String> propertiesMap = toMap(props);

        try {
            beanUtilsBean.populate(wifi, propertiesMap);
        } catch (final Exception e) {
            log.error("Could not set wifi properties", e);
        }

        networkSettings.setWifi(wifi);

        final Ethernet ethernet = new Ethernet();
        try {
            beanUtilsBean.populate(ethernet, propertiesMap);
        } catch (final Exception e) {
            log.error("Could not set ethernet properties", e);
        }
        networkSettings.setEthernet(ethernet);
    }

    private static Map<String, String> toMap(final Properties properties) {
        final Map<String, String> map = new HashMap<>();
        for (final String name : properties.stringPropertyNames()) {
            map.put(name, properties.getProperty(name));
        }
        return map;
    }

    public static void save(final File file, final NetworkSettings networkSettings) {
        if (networkSettings == null) return;

        try (final OutputStream out = new FileOutputStream(file)) {
            final Properties props = new Properties();

            if (networkSettings.getWifi() != null) {
                final Map<String, String> wifiSettings = beanUtilsBean.describe(networkSettings.getWifi());
                props.putAll(wifiSettings);
            }

            if (networkSettings.getEthernet() != null) {
                final Map<String, String> ethSettings = beanUtilsBean.describe(networkSettings.getEthernet());
                props.putAll(ethSettings);
            }

            props.store(out, "Network settings");
        } catch (Exception e) {
            log.error("Error saving network settings to properties file", e);
        }
    }
}
