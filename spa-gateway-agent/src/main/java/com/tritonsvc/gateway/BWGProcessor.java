/*
 * Copyright (c) Triton Services. All rights reserved. http://www.tritonsvc.com
 *
 */
package com.tritonsvc.gateway;

import com.bwg.iot.model.SpaCommandAttributeName;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableMap;
import com.google.common.primitives.Ints;
import com.tritonsvc.agent.AgentConfiguration;
import com.tritonsvc.agent.MQTTCommandProcessor;
import com.tritonsvc.gateway.DeviceRegistration.DeviceRegistrationSerializer;
import com.tritonsvc.spa.communication.proto.Bwg;
import com.tritonsvc.spa.communication.proto.Bwg.AckResponseCode;
import com.tritonsvc.spa.communication.proto.Bwg.Downlink.Model.RegistrationAckState;
import com.tritonsvc.spa.communication.proto.Bwg.Downlink.Model.RegistrationResponse;
import com.tritonsvc.spa.communication.proto.Bwg.Downlink.Model.Request;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.Model.DownlinkAcknowledge;
import jdk.dio.DeviceManager;
import jdk.dio.uart.UART;
import jdk.dio.uart.UARTConfig;
import org.mapdb.DB;
import org.mapdb.DBMaker;
import org.mapdb.Serializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static com.google.common.collect.Maps.newHashMap;

/**
 * Gateway Agent processing, collects WSN data also.
 * 
 */
public class BWGProcessor extends MQTTCommandProcessor {

    public static final String DYNAMIC_DEVICE_OID_PROPERTY = "device.MAC.DEVICE_NAME.oid";
    private static final long MAX_REG_WAIT_TIME = 120000;
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
                    updateHeater(request.getMetadataList(), rs485DataHarvester.getRegisteredAddress());
                    break;
                default:
                    sendAck(hardwareId, originatorId, AckResponseCode.ERROR, "not supported");
            }
        }
        catch (RS485Exception ex) {
            LOGGER.error("had rs485 problem when sending a command ", ex);
            sendAck(hardwareId, originatorId, AckResponseCode.ERROR, ex.getMessage());
            return;
        }

    }

    private void updateHeater(final List<Bwg.Metadata> metadataList, byte registeredAddress) throws RS485Exception{
        Integer temperature = null;

        if (metadataList != null && metadataList.size() > 0) {
            for (final Bwg.Metadata metadata : metadataList) {
                if (SpaCommandAttributeName.DESIRED_TEMP.equals(metadata.getName())) {
                    temperature = new Integer(metadata.getValue());
                }
            }
        }

        if (temperature != null) {
            rs485MessagePublisher.setTemperature(temperature, registeredAddress);
        } else {
            throw new RS485Exception("Update heater command did not have required metadata param: " + SpaCommandAttributeName.DESIRED_TEMP);
        }
    }

    @Override
    public void handleUplinkAck(DownlinkAcknowledge ack, String originatorId) {
        //TODO
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
        rs485DataHarvester.sendPeriodicSpaInfo(registeredController.getHardwareId());
        LOGGER.info("Finished data harvest periodic iteration");
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
            regLock.readLock().lock();
            readLocked = true;
            // only send reg if the device has not reg'd yet
            String registrationHashCode = generateRegistrationKey(parentHwId, deviceTypeName, identityAttributes);
            DeviceRegistration registeredDevice = getValidRegistration(registrationHashCode);
            if (registeredDevice != null) {
                return registeredDevice;
            }

            regLock.readLock().unlock();
            readLocked = false;
            regLock.writeLock().lock();
            writeLocked = true;
            registeredDevice = getValidRegistration(registrationHashCode);
            if (registeredDevice != null) {
                return registeredDevice;
            }

            registeredDevice = new DeviceRegistration();
            getRegisteredHWIds().put(registrationHashCode, registeredDevice);
            commitData();
            super.sendRegistration(parentHwId, deviceTypeName, identityAttributes, registrationHashCode);
            return registeredDevice;
        } finally {
            if (readLocked) {
                regLock.readLock().unlock();
            }
            if (writeLocked) {
                regLock.writeLock().unlock();
            }
        }
    }

    private DeviceRegistration getValidRegistration(String registrationHashCode) {
        if (getRegisteredHWIds().containsKey(registrationHashCode)) {
            DeviceRegistration registeredDevice = getRegisteredHWIds().get(registrationHashCode);
            // if the reg is too old, attempt another one
            if (registeredDevice.getHardwareId() != null || System.currentTimeMillis() - registeredDevice.getLastTime() < MAX_REG_WAIT_TIME) {
                return registeredDevice;
            }
        }
        return null;
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
