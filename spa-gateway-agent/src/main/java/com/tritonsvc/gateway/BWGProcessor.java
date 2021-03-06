/*
 * Copyright (c) Triton Services. All rights reserved. http://www.tritonsvc.com
 *
 */
package com.tritonsvc.gateway;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableMap;
import com.google.common.primitives.Ints;
import com.google.common.primitives.Longs;
import com.tritonsvc.HostUtils;
import com.tritonsvc.agent.Agent;
import com.tritonsvc.agent.AgentConfiguration;
import com.tritonsvc.agent.AgentSettingsPersister;
import com.tritonsvc.agent.MQTTCommandProcessor;
import com.tritonsvc.httpd.RegistrationInfoHolder;
import com.tritonsvc.httpd.WebServer;
import com.tritonsvc.model.AgentSettings;
import com.tritonsvc.model.GenericSettings;
import com.tritonsvc.spa.communication.proto.Bwg;
import com.tritonsvc.spa.communication.proto.Bwg.AckResponseCode;
import com.tritonsvc.spa.communication.proto.Bwg.Downlink.Model.*;
import com.tritonsvc.spa.communication.proto.Bwg.Metadata;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.Model.Components.BlowerComponent;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.Model.Components.LightComponent;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.Model.Components.PumpComponent;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.Model.Components.ToggleComponent;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.Model.Constants.*;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.Model.Event;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.Model.Measurement.DataType;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.Model.SpaState;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.Model.WifiStat;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.UplinkCommandType;
import com.tritonsvc.spa.communication.proto.BwgHelper;
import com.tritonsvc.sw_upgrade.SoftwareUpgradeManager;
import com.tritonsvc.wifi.ParserIwconfig;
import jdk.dio.DeviceManager;
import jdk.dio.uart.UART;
import jdk.dio.uart.UARTConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

import static com.google.common.collect.Lists.newArrayList;
import static com.google.common.collect.Maps.newHashMap;

/**
 * Gateway Agent processing, collects WSN data also.
 */
public class BWGProcessor extends MQTTCommandProcessor implements RegistrationInfoHolder {

    public static final String DYNAMIC_DEVICE_OID_PROPERTY = "device.MAC.DEVICE_NAME.oid";
    private static final long MAX_NEW_REG_WAIT_TIME = 30000;
    private static final long MAX_REG_LIFETIME = Agent.MAX_SUBSCRIPTION_INACTIVITY_TIME - 30000; // set this to the same value
    // this guarantees that at least one
    // mqtt downlink is due to arrive into agent via the reg ack in given time
    private static final long MAX_PANEL_REQUEST_INTERIM = 10000;
    private static final long DEFAULT_UPDATE_INTERVAL = 60000; //0 is continuous, in ms
    private static final long DEFAULT_WIFIUPDATE_INTERVAL = 3600000; // 1 hour
    private static final long DEFAULT_AMBIENT_INTERVAL = 300000; // 5 mins
    private static final long DEFAULT_PUMP_CURRENT_INTERVAL = 300000; // 5 mins

    private static Logger LOGGER = LoggerFactory.getLogger(BWGProcessor.class);
    private static Map<String, String> DEFAULT_EMPTY_MAP = newHashMap();
    private final ReentrantReadWriteLock regLock = new ReentrantReadWriteLock();
    private Map<String, DeviceRegistration> registeredHwIds = newHashMap();
    private Properties configProps;
    private String gwSerialNumber;
    private OidProperties oidProperties = new OidProperties();
    private RS485DataHarvester rs485DataHarvester;
    private RS485MessagePublisher rs485MessagePublisher;
    private FaultLogManager faultLogManager;
    private UART rs485Uart;
    private long lastSpaDetailsSent = 0;
    private long lastWifiStatsSent = 0;
    private long lastPanelRequestSent = 0;
    private AtomicLong updateInterval = new AtomicLong(DEFAULT_UPDATE_INTERVAL);
    private AtomicLong wifiStatUpdateInterval = new AtomicLong(DEFAULT_WIFIUPDATE_INTERVAL);
    private AtomicLong ambientUpdateInterval = new AtomicLong(DEFAULT_AMBIENT_INTERVAL);
    private AtomicLong pumpCurrentUpdateInterval = new AtomicLong(DEFAULT_PUMP_CURRENT_INTERVAL);
    private Byte persistedRS485Address = null;
    private long lastFaultLogsSent = 0;
    private long lastAmbientSent = 0;
    private long lastPumpCurrentSent = 0;
    private long lastWifiStatsRead = 0;
    private long lastRS485StatusChangeEventSent = 0;
    private ScheduledExecutorService es = null;
    private ScheduledFuture<?> intervalResetFuture = null;
    private String rs485ControllerType = null;
    private String wifiDevice = null;
    private String ifConfigPath = null;
    private String iwConfigPath = null;
    private String ethernetDevice = null;
    private WifiStat lastWifiStatParsed = null;
    private WifiStat lastWifiStatSent = null;
    private ParserIwconfig lwconfigParser = new ParserIwconfig();
    private boolean sentRebootEvent = false;
    private ButtonManager buttonManager;
    private String homePath;
    private WSNDataHarvester wsnDataHarvester;
    private SoftwareUpgradeManager softwareUpgradeManager;
    private boolean skipSoftwareUpgrade = false;
    private String serialPort;
    private boolean militaryTimeDisplay;

    /**
     * Constructor
     *
     * @param persister
     */
    public BWGProcessor(AgentSettingsPersister persister) {
        super(persister);
    }

    @Override
    public void handleShutdown() {
        try {
            rs485Uart.stopReading();
        } catch (Exception ex) {
        }
        try {
            rs485Uart.stopWriting();
        } catch (Exception ex) {
        }
        try {
            rs485Uart.close();
        } catch (Exception ex) {
        }

        if (es != null) {
            es.shutdown();
        }
        if (buttonManager != null) {
            buttonManager.stopAPProcessIfPresent();
        }
        if (softwareUpgradeManager != null) {
            softwareUpgradeManager.shutdown();
        }
        LOGGER.info("Agent shutdown complete");
    }

    @Override
    public void handleStartup(String gwSerialNumber, Properties configProps, String homePath, ScheduledExecutorService executorService) {
        // persistent key/value db
        this.gwSerialNumber = gwSerialNumber;
        this.configProps = configProps;
        this.wifiDevice = configProps.getProperty(AgentConfiguration.WIFI_DEVICE_NAME, "wlan0");
        this.iwConfigPath = configProps.getProperty(AgentConfiguration.WIFI_IWCONFIG_PATH, "/sbin/iwconfig");
        this.ifConfigPath = configProps.getProperty(AgentConfiguration.WIFI_IFCONFIG_PATH, "/sbin/ifconfig");
        this.ethernetDevice = configProps.getProperty(AgentConfiguration.ETHERNET_DEVICE_NAME, "eth0");
        this.skipSoftwareUpgrade = Boolean.parseBoolean(configProps.getProperty(AgentConfiguration.SKIP_UPGARDE, "false"));
        this.serialPort = configProps.getProperty(AgentConfiguration.RS485_LINUX_SERIAL_PORT, "ttys0");
        this.es = executorService;
        this.homePath = homePath;
        Long timeoutMs = Longs.tryParse(configProps.getProperty(AgentConfiguration.AP_MODE_WEB_SERVER_TIMEOUT_SECONDS, "300"));
        if (timeoutMs == null) {
            timeoutMs = 300000L;
        } else {
            timeoutMs *= 1000;
        }
        this.buttonManager = new ButtonManager(new WebServer(configProps, this, this, timeoutMs), timeoutMs, this, ifConfigPath, iwConfigPath);

        setupAgentSettings();
        validateOidProperties();
        setUpRS485Processors();
        wsnDataHarvester = new WSNDataHarvester(this);
        executorService.execute(wsnDataHarvester);

        this.softwareUpgradeManager = new SoftwareUpgradeManager(configProps);

        LOGGER.info("finished startup.");
    }

