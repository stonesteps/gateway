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
import com.tritonsvc.spa.communication.proto.Bwg.Downlink.Model.SpaCommandAttribName;
import com.tritonsvc.spa.communication.proto.Bwg.Downlink.Model.SpaRegistrationResponse;
import com.tritonsvc.spa.communication.proto.Bwg.Downlink.Model.UplinkAcknowledge;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.Model.Constants.ComponentType;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.Model.Constants.HeaterMode;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.Model.Constants.PanelDisplayCode;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.Model.Constants.TempRange;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.Model.SpaState;
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

import static com.google.common.collect.Maps.newHashMap;

/**
 * Gateway Agent processing, collects WSN data also.
 * 
 */
public class BWGProcessor extends MQTTCommandProcessor {

    public static final String DYNAMIC_DEVICE_OID_PROPERTY = "device.MAC.DEVICE_NAME.oid";
    private static final long MAX_NEW_REG_WAIT_TIME = 120000;
    private static final long MAX_REG_LIFETIME = 240000;
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

        if (response.getState() == RegistrationAckState.ALREADY_REGISTERED) {
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

        if (response.getState() == RegistrationAckState.ALREADY_REGISTERED) {
            LOGGER.info("confirmed registration state in cloud for spa id = {}", hardwareId);
            return;
        }

        if (getRegisteredHWIds().containsKey(originatorId)) {
            DeviceRegistration registered = getRegisteredHWIds().get(originatorId);
            registered.setHardwareId(hardwareId);
            registered.getMeta().put("apSSID", response.getP2PAPSSID());
            registered.getMeta().put("apPassword", response.getP2PAPPassword());
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

        try {
            switch (request.getRequestType()) {
                case HEATER:
                    rs485DataHarvester.arePanelCommandsSafe(true);
                    updateHeater(request.getMetadataList(), rs485DataHarvester.getRegisteredAddress(), originatorId, hardwareId, rs485DataHarvester.requiresCelsius());
                    break;
                case PUMPS:
                    rs485DataHarvester.arePanelCommandsSafe(false);
                    updatePumps(request.getMetadataList(), rs485DataHarvester.getRegisteredAddress(), originatorId, hardwareId);
                    break;
                default:
                    sendAck(hardwareId, originatorId, AckResponseCode.ERROR, "not supported");
            }
        }
        catch (Exception ex) {
            LOGGER.error("had problem when sending a command ", ex);
            sendAck(hardwareId, originatorId, AckResponseCode.ERROR, ex.getMessage());
            return;
        }

        LOGGER.info("received downlink command from cloud and queued it up for rs485 transmission {}, originatorid = {}, sent RECEIVED ack back to cloud", request.getRequestType().name(), originatorId);
        sendAck(hardwareId, originatorId, AckResponseCode.RECEIVED, null);
    }

    private void updateHeater(final List<RequestMetadata> metadataList, byte registeredAddress, String originatorId, String hardwareId, boolean celsius) throws Exception{
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

    private void updatePumps(final List<RequestMetadata> metadataList, byte registeredAddress, String originatorId, String hardwareId) throws Exception{
        Integer port = null;
        String desiredState = null;

        if (metadataList != null && metadataList.size() > 0) {
            for (final RequestMetadata metadata : metadataList) {
                if (SpaCommandAttribName.PORT.equals(metadata.getName())) {
                    Integer temp = new Integer(metadata.getValue()) + 1;
                    if (temp > 0 && temp < 9) {
                        port = temp;
                    } else {
                        throw new RS485Exception("Invalid pump port param, must be between 0 and 7 inclusive: " + (temp-1));
                    }
                }
                if (SpaCommandAttribName.DESIREDSTATE.equals(metadata.getName())) {
                    desiredState = metadata.getValue();
                }
            }
        }

        if (port != null && desiredState != null) {
            // make an attempt to detect current state and not change if already set to desired
            // there's no certain way to know how many states a pump supports, so if current and desired are different, just send the
            // button command regardless of the desiredState value.
            String currentState = rs485DataHarvester.getComponentState(ComponentType.PUMP, port);
            if (Objects.equals(desiredState, currentState)) {
                LOGGER.info("Request to change pump {} to {} was already current state, not sending rs485 command" );
                return;
            }

            ButtonCode pumpButton = ButtonCode.valueOf("kJets<port>MetaButton".replaceAll("<port>", Integer.toString(port)));
            rs485MessagePublisher.sendButtonCode(pumpButton, registeredAddress, originatorId, hardwareId);
        } else {
            throw new RS485Exception("Update pumps command did not have required port and desiredState param");
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

        DeviceRegistration registeredController = obtainControllerRegistration(registeredSpa.getHardwareId());
        if (registeredController.getHardwareId() == null) {
            LOGGER.info("skipping data harvest, spa controller has not been registered");
            return;
        }

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

            if (rs485DataHarvester.getLatestSpaInfo().hasComponents() == false ||
                    rs485DataHarvester.getLatestSpaInfo().hasSystemInfo() == false ||
                    rs485DataHarvester.getLatestSpaInfo().hasSetupParams() == false) {
                rs485MessagePublisher.sendPanelRequest(rs485DataHarvester.getRegisteredAddress(), null);
                LOGGER.info("do not have DeviceConfig, SystemInfo, SetupParams yet, sent panel request");
            }
            getCloudDispatcher().sendUplink(registeredController.getHardwareId(), null, UplinkCommandType.SPA_STATE, rs485DataHarvester.getLatestSpaInfo());
            LOGGER.info("Finished data harvest periodic iteration");
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
        return sendRegistration(null, "gateway", ImmutableMap.of("serialNumber", gwSerialNumber));
    }

    /**
     * initiate a cloud reg for the spa controller
     * @param spaHardwareId
     * @return
     */
    public DeviceRegistration obtainControllerRegistration(String spaHardwareId) {
        return sendRegistration(spaHardwareId, "controller", newHashMap());
    }

    /**
     * initiate a cloud reg for a mote
     * @param spaHardwareId
     * @param macAddress
     * @return
     */
    public DeviceRegistration obtainMoteRegistration(String spaHardwareId, String macAddress) {
        return sendRegistration(spaHardwareId, "mote", ImmutableMap.of("mac", macAddress));
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

    private void generateMapDb() {
        mapDb = DBMaker.fileDB(new File(homePath + File.separator + "spa_repo.dat"))
                .closeOnJvmShutdown()
                .encryptionEnable("password")
                .make();

        registeredHwIds = mapDb.hashMap("registeredHwIds", Serializer.STRING, new DeviceRegistrationSerializer());
    }

    private DeviceRegistration sendRegistration(String parentHwId, String deviceTypeName, Map<String, String> identityAttributes) {
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
            super.sendRegistration(parentHwId, deviceTypeName, identityAttributes, registrationHashCode);
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
        catch (Exception ex) {
            throw Throwables.propagate(ex);
        }

        LOGGER.info("initialized rs 485 serial port {}", serialPort);
    }
}
