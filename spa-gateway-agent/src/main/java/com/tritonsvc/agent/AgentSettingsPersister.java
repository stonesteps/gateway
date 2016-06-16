package com.tritonsvc.agent;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.primitives.Ints;
import com.tritonsvc.gateway.BWGProcessor;
import com.tritonsvc.model.AgentSettings;
import com.tritonsvc.model.Ethernet;
import com.tritonsvc.model.GenericSettings;
import com.tritonsvc.model.NetworkSettings;
import com.tritonsvc.model.Wifi;
import org.apache.commons.beanutils.BeanUtilsBean;
import org.apache.commons.beanutils.ConvertUtilsBean;
import org.apache.commons.configuration2.Configuration;
import org.apache.commons.configuration2.FileBasedConfiguration;
import org.apache.commons.configuration2.PropertiesConfiguration;
import org.apache.commons.configuration2.builder.FileBasedConfigurationBuilder;
import org.apache.commons.configuration2.builder.fluent.Parameters;
import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Agent settings are stored in linux network files and in an app specific property file stored in current directory
 * support retrieving and storing of settings to the correct physical files
 */
public class AgentSettingsPersister {

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

    /**
     * load the agent's settings from files
     *
     * @param file the file to store general non-network settings
     * @param osType the type of os, used to find the correct network files
     * @param ethDeviceName the name of the ethernet device in linux
     * @param wifiDeviceName name of wifi device
     * @return
     */
    public AgentSettings load(final File file, String osType, String ethDeviceName, String wifiDeviceName) {
        final AgentSettings agentSettings = new AgentSettings();
        final Properties props = loadProperties(file);
        fillAgentSettings(agentSettings, props, osType, ethDeviceName, wifiDeviceName);
        return agentSettings;
    }

    /**
     * persist settings to files, if osType is null, then don't update the network settings
     *
     * @param file the file to store general non-network settings
     * @param agentSettings all the settings to save
     * @param osType type of os, only used for network saves
     * @param ethDevice ethernet device name, only used for network saves
     * @param homePath where the agent is installed, only used for network saves
     * @param wifiDevice wifi device name, only used for network saves
     * @param networkSettings if updating network area of settings
     */
    public void save(final File file, final AgentSettings agentSettings, String osType, String ethDevice, String homePath, String wifiDevice, NetworkSettings networkSettings) {
        if (agentSettings == null) return;
        try {
            if (networkSettings != null) {
                FileBasedConfigurationBuilder<FileBasedConfiguration> builder  = getWifiSupplicant(osType, wifiDevice);
                Configuration wifiProps = builder.getConfiguration();
                // the wpa_supplicant.conf, needs to have the 'disabled' attribute in the 'network' component, or this
                // won't persist the 'disabled' attribute inside the 'network' element correctly
                //
                // this does not restart wpa_supplicant after config changes, that is to be done by the
                // client processs, which is the web server on gateway, it determines when the ButtonManager
                // will restart wpa_supplicant(and shut off ap mode) based on lastActivityTime.
                if (networkSettings.getWifi() != null) {
                    wifiProps.setProperty("ssid",  toHex(networkSettings.getWifi().getSsid().getBytes()) );
                    if (networkSettings.getWifi().getPassword() != null) {
                        wifiProps.setProperty("psk", pbkdf2(networkSettings.getWifi().getPassword().toCharArray(), networkSettings.getWifi().getSsid().getBytes(), 4096,32));
                    }
                    wifiProps.setProperty("disabled","0");
                } else {
                    wifiProps.setProperty("disabled","1");
                }
                builder.save();

                if (networkSettings.getEthernet() != null) {
                    if (osType.equals(BWGProcessor.TS_IMX6)) {
                        saveSystemDEthernet(networkSettings.getEthernet(), ethDevice, homePath);
                    } else {
                        saveSysVEthernet(networkSettings.getEthernet(), ethDevice, homePath);
                    }
                }
            }
            if (agentSettings.getGenericSettings() != null) {
                final Properties props = new Properties();
                final Map<String, String> genericSettings = removeNulls(beanUtilsBean.describe(agentSettings.getGenericSettings()));
                props.putAll(genericSettings);
                saveProperties(file, props, "Agent Settings");
            }
        } catch (Exception e) {
            log.error("Error setting properties from agent settings", e);
        }
    }