    @Override
    public void handleRegistrationAck(RegistrationResponse response, String originatorId, String hardwareId) {
        if (response.getState() == RegistrationAckState.REGISTRATION_ERROR) {
            LOGGER.info("received registration error state {}", response.getErrorMessage());
            return;
        }

        if (response.getState() == RegistrationAckState.ALREADY_REGISTERED &&
                getRegisteredHWIds().containsKey(originatorId) &&
                Objects.equals(getRegisteredHWIds().get(originatorId).getHardwareId(), hardwareId)) {
            LOGGER.info("confirmed registration state in cloud for id {}", hardwareId);
            return;
        }

        if (getRegisteredHWIds().containsKey(originatorId)) {
            DeviceRegistration registered = getRegisteredHWIds().get(originatorId);
            registered.setHardwareId(hardwareId);
            getRegisteredHWIds().put(originatorId, registered);
            LOGGER.info("received successful registration, originatorid {} for hardwareid {} ", originatorId, hardwareId);

        } else {
            LOGGER.info("received registration {} for hardwareid {} that did not have a previous code for ", originatorId, hardwareId);
        }
    }

    @Override
    public void handleSpaRegistrationAck(SpaRegistrationResponse response, String originatorId, String hardwareId) {
        if (response.getState() == RegistrationAckState.REGISTRATION_ERROR) {
            LOGGER.info("received spa registration error state {}", response.getErrorMessage());
            return;
        }

        if (!sentRebootEvent) {
            long timestamp = System.currentTimeMillis();
            Event event = Event.newBuilder()
                    .setEventOccuredTimestamp(timestamp)
                    .setEventReceivedTimestamp(timestamp)
                    .setEventType(EventType.NOTIFICATION)
                    .setDescription("Gateway agent was restarted")
                    .build();
            sendEvents(hardwareId, newArrayList(event));
            sentRebootEvent = true;
        }

        if (!skipSoftwareUpgrade) {
            checkIfStartupAfterUpgrade(hardwareId);
            if (getRegisteredHWIds().containsKey(originatorId) && response.hasSwUpgradeUrl()) {
                checkAndPerformSoftwareUpgrade(response.getSwUpgradeUrl(), hardwareId);
            }
        }

        if (response.getState() == RegistrationAckState.ALREADY_REGISTERED &&
                getRegisteredHWIds().containsKey(originatorId) &&
                Objects.equals(getRegisteredHWIds().get(originatorId).getHardwareId(), hardwareId)) {
            LOGGER.info("confirmed registration state in cloud for spa id = {}", hardwareId);
            return;
        }

        if (getRegisteredHWIds().containsKey(originatorId)) {
            DeviceRegistration registered = getRegisteredHWIds().get(originatorId);
            registered.setHardwareId(hardwareId);
            registered.getMeta().put("regKey", response.hasRegKey() ? response.getRegKey() : null);
            registered.getMeta().put("regUserId", response.hasRegUserId() ? response.getRegUserId() : null);
            registered.getMeta().put("swUpgradeUrl", response.hasSwUpgradeUrl() ? response.getSwUpgradeUrl() : null);
            getRegisteredHWIds().put(originatorId, registered);
            LOGGER.info("received successful spa registration, originatorid {} for hardwareid {} ", originatorId, hardwareId);
        } else {
            LOGGER.info("received spa registration {} for hardwareid {} that did not have a previous code for ", originatorId, hardwareId);
        }
    }

    @Override
    public synchronized void handleDownlinkCommand(Request request, String hardwareId, String originatorId) {
        if (request == null || !request.hasRequestType()) {
            LOGGER.error("Request is null, not processing, []", originatorId);
            sendAck(hardwareId, originatorId, AckResponseCode.ERROR, "gateway has not registered with controller yet");
            return;
        }

        LOGGER.info("received downlink command from cloud {}, originatorid = {}", request.getRequestType().name(), originatorId);
        sendAck(hardwareId, originatorId, AckResponseCode.RECEIVED, null);
        long receivedTime = System.currentTimeMillis();
        Event event = Event.newBuilder()
                .setEventOccuredTimestamp(receivedTime)
                .setEventReceivedTimestamp(receivedTime)
                .setEventType(EventType.REQUEST)
                .setDescription("Received " + request.getRequestType().name() + " request")
                .addAllMetadata(convertRequestToMetaData(request.getMetadataList()))
                .addMetadata(Metadata.newBuilder().setName("originatorId").setValue(originatorId))
                .build();

        sendEvents(hardwareId, newArrayList(event));

        try {
            if (request.getRequestType().equals(RequestType.HEATER)) {
                updateHeater(request.getMetadataList(), getRS485DataHarvester().getRegisteredAddress(), originatorId, hardwareId, getRS485DataHarvester().usesCelsius());
            } else if (request.getRequestType().equals(RequestType.SET_TIME)) {
                setTime(request.getMetadataList(), originatorId, hardwareId, getRS485DataHarvester().getRegisteredAddress());
            } else if (request.getRequestType().equals(RequestType.UPDATE_AGENT_SETTINGS)) {
                updateAgentSettings(request.getMetadataList());
            } else {
                getRS485DataHarvester().arePanelCommandsSafe(false);
                switch (request.getRequestType()) {
                    case PUMP:
                        updatePeripherlal(request.getMetadataList(), getRS485DataHarvester().getRegisteredAddress(), originatorId, hardwareId, "kJets<port>MetaButton", ComponentType.PUMP);
                        break;
                    case CIRCULATION_PUMP:
                        updateCircPump(request.getMetadataList(), getRS485DataHarvester().getRegisteredAddress(), originatorId, hardwareId);
                        break;
                    case LIGHT:
                        updatePeripherlal(request.getMetadataList(), getRS485DataHarvester().getRegisteredAddress(), originatorId, hardwareId, "kLight<port>MetaButton", ComponentType.LIGHT);
                        break;
                    case BLOWER:
                        updatePeripherlal(request.getMetadataList(), getRS485DataHarvester().getRegisteredAddress(), originatorId, hardwareId, "kBlower<port>MetaButton", ComponentType.BLOWER);
                        break;
                    case MISTER:
                        updatePeripherlal(request.getMetadataList(), getRS485DataHarvester().getRegisteredAddress(), originatorId, hardwareId, "kMister<port>MetaButton", ComponentType.MISTER);
                        break;
                    case FILTER:
                        updateFilter(request.getMetadataList(), getRS485DataHarvester().getRegisteredAddress(), originatorId, hardwareId);
                        break;
                    case OZONE:
                        updateReservedComponent(request.getMetadataList(), originatorId, hardwareId, ComponentType.OZONE, "kOzoneMetaButton");
                        break;
                    case MICROSILK:
                        updateReservedComponent(request.getMetadataList(), originatorId, hardwareId, ComponentType.MICROSILK, "kMicroSilkQuietMetaButton");
                        break;
                    case AUX:
                        updatePeripherlal(request.getMetadataList(), getRS485DataHarvester().getRegisteredAddress(), originatorId, hardwareId, "kOption<port>MetaButton", ComponentType.AUX);
                        break;
                    default:
                        sendAck(hardwareId, originatorId, AckResponseCode.ERROR, "not supported");
                }
            }
        } catch (Exception ex) {
            LOGGER.error("had problem when sending a command ", ex);
            sendAck(hardwareId, originatorId, AckResponseCode.ERROR, ex.getMessage());
            return;
        }
    }

    @Override
    public String getEthernetDeviceName() {
        return ethernetDevice;
    }

    @Override
    public String getWifiDeviceName() {
        return wifiDevice;
    }

    @Override
    public void handleUplinkAck(UplinkAcknowledge ack, String originatorId) {
        //TODO - check if ack.NOT_REGISTERED and send up a registration if so
    }

    @Override
    public void processEventsHandler() {
        buttonManager.finish();
        buttonManager.mark();
    }

