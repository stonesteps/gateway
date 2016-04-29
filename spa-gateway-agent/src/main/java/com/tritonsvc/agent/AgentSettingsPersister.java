package com.tritonsvc.agent;

import com.tritonsvc.model.*;
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
public final class AgentSettingsPersister {

    private static final Logger log = LoggerFactory.getLogger(AgentSettingsPersister.class);

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

    private AgentSettingsPersister() {
        // utility class
    }

    public static AgentSettings load(final File file) {
        final AgentSettings agentSettings = new AgentSettings();
        final Properties props = loadProperties(file);
        fillAgentSettings(agentSettings, props);
        return agentSettings;
    }

    private static Properties loadProperties(final File file) {
        final Properties props = new Properties();
        try (final InputStream in = new FileInputStream(file)) {
            props.load(in);
        } catch (final FileNotFoundException e) {}
        catch (final IOException e) {
            log.error("Error loading properties file", e);
        }

        return props;
    }

    private static void fillAgentSettings(final AgentSettings agentSettings, final Properties props) {
        final NetworkSettings networkSettings = new NetworkSettings();
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
        agentSettings.setNetworkSettings(networkSettings);

        // load generic settings
        final GenericSettings genericSettings = new GenericSettings();
        try {
            beanUtilsBean.populate(genericSettings, propertiesMap);
        } catch (final Exception e) {
            log.error("Could not set wifi properties", e);
        }
        agentSettings.setGenericSettings(genericSettings);
    }

    private static Map<String, String> toMap(final Properties properties) {
        final Map<String, String> map = new HashMap<>();
        for (final String name : properties.stringPropertyNames()) {
            map.put(name, properties.getProperty(name));
        }
        return map;
    }

    public static void save(final File file, final AgentSettings agentSettings) {
        if (agentSettings == null) return;

        final Properties props = new Properties();

        try {
            if (agentSettings.getNetworkSettings() != null) {
                if (agentSettings.getNetworkSettings().getWifi() != null) {
                    final Map<String, String> wifiSettings = removeNulls(beanUtilsBean.describe(agentSettings.getNetworkSettings().getWifi()));
                    props.putAll(wifiSettings);
                }

                if (agentSettings.getNetworkSettings().getEthernet() != null) {
                    final Map<String, String> ethSettings = removeNulls(beanUtilsBean.describe(agentSettings.getNetworkSettings().getEthernet()));
                    props.putAll(ethSettings);
                }
            }
            if (agentSettings.getGenericSettings() != null) {
                final Map<String, String> genericSettings = removeNulls(beanUtilsBean.describe(agentSettings.getGenericSettings()));
                props.putAll(genericSettings);
            }
        } catch (Exception e) {
            log.error("Error setting properties from agent settings", e);
        }

        saveProperties(file, props, "Agent Settings");
    }

    private static Map<String, String> removeNulls(Map<String, String> inMap) {
        while(inMap.values().remove(null)){}
        return inMap;
    }

    private static void saveProperties(final File file, final Properties props, final String title) {
        try (final OutputStream out = new FileOutputStream(file)) {
            props.store(out, title);
        } catch (Exception e) {
            log.error("Error saving properties file", e);
        }
    }
}
