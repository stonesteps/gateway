/*
 * Copyright (c) Triton Services. All rights reserved. http://www.tritonsvc.com
 *
 */
package com.tritonsvc.gateway;

import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableMap;
import com.google.common.primitives.Ints;
import com.tritonsvc.agent.AgentConfiguration;
import com.tritonsvc.agent.MQTTCommandProcessor;
import com.tritonsvc.gateway.DeviceRegistration.DeviceRegistrationSerializer;
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
import jdk.dio.DeviceManager;
import jdk.dio.uart.UART;
import jdk.dio.uart.UARTConfig;
import org.mapdb.DB;
import org.mapdb.DBMaker;
import org.mapdb.Serializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static com.google.common.collect.Lists.newArrayList;
import static com.google.common.collect.Maps.newHashMap;

/**
 * Gateway Agent processing, collects WSN data also.
 */
public class BWGProcessor extends MQTTCommandProcessor {

    public static final String DYNAMIC_DEVICE_OID_PROPERTY = "device.MAC.DEVICE_NAME.oid";
    private static final long MAX_NEW_REG_WAIT_TIME = 120000;
    private static final long MAX_REG_LIFETIME = 240000;
    private static final long MAX_PANEL_REQUEST_INTERIM = 30000;
    private static Logger LOGGER = LoggerFactory.getLogger(BWGProcessor.class);
    final ReentrantReadWriteLock regLock = new ReentrantReadWriteLock();
    private DB mapDb;
    private Map<String, DeviceRegistration> registeredHwIds;
    private Properties configProps;
    private String gwSerialNumber;
    private OidProperties oidProperties = new OidProperties();
    private RS485DataHarvester rs485DataHarvester;
    private RS485MessagePublisher rs485MessagePublisher;
    private UART rs485Uart;
    private String homePath;
    private AtomicLong lastSpaDetailsSent = new AtomicLong(0);
    private AtomicLong lastPanelRequestSent = new AtomicLong(0);
    private static List<ComponentType> quadStateComponents = newArrayList(ComponentType.BLOWER, ComponentType.LIGHT);
    private static List<ComponentType> triStateComponents = newArrayList(ComponentType.PUMP);


    @Override
    public void handleShutdown() {
        mapDb.close();
        try {rs485Uart.stopReading();} catch (Exception ex) {}
        try {rs485Uart.stopWriting();} catch (Exception ex) {}
        try {rs485Uart.close();} catch (Exception ex) {}
    }

    @Override
    public void handleStartup(String gwSerialNumber, Properties configProps, String homePath, ScheduledExecutorService executorService) {
        // persistent key/value db
        this.gwSerialNumber = gwSerialNumber;
        this.configProps = configProps;
        this.homePath = homePath;

        generateMapDb();
        setUpRS485();
        validateOidProperties();
        obtainSpaRegistration();

        rs485MessagePublisher = new RS485MessagePublisher(this);
        rs485DataHarvester = new RS485DataHarvester(this, rs485MessagePublisher);
        executorService.execute(new WSNDataHarvester(this));
        executorService.execute(rs485DataHarvester);

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
            commitData();
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
                Objects.equals(getRegisteredHWIds().get(originatorId).getHardwareId(),hardwareId)) {
            LOGGER.info("confirmed registration state in cloud for spa id = {}", hardwareId);
            return;
        }