    @Override
    public synchronized void processDataHarvestIteration() {
        boolean locked = false;
        try {
            DeviceRegistration registeredSpa = obtainSpaRegistration();
            if (registeredSpa.getHardwareId() == null) {
                if(LOGGER.isDebugEnabled()) LOGGER.debug("skipping data harvest, spa gateway has not been registered");
                return;
            }
            long timestamp = System.currentTimeMillis();

            // make sure the controller is registered as a compoenent to cloud
            obtainControllerRegistration(registeredSpa.getHardwareId());
            processWifiDiag(registeredSpa.getHardwareId());
            buttonManager.sendPendingEventIfAvailable();

            boolean rs485Active;
            getRS485DataHarvester().getLatestSpaInfoLock().readLock().lockInterruptibly();
            locked = true;
            if (getRS485DataHarvester().getLatestSpaInfo().hasController() && getRS485DataHarvester().getLatestSpaInfo().getController().hasMilitary()) {
                militaryTimeDisplay = getRS485DataHarvester().getLatestSpaInfo().getController().getMilitary();
            }
            if (!getRS485DataHarvester().hasAllConfigState() &&
                    (timestamp - lastPanelRequestSent > MAX_PANEL_REQUEST_INTERIM)) {
                getRS485MessagePublisher().sendPanelRequest(getRS485DataHarvester().getRegisteredAddress(), false, null);
                lastPanelRequestSent = timestamp;
            }
            rs485Active = getRS485DataHarvester().getLatestSpaInfo().getRs485AddressActive();
            // this loop runs often(once every 3 seconds), but only send up to cloud when timestamps on state data change
            // or at least the update interval has passed since last cloud update was sent
            if (timestamp - lastSpaDetailsSent > updateInterval.get()) {
                getRS485DataHarvester().getLatestSpaInfoLock().readLock().unlock();
                locked = false;
                updateSpaInfoStateForLatestCloudUpdate(timestamp);
                getRS485DataHarvester().getLatestSpaInfoLock().readLock().lockInterruptibly();
                locked = true;
            }

            if (lastSpaDetailsSent != getRS485DataHarvester().getLatestSpaInfo().getLastUpdateTimestamp()) {
                getCloudDispatcher().sendUplink(registeredSpa.getHardwareId(), null, UplinkCommandType.SPA_STATE, getRS485DataHarvester().getLatestSpaInfo(), false);
                lastSpaDetailsSent = getRS485DataHarvester().getLatestSpaInfo().getLastUpdateTimestamp();
                LOGGER.info("Finished data harvest periodic iteration, sent spa state to cloud");
            }
            getRS485DataHarvester().getLatestSpaInfoLock().readLock().unlock();
            locked = false;

            processFaultLogs(registeredSpa.getHardwareId(), rs485Active);
            processMeasurements(registeredSpa.getHardwareId());
        } catch (Exception ex) {
            LOGGER.error("error while processing data harvest", ex);
        } finally {
            if (locked) {
                getRS485DataHarvester().getLatestSpaInfoLock().readLock().unlock();
            }
        }
    }

    @Override
    public String getRegKey() {
        return getGatewayMetaParam("regKey");
    }

    @Override
    public String getRegUserId() {
        return getGatewayMetaParam("regUserId");
    }

    @Override
    public String getSpaId() {
        return getGatewayHardwareId();
    }

    @Override
    public String getSerialNumber() {
        return this.gwSerialNumber;
    }

    @Override
    public boolean isAPModeOn() {
        return buttonManager != null ? buttonManager.isAPModeOn() : false;
    }

    public synchronized void setUpRS485Processors() {
        this.faultLogManager = new FaultLogManager(getConfigProps());
        if (Objects.equals(getRS485ControllerType(), "JACUZZI")) {
            setRS485MessagePublisher(new JacuzziMessagePublisher(this));
            setRS485DataHarvester(new JacuzziDataHarvester(this, (JacuzziMessagePublisher) getRS485MessagePublisher(), faultLogManager));
            LOGGER.info("Configured RS485 connection for Jacuzzi Protocol");
        } else {
            setRS485MessagePublisher(new NGSCMessagePublisher(this));
            setRS485DataHarvester(new NGSCDataHarvester(this, (NGSCMessagePublisher) getRS485MessagePublisher(), faultLogManager));
            LOGGER.info("Configured RS485 connection for BWG NGSC Protocol");
        }
        es.execute(getRS485DataHarvester());
    }

    public String getRS485ControllerType() {
        return rs485ControllerType;
    }

    public void setRS485ControllerType(String type) {
        this.rs485ControllerType = type;
    }

    public String getHomePath() {
        return homePath;
    }

    /**
     * get the oid properties mapping in config file
     *
     * @return
     */
    public OidProperties getOidProperties() {
        return oidProperties;
    }

    /**
     * get all the config file props
     *
     * @return
     */
    public Properties getConfigProps() {
        return configProps;
    }

    /**
     * get the handle to the rs 485 serial port
     *
     * @return
     */
    public UART getRS485UART() {
        return rs485Uart;
    }

    /**
     * retreive the cloud registrations that have been sent for local devices
     *
     * @return
     */
    public Map<String, DeviceRegistration> getRegisteredHWIds() {
        return registeredHwIds;
    }

    /**
     * initiate a cloud registration for spa system as whole based on gateway serial number
     *
     * @return
     */
    public DeviceRegistration obtainSpaRegistration() {
        Map<String, String> metaParams = newHashMap(getBuildParams());
        metaParams.put("BWG-Agent-RS485-Controller-Type", getRS485ControllerType() == null ? "NGSC" : getRS485ControllerType());
        return sendRegistration(null, gwSerialNumber, "gateway", DEFAULT_EMPTY_MAP, metaParams);
    }

    /**
     * initiate a cloud reg for the spa controller
     *
     * @param spaHardwareId
     * @return
     */
    public DeviceRegistration obtainControllerRegistration(String spaHardwareId) {
        return sendRegistration(spaHardwareId, gwSerialNumber, "controller", DEFAULT_EMPTY_MAP, DEFAULT_EMPTY_MAP);
    }

    /**
     * initiate a cloud reg for a mote
     *
     * @param spaHardwareId
     * @param macAddress
     * @return
     */
    public DeviceRegistration obtainMoteRegistration(String spaHardwareId, String macAddress, String additionalName) {
        return sendRegistration(spaHardwareId, gwSerialNumber, "mote", ImmutableMap.of("mac", macAddress), (additionalName != null ? ImmutableMap.of("mote_type", additionalName) : DEFAULT_EMPTY_MAP));
    }

    /**
     * retrieve the last known state of the a light component
     *
     * @param port
     * @return
     * @throws Exception
     */
    public LightComponent.State getLatestLightState(int port) throws Exception {
        boolean locked = false;
        try {
            getRS485DataHarvester().getLatestSpaInfoLock().readLock().lockInterruptibly();
            locked = true;
            SpaState spaState = getRS485DataHarvester().getLatestSpaInfo();
            if (spaState.hasController() && spaState.hasComponents()) {
                switch (port) {
                    case 1:
                        if (spaState.getComponents().hasLight1()) {
                            return spaState.getComponents().getLight1().getCurrentState();
                        }
                    case 2:
                        if (spaState.getComponents().hasLight2()) {
                            return spaState.getComponents().getLight2().getCurrentState();
                        }
                    case 3:
                        if (spaState.getComponents().hasLight3()) {
                            return spaState.getComponents().getLight3().getCurrentState();
                        }
                    case 4:
                        if (spaState.getComponents().hasLight4()) {
                            return spaState.getComponents().getLight4().getCurrentState();
                        }
                }
            }
            return null;
        } finally {
            if (locked) {
                getRS485DataHarvester().getLatestSpaInfoLock().readLock().unlock();
            }
        }
    }

