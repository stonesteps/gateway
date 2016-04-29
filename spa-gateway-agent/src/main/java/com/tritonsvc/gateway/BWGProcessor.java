/*
 * Copyright (c) Triton Services. All rights reserved. http://www.tritonsvc.com
 *
 */
package com.tritonsvc.gateway;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableMap;
import com.google.common.primitives.Ints;
import com.tritonsvc.agent.AgentConfiguration;
import com.tritonsvc.agent.MQTTCommandProcessor;
import com.tritonsvc.httpd.RegistrationInfoHolder;
import com.tritonsvc.httpd.WebServer;
import com.tritonsvc.model.AgentSettings;
import com.tritonsvc.model.GenericSettings;
import com.tritonsvc.spa.communication.proto.Bwg.AckResponseCode;
import com.tritonsvc.spa.communication.proto.Bwg.Downlink.Model.RegistrationAckState;
import com.tritonsvc.spa.communication.proto.Bwg.Downlink.Model.RegistrationResponse;
import com.tritonsvc.spa.communication.proto.Bwg.Downlink.Model.Request;
import com.tritonsvc.spa.communication.proto.Bwg.Downlink.Model.RequestMetadata;
import com.tritonsvc.spa.communication.proto.Bwg.Downlink.Model.RequestType;
import com.tritonsvc.spa.communication.proto.Bwg.Downlink.Model.SpaCommandAttribName;
import com.tritonsvc.spa.communication.proto.Bwg.Downlink.Model.SpaRegistrationResponse;
import com.tritonsvc.spa.communication.proto.Bwg.Downlink.Model.UplinkAcknowledge;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.Model.Components.BlowerComponent;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.Model.Components.LightComponent;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.Model.Components.PumpComponent;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.Model.Components.ToggleComponent;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.Model.Constants.ComponentType;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.UplinkCommandType;
import com.tritonsvc.spa.communication.proto.BwgHelper;
import jdk.dio.DeviceManager;
import jdk.dio.uart.UART;
import jdk.dio.uart.UARTConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.net.URL;
import java.util.Enumeration;
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
import java.util.jar.Manifest;

import static com.google.common.collect.Maps.newHashMap;

/**
 * Gateway Agent processing, collects WSN data also.
 */
public class BWGProcessor extends MQTTCommandProcessor implements RegistrationInfoHolder {

    public static final String DYNAMIC_DEVICE_OID_PROPERTY = "device.MAC.DEVICE_NAME.oid";
    private static final long MAX_NEW_REG_WAIT_TIME = 120000;
    private static final long MAX_REG_LIFETIME = 240000;
    private static final long MAX_PANEL_REQUEST_INTERIM = 30000;
    private static final long DEFAULT_UPDATE_INTERVAL = 0; //continuous

    private static Logger LOGGER = LoggerFactory.getLogger(BWGProcessor.class);
    private static Map<String, String> DEFAULT_EMPTY_MAP = newHashMap();
    final ReentrantReadWriteLock regLock = new ReentrantReadWriteLock();
    private Map<String, DeviceRegistration> registeredHwIds = newHashMap();
    private Properties configProps;
    private String gwSerialNumber;
    private OidProperties oidProperties = new OidProperties();
    private RS485DataHarvester rs485DataHarvester;
    private RS485MessagePublisher rs485MessagePublisher;
    private UART rs485Uart;
    private AtomicLong lastSpaDetailsSent = new AtomicLong(0);
    private AtomicLong lastPanelRequestSent = new AtomicLong(0);
    private AtomicLong updateInterval = new AtomicLong(DEFAULT_UPDATE_INTERVAL);
    private WebServer webServer = null;
    private ScheduledExecutorService es = null;
    private ScheduledFuture<?> intervalResetFuture = null;
    private Map<String, String> buildParams;
    private String rs485ControllerType = null;

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
        this.webServer = new WebServer(configProps, this, this);
        this.es = executorService;

