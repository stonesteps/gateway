package com.tritonsvc.gateway;

import com.google.common.annotations.VisibleForTesting;
import com.tritonsvc.httpd.WebServer;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.Model.Constants.EventType;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.Model.Event;
import org.apache.commons.configuration2.Configuration;
import org.apache.commons.configuration2.FileBasedConfiguration;
import org.apache.commons.configuration2.PropertiesConfiguration;
import org.apache.commons.configuration2.builder.FileBasedConfigurationBuilder;
import org.apache.commons.configuration2.builder.fluent.Parameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;

import static com.google.common.collect.Lists.newArrayList;
import static org.apache.commons.io.FileUtils.forceDelete;

/**
 * Track the status of the button press event so that Wifi AP mode can be triggered.
 */
public class ButtonManager {

    private static Logger LOGGER = LoggerFactory.getLogger(BWGProcessor.class);
    private String systemType = null;
    private WebServer webServer;
    private ButtonState buttonState;
    private long webServerTimeoutMs;
    private BWGProcessor processor;
    private ConcurrentLinkedQueue<Event> events;
    private String ifConfigPath = null;
    private String iwConfigPath = null;

    private enum ButtonState {
        NOT_PRESSED,
        PRESSED,
        WEB_SERVER_RUNNING
    }

    /**
     * Constructor
     */
    public ButtonManager(WebServer webServer, long webServerTimeoutMs, BWGProcessor processor, String ifConfigPath, String iwConfigPath) {
        this.webServer = webServer;
        buttonState = ButtonState.NOT_PRESSED;
        this.webServerTimeoutMs = webServerTimeoutMs;
        systemType = processor.getOsType();
        this.processor = processor;
        events = new ConcurrentLinkedQueue<>();
        this.ifConfigPath = ifConfigPath;
        this.iwConfigPath = iwConfigPath;
    }

    /**
     * denote when button state is first checked
     */
    public void mark() {
        if (buttonState.equals(ButtonState.NOT_PRESSED)) {
            buttonState = checkButtonState() ? ButtonState.PRESSED : ButtonState.NOT_PRESSED;
        }
    }

    /**
     * compare present state of button to marked state if button was pressed at both points in time
     * then launch ap mode web service
     *
     * @return
     */
    public void finish() {
        if (buttonState.equals(ButtonState.PRESSED)) {
            if (checkButtonState()) {
                try {
                    buttonState = ButtonState.WEB_SERVER_RUNNING;
                    startWifiAP();
                    webServer.start();
                    LOGGER.info("launched ap mode due to button press");
                } catch (Exception ex) {
                    LOGGER.error("problem starting ap mode", ex);
                }
                return;
            }
        } else if (buttonState.equals(ButtonState.WEB_SERVER_RUNNING)) {
            if (System.currentTimeMillis() - webServer.getLastActivity() > webServerTimeoutMs) {
                // network settings change in web server, will set lastActivity to 0, which triggers network restart here
                stopWifiAP(webServer.updatedNetwork());
                webServer.stop();
                LOGGER.info("stopped ap mode due to web server notification of timeout or complete.");
            } else {
                return;
            }
        }
        buttonState = ButtonState.NOT_PRESSED;
    }

    /**
     * send any queued events that are waiting to be sent
     */
    public void sendPendingEventIfAvailable() {
        Event event = events.poll();
        if (event != null) {
            processor.sendEvents(processor.getSpaId(), newArrayList(event));
        }
    }

    /**
     *
     */
    public void stopAPProcessIfPresent() {
        try {
            executeUnixCommand("sudo pkill -9 udhcpd").waitFor(10, TimeUnit.SECONDS);
            LOGGER.info("stopped udhcpd");
        } catch (Exception ex) {
            LOGGER.error("had error stopping udhcpd process", ex);
        }

        try {
            executeUnixCommand("sudo pkill -9 hostapd").waitFor(10, TimeUnit.SECONDS);
            LOGGER.info("stopped hostapd");
        } catch (Exception ex) {
            LOGGER.error("had error stopping hostapd process", ex);
        }

        try {
            executeUnixCommand("sudo " + iwConfigPath + " " + processor.getWifiDeviceName() + " mode Managed").waitFor(10, TimeUnit.SECONDS);
            LOGGER.info("changed wifi ap mode to managed");
        } catch (Exception ex) {
            LOGGER.error("had error stopping wlan ap interface", ex);
        }

        try {
            executeUnixCommand("sudo " + ifConfigPath + " " + processor.getWifiDeviceName() + " down").waitFor(10, TimeUnit.SECONDS);
            LOGGER.info("brought wifi device down");
        } catch (Exception ex) {
            LOGGER.error("had error stopping wlan ap interface", ex);
        }

        try {
            executeUnixCommand("sudo " + ifConfigPath + " " + processor.getWifiDeviceName() + " up").waitFor(10, TimeUnit.SECONDS);
            LOGGER.info("brought wifi device up");
        } catch (Exception ex) {
            LOGGER.error("had error starting wlan ap interface", ex);
        }
    }