    public void setUpRS485() {
        int baudRate = Ints.tryParse(configProps.getProperty(AgentConfiguration.RS485_LINUX_SERIAL_PORT_BAUD, "")) != null ?
                Ints.tryParse(configProps.getProperty(AgentConfiguration.RS485_LINUX_SERIAL_PORT_BAUD)) : 115200;

        UARTConfig config = new UARTConfig.Builder()
                .setControllerName(serialPort)
                .setBaudRate(baudRate)
                .setDataBits(UARTConfig.DATABITS_8)
                .setParity(UARTConfig.PARITY_NONE)
                .setStopBits(UARTConfig.STOPBITS_1)
                .setFlowControlMode(UARTConfig.FLOWCONTROL_NONE)
                .build();
        try {
            setRS485(DeviceManager.open(config));
        } catch (Throwable ex) {
            throw Throwables.propagate(ex);
        }

        LOGGER.info("initialized rs 485 serial port {}", serialPort);
    }

    public void saveDynamicRS485AddressInAgentSettings(byte address) {
        persistedRS485Address = address;
        if (getAgentSettings() != null) {
            validateGenericSettings().setPersistedRS485Address((int) address);
        }
        saveAgentSettings(null);
    }

    public void clearDynamicRS485AddressFromAgentSettings() {
        persistedRS485Address = null;
        if (getAgentSettings() != null) {
            validateGenericSettings().setPersistedRS485Address(null);
        }
        saveAgentSettings(null);
    }

    public int getUpdateIntervalSeconds() {
        return (int) (updateInterval.get() / 1000L);
    }

    public int getWifiUpdateIntervalSeconds() {
        return (int) (wifiStatUpdateInterval.get() / 1000L);
    }

    public int getAmbientUpdateIntervalSeconds() {
        return (int) (ambientUpdateInterval.get() / 1000L);
    }

    public int getPumpCurrentUpdateIntervalSeconds() {
        return (int) (pumpCurrentUpdateInterval.get() / 1000L);
    }

    public Byte getPersistedRS485Address() {
        return persistedRS485Address;
    }

    public String getSerialPort() {
        return serialPort;
    }

    public void pauseDataHarvester(boolean toggle) {
        rs485DataHarvester.pause(toggle);
    }

    @VisibleForTesting
    void setRS485MessagePublisher(RS485MessagePublisher rs485MessagePublisher) {
        this.rs485MessagePublisher = rs485MessagePublisher;
    }

    @VisibleForTesting
    void setRS485DataHarvester(RS485DataHarvester rs485DataHarvester) {
        this.rs485DataHarvester = rs485DataHarvester;
    }

    @VisibleForTesting
    void setRS485(UART uart) {
        this.rs485Uart = uart;
    }

    @VisibleForTesting
    void setFaultLogManager(FaultLogManager faultLogManager) {
        this.faultLogManager = faultLogManager;
    }

    private List<Metadata> convertRequestToMetaData(Collection<RequestMetadata> requestMetadata) {
        return requestMetadata.stream()
                .map(request -> Metadata.newBuilder().setName(request.getName().name()).setValue(request.getValue()).build())
                .collect(Collectors.toList());
    }

    private void updateHeater(final List<RequestMetadata> metadataList, byte registeredAddress, String originatorId, String hardwareId, boolean celsius) throws Exception {
        getRS485DataHarvester().arePanelCommandsSafe(true);
        Integer temperature = null;
        if (metadataList != null && metadataList.size() > 0) {
            for (final RequestMetadata metadata : metadataList) {
                if (SpaCommandAttribName.DESIREDTEMP.equals(metadata.getName())) {
                    temperature = new Integer(metadata.getValue());
                }
            }
        }

        if (temperature != null) {
            if (celsius) {
                // convert fahr temp into bwg celsius value
                temperature = new BigDecimal(2 *
                        (new BigDecimal((5.0 / 9.0) * (temperature - 32)).setScale(1, BigDecimal.ROUND_HALF_UP).doubleValue()))
                        .setScale(0, BigDecimal.ROUND_HALF_UP).intValue();
            }

            boolean locked = false;
            TempRange tempRange = null;
            int waterTemp = 0;
            HeaterMode heaterMode = null;
            int highHigh = 0;
            int highLow = 0;
            int lowHigh = 0;
            int lowLow = 0;
            try {
                getRS485DataHarvester().getLatestSpaInfoLock().readLock().lockInterruptibly();
                locked = true;
                if (getRS485DataHarvester().getLatestSpaInfo().hasController()) {
                    tempRange = getRS485DataHarvester().getLatestSpaInfo().getController().getTempRange();
                    waterTemp = getRS485DataHarvester().getLatestSpaInfo().getController().getCurrentWaterTemp();
                    heaterMode = getRS485DataHarvester().getLatestSpaInfo().getController().getHeaterMode();
                }
                if (getRS485DataHarvester().getLatestSpaInfo().hasSetupParams()) {
                    highHigh = getRS485DataHarvester().getLatestSpaInfo().getSetupParams().getHighRangeHigh();
                    highLow = getRS485DataHarvester().getLatestSpaInfo().getSetupParams().getHighRangeLow();
                    lowHigh = getRS485DataHarvester().getLatestSpaInfo().getSetupParams().getLowRangeHigh();
                    lowLow = getRS485DataHarvester().getLatestSpaInfo().getSetupParams().getLowRangeLow();
                }
            } catch (Exception ex) {
                LOGGER.error("error while processing data harvest", ex);
            } finally {
                if (locked) {
                    getRS485DataHarvester().getLatestSpaInfoLock().readLock().unlock();
                }
            }

            getRS485MessagePublisher().setTemperature(temperature, tempRange, waterTemp, heaterMode, registeredAddress, originatorId, hardwareId, highHigh, highLow, lowHigh, lowLow);
        } else {
            throw new RS485Exception("Update heater command did not have required metadata param: " + SpaCommandAttribName.DESIREDTEMP.name());
        }
    }

    private void setTime(final List<RequestMetadata> metadataList, String originatorId, String hardwareId, final byte address) throws Exception {

        String yearStr = BwgHelper.getRequestMetadataValue(SpaCommandAttribName.DATE_YEAR.name(), metadataList);
        Integer year = yearStr != null ? Ints.tryParse(yearStr) : null;

        String monthStr = BwgHelper.getRequestMetadataValue(SpaCommandAttribName.DATE_MONTH.name(), metadataList);
        Integer month = monthStr != null ? Ints.tryParse(monthStr) : null;

        String dayStr = BwgHelper.getRequestMetadataValue(SpaCommandAttribName.DATE_DAY.name(), metadataList);
        Integer day = dayStr != null ? Ints.tryParse(dayStr) : null;

        String hourStr = BwgHelper.getRequestMetadataValue(SpaCommandAttribName.TIME_HOUR.name(), metadataList);
        Integer hour = hourStr != null ? Ints.tryParse(hourStr) : null;

        String minuteStr = BwgHelper.getRequestMetadataValue(SpaCommandAttribName.TIME_MINUTE.name(), metadataList);
        Integer minute = minuteStr != null ? Ints.tryParse(minuteStr) : null;

        getRS485MessagePublisher().updateSpaTime(originatorId, hardwareId, militaryTimeDisplay, address, year, month, day, hour, minute);
    }