        setupAgentSettings();
        setUpRS485();
        validateOidProperties();
        obtainSpaRegistration();
        setUpRS485Processors();

        executorService.execute(new WSNDataHarvester(this));
        executorService.execute(getRS485DataHarvester());
        buildParams = getBuildProps();

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

        try {
            if (request.getRequestType().equals(RequestType.HEATER)) {
                updateHeater(request.getMetadataList(), getRS485DataHarvester().getRegisteredAddress(), originatorId, hardwareId, getRS485DataHarvester().requiresCelsius());
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
                        updateReservedComponent(request.getMetadataList(), originatorId, hardwareId, ComponentType.OZONE, ButtonCode.kOzoneMetaButton);
                        break;
                    case MICROSILK:
                        updateReservedComponent(request.getMetadataList(), originatorId, hardwareId, ComponentType.MICROSILK, ButtonCode.kMicroSilkQuietMetaButton);
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
            getRS485DataHarvester().prepareForTemperatureChangeRequest(temperature);
            getRS485MessagePublisher().setTemperature(temperature, registeredAddress, originatorId, hardwareId);
        } else {
            throw new RS485Exception("Update heater command did not have required metadata param: " + SpaCommandAttribName.DESIREDTEMP.name());
        }
    }

    private void updateAgentSettings(final List<RequestMetadata> metadataList) {
        final Integer intervalSeconds = Ints.tryParse(BwgHelper.getRequestMetadataValue(SpaCommandAttribName.INTERVAL_SECONDS.name(), metadataList));
        final Integer durationMinutes = Ints.tryParse(BwgHelper.getRequestMetadataValue(SpaCommandAttribName.DURATION_MINUTES.name(), metadataList));
        final String rs485ControllerType = BwgHelper.getRequestMetadataValue(SpaCommandAttribName.RS485_CONTROLLER_TYPE.name(), metadataList);

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

        if (rs485ControllerType != null) {
            this.rs485ControllerType = rs485ControllerType;
            if (getRS485DataHarvester() != null) {
                getRS485DataHarvester().cancel();
            }
            setUpRS485Processors();
            es.execute(getRS485DataHarvester());
        }
    }

    private synchronized void setUpRS485Processors() {
        if (rs485ControllerType != null && rs485ControllerType.equalsIgnoreCase("JACUZZI")) {
            //TODO use new jacuzzi class here
        } else {
            setRS485MessagePublisher(new RS485MessagePublisher(this));
            setRS485DataHarvester(new RS485DataHarvester(this, getRS485MessagePublisher()));
        }
    }

    private void updateReservedComponent(final List<RequestMetadata> metadataList, String originatorId, String hardwareId, ComponentType componentType, ButtonCode buttonCode) throws Exception {
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

        getRS485MessagePublisher().sendButtonCode(buttonCode, getRS485DataHarvester().getRegisteredAddress(), originatorId, hardwareId);
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
        getRS485MessagePublisher().initiateFilterCycleRequest(port, durationMinutes, registeredAddress, originatorId, hardwareId);
    }

    private void updatePeripherlal(final List<RequestMetadata> metadataList, final byte registeredAddress,
                                   final String originatorId, final String hardwareId, final String buttonCodeTemplate,
                                   ComponentType componentType) throws Exception {

        RequiredParams params = collectRequiredParams(metadataList, componentType.name());
        ButtonCode deviceButton = ButtonCode.valueOf(buttonCodeTemplate.replaceAll("<port>", Integer.toString(params.getPort())));

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
            getRS485MessagePublisher().sendButtonCode(deviceButton, registeredAddress, originatorId, hardwareId);
            return;
        }

        // this sends multiple button commands to get to the desired state within available states for compnonent
        while (currentIndex != desiredIndex) {
            getRS485MessagePublisher().sendButtonCode(deviceButton, registeredAddress, originatorId, hardwareId);
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
            getRS485MessagePublisher().sendButtonCode(ButtonCode.kPump0MetaButton, registeredAddress, originatorId, hardwareId);
            return;
        }