        if (getRegisteredHWIds().containsKey(originatorId)) {
            DeviceRegistration registered = getRegisteredHWIds().get(originatorId);
            registered.setHardwareId(hardwareId);
            registered.getMeta().put("apSSID", response.hasP2PAPSSID() ? response.getP2PAPPassword() : null);
            registered.getMeta().put("apPassword", response.hasP2PAPPassword() ? response.getP2PAPPassword() : null);
            getRegisteredHWIds().put(originatorId, registered);
            commitData();
            LOGGER.info("received successful spa registration, originatorid {} for hardwareid {} ", originatorId, hardwareId);

        } else {
            LOGGER.info("received spa registration {} for hardwareid {} that did not have a previous code for ", originatorId, hardwareId);
        }
    }

    @Override
    public void handleDownlinkCommand(Request request, String hardwareId, String originatorId) {
        if (request == null || !request.hasRequestType()) {
            LOGGER.error("Request is null, not processing, []", originatorId);
            sendAck(hardwareId, originatorId, AckResponseCode.ERROR, "gateway has not registered with controller yet");
            return;
        }

        if (rs485DataHarvester.getRegisteredAddress() == null) {
            LOGGER.error("received request {}, gateway has not registered with controller yet ", request.getRequestType().name());
            sendAck(hardwareId, originatorId, AckResponseCode.ERROR, "gateway has not registered with controller yet");
            return;
        }

        LOGGER.info("received downlink command from cloud {}, originatorid = {}", request.getRequestType().name(), originatorId);
        sendAck(hardwareId, originatorId, AckResponseCode.RECEIVED, null);

        try {
            if (request.getRequestType().equals(RequestType.HEATER)) {
                updateHeater(request.getMetadataList(), rs485DataHarvester.getRegisteredAddress(), originatorId, hardwareId, rs485DataHarvester.requiresCelsius());
            } else {
                rs485DataHarvester.arePanelCommandsSafe(false);
                switch (request.getRequestType()) {
                    case PUMPS:
                        updatePeripherlal(request.getMetadataList(), rs485DataHarvester.getRegisteredAddress(), originatorId, hardwareId, "kJets<port>MetaButton", ComponentType.PUMP);
                        break;
                    case CIRC_PUMP:
                        updateReservedComponent(request.getMetadataList(), originatorId, hardwareId, ComponentType.CIRCULATION_PUMP, ButtonCode.kPump0MetaButton);
                        break;
                    case LIGHTS:
                        updatePeripherlal(request.getMetadataList(), rs485DataHarvester.getRegisteredAddress(), originatorId, hardwareId, "kLight<port>MetaButton", ComponentType.LIGHT);
                        break;
                    case BLOWER:
                        updatePeripherlal(request.getMetadataList(), rs485DataHarvester.getRegisteredAddress(), originatorId, hardwareId, "kBlower<port>MetaButton", ComponentType.BLOWER);
                        break;
                    case MISTER:
                        updatePeripherlal(request.getMetadataList(), rs485DataHarvester.getRegisteredAddress(), originatorId, hardwareId, "kMister<port>MetaButton", ComponentType.MISTER);
                        break;
                    case FILTER:
                        updateFilter(request.getMetadataList(), rs485DataHarvester.getRegisteredAddress(), originatorId, hardwareId);
                        break;
                    case OZONE:
                        updateReservedComponent(request.getMetadataList(), originatorId, hardwareId, ComponentType.OZONE, ButtonCode.kOzoneMetaButton);
                        break;
                    case MICROSILK:
                        updateReservedComponent(request.getMetadataList(), originatorId, hardwareId, ComponentType.MICROSILK, ButtonCode.kMicroSilkQuietMetaButton);
                        break;
                    case AUX:
                        updatePeripherlal(request.getMetadataList(), rs485DataHarvester.getRegisteredAddress(), originatorId, hardwareId, "kOption<port>MetaButton", ComponentType.AUX);
                        break;
                    default:
                        sendAck(hardwareId, originatorId, AckResponseCode.ERROR, "not supported");
                }
            }
        }
        catch (Exception ex) {
            LOGGER.error("had problem when sending a command ", ex);
            sendAck(hardwareId, originatorId, AckResponseCode.ERROR, ex.getMessage());
            return;
        }
    }

    private void updateHeater(final List<RequestMetadata> metadataList, byte registeredAddress, String originatorId, String hardwareId, boolean celsius) throws Exception{
        rs485DataHarvester.arePanelCommandsSafe(true);
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
                        (new BigDecimal((5.0/9.0)*(temperature - 32)).setScale(1, BigDecimal.ROUND_HALF_UP).doubleValue()))
                        .setScale(0, BigDecimal.ROUND_HALF_UP).intValue();
            }
            rs485DataHarvester.prepareForTemperatureChangeRequest(temperature);
            rs485MessagePublisher.setTemperature(temperature, registeredAddress, originatorId, hardwareId);
        } else {
            throw new RS485Exception("Update heater command did not have required metadata param: " + SpaCommandAttribName.DESIREDTEMP.name());
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

        ComponentInfo currentState = rs485DataHarvester.getComponentState(componentType, 0);
        if (currentState == null) {
            LOGGER.error("Request for state for {} component was not found, aborting command", componentType.name());
            throw new RS485Exception(componentType.name() + " Device is not installed, cannot submit command for it");
        }

        if (Objects.equals(desiredState, currentState.getCurrentState())) {
            LOGGER.info("Request to change {} to {} was already current state, not sending rs485 command" , componentType.name(), desiredState);
            sendAck(hardwareId, originatorId, AckResponseCode.OK, null);
            return;
        }

        rs485MessagePublisher.sendButtonCode(buttonCode, rs485DataHarvester.getRegisteredAddress(), originatorId, hardwareId);
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
        rs485MessagePublisher.initiateFilterCycleRequest(port, durationMinutes, registeredAddress, originatorId, hardwareId);
    }

    private void updatePeripherlal(final List<RequestMetadata> metadataList, final byte registeredAddress,
                                   final String originatorId, final String hardwareId, final String buttonCodeTemplate,
                                   ComponentType componentType) throws Exception {

        RequiredParams params = collectRequiredParams(metadataList, componentType.name());
        ButtonCode deviceButton = ButtonCode.valueOf(buttonCodeTemplate.replaceAll("<port>", Integer.toString(params.getPort())));

        ComponentInfo currentState = rs485DataHarvester.getComponentState(componentType, params.getPort());
        if (currentState == null) {
            LOGGER.error("Request for state for port {} of component {} was not found, aborting command", params.getPort(), componentType.name());
            throw new RS485Exception("Device command for " + componentType.name() + " did not have required port " + params.getPort() + "  and desiredState " + params.getDesiredState());
        }

        if (Objects.equals(params.getDesiredState(), currentState.getCurrentState())) {
            LOGGER.info("Request to change {} to {} was already current state, not sending rs485 command" , componentType.name(), params.getDesiredState());
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
            rs485MessagePublisher.sendButtonCode(deviceButton, registeredAddress, originatorId, hardwareId);
            return;
        }

        // this sends multiple button commands to get to the desired state within available states for compnonent
        while (currentIndex != desiredIndex) {
            rs485MessagePublisher.sendButtonCode(deviceButton, registeredAddress, originatorId, hardwareId);
            currentIndex = (currentIndex + 1) % availableStates.size();
        }
    }

    @Override
    public void handleUplinkAck(UplinkAcknowledge ack, String originatorId) {
        //TODO - check if ack.NOT_REGISTERED and send up a registration if so
    }

    @Override
    public void processDataHarvestIteration() {
        DeviceRegistration registeredSpa = obtainSpaRegistration();
        if (registeredSpa.getHardwareId() == null) {
            LOGGER.info("skipping data harvest, spa gateway has not been registered");
            return;
        }

        // make sure the controller is registered as a compoenent to cloud
        obtainControllerRegistration(registeredSpa.getHardwareId());

        if (rs485DataHarvester.getRegisteredAddress() == null) {
            LOGGER.info("skipping data harvest, gateway has not registered over 485 bus with spa controller yet");
            return;
        }

        boolean locked = false;
        try {
            rs485DataHarvester.getLatestSpaInfoLock().readLock().lockInterruptibly();
            locked = true;
            if (rs485DataHarvester.getLatestSpaInfo().hasController() == false) {
                throw new RS485Exception("panel update message has not been received yet, cannot generate spa state yet.");
            }

            if ((rs485DataHarvester.getLatestSpaInfo().hasComponents() == false ||
                    rs485DataHarvester.getLatestSpaInfo().hasSystemInfo() == false ||
                    rs485DataHarvester.getLatestSpaInfo().hasSetupParams() == false) &&
                    System.currentTimeMillis() - lastPanelRequestSent.get() > MAX_PANEL_REQUEST_INTERIM) {
                rs485MessagePublisher.sendPanelRequest(rs485DataHarvester.getRegisteredAddress(), null);
                LOGGER.info("do not have DeviceConfig, SystemInfo, SetupParams yet, sent panel request");
                lastPanelRequestSent.set(System.currentTimeMillis());
            }

            // this loop runs often(once every 3 seconds), but only send up to cloud when timestamps on state data change
            if ( rs485DataHarvester.getLatestSpaInfo().hasLastUpdateTimestamp() && lastSpaDetailsSent.get() != rs485DataHarvester.getLatestSpaInfo().getLastUpdateTimestamp()) {
                getCloudDispatcher().sendUplink(registeredSpa.getHardwareId(), null, UplinkCommandType.SPA_STATE, rs485DataHarvester.getLatestSpaInfo());
                lastSpaDetailsSent.set(rs485DataHarvester.getLatestSpaInfo().getLastUpdateTimestamp());
                LOGGER.info("Finished data harvest periodic iteration, sent spa state to cloud");
            }
        } catch (Exception ex) {
            LOGGER.error("error while processing data harvest", ex);
        } finally {
            if (locked) {
                rs485DataHarvester.getLatestSpaInfoLock().readLock().unlock();
            }
        }
    }

    /**
     * get the oid properties mapping in config file
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
     * @return
     */
    public UART getRS485UART() {
        return rs485Uart;
    }

    /**
     * push any memory changes into file for db
     */
    public void commitData() {
        try {
            mapDb.commit();
        } catch (Throwable th) {
            // this is extreme, but if the map gets in a bad state in memory,
            // this is best way to allow it to get back up
            try {mapDb.close();} catch (Throwable th2){}
            generateMapDb();
        }
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
     * @return
     */
    public DeviceRegistration obtainSpaRegistration() {
        return sendRegistration(null, gwSerialNumber, "gateway", newHashMap());
    }

    /**
     * initiate a cloud reg for the spa controller
     * @param spaHardwareId
     * @return
     */
    public DeviceRegistration obtainControllerRegistration(String spaHardwareId) {
        return sendRegistration(spaHardwareId, gwSerialNumber, "controller", newHashMap());
    }

    /**
     * initiate a cloud reg for a mote
     * @param spaHardwareId
     * @param macAddress
     * @return
     */
    public DeviceRegistration obtainMoteRegistration(String spaHardwareId, String macAddress) {
        return sendRegistration(spaHardwareId, gwSerialNumber, "mote", ImmutableMap.of("mac", macAddress));
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
    public void init(Properties props) {
        // nothing here
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

    private void generateMapDb() {
        mapDb = DBMaker.fileDB(new File(homePath + File.separator + "spa_repo.dat"))
                .closeOnJvmShutdown()
                .encryptionEnable("password")
                .make();

        registeredHwIds = mapDb.hashMap("registeredHwIds", Serializer.STRING, new DeviceRegistrationSerializer());
    }

    private DeviceRegistration sendRegistration(String parentHwId, String gwSerialNumber, String deviceTypeName, Map<String, String> identityAttributes) {
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
            commitData();
            super.sendRegistration(parentHwId, gwSerialNumber, deviceTypeName, identityAttributes, registrationHashCode);
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
        int baudRate = Ints.tryParse(configProps.getProperty(AgentConfiguration.RS485_LINUX_SERIAL_PORT_BAUD,"")) != null ?
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
            rs485Uart = DeviceManager.open(config);
        }
        catch (Throwable ex) {
            throw Throwables.propagate(ex);
        }

        LOGGER.info("initialized rs 485 serial port {}", serialPort);
    }
}