    private void updateAgentSettings(final List<RequestMetadata> metadataList) {
        final Integer intervalSeconds = Ints.tryParse(BwgHelper.getRequestMetadataValue(SpaCommandAttribName.INTERVAL_SECONDS.name(), metadataList));
        final Integer durationMinutes = Ints.tryParse(BwgHelper.getRequestMetadataValue(SpaCommandAttribName.DURATION_MINUTES.name(), metadataList));
        final String rs485ControllerType = BwgHelper.getRequestMetadataValue(SpaCommandAttribName.RS485_CONTROLLER_TYPE.name(), metadataList);
        final Integer wifiIntervalSeconds = Ints.tryParse(BwgHelper.getRequestMetadataValue(SpaCommandAttribName.WIFI_INTERVAL_SECONDS.name(), metadataList));
        final Integer ambientIntervalSeconds = Ints.tryParse(BwgHelper.getRequestMetadataValue(SpaCommandAttribName.AMBIENT_INTERVAL_SECONDS.name(), metadataList));
        final Integer pumpCurrentIntervalSeconds = Ints.tryParse(BwgHelper.getRequestMetadataValue(SpaCommandAttribName.PUMP_CURRENT_INTERVAL_SECONDS.name(), metadataList));

        if (intervalSeconds != null && durationMinutes != null) {
            updateInterval.set(1000L * intervalSeconds.longValue());
            if (this.intervalResetFuture != null) {
                this.intervalResetFuture.cancel(false);
            }
            // -1 tells to save current update interval in agent settings for reuse with next software startup
            // update interval is set permanently
            if (durationMinutes == -1) {
                saveCurrentUpdateIntervalInAgentSettings();
            } else {
                // clear update interval from agent settings, so update interval goes to default state with next
                // software startup and furthermore is reverted to default (60s) after given duration minutes
                clearUpdateIntervalFromAgentSettings();
                // after given minutes, update interval returns to its original state
                if (this.es != null) {
                    this.intervalResetFuture = this.es.schedule(() -> {
                        updateInterval.set(DEFAULT_UPDATE_INTERVAL);
                    }, durationMinutes.longValue(), TimeUnit.MINUTES);
                }
            }
        }

        if (wifiIntervalSeconds != null) {
            if (wifiIntervalSeconds < 0) {
                wifiStatUpdateInterval.set(DEFAULT_WIFIUPDATE_INTERVAL);
                clearWifiUpdateIntervalFromAgentSettings();
            } else {
                wifiStatUpdateInterval.set(1000L * wifiIntervalSeconds.longValue());
                saveCurrentWifiUpdateIntervalInAgentSettings();
            }
        }

        if (ambientIntervalSeconds != null) {
            if (ambientIntervalSeconds < 0) {
                ambientUpdateInterval.set(DEFAULT_AMBIENT_INTERVAL);
                clearAmbientUpdateIntervalFromAgentSettings();
            } else {
                ambientUpdateInterval.set(1000L * ambientIntervalSeconds.longValue());
                saveCurrentAmbientUpdateIntervalInAgentSettings();
            }
        }

        if (pumpCurrentIntervalSeconds != null) {
            if (pumpCurrentIntervalSeconds < 0) {
                pumpCurrentUpdateInterval.set(DEFAULT_PUMP_CURRENT_INTERVAL);
                clearPumpCurrentUpdateIntervalFromAgentSettings();
            } else {
                pumpCurrentUpdateInterval.set(1000L * pumpCurrentIntervalSeconds.longValue());
                saveCurrentPumpCurrentUpdateIntervalInAgentSettings();
            }
        }

        if (!Strings.isNullOrEmpty(rs485ControllerType)) {
            if (!Objects.equals(rs485ControllerType, getRS485ControllerType())) {
                setRS485ControllerType(rs485ControllerType);
                if (getRS485DataHarvester() != null) {
                    getRS485DataHarvester().cancel();
                }
                setUpRS485Processors();
            }
            saveCurrentProcessorTypeInAgentSettings();
        } else if (rs485ControllerType != null) {
            // this is empty string, means get rid of setting
            if (getRS485ControllerType() != null) {
                setRS485ControllerType(null);
                if (getRS485DataHarvester() != null) {
                    getRS485DataHarvester().cancel();
                }
                setUpRS485Processors();
            }
            clearProcessorTypeFromAgentSettings();
        }
    }

    private void updateReservedComponent(final List<RequestMetadata> metadataList, String originatorId, String hardwareId, ComponentType componentType, String buttonCodeValue) throws Exception {
        String desiredState = null;
        if (metadataList != null && metadataList.size() > 0) {
            for (final RequestMetadata metadata : metadataList) {
                if (SpaCommandAttribName.DESIREDSTATE.equals(metadata.getName())) {
                    desiredState = metadata.getValue();
                }
            }
        }
        if (desiredState == null) {
            throw new RS485Exception("Device command for " + componentType.name() + " did not have required desiredState param");
        }

        ComponentInfo currentState = getRS485DataHarvester().getComponentState(componentType, 0);
        if (currentState == null) {
            LOGGER.error("Request for state for {} component was not found, aborting command", componentType.name());
            throw new RS485Exception(componentType.name() + " Device is not installed, cannot submit command for it");
        }

        if (Objects.equals(desiredState, currentState.getCurrentState())) {
            LOGGER.info("Request to change {} to {} was already current state, not sending rs485 command", componentType.name(), desiredState);
            sendAck(hardwareId, originatorId, AckResponseCode.OK, null);
            return;
        }

        Codeable deviceCode = getRS485MessagePublisher().getCode(buttonCodeValue);
        getRS485MessagePublisher().sendCode(deviceCode.getCode(), getRS485DataHarvester().getRegisteredAddress(), originatorId, hardwareId);
    }

    private void updateFilter(List<RequestMetadata> metadataList, Byte registeredAddress, String originatorId, String hardwareId) throws Exception {
        Integer port = null;
        Integer durationMinutes = null;

        if (metadataList != null && metadataList.size() > 0) {
            for (final RequestMetadata metadata : metadataList) {
                if (SpaCommandAttribName.PORT.equals(metadata.getName())) {
                    port = new Integer(metadata.getValue());
                }
                if (SpaCommandAttribName.FILTER_DURATION_15MINUTE_INTERVALS.equals(metadata.getName())) {
                    durationMinutes = new Integer(metadata.getValue()) * 15;
                }
            }
        }

        if (port == null || durationMinutes == null) {
            throw new RS485Exception("Device command for " + ComponentType.FILTER.name() + " did not have required port and duration param");
        }
        getRS485MessagePublisher().sendFilterCycleRequest(port, durationMinutes, registeredAddress, originatorId, hardwareId, getRS485DataHarvester().getSpaClock());
    }

    private void updatePeripherlal(final List<RequestMetadata> metadataList, final byte registeredAddress,
                                   final String originatorId, final String hardwareId, final String buttonCodeTemplate,
                                   ComponentType componentType) throws Exception {

        RequiredParams params = collectRequiredParams(metadataList, componentType.name());
        Codeable deviceButton = getRS485MessagePublisher().getCode(buttonCodeTemplate.replaceAll("<port>", Integer.toString(params.getPort())));

        ComponentInfo currentState = getRS485DataHarvester().getComponentState(componentType, params.getPort());
        if (currentState == null) {
            LOGGER.error("Request for state for port {} of component {} was not found, aborting command", params.getPort(), componentType.name());
            throw new RS485Exception("Device command for " + componentType.name() + " did not have required port " + params.getPort() + "  and desiredState " + params.getDesiredState());
        }

        if (Objects.equals(params.getDesiredState(), currentState.getCurrentState())) {
            LOGGER.info("Request to change {} to {} was already current state, not sending rs485 command", componentType.name(), params.getDesiredState());
            sendAck(hardwareId, originatorId, AckResponseCode.OK, null);
            return;
        }

        int currentIndex;
        int desiredIndex;
        List<?> availableStates = currentState.getNumberOfSupportedStates();

        if (componentType.equals(ComponentType.LIGHT)) {
            currentIndex = availableStates.indexOf(LightComponent.State.valueOf(currentState.getCurrentState()));
            desiredIndex = availableStates.indexOf(LightComponent.State.valueOf(params.getDesiredState()));
        } else if (componentType.equals(ComponentType.PUMP)) {
            currentIndex = availableStates.indexOf(PumpComponent.State.valueOf(currentState.getCurrentState()));
            desiredIndex = availableStates.indexOf(PumpComponent.State.valueOf(params.getDesiredState()));
        } else if (componentType.equals(ComponentType.BLOWER)) {
            currentIndex = availableStates.indexOf(BlowerComponent.State.valueOf(currentState.getCurrentState()));
            desiredIndex = availableStates.indexOf(BlowerComponent.State.valueOf(params.getDesiredState()));
        } else {
            currentIndex = availableStates.indexOf(ToggleComponent.State.valueOf(currentState.getCurrentState()));
            desiredIndex = availableStates.indexOf(ToggleComponent.State.valueOf(params.getDesiredState()));
        }

        if (currentIndex < 0 || desiredIndex < 0) {
            LOGGER.warn("{} command request state {} or current state {} were not valid, ignoring, will send one button command", componentType.name(), params.getDesiredState(), currentState.getCurrentState());
            getRS485MessagePublisher().sendCode(deviceButton.getCode(), registeredAddress, originatorId, hardwareId);
            return;
        }

        // this sends multiple button commands to get to the desired state within available states for compnonent
        while (currentIndex != desiredIndex) {
            getRS485MessagePublisher().sendCode(deviceButton.getCode(), registeredAddress, originatorId, hardwareId);
            currentIndex = (currentIndex + 1) % availableStates.size();
        }
    }