        // this sends multiple button commands to get to the desired state within available states for compnonent
        while (currentIndex != desiredIndex) {
            getRS485MessagePublisher().sendButtonCode(ButtonCode.kPump0MetaButton, registeredAddress, originatorId, hardwareId);
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

            if ((getRS485DataHarvester().getLatestSpaInfo().hasComponents() == false ||
                    getRS485DataHarvester().getLatestSpaInfo().hasSystemInfo() == false ||
                    getRS485DataHarvester().getLatestSpaInfo().hasSetupParams() == false ||
                    getRS485DataHarvester().getLatestSpaInfo().getComponents().hasFilterCycle1() == false) &&
                    System.currentTimeMillis() - lastPanelRequestSent.get() > MAX_PANEL_REQUEST_INTERIM) {
                getRS485MessagePublisher().sendPanelRequest(getRS485DataHarvester().getRegisteredAddress(), null);
                LOGGER.info("do not have all DeviceConfig, SystemInfo, SetupParams, FilterCycle yet, sent panel request");
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
        Map<String, String> metaParams = newHashMap(buildParams);
        metaParams.put("BWG-Agent-RS485-Controller-Type", rs485ControllerType == null ? "NGSC" : rs485ControllerType);
        return sendRegistration(null, gwSerialNumber, "gateway", DEFAULT_EMPTY_MAP, buildParams);
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

    @VisibleForTesting
    void setRS485MessagePublisher(RS485MessagePublisher rs485MessagePublisher) {
        this.rs485MessagePublisher = rs485MessagePublisher;
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

    private void setUpRS485() {
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

    private void saveCurrentUpdateIntervalInAgentSettings() {
        if (getAgentSettings() != null) {
            GenericSettings genericSettings = getAgentSettings().getGenericSettings();
            if (genericSettings == null) {
                genericSettings = new GenericSettings();
                getAgentSettings().setGenericSettings(genericSettings);
            }
            genericSettings.setUpdateInterval(getUpdateIntervalSeconds());
        }
    }

    private void clearUpdateIntervalFromAgentSettings() {
        if (getAgentSettings() != null && getAgentSettings().getGenericSettings() != null) {
            getAgentSettings().getGenericSettings().setUpdateInterval(null);
        }
        saveAgentSettings();
    }

    private void setupAgentSettings() {
        final AgentSettings agentSettings = getAgentSettings();
        if (agentSettings != null && agentSettings.getGenericSettings() != null && agentSettings.getGenericSettings().getUpdateInterval() != null) {
            // translate seconds to milliseconds
            updateInterval.set(agentSettings.getGenericSettings().getUpdateInterval().longValue() * 1000L);
        }

        if (agentSettings != null && agentSettings.getGenericSettings() != null && agentSettings.getGenericSettings().getRs485ControllerType() != null) {
            // translate seconds to milliseconds
            rs485ControllerType = agentSettings.getGenericSettings().getRs485ControllerType();
        }
    }

    private Map<String, String> getBuildProps() {
        Map<String, String> params = newHashMap();
        try {
            Enumeration<URL> resources = getClass().getClassLoader().getResources("META-INF/MANIFEST.MF");
            while (resources.hasMoreElements()) {
                Manifest manifest = new Manifest(resources.nextElement().openStream());
                if (manifest.getMainAttributes().getValue("BWG-Version") != null ) {
                    params.put("BWG-Agent-Version", manifest.getMainAttributes().getValue("BWG-Version"));
                    params.put("BWG-Agent-Build-Number", manifest.getMainAttributes().getValue("BWG-Build-Number"));
                    params.put("BWG-Agent-SCM-Revision", manifest.getMainAttributes().getValue("BWG-SCM-Revision"));
                    break;
                }
            }
        } catch (Exception ex) {
            LOGGER.info("unable to obtain build info from jar");
        }
        return params;
    }

    public int getUpdateIntervalSeconds() {
        return (int) (updateInterval.get() / 1000L);
    }

}
