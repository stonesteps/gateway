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
import com.tritonsvc.agent.AgentConfiguration;
import com.tritonsvc.agent.MQTTCommandProcessor;
import com.tritonsvc.httpd.RegistrationInfoHolder;
import com.tritonsvc.httpd.WebServer;
import com.tritonsvc.model.AgentSettings;
import com.tritonsvc.model.GenericSettings;
import com.tritonsvc.spa.communication.proto.Bwg;
import com.tritonsvc.spa.communication.proto.Bwg.AckResponseCode;
import com.tritonsvc.spa.communication.proto.Bwg.Downlink.Model.RegistrationAckState;
import com.tritonsvc.spa.communication.proto.Bwg.Downlink.Model.RegistrationResponse;
import com.tritonsvc.spa.communication.proto.Bwg.Downlink.Model.Request;
import com.tritonsvc.spa.communication.proto.Bwg.Downlink.Model.RequestMetadata;
import com.tritonsvc.spa.communication.proto.Bwg.Downlink.Model.RequestType;
import com.tritonsvc.spa.communication.proto.Bwg.Downlink.Model.SpaCommandAttribName;
import com.tritonsvc.spa.communication.proto.Bwg.Downlink.Model.SpaRegistrationResponse;
import com.tritonsvc.spa.communication.proto.Bwg.Downlink.Model.UplinkAcknowledge;
import com.tritonsvc.spa.communication.proto.Bwg.Metadata;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.Model.Components.BlowerComponent;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.Model.Components.LightComponent;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.Model.Components.PumpComponent;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.Model.Components.ToggleComponent;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.Model.Constants.ComponentType;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.Model.Constants.EventType;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.Model.Constants.HeaterMode;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.Model.Constants.TempRange;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.Model.Constants.WifiConnectionHealth;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.Model.Event;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.Model.SpaState;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.Model.WifiStat;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.UplinkCommandType;
import com.tritonsvc.spa.communication.proto.BwgHelper;
import com.tritonsvc.wifi.ParserIwconfig;
import jdk.dio.DeviceManager;
import jdk.dio.uart.UART;
import jdk.dio.uart.UARTConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
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
    private static final long MAX_REG_LIFETIME = 240000;
    private static final long MAX_PANEL_REQUEST_INTERIM = 30000;
    private static final long DEFAULT_UPDATE_INTERVAL = 0; //continuous
    private static final long DEFAULT_WIFIUPDATE_INTERVAL = 0x9000000; // 4 hours

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
    private AtomicLong lastSpaDetailsSent = new AtomicLong(0);
    private AtomicLong lastWifiStatsSent = new AtomicLong(0);
    private AtomicLong lastPanelRequestSent = new AtomicLong(0);
    private AtomicLong updateInterval = new AtomicLong(DEFAULT_UPDATE_INTERVAL);
    private AtomicLong wifiStatUpdateInterval = new AtomicLong(DEFAULT_WIFIUPDATE_INTERVAL);
    private AtomicLong lastFaultLogsSent = new AtomicLong(0);
    private ScheduledExecutorService es = null;
    private ScheduledFuture<?> intervalResetFuture = null;
    private String rs485ControllerType = null;
    private String wifiDevice = null;
    private String iwConfigPath = null;
    private WifiStat lastWifiStatParsed = null;
    private WifiStat lastWifiStatSent = null;
    private ParserIwconfig lwconfigParser = new ParserIwconfig();

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

        if (es != null) {es.shutdown();}
    }

    @Override
    public void handleStartup(String gwSerialNumber, Properties configProps, String homePath, ScheduledExecutorService executorService) {
        // persistent key/value db
        this.gwSerialNumber = gwSerialNumber;
        this.configProps = configProps;
        this.wifiDevice = configProps.getProperty(AgentConfiguration.WIFI_DEVICE_NAME, "wlan0");
        this.iwConfigPath = configProps.getProperty(AgentConfiguration.WIFI_IWCONFIG_PATH, "/sbin/iwconfig");
        new WebServer(configProps, this, this);
        this.es = executorService;
        setupAgentSettings();
        validateOidProperties();
        obtainSpaRegistration();
        setUpRS485Processors();
        executorService.execute(new WSNDataHarvester(this));

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

        if (response.getState() == RegistrationAckState.ALREADY_REGISTERED &&
                getRegisteredHWIds().containsKey(originatorId) &&
                Objects.equals(getRegisteredHWIds().get(originatorId).getHardwareId(), hardwareId)) {
            LOGGER.info("confirmed registration state in cloud for spa id = {}", hardwareId);
            return;
        }

        if (getRegisteredHWIds().containsKey(originatorId)) {
            DeviceRegistration registered = getRegisteredHWIds().get(originatorId);
            registered.setHardwareId(hardwareId);
            registered.getMeta().put("apSSID", response.hasP2PAPSSID() ? response.getP2PAPPassword() : null);
            registered.getMeta().put("apPassword", response.hasP2PAPPassword() ? response.getP2PAPPassword() : null);
            registered.getMeta().put("regKey", response.hasRegKey() ? response.getRegKey() : null);
            registered.getMeta().put("regUserId", response.hasRegUserId() ? response.getRegUserId() : null);
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

        if (getRS485DataHarvester().getRegisteredAddress() == null) {
            LOGGER.error("received request {}, gateway has not registered with controller yet ", request.getRequestType().name());
            sendAck(hardwareId, originatorId, AckResponseCode.ERROR, "gateway has not registered with controller yet");
            return;
        }

        LOGGER.info("received downlink command from cloud {}, originatorid = {}", request.getRequestType().name(), originatorId);
        sendAck(hardwareId, originatorId, AckResponseCode.RECEIVED, null);
        long receivedTime = new Date().getTime();
        Event event = Event.newBuilder()
                .setEventOccuredTimestamp(receivedTime)
                .setEventReceivedTimestamp(receivedTime)
                .setEventType(EventType.REQEUST)
                .setDescription("Received " + request.getRequestType().name() + " request")
                .addAllMetadata(convertRequestToMetaData(request.getMetadataList()))
                .addMetadata(Metadata.newBuilder().setName("originatorId").setValue(originatorId))
                .build();

        sendEvents(hardwareId, newArrayList(event));

        try {
            if (request.getRequestType().equals(RequestType.HEATER)) {
                updateHeater(request.getMetadataList(), getRS485DataHarvester().getRegisteredAddress(), originatorId, hardwareId, getRS485DataHarvester().usesCelsius());
            } else if (request.getRequestType().equals(RequestType.UPDATE_AGENT_SETTINGS)) {
                updateAgentSettings(request.getMetadataList());
            } else {
                getRS485DataHarvester().arePanelCommandsSafe(false);
                switch (request.getRequestType()) {
                    case PUMPS:
                        updatePeripherlal(request.getMetadataList(), getRS485DataHarvester().getRegisteredAddress(), originatorId, hardwareId, "kJets<port>MetaButton", ComponentType.PUMP);
                        break;
                    case CIRCULATION_PUMP:
                        updateCircPump(request.getMetadataList(), getRS485DataHarvester().getRegisteredAddress(), originatorId, hardwareId);
                        break;
                    case LIGHTS:
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

    public synchronized void setUpRS485Processors() {
        this.faultLogManager = new FaultLogManager(getConfigProps());
        if (Objects.equals(getRS485ControllerType(), "JACUZZI")) {
            setRS485MessagePublisher(new JacuzziMessagePublisher(this));
            setRS485DataHarvester(new JacuzziDataHarvester(this, (JacuzziMessagePublisher) getRS485MessagePublisher(),faultLogManager));
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

    private List<Metadata> convertRequestToMetaData(Collection<RequestMetadata> requestMetadata) {
        return requestMetadata.stream()
                .map(request ->  Metadata.newBuilder().setName(request.getName().name()).setValue(request.getValue()).build())
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

    private void updateAgentSettings(final List<RequestMetadata> metadataList) {
        final Integer intervalSeconds = Ints.tryParse(BwgHelper.getRequestMetadataValue(SpaCommandAttribName.INTERVAL_SECONDS.name(), metadataList));
        final Integer durationMinutes = Ints.tryParse(BwgHelper.getRequestMetadataValue(SpaCommandAttribName.DURATION_MINUTES.name(), metadataList));
        final String rs485ControllerType = BwgHelper.getRequestMetadataValue(SpaCommandAttribName.RS485_CONTROLLER_TYPE.name(), metadataList);
        final Integer wifiIntervalSeconds = Ints.tryParse(BwgHelper.getRequestMetadataValue(SpaCommandAttribName.WIFI_INTERVAL_SECONDS.name(), metadataList));

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
            if (wifiIntervalSeconds < 0)
            {
                wifiStatUpdateInterval.set(DEFAULT_WIFIUPDATE_INTERVAL);
                clearWifiUpdateIntervalFromAgentSettings();
            } else {
                wifiStatUpdateInterval.set(1000L * wifiIntervalSeconds.longValue());
                saveCurrentWifiUpdateIntervalInAgentSettings();
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

    @Override
    public void handleUplinkAck(UplinkAcknowledge ack, String originatorId) {
        //TODO - check if ack.NOT_REGISTERED and send up a registration if so
    }

    @Override
    public synchronized void processDataHarvestIteration() {
        DeviceRegistration registeredSpa = obtainSpaRegistration();
        if (registeredSpa.getHardwareId() == null) {
            LOGGER.info("skipping data harvest, spa gateway has not been registered");
            return;
        }

        // make sure the controller is registered as a compoenent to cloud
        obtainControllerRegistration(registeredSpa.getHardwareId());

        processWifiDiag(registeredSpa.getHardwareId());
        if (getRS485DataHarvester().getRegisteredAddress() == null) {
            LOGGER.info("skipping data harvest, gateway has not registered over 485 bus with spa controller yet");
            return;
        }

        boolean locked = false;
        try {
            getRS485DataHarvester().getLatestSpaInfoLock().readLock().lockInterruptibly();
            locked = true;
            if (getRS485DataHarvester().getLatestSpaInfo().hasController() == false) {
                throw new RS485Exception("panel update message has not been received yet, cannot generate spa state yet.");
            }

            if (!getRS485DataHarvester().hasAllConfigState() &&
                    System.currentTimeMillis() - lastPanelRequestSent.get() > MAX_PANEL_REQUEST_INTERIM) {
                getRS485MessagePublisher().sendPanelRequest(getRS485DataHarvester().getRegisteredAddress(), false, null);
                lastPanelRequestSent.set(System.currentTimeMillis());
            }

            // this loop runs often(once every 3 seconds), but only send up to cloud when timestamps on state data change
            if (getRS485DataHarvester().getLatestSpaInfo().hasLastUpdateTimestamp() &&
                    lastSpaDetailsSent.get() != getRS485DataHarvester().getLatestSpaInfo().getLastUpdateTimestamp() &&
                    System.currentTimeMillis() - lastSpaDetailsSent.get() > updateInterval.get()) {
                getCloudDispatcher().sendUplink(registeredSpa.getHardwareId(), null, UplinkCommandType.SPA_STATE, getRS485DataHarvester().getLatestSpaInfo());
                lastSpaDetailsSent.set(getRS485DataHarvester().getLatestSpaInfo().getLastUpdateTimestamp());
                LOGGER.info("Finished data harvest periodic iteration, sent spa state to cloud");
            }

            getRS485DataHarvester().getLatestSpaInfoLock().readLock().unlock();
            locked = false;
            processFaultLogs(registeredSpa.getHardwareId());
        } catch (Exception ex) {
            LOGGER.error("error while processing data harvest", ex);
        } finally {
            if (locked) {
                getRS485DataHarvester().getLatestSpaInfoLock().readLock().unlock();
            }
        }
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
    public DeviceRegistration obtainMoteRegistration(String spaHardwareId, String macAddress) {
        return sendRegistration(spaHardwareId, gwSerialNumber, "mote", ImmutableMap.of("mac", macAddress), DEFAULT_EMPTY_MAP);
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

    @VisibleForTesting
    void setRS485MessagePublisher(RS485MessagePublisher rs485MessagePublisher) {
        this.rs485MessagePublisher = rs485MessagePublisher;
    }

    private void processFaultLogs (String hardwareId) {
        // send when logs fetched from device become available
        if (faultLogManager.hasUnsentFaultLogs()) {
            final Bwg.Uplink.Model.FaultLogs faultLogs = faultLogManager.getUnsentFaultLogs();
            if (faultLogs != null) {
                LOGGER.info("sent {} fault logs to cloud", faultLogs.getFaultLogsCount());
                getCloudDispatcher().sendUplink(hardwareId, null, UplinkCommandType.FAULT_LOGS, faultLogs);
            }
        }

        // fault logs - check FaultLogsManager for default fetch interval
        // issue fetch logs command untill there are logs to collect from device
        final int nextLogNumberToFetch = faultLogManager.generateFetchNext();
        if ((System.currentTimeMillis() - lastFaultLogsSent.get() > faultLogManager.getFetchInterval()) || nextLogNumberToFetch > -1) {
            // get latest fetch log entry or entry with number held by fault log manager
            Short logNumber = nextLogNumberToFetch > -1 ? Short.valueOf((short) nextLogNumberToFetch): null;
            LOGGER.info("sending request for fault log number {}", logNumber != null ? logNumber.toString() : 255);

            try {
                getRS485MessagePublisher().sendPanelRequest(getRS485DataHarvester().getRegisteredAddress(), true, logNumber);
            } catch (RS485Exception ex) {
                LOGGER.error("unable to send request for fault log", ex);
            }
            lastFaultLogsSent.set(System.currentTimeMillis());
        }
    }

    private void processWifiDiag (String hardwareId) {
        boolean wLocked = false;
        try {
            WifiStat currentWifiStat = lwconfigParser.parseStat(wifiDevice, lastWifiStatSent, iwConfigPath);

            if (lastWifiStatParsed == null || !Objects.equals(currentWifiStat.getWifiConnectionHealth(), lastWifiStatParsed.getWifiConnectionHealth())) {
                getRS485DataHarvester().getLatestSpaInfoLock().writeLock().lockInterruptibly();
                wLocked = true;
                long receivedTime = new Date().getTime() + 1;
                SpaState.Builder stateBuilder = SpaState.newBuilder(getRS485DataHarvester().getLatestSpaInfo());
                stateBuilder.setWifiState(currentWifiStat.getWifiConnectionHealth());
                stateBuilder.setLastUpdateTimestamp(receivedTime);
                getRS485DataHarvester().setLatestSpaInfo(stateBuilder.build());
                getRS485DataHarvester().getLatestSpaInfoLock().writeLock().unlock();
                wLocked = false;

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
                sendWifiStats(hardwareId, newArrayList(currentWifiStat));
                lastWifiStatsSent.set(new Date().getTime());
                lastWifiStatSent = currentWifiStat;
                LOGGER.info("sent wifi stat and event to cloud, connection health changed from {} to {}", oldWifiStatus, currentWifiStat.getWifiConnectionHealth().name());
            }

            if (wifiStatUpdateInterval.get() < 1) {
                if (System.currentTimeMillis() - lastWifiStatsSent.get() > 60000) {
                    sendWifiStats(hardwareId, newArrayList(currentWifiStat));
                    lastWifiStatsSent.set(new Date().getTime());
                    lastWifiStatSent = currentWifiStat;
                    LOGGER.info("sent wifi stat to cloud, connection health {}", currentWifiStat.getWifiConnectionHealth().name());
                }
            } else if (System.currentTimeMillis() - lastWifiStatsSent.get() > wifiStatUpdateInterval.get()) {
                sendWifiStats(hardwareId, newArrayList(currentWifiStat));
                lastWifiStatsSent.set(new Date().getTime());
                lastWifiStatSent = currentWifiStat;
                LOGGER.info("sent wifi stat to cloud, connection health {}", currentWifiStat.getWifiConnectionHealth().name());
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

    @VisibleForTesting
    void setRS485DataHarvester(RS485DataHarvester rs485DataHarvester) {
        this.rs485DataHarvester = rs485DataHarvester;
    }

    private RS485DataHarvester getRS485DataHarvester() {
        return this.rs485DataHarvester;
    }

    @VisibleForTesting
    void setRS485(UART uart) {
        this.rs485Uart = uart;
    }

    @VisibleForTesting
    void setFaultLogManager(FaultLogManager faultLogManager) {
        this.faultLogManager = faultLogManager;
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

    public void setUpRS485() {
        String serialPort = configProps.getProperty(AgentConfiguration.RS485_LINUX_SERIAL_PORT, "/dev/ttys0");
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
        saveAgentSettings();
    }

    private void clearUpdateIntervalFromAgentSettings() {
        if (getAgentSettings() != null) {
            validateGenericSettings().setUpdateInterval(null);
        }
        saveAgentSettings();
    }

    private void saveCurrentWifiUpdateIntervalInAgentSettings() {
        if (getAgentSettings() != null) {
            validateGenericSettings().setWifiUpdateInterval(getWifiUpdateIntervalSeconds());
        }
        saveAgentSettings();
    }

    private void clearWifiUpdateIntervalFromAgentSettings() {
        if (getAgentSettings() != null) {
            validateGenericSettings().setWifiUpdateInterval(null);
        }
        saveAgentSettings();
    }


    private void saveCurrentProcessorTypeInAgentSettings() {
        if (getAgentSettings() != null) {
            validateGenericSettings().setRs485ControllerType(getRS485ControllerType());
        }
        saveAgentSettings();
    }

    private void clearProcessorTypeFromAgentSettings() {
        if (getAgentSettings() != null) {
            validateGenericSettings().setUpdateInterval(null);
        }
        saveAgentSettings();
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

        if (agentSettings != null && agentSettings.getGenericSettings() != null && !Strings.isNullOrEmpty(agentSettings.getGenericSettings().getRs485ControllerType())) {
            setRS485ControllerType(agentSettings.getGenericSettings().getRs485ControllerType());
        }
    }

    public int getUpdateIntervalSeconds() {
        return (int) (updateInterval.get() / 1000L);
    }

    public int getWifiUpdateIntervalSeconds() {
        return (int) (wifiStatUpdateInterval.get() / 1000L);
    }

}