    private void updateCircPump(final List<RequestMetadata> metadataList, final byte registeredAddress,
                                final String originatorId, final String hardwareId) throws Exception {
        String desiredState = null;

        if (metadataList != null) {
            for (final RequestMetadata metadata : metadataList) {
                if (SpaCommandAttribName.DESIREDSTATE.equals(metadata.getName())) {
                    desiredState = metadata.getValue();
                }
            }
        }

        if (desiredState == null) {
            throw new RS485Exception("Device command for " + ComponentType.CIRCULATION_PUMP.name() + " did not have required desiredState param");
        }

        ComponentInfo currentState = getRS485DataHarvester().getComponentState(ComponentType.CIRCULATION_PUMP, 0);
        if (currentState == null) {
            LOGGER.error("Requested component {} was not found, aborting command", ComponentType.CIRCULATION_PUMP.name());
            throw new RS485Exception("Device command for " + ComponentType.CIRCULATION_PUMP.name() + ", component does not exist on current system.");
        }

        if (Objects.equals(desiredState, currentState.getCurrentState())) {
            LOGGER.info("Request to change {} to {} was already current state, not sending rs485 command", ComponentType.CIRCULATION_PUMP.name(), desiredState);
            sendAck(hardwareId, originatorId, AckResponseCode.OK, null);
            return;
        }

        List<?> availableStates = currentState.getNumberOfSupportedStates();

        int currentIndex = availableStates.indexOf(PumpComponent.State.valueOf(currentState.getCurrentState()));
        int desiredIndex = availableStates.indexOf(PumpComponent.State.valueOf(desiredState));

        if (currentIndex < 0 || desiredIndex < 0) {
            LOGGER.warn("{} command request state {} or current state {} were not valid, ignoring, will send one button command", ComponentType.CIRCULATION_PUMP.name(), desiredState, currentState.getCurrentState());
            Codeable deviceButton = getRS485MessagePublisher().getCode("kPump0MetaButton");
            getRS485MessagePublisher().sendCode(deviceButton.getCode(), registeredAddress, originatorId, hardwareId);
            return;
        }

        // this sends multiple button commands to get to the desired state within available states for compnonent
        while (currentIndex != desiredIndex) {
            Codeable deviceButton = getRS485MessagePublisher().getCode("kPump0MetaButton");
            getRS485MessagePublisher().sendCode(deviceButton.getCode(), registeredAddress, originatorId, hardwareId);
            currentIndex = (currentIndex + 1) % availableStates.size();
        }
    }

    private void checkIfStartupAfterUpgrade(final String hardwareId) {
        final String oldVersionNumber = softwareUpgradeManager.readOldVersionNumber();
        if (oldVersionNumber != null) {
            // current version
            final String buildNumber = getBuildParams().get("BWG-Agent-Build-Number");

            final long timestamp = System.currentTimeMillis();
            Event event = Event.newBuilder()
                    .setEventOccuredTimestamp(timestamp)
                    .setEventReceivedTimestamp(timestamp)
                    .setEventType(EventType.NOTIFICATION)
                    .setDescription("Software upgrade from version " + oldVersionNumber + " to " + buildNumber + " has completed successfully")
                    .build();
            sendEvents(hardwareId, newArrayList(event));
        }
    }

    private void checkAndPerformSoftwareUpgrade(final String swUpgradeUrl, final String hardwareId) {
        final String buildNumber = getBuildParams().get("BWG-Agent-Build-Number");
        softwareUpgradeManager.checkAndPerformSoftwareUpgrade(swUpgradeUrl, buildNumber, hardwareId, this);
    }

    private void updateSpaInfoStateForLatestCloudUpdate(long timestamp) throws Exception {
        boolean wLocked = false;

        try {
            getRS485DataHarvester().getLatestSpaInfoLock().writeLock().lockInterruptibly();
            wLocked = true;
            SpaState.Builder stateBuilder = SpaState.newBuilder(getRS485DataHarvester().getLatestSpaInfo());
            stateBuilder.setLastUpdateTimestamp(timestamp);
            getRS485DataHarvester().setLatestSpaInfo(stateBuilder.build());
        } finally {
            if (wLocked) {
                getRS485DataHarvester().getLatestSpaInfoLock().writeLock().unlock();
            }
        }
    }

    private void processMeasurements(String hardwareId) throws IOException {
        long now = System.currentTimeMillis();
        if ( (ambientUpdateInterval.get() < 1 && (now - lastAmbientSent > DEFAULT_AMBIENT_INTERVAL)) ||
                (now - lastAmbientSent > ambientUpdateInterval.get())) {
            wsnDataHarvester.sendLatestWSNDataToCloud(newArrayList(DataType.AMBIENT_TEMP, DataType.AMBIENT_HUMIDITY), hardwareId);
            lastAmbientSent = now;
        }

        if ( (pumpCurrentUpdateInterval.get() < 1 && (now - lastPumpCurrentSent > DEFAULT_PUMP_CURRENT_INTERVAL)) ||
                (now - lastPumpCurrentSent > pumpCurrentUpdateInterval.get())) {
            wsnDataHarvester.sendLatestWSNDataToCloud(newArrayList(DataType.PUMP_AC_CURRENT), hardwareId);
            lastPumpCurrentSent = now;
        }
    }