    private String pbkdf2(char[] password, byte[] salt, int iterations, int bytes) throws Exception
    {
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, bytes * 8);
        SecretKeyFactory skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1");
        return toHex(skf.generateSecret(spec).getEncoded());
    }

    private Properties loadProperties(final File file) {
        final Properties props = new Properties();
        try (final InputStream in = new FileInputStream(file)) {
            props.load(in);
        } catch (final FileNotFoundException e) {}
        catch (final IOException e) {
            log.error("Error loading properties file", e);
        }

        return props;
    }

    private void fillAgentSettings(final AgentSettings agentSettings, final Properties props, String osType, String ethDevice, String wifiDevice) {
        final NetworkSettings networkSettings = new NetworkSettings();
        agentSettings.setNetworkSettings(networkSettings);

        final Map<String, String> propertiesMap = toMap(props);

        // only return Wifi if it's enabled, otherwise null
        FileBasedConfigurationBuilder<FileBasedConfiguration> builder = getWifiSupplicant(osType, wifiDevice);
        Configuration wifiProps;
        try {
            wifiProps = builder.getConfiguration();
        } catch (Exception ex) {
            log.error("problem accessing wifi supplicant ", ex);
            wifiProps = new PropertiesConfiguration();
        }
        if (wifiProps.getString("disabled") == null || !wifiProps.getString("disabled").equals("1")) {
            final Wifi wifi = new Wifi();
            if (wifiProps.getString("ssid","").startsWith("\"")) {
                String wc = wifiProps.getString("ssid").replaceFirst("\"", "");
                if (wc.lastIndexOf("\"") == (wc.length() -1)) {
                    wc = wc.substring(0, wc.length() -1);
                }
                wifi.setSsid(wc);
            } else {
                wifi.setSsid(fromHex(wifiProps.getString("ssid", "")));
            }
            // do not set password
            networkSettings.setWifi(wifi);
        }

        // always return ethernet whether it's plugged in or not
        if (osType.equals(BWGProcessor.TS_IMX6)) {
            networkSettings.setEthernet(loadSystemDEthernet(ethDevice));
        } else {
            networkSettings.setEthernet(loadSysVEthernet(ethDevice));
        }

        // load generic settings
        final GenericSettings genericSettings = new GenericSettings();
        try {
            beanUtilsBean.populate(genericSettings, propertiesMap);
        } catch (final Exception e) {
            log.error("Could not set wifi properties", e);
        }
        agentSettings.setGenericSettings(genericSettings);
    }

    private Map<String, String> toMap(final Properties properties) {
        final Map<String, String> map = new HashMap<>();
        for (final String name : properties.stringPropertyNames()) {
            map.put(name, properties.getProperty(name));
        }
        return map;
    }

    private Map<String, String> removeNulls(Map<String, String> inMap) {
        while(inMap.values().remove(null)){}
        return inMap;
    }

    private void saveProperties(final File file, final Properties props, final String title) {
        try (final OutputStream out = new FileOutputStream(file)) {
            props.store(out, title);
        } catch (Exception e) {
            log.error("Error saving properties file", e);
        }
    }

    private Ethernet loadSysVEthernet(String deviceName){
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(getSystemFile("/etc/network/interfaces"))))) {
            String line;
            while ((line = reader.readLine()) != null) {
                // looks specifically for eht0:1
                // sample of what it looks like, eth0 is reserved for static 192.168.77.15 address
                // auto eth0
                // iface eth0 inet static
                // address 192.168.77.52
                // netmask 255.255.255.0
                //
                // auto eth0:1
                // allow-hotplug eth0:1
                // iface eth0:1 inet dhcp

                if (line.contains("iface " + deviceName + ":1")) {
                    Ethernet eth = new Ethernet();
                    if (line.contains("static")) {
                        while ((line = reader.readLine()) != null && (line.contains("address") || line.contains("netmask") || line.contains("gateway"))) {
                            processEthernetParam(line, eth, "\\s");
                        }
                        return eth;
                    } else {
                        eth.setDhcp(true);
                        return eth;
                    }
                }
            }
        } catch (Exception ex) {
            log.error("unable to open ethernet config file");
        }
        return null;
    }

    private Ethernet loadSystemDEthernet(String deviceName){
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(getSystemFile("/etc/systemd/network/" + deviceName + ".network"))))) {
            String line;
            // parse the systemd format
            // [Match]
            // Name=eth0
            //
            // [Network]
            // DHCP=yes
            // [Network]
            // Address=192.168.77.15
            // Netmask=255.255.255.0
            //
            while ((line = reader.readLine()) != null) {
                if (line.contains("[Network]")) {
                    line = reader.readLine();
                    if (line == null) {
                        return null;
                    } else if (!(line = line.toLowerCase()).equals("address=192.168.77.15")) {
                        Ethernet eth = new Ethernet();
                        while ((line.contains("address") || line.contains("netmask") || line.contains("gateway") || line.contains("dhcp"))) {
                            if (line.contains("dhcp=yes")) {
                                eth = new Ethernet();
                                eth.setDhcp(true);
                                return eth;
                            } else {
                                processEthernetParam(line, eth, "\\=");
                            }
                            line = reader.readLine();
                            if (line == null) {
                                break;
                            }
                            line = line.toLowerCase();
                        }
                        return eth;
                    }
                }
            }
        } catch (Exception ex) {
            log.error("unable to open ethernet config file", ex);
        }
        return null;
    }

    private void saveSystemDEthernet(Ethernet eth, String deviceName, String homePath) throws Exception {
        File tempFile = new File(homePath, deviceName + ".network");
        File ethFile = getSystemFile("/etc/systemd/network/" + deviceName + ".network");
        try (PrintWriter writer = new PrintWriter(new FileWriter(tempFile));
             BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(ethFile)))) {
            String line;
            // parse the systemd format
            // [Match]
            // Name=eth0
            //
            // [Network]
            // DHCP=yes
            // [Network]
            // Address=192.168.77.15
            // Netmask=255.255.255.0
            //
            while ((line = reader.readLine()) != null) {
                if (line.contains("[Network]")) {
                    writer.println(line);
                    line = reader.readLine();
                    if (line == null) {
                        break;
                    } else if (!line.equals("Address=192.168.77.15")) {
                        while ((line.contains("Address") || line.contains("Netmask") || line.contains("Gateway") || line.contains("DHCP"))) {
                            line = reader.readLine();
                            if (line == null) {
                                break;
                            }
                        }
                        if (eth.isDhcp()) {
                            writer.println("DHCP=yes");
                        } else {
                            if (eth.getIpAddress() != null) {
                                writer.println("Address=" + eth.getIpAddress());
                            }
                            if (eth.getNetmask() != null) {
                                writer.println("Netmask=" + eth.getNetmask());
                            }
                            if (eth.getNetmask() != null) {
                                writer.println("Gateway=" + eth.getGateway());
                            }
                        }
                        if (line == null) {
                            break;
                        }
                    }
                }
                writer.println(line);
            }
            writer.flush();
            FileUtils.copyFile(tempFile, ethFile);
            FileUtils.forceDelete(tempFile);
        } catch (Exception ex) {
            log.error("unable to save ethernet config file");
        }
    }

    private void saveSysVEthernet(Ethernet eth, String deviceName, String homePath) throws Exception {
        File tempFile = new File(homePath, "interfaces");
        File networkFile = getSystemFile("/etc/network/interfaces");
        try (PrintWriter writer = new PrintWriter(new FileWriter(tempFile));
             BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(networkFile)))) {
            String line;
            // sample of what it looks like, eth0 is reserved for static 192.168.77.15 address
            // auto eth0
            // iface eth0 inet static
            // address 192.168.77.52
            // netmask 255.255.255.0
            //
            // auto eth0:1
            // allow-hotplug eth0:1
            // iface eth0:1 inet dhcp
            while ((line = reader.readLine()) != null) {
                if (line.contains("iface " + deviceName + ":1")) {
                    while ((line = reader.readLine()) != null && (line.contains("address") || line.contains("netmask") || line.contains("gateway"))) {}
                    if (eth.isDhcp()) {
                        writer.println("iface eth0:1 inet dhcp");
                    } else {
                        writer.println("iface eth0:1 inet static");
                        if (eth.getIpAddress() != null) {
                            writer.println("address " + eth.getIpAddress());
                        }
                        if (eth.getNetmask() != null) {
                            writer.println("netmask " + eth.getNetmask());
                        }
                        if (eth.getNetmask() != null) {
                            writer.println("gateway " + eth.getGateway());
                        }
                    }
                    if (line == null) {
                        break;
                    }
                }
                writer.println(line);
            }
            writer.flush();
            FileUtils.copyFile(tempFile, networkFile);
            FileUtils.forceDelete(tempFile);
        } catch (Exception ex) {
            log.error("unable to save ethernet config file");
        }
    }

    private void processEthernetParam(String line, Ethernet eth, String separator) {
        line = line.toLowerCase();
        if (line.contains("address")) {
            String[] parts = line.split("\\s");
            if (parts.length ==2) {
                eth.setIpAddress(parts[1]);
            }
        } else if (line.contains("netmask")) {
            String[] parts = line.split("\\s");
            if (parts.length ==2) {
                eth.setNetmask(parts[1]);
            }
        } else if (line.contains("gateway")) {
            String[] parts = line.split("\\s");
            if (parts.length ==2) {
                eth.setGateway(parts[1]);
            }
        }
    }

    private String fromHex(String hex)
    {
        byte[] binary = new byte[hex.length() / 2];
        try {
            for (int i = 0; i < binary.length; i++) {
                binary[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
            }
        } catch (NumberFormatException nf) {}
        return new String(binary);
    }

    private String toHex(byte[] array)
    {
        BigInteger bi = new BigInteger(1, array);
        String hex = bi.toString(16);
        int paddingLength = (array.length * 2) - hex.length();
        if(paddingLength > 0)
            return String.format("%0" + paddingLength + "d", 0) + hex;
        else
            return hex;
    }

    @VisibleForTesting
    FileBasedConfigurationBuilder<FileBasedConfiguration> getWifiSupplicant(String osType, String wifiDevice){
        File file;
        if (osType.equals(BWGProcessor.TS_IMX6)) {
            file = getSystemFile("/etc/wpa_supplicant/wpa_supplicant-" + wifiDevice + ".conf");
        } else {
            file = getSystemFile("/etc/wpa_supplicant/wpa_supplicant.conf");
        }
        return new FileBasedConfigurationBuilder<FileBasedConfiguration>(PropertiesConfiguration.class)
                .configure(new Parameters().properties()
                        .setFile(file)
                        .setThrowExceptionOnMissing(true)
                        .setIncludesAllowed(false));
    }

    @VisibleForTesting
    File getSystemFile(String path) {
        return new File(path);
    }

    @VisibleForTesting
    Process executeUnixCommand(String command) throws IOException {
        return Runtime.getRuntime().exec(command);
    }
}