    private void startWifiAP() throws Exception {
        File factoryApMod = new File(processor.getHomePath(), "factory_ap");
        FileBasedConfigurationBuilder<FileBasedConfiguration> builder = getHostApdConf();
        Configuration config = builder.getConfiguration();
        String spaSSID = "BWG_SPA_" + processor.getSerialNumber();
        boolean factoryMode = false;
        if (factoryApMod.exists()) {
            forceDelete(factoryApMod);
            factoryMode = true;
        } else if (!config.getProperty("ssid").equals(spaSSID)) {
            config.setProperty("ssid", spaSSID);
            config.setProperty("wpa_passphrase", "controlmyspa");
            builder.save();
            LOGGER.info("reconfigred hostapd to use SSID of {}", spaSSID);
        }

        long eventTime = System.currentTimeMillis();
        Event event = Event.newBuilder()
                .setEventOccuredTimestamp(eventTime)
                .setEventReceivedTimestamp(eventTime)
                .setEventType(EventType.NOTIFICATION)
                .setDescription("Wifi AP mode launched " + (factoryMode? "in factory mode" : "")  + " with SSID: " + config.getProperty("ssid") + " due to button pressed on gateway")
                .build();
        addPendingEvent(event);

        stopAPProcessIfPresent();
        if (systemType.equals(BWGProcessor.TS_IMX6)) {
            executeUnixCommand("sudo systemctl stop wpa_supplicant@" + processor.getWifiDeviceName()).waitFor(10, TimeUnit.SECONDS);
        } else {
            //TODO - check renesas linux, make sure inti.d/wpa_supplicant exists
            executeUnixCommand("sudo /etc/init.d/wpa_supplicant stop").waitFor(10, TimeUnit.SECONDS);
        }
        LOGGER.info("stopped wpa supplicant");

        executeUnixCommand("sudo /usr/sbin/udhcpd");
        LOGGER.info("started udhcpd");
        executeUnixCommand("sudo /usr/sbin/hostapd -B /etc/hostapd.conf");
        LOGGER.info("started hostapd");
    }

    private void addPendingEvent(Event event) {
        if (events.size() < 20) {
            events.add(event);
        }
    }

    private void stopWifiAP(boolean restartNetwork) {
        stopAPProcessIfPresent();
        try {
            if (systemType.equals(BWGProcessor.TS_IMX6)) {
                executeUnixCommand("sudo systemctl restart wpa_supplicant@" + processor.getWifiDeviceName()).waitFor(10, TimeUnit.SECONDS);
            } else {
                //TODO - check renesas linux, make sure inti.d/wpa_supplicant exists
                executeUnixCommand("sudo /etc/init.d/wpa_supplicant restart").waitFor(10, TimeUnit.SECONDS);
            }
            LOGGER.info("restarted wpa_supplicant");
        } catch (Exception ex) {
            LOGGER.error("had error restarting wpa wifi client process", ex);
        }

        if (restartNetwork) {
            try {
                if (systemType.equals(BWGProcessor.TS_IMX6)) {
                    executeUnixCommand("sudo " + ifConfigPath + " " + processor.getEthernetDeviceName() + " down").waitFor(10, TimeUnit.SECONDS);
                    executeUnixCommand("sudo ip addr flush dev " + processor.getEthernetDeviceName()).waitFor(10, TimeUnit.SECONDS);
                    executeUnixCommand("sudo systemctl restart systemd-networkd").waitFor(10, TimeUnit.SECONDS);
                } else {
                    executeUnixCommand("sudo /etc/init.d/networking restart").waitFor(10, TimeUnit.SECONDS);
                }
                LOGGER.info("restarted linux networking");
            } catch (Exception ex) {
                LOGGER.error("had error restarting networking", ex);
            }
        }

        long eventTime = System.currentTimeMillis();
        Event event = Event.newBuilder()
                .setEventOccuredTimestamp(eventTime)
                .setEventReceivedTimestamp(eventTime)
                .setEventType(EventType.NOTIFICATION)
                .setDescription("Wifi AP mode stopped" + (restartNetwork ? ", network settings were changed, and networking was restarted." : ""))
                .build();
        addPendingEvent(event);
        LOGGER.info("stopped ap mode, and restarted wifi client mode");
    }

    private boolean checkButtonState() {
        try {
            if (systemType.equals(BWGProcessor.TS_IMX6)) {
                Process proc = executeUnixCommand("sudo tshwctl --addr 31 --peek");
                proc.waitFor(2, TimeUnit.SECONDS);
                String line;
                try (BufferedReader output = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                    while ((line = output.readLine()) != null) {
                        if (line.toLowerCase().contains("0x0")) {
                            return true;
                        }
                    }
                }
            } else {
                //TODO, do the real Renesas button check in linux sysfs /sys/class/gpio
            }
        } catch (Exception ex) {
            LOGGER.error("unable to check button state", ex);
        }
        return false;
    }

    @VisibleForTesting
    Process executeUnixCommand(String command) throws IOException {
        return Runtime.getRuntime().exec(command);
    }

    @VisibleForTesting FileBasedConfigurationBuilder<FileBasedConfiguration> getHostApdConf() throws Exception {
       return new FileBasedConfigurationBuilder<FileBasedConfiguration>(PropertiesConfiguration.class)
                .configure(new Parameters().properties()
                        .setFile(new File("/etc/hostapd.conf"))
                        .setThrowExceptionOnMissing(true)
                        .setIncludesAllowed(false));
    }
}