    private void processFaultLogs(String hardwareId, boolean lastRs485Active) throws Exception {        // send when logs fetched from device become available
        long timestamp = System.currentTimeMillis();
        if (faultLogManager.hasUnsentFaultLogs()) {
            final Bwg.Uplink.Model.FaultLogs faultLogs = faultLogManager.getUnsentFaultLogs();
            if (faultLogs != null) {
                if (LOGGER.isDebugEnabled()) LOGGER.debug("sent {} fault logs to cloud", faultLogs.getFaultLogsCount());
                getCloudDispatcher().sendUplink(hardwareId, null, UplinkCommandType.FAULT_LOGS, faultLogs, false);
            }
        }

        // fault logs - check FaultLogsManager for default fetch interval
        // issue fetch logs command untill there are logs to collect from device
        final int nextLogNumberToFetch = faultLogManager.generateFetchNext();
        if ((timestamp - lastFaultLogsSent > faultLogManager.getFetchInterval()) || nextLogNumberToFetch > -1) {
            // get latest fetch log entry or entry with number held by fault log manager
            Short logNumber = nextLogNumberToFetch > -1 ? Short.valueOf((short) nextLogNumberToFetch) : null;
            LOGGER.info("sending request for fault log number {}, address {}", logNumber != null ? logNumber.toString() : 255, getRS485DataHarvester().getRegisteredAddress());

            try {
                getRS485MessagePublisher().sendPanelRequest(getRS485DataHarvester().getRegisteredAddress(), true, logNumber);
            } catch (RS485Exception ex) {
                LOGGER.error("unable to send request for fault log", ex);
            }
            lastFaultLogsSent = timestamp;
        }

        long faultLogHisteresis = (5 * faultLogManager.getFetchInterval());
        boolean currentRs485Active = (faultLogManager.getLastLogReceived() + (faultLogHisteresis)) > lastFaultLogsSent;
        if (currentRs485Active != lastRs485Active) {
            LOGGER.info("rs 485 status change detected from {} to {}", lastRs485Active, currentRs485Active);
            boolean wLocked = false;

            try {
                getRS485DataHarvester().getLatestSpaInfoLock().writeLock().lockInterruptibly();
                wLocked = true;
                SpaState.Builder stateBuilder = SpaState.newBuilder(getRS485DataHarvester().getLatestSpaInfo());
                stateBuilder.setRs485AddressActive(currentRs485Active);
                stateBuilder.setLastUpdateTimestamp(timestamp);
                getRS485DataHarvester().setLatestSpaInfo(stateBuilder.build());
            } finally {
                if (wLocked) {
                    getRS485DataHarvester().getLatestSpaInfoLock().writeLock().unlock();
                }
            }
            if ((timestamp - lastRS485StatusChangeEventSent) > faultLogHisteresis) {
                lastRS485StatusChangeEventSent = timestamp;
                Event event = Event.newBuilder()
                        .setEventOccuredTimestamp(timestamp)
                        .setEventReceivedTimestamp(timestamp)
                        .setEventType(currentRs485Active ? EventType.NOTIFICATION : EventType.ALERT)
                        .setDescription("Spa Controller rs-485 connectivity status change detected, from " + textualRS485Meaning(lastRs485Active) + " to " + textualRS485Meaning(currentRs485Active))
                        .addMetadata(Metadata.newBuilder().setName("oldRS485Status").setValue(textualRS485Meaning(lastRs485Active)))
                        .addMetadata(Metadata.newBuilder().setName("newRS485Status").setValue(textualRS485Meaning(currentRs485Active)))
                        .build();
                sendEvents(hardwareId, newArrayList(event));
            }
        }

        if (!currentRs485Active) {
            rs485DataHarvester.rollAddressState();
        } else {
            rs485DataHarvester.confirmAddressState(timestamp);
        }
    }

    private String textualRS485Meaning(boolean state) {
        return (state ? "connected" : "disconnected");
    }

    private boolean hasWifiStateChanged(WifiStat currentWifiStat) {
        return lastWifiStatParsed == null ||
                !Objects.equals(currentWifiStat.getWifiConnectionHealth(), lastWifiStatParsed.getWifiConnectionHealth()) ||
                !Objects.equals(currentWifiStat.getEthernetPluggedIn(), lastWifiStatParsed.getEthernetPluggedIn());
    }

    private void processWifiDiag(String hardwareId) {
        boolean wLocked = false;
        long now = System.currentTimeMillis();

        if (now - lastWifiStatsRead < 60000) {
            return;
        }

        lastWifiStatsRead = now;
        try {
            WifiStat currentWifiStat = lwconfigParser.parseStat(wifiDevice, lastWifiStatSent, iwConfigPath, ethernetDevice);
            long receivedTime = now + 1;

            if (hasWifiStateChanged(currentWifiStat)) {
                getRS485DataHarvester().getLatestSpaInfoLock().writeLock().lockInterruptibly();
                wLocked = true;
                SpaState.Builder stateBuilder = SpaState.newBuilder(getRS485DataHarvester().getLatestSpaInfo());
                stateBuilder.setWifiState(currentWifiStat.getWifiConnectionHealth());
                stateBuilder.setEthernetPluggedIn(currentWifiStat.getEthernetPluggedIn());
                stateBuilder.setLastUpdateTimestamp(receivedTime);
                stateBuilder.setUpdateInterval(getUpdateIntervalSeconds());
                stateBuilder.setWifiUpdateInterval(getWifiUpdateIntervalSeconds());
                getRS485DataHarvester().setLatestSpaInfo(stateBuilder.build());
                getRS485DataHarvester().getLatestSpaInfoLock().writeLock().unlock();
                wLocked = false;
                String oldWifiStatus = (lastWifiStatParsed == null ? WifiConnectionHealth.UNKONWN.name() : lastWifiStatParsed.getWifiConnectionHealth().name());
                LOGGER.info("Wifi status change detected, from " + oldWifiStatus + " to " + currentWifiStat.getWifiConnectionHealth().name());
            }

            if ((wifiStatUpdateInterval.get() < 1 && (now - lastWifiStatsSent > 60000)) ||
                    (now - lastWifiStatsSent > wifiStatUpdateInterval.get())) {
                sendWifiStats(hardwareId, newArrayList(currentWifiStat));
                lastWifiStatsSent = now;
                lastWifiStatSent = currentWifiStat;
                LOGGER.info("sent wifi stat to cloud, connection health {}", currentWifiStat.getWifiConnectionHealth().name());

                if (hasWifiStateChanged(currentWifiStat)) {
                    String oldWifiStatus = (lastWifiStatParsed == null ? WifiConnectionHealth.UNKONWN.name() : lastWifiStatParsed.getWifiConnectionHealth().name());
                    Event event = Event.newBuilder()
                            .setEventOccuredTimestamp(receivedTime)
                            .setEventReceivedTimestamp(receivedTime)
                            .setEventType(EventType.NOTIFICATION)
                            .setDescription("Wifi status change detected, from " + oldWifiStatus + " to " + currentWifiStat.getWifiConnectionHealth().name())
                            .addMetadata(Metadata.newBuilder().setName("oldWifiStatus").setValue(oldWifiStatus))
                            .addMetadata(Metadata.newBuilder().setName("newWifiStatus").setValue(currentWifiStat.getWifiConnectionHealth().name()))
                            .build();
                    sendEvents(hardwareId, newArrayList(event));
                    LOGGER.info("sent wifi event to cloud, connection health {}", currentWifiStat.getWifiConnectionHealth().name());
                }
            }
            lastWifiStatParsed = currentWifiStat;

        } catch (InterruptedException ex) {
            throw Throwables.propagate(ex);
        } catch (Exception ex) {
            LOGGER.error("problem while processing wifi diag", ex);
        } finally {
            if (wLocked) {
                getRS485DataHarvester().getLatestSpaInfoLock().writeLock().unlock();
            }
        }
    }

    private RS485MessagePublisher getRS485MessagePublisher() {
        return this.rs485MessagePublisher;
    }

    private RS485DataHarvester getRS485DataHarvester() {
        return this.rs485DataHarvester;
    }

    private void validateOidProperties() {
        String preOidPropName = DYNAMIC_DEVICE_OID_PROPERTY
                .replaceAll("MAC", "controller")
                .replaceAll("DEVICE_NAME", "pre_heat");
        String postOidPropName = DYNAMIC_DEVICE_OID_PROPERTY
                .replaceAll("MAC", "controller")
                .replaceAll("DEVICE_NAME", "post_heat");

        oidProperties.setPreHeaterTemp(configProps.getProperty(preOidPropName));
        oidProperties.setPostHeaterTemp(configProps.getProperty(postOidPropName));

        if (oidProperties.getPreHeaterTemp() == null) {
            LOGGER.error("Unable to find oid property for " + preOidPropName + " in config.properties, will not be reported");
        }
        if (oidProperties.getPostHeaterTemp() == null) {
            LOGGER.error("Unable to find oid property for " + preOidPropName + " in config.properties, will not be reported");
        }
    }

    private RequiredParams collectRequiredParams(List<RequestMetadata> metadataList, String componentTypeName) throws Exception {
        Integer port = null;
        String desiredState = null;

        if (metadataList != null && metadataList.size() > 0) {
            for (final RequestMetadata metadata : metadataList) {
                if (SpaCommandAttribName.PORT.equals(metadata.getName())) {
                    port = new Integer(metadata.getValue()) + 1;
                }
                if (SpaCommandAttribName.DESIREDSTATE.equals(metadata.getName())) {
                    desiredState = metadata.getValue();
                }
            }
        }

        if (port == null || desiredState == null) {
            throw new RS485Exception("Device command for " + componentTypeName + " did not have required port and desiredState param");
        }

        return new RequiredParams(port, desiredState);
    }

