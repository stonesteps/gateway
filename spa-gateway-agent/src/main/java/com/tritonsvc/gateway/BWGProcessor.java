/*
 * Copyright (c) Triton Services. All rights reserved. http://www.tritonsvc.com
 *
 */
package com.tritonsvc.gateway;

import com.google.common.collect.ImmutableMap;
import com.tritonsvc.agent.MQTTCommandProcessor;
import com.tritonsvc.spa.communication.proto.Bwg.Downlink.Model.RegistrationAckState;
import com.tritonsvc.spa.communication.proto.Bwg.Downlink.Model.RegistrationResponse;
import com.tritonsvc.spa.communication.proto.Bwg.Downlink.Model.Request;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.Model.DownlinkAcknowledge;
import org.mapdb.DB;
import org.mapdb.DBMaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
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

	private static Logger LOGGER = LoggerFactory.getLogger(BWGProcessor.class);
    private DB mapDb;
	private Map<String, DeviceRegistration> registeredHwIds;
	private Properties configProps;
    private String gwSerialNumber;
    private OidProperties oidProperties = new OidProperties();
	public static final String DYNAMIC_DEVICE_OID_PROPERTY = "device.MAC.DEVICE_NAME.oid";
    private static final long MAX_REG_WAIT_TIME = 120000;
    private RS485DataHarvester rs485DataHarvester;
    private RS485MessagePublisher rs485MessagePublisher;
    final ReentrantReadWriteLock regLock = new ReentrantReadWriteLock();

    @Override
    public void handleShutdown() {
        mapDb.close();
    }

	@Override
	public void handleStartup(String gwSerialNumber, Properties configProps, String homePath, ScheduledExecutorService executorService) {
        // persistent key/value db
        this.gwSerialNumber = gwSerialNumber;
		mapDb = DBMaker.fileDB(new File(homePath + File.separator + "spa_repo.dat"))
        .closeOnJvmShutdown()
        .encryptionEnable("password")
        .make();

        registeredHwIds = mapDb.hashMap("registeredHwIds");
        validateOidProperties();

        obtainSpaRegistration();

        this.configProps = configProps;
        executorService.execute(new WSNDataHarvester(this));
        rs485DataHarvester = new RS485DataHarvester(this);
        executorService.execute(rs485DataHarvester);
        rs485MessagePublisher = new RS485MessagePublisher(this,rs485DataHarvester);
        LOGGER.info("finished startup.");
	}

    @Override
	public void handleRegistrationAck(RegistrationResponse response, String originatorId, String hardwareId) {

        if (response.getState() == RegistrationAckState.REGISTRATION_ERROR) {
            LOGGER.info("received registration error state %s", response.getErrorMessage());
            return;
        }
        if (registeredHwIds.containsKey(originatorId)) {
            DeviceRegistration registered = registeredHwIds.get(originatorId);
            registered.setHardwareId(hardwareId);
            registeredHwIds.put(originatorId, registered);
            mapDb.commit();
            LOGGER.info("received successful registration, originatorid %s for hardwareid %s ", originatorId, hardwareId);

        } else {
            LOGGER.info("received registration %s for hardwareid %s that did not have a previous code for ", originatorId, hardwareId);
        }
	}

    @Override
	public void handleDownlinkCommand(Request request, String originatorId) {
		//TODO-Marek: get message-processor changed to parse 'SetTargetTemperature' requests from
        // mongodb Requests collection, and marshal into a new bwg.proto downlink message for 'SetTargetTemperature',
        // and push out to mqtt downlink topic for specific spa.
        // Then, make sure it arrives here and parsed out as a new 'SetTargetTemperature' downlink message from bwg.proto
        // invoke call on rs485MessagePublisher.setTemperature() with real requested temp. Done.
        //
        // I'll be working on the impl of rs485MessagePublisher.setTemperature(78.0) at same time.

        // if request is a SetTargetTemp then:
            rs485MessagePublisher.setTemperature(78.0);
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

        // TODO - error handling if cloud side push fails -> Cacheing locally with mapDb

        rs485DataHarvester.sendPeriodicControllerMeasurements(registeredController.getHardwareId());

        LOGGER.info("Sent harvest periodic reports");
    }

    public OidProperties getOidProperties() {
        return oidProperties;
    }

    public Properties getConfigProps() {
        return configProps;
    }

    public DeviceRegistration obtainSpaRegistration() {
        return sendRegistration(null, "gateway", ImmutableMap.of("serialNumber", gwSerialNumber));
    }

    public DeviceRegistration obtainControllerRegistration(String spaHardwareId) {
        return sendRegistration(spaHardwareId, "controller", newHashMap());
    }

    public DeviceRegistration obtainMoteRegistration(String spaHardwareId, String macAddress) {
        return sendRegistration(spaHardwareId, "mote", ImmutableMap.of("mac", macAddress));
    }

    private void validateOidProperties() {
        String preOidPropName = DYNAMIC_DEVICE_OID_PROPERTY
                .replaceAll("MAC","controller")
                .replaceAll("DEVICE_NAME", "pre_heat");
        String postOidPropName = DYNAMIC_DEVICE_OID_PROPERTY
                .replaceAll("MAC","controller")
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
            registeredHwIds.put(registrationHashCode, registeredDevice);
            mapDb.commit();
            super.sendRegistration(parentHwId, deviceTypeName, identityAttributes, registrationHashCode);
            return registeredDevice;
        }
        finally {
            if (readLocked) {
                regLock.readLock().unlock();
            }
            if (writeLocked) {
                regLock.writeLock().unlock();
            }
        }
    }

    private DeviceRegistration getValidRegistration(String registrationHashCode) {
        if (registeredHwIds.containsKey(registrationHashCode)) {
            DeviceRegistration registeredDevice = registeredHwIds.get(registrationHashCode);
            // if the reg is too old, send another one
            if (registeredDevice.getHardwareId() != null || System.currentTimeMillis() - registeredDevice.getLastTime() < MAX_REG_WAIT_TIME) {
                return registeredDevice;
            }
        }
        return null;
    }
}