    private String getGatewayMetaParam(final String paramName) {
        final DeviceRegistration gateway = getGatewayDeviceRegistration();
        final String value;
        if (gateway != null) {
            value = gateway.getMeta().get(paramName);
        } else {
            value = null;
        }
        return value;
    }

    private String getGatewayHardwareId() {
        final DeviceRegistration gateway = getGatewayDeviceRegistration();
        final String value;
        if (gateway != null) {
            value = gateway.getHardwareId();
        } else {
            value = null;
        }
        return value;
    }

    private DeviceRegistration getGatewayDeviceRegistration() {
        final String gatewayKey = generateRegistrationKey(null, "gateway", new HashMap<>());
        final DeviceRegistration gateway = getRegisteredHWIds().get(gatewayKey);
        return gateway;
    }

    private static class RequiredParams {
        int port;
        String desiredState;

        public RequiredParams(int port, String desiredState) {
            this.port = port;
            this.desiredState = desiredState;
        }

        public int getPort() {
            return port;
        }

        public String getDesiredState() {
            return desiredState;
        }
    }

    private DeviceRegistration sendRegistration(String parentHwId, String gwSerialNumber, String deviceTypeName, Map<String, String> identityAttributes, Map<String, String> metaAttributes) {
        boolean readLocked = false;
        boolean writeLocked = false;
        try {
            regLock.readLock().lockInterruptibly();
            readLocked = true;
            // only send reg if current one has not expired or is absent
            String registrationHashCode = generateRegistrationKey(parentHwId, deviceTypeName, identityAttributes);
            if (isValidRegistration(registrationHashCode)) {
                return getRegisteredHWIds().get(registrationHashCode);
            }

            regLock.readLock().unlock();
            readLocked = false;
            regLock.writeLock().lockInterruptibly();
            writeLocked = true;
            if (isValidRegistration(registrationHashCode)) {
                return getRegisteredHWIds().get(registrationHashCode);
            }

            DeviceRegistration registeredDevice = (getRegisteredHWIds().containsKey(registrationHashCode) ?
                    getRegisteredHWIds().get(registrationHashCode) : new DeviceRegistration());

            registeredDevice.setLastTime(System.currentTimeMillis());
            getRegisteredHWIds().put(registrationHashCode, registeredDevice);
            Map<String, String> deviceMeta = newHashMap(identityAttributes);
            deviceMeta.putAll(metaAttributes);
            super.sendRegistration(parentHwId, gwSerialNumber, deviceTypeName, deviceMeta, registrationHashCode);
            return registeredDevice;
        } catch (InterruptedException ex) {
            throw Throwables.propagate(ex);
        } finally {
            if (readLocked) {
                regLock.readLock().unlock();
            }
            if (writeLocked) {
                regLock.writeLock().unlock();
            }
        }
    }

    private boolean isValidRegistration(String registrationHashCode) {
        if (getRegisteredHWIds().containsKey(registrationHashCode)) {
            DeviceRegistration registeredDevice = getRegisteredHWIds().get(registrationHashCode);
            // if the reg request is too old, attempt another one
            if ((registeredDevice.getHardwareId() != null && System.currentTimeMillis() - registeredDevice.getLastTime() < MAX_REG_LIFETIME) ||
                    (registeredDevice.getHardwareId() == null && System.currentTimeMillis() - registeredDevice.getLastTime() < MAX_NEW_REG_WAIT_TIME)) {
                return true;
            }
        }
        return false;
    }

    private GenericSettings validateGenericSettings() {
        GenericSettings genericSettings = getAgentSettings().getGenericSettings();
        if (genericSettings == null) {
            genericSettings = new GenericSettings();
            getAgentSettings().setGenericSettings(genericSettings);
        }
        return genericSettings;
    }

    private void saveCurrentUpdateIntervalInAgentSettings() {
        if (getAgentSettings() != null) {
            validateGenericSettings().setUpdateInterval(getUpdateIntervalSeconds());
        }
        saveAgentSettings(null);
    }

    private void clearUpdateIntervalFromAgentSettings() {
        if (getAgentSettings() != null) {
            validateGenericSettings().setUpdateInterval(null);
        }
        saveAgentSettings(null);
    }

    private void saveCurrentWifiUpdateIntervalInAgentSettings() {
        if (getAgentSettings() != null) {
            validateGenericSettings().setWifiUpdateInterval(getWifiUpdateIntervalSeconds());
        }
        saveAgentSettings(null);
    }

    private void clearWifiUpdateIntervalFromAgentSettings() {
        if (getAgentSettings() != null) {
            validateGenericSettings().setWifiUpdateInterval(null);
        }
        saveAgentSettings(null);
    }

    private void saveCurrentAmbientUpdateIntervalInAgentSettings() {
        if (getAgentSettings() != null) {
            validateGenericSettings().setAmbientUpdateInterval(getAmbientUpdateIntervalSeconds());
        }
        saveAgentSettings(null);
    }

    private void clearAmbientUpdateIntervalFromAgentSettings() {
        if (getAgentSettings() != null) {
            validateGenericSettings().setAmbientUpdateInterval(null);
        }
        saveAgentSettings(null);
    }

    private void saveCurrentPumpCurrentUpdateIntervalInAgentSettings() {
        if (getAgentSettings() != null) {
            validateGenericSettings().setPumpCurrentUpdateInterval(getPumpCurrentUpdateIntervalSeconds());
        }
        saveAgentSettings(null);
    }

    private void clearPumpCurrentUpdateIntervalFromAgentSettings() {
        if (getAgentSettings() != null) {
            validateGenericSettings().setPumpCurrentUpdateInterval(null);
        }
        saveAgentSettings(null);
    }

    private void saveCurrentProcessorTypeInAgentSettings() {
        if (getAgentSettings() != null) {
            validateGenericSettings().setRs485ControllerType(getRS485ControllerType());
        }
        saveAgentSettings(null);
    }

    private void clearProcessorTypeFromAgentSettings() {
        if (getAgentSettings() != null) {
            validateGenericSettings().setUpdateInterval(null);
        }
        saveAgentSettings(null);
    }

    private void setupAgentSettings() {
        final AgentSettings agentSettings = getAgentSettings();
        if (agentSettings != null && agentSettings.getGenericSettings() != null && agentSettings.getGenericSettings().getUpdateInterval() != null) {
            // translate seconds to milliseconds
            updateInterval.set(agentSettings.getGenericSettings().getUpdateInterval().longValue() * 1000L);
        }

        if (agentSettings != null && agentSettings.getGenericSettings() != null && agentSettings.getGenericSettings().getWifiUpdateInterval() != null) {
            // translate seconds to milliseconds
            wifiStatUpdateInterval.set(agentSettings.getGenericSettings().getWifiUpdateInterval().longValue() * 1000L);
        }

        if (agentSettings != null && agentSettings.getGenericSettings() != null && agentSettings.getGenericSettings().getAmbientUpdateInterval() != null) {
            // translate seconds to milliseconds
            ambientUpdateInterval.set(agentSettings.getGenericSettings().getAmbientUpdateInterval().longValue() * 1000L);
        }

        if (agentSettings != null && agentSettings.getGenericSettings() != null && agentSettings.getGenericSettings().getPumpCurrentUpdateInterval() != null) {
            // translate seconds to milliseconds
            pumpCurrentUpdateInterval.set(agentSettings.getGenericSettings().getPumpCurrentUpdateInterval().longValue() * 1000L);
        }

        if (agentSettings != null && agentSettings.getGenericSettings() != null && agentSettings.getGenericSettings().getPersistedRS485Address() != null) {
            // translate seconds to milliseconds
            persistedRS485Address = agentSettings.getGenericSettings().getPersistedRS485Address().byteValue();
        }

        if (agentSettings != null && agentSettings.getGenericSettings() != null && !Strings.isNullOrEmpty(agentSettings.getGenericSettings().getRs485ControllerType())) {
            setRS485ControllerType(agentSettings.getGenericSettings().getRs485ControllerType());
        }
    }
}
