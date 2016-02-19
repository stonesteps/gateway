/*
 * Copyright (c) Triton Services. All rights reserved. http://www.tritonsvc.com
 *
 */
package com.tritonsvc.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableMap;
import com.tritonsvc.agent.MQTTCommandProcessor;
import com.tritonsvc.gateway.wsn.WsnData;
import com.tritonsvc.gateway.wsn.WsnValue;
import com.tritonsvc.spa.communication.proto.Bwg.Downlink.Model.RegistrationAckState;
import com.tritonsvc.spa.communication.proto.Bwg.Downlink.Model.RegistrationResponse;
import com.tritonsvc.spa.communication.proto.Bwg.Downlink.Model.Request;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.Model.DownlinkAcknowledge;
import org.mapdb.DB;
import org.mapdb.DBMaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.zeromq.ZMQ;

import java.io.File;
import java.io.IOException;
import java.util.Date;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
import java.util.concurrent.ScheduledExecutorService;

import static com.google.common.collect.Maps.newHashMap;
import static org.zeromq.ZMQ.PollItem;
import static org.zeromq.ZMQ.Poller;
import static org.zeromq.ZMQ.Socket;
import static org.zeromq.ZMQ.poll;

/**
 * Gateway Agent processing, collects WSN data also.
 * 
 */
public class BWGProcessor extends MQTTCommandProcessor {

	private static Logger LOGGER = LoggerFactory.getLogger(BWGProcessor.class);
	private ZMQ.Context context = ZMQ.context(1);
    private DB mapDb;
	private Map<String, DeviceRegistration> registeredHwIds;
	private Properties configProps;
    private String gwSerialNumber;
    private OidProperties oidProperties = new OidProperties();
	private static final String DYNAMIC_DEVICE_OID_PROPERTY = "device.MAC.DEVICE_NAME.oid";
	private static final String DATA_HARVEST_SUBSCRIPTION_ADDRESS = "wsn.data.harvest.subscription.address";
    private static final long MAX_REG_WAIT_TIME = 120000;

    @Override
    public void handleShutdown() {
        mapDb.close();
        context.term();
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
		//TODO
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
        // TODO the pump will be registered in here once the RFID tag for the pump appears
        // TODO get the controller board state message from rs-485, fill out properties and measurements
        Map<String, String> meta = newHashMap();
        Map<String, Double> measurement = newHashMap();
        setControllerTemps(meta, measurement);
        meta.put("comment", "controller data harvest");

        sendMeasurements(registeredController.getHardwareId(), null, measurement, new Date().getTime(), meta);

        // TODO get the nfc tag data for pumps and register pumps
        LOGGER.info("Sent harvest periodic reports");
    }

    Socket createWSNSubscriberSocket() {
        Socket subscriber = context.socket(ZMQ.SUB);
        subscriber.setRcvHWM(0);
        subscriber.connect("tcp://" + configProps.getProperty(DATA_HARVEST_SUBSCRIPTION_ADDRESS));
        subscriber.subscribe("".getBytes());
        return subscriber;
    }

    void sendWSNDataToCloud(String json) throws IOException {
        DeviceRegistration registeredSpa = obtainSpaRegistration();
        if (registeredSpa .getHardwareId() == null) {
            LOGGER.info("skipping data harvest, spa gateway has not been registered");
            return;
        }

        ObjectMapper mapper = new ObjectMapper();
        WsnData wsnData = mapper.readValue(json, WsnData.class);

        DeviceRegistration registeredMote = obtainMoteRegistration(registeredSpa.getHardwareId(), wsnData.getMac());
        if (registeredMote.getHardwareId() == null) {
            LOGGER.info("skipping wsn data harvest, mote %s has not been registered", wsnData.getMac());
            return;
        }

        Map<String, String> meta = newHashMap();
        meta.put("receivedDate", Long.toString(wsnData.getReceivedUnixTimestamp() * 1000));
        if (wsnData.getRssi() != null) {
            meta.put("rssi_quality", Double.toString(wsnData.getRssi().getQuality()));
            meta.put("rssi_ul", Double.toString(wsnData.getRssi().getUplink()));
            meta.put("rssi_dl", Double.toString(wsnData.getRssi().getDownlink()));
        }

        String safeMacKey = wsnData.getMac().replaceAll(":","").toLowerCase();
        for (WsnValue wsnValue : wsnData.getValues()) {
            String oid = configProps.getProperty(DYNAMIC_DEVICE_OID_PROPERTY
                    .replaceAll("MAC",safeMacKey)
                    .replaceAll("DEVICE_NAME", wsnValue.getDeviceName()));

            if (oid == null) {
                LOGGER.info("Unable to send sensor data to cloud, no oid and specId properties for it's mac address " +
                        safeMacKey + " and device name " + wsnValue.getDeviceName() + " were found in config.properties");
                continue;
            }

            Map<String, Double> measurement = newHashMap();
            measurement.put(oid, wsnValue.getValue());
            long timestamp = wsnData.getRecordedUnixTimestamp() != null ? wsnData.getRecordedUnixTimestamp() * 1000 : wsnData.getReceivedUnixTimestamp() * 1000;
            sendMeasurements(registeredMote.getHardwareId(), null, measurement, timestamp, meta);
            LOGGER.info(" sent measurement for mote %s, registered id %s %s %s", wsnValue.getDeviceName(), registeredMote.getHardwareId(), oid, Double.toString(wsnValue.getValue()));
        }
    }

    String waitForWSNData(Socket client, int timeout) {
        PollItem items[] = {new PollItem(client, Poller.POLLIN)};
        int rc = poll(items, timeout);
        if (rc == -1) {
            return null; //  Interrupted
        }

        if (items[0].isReadable()) {
            //  We got a msg from the ZeroMQ socket, it's a new WSN data message
            return client.recvStr();
        } else {
            LOGGER.info("timed out waiting for wsn data");
            return null;
        }
    }

    private DeviceRegistration obtainSpaRegistration() {
        return sendRegistration(null, "gateway", ImmutableMap.of("serialNumber", gwSerialNumber));
    }

    private DeviceRegistration obtainControllerRegistration(String spaHardwareId) {
        return sendRegistration(spaHardwareId, "controller", newHashMap());
    }

    private DeviceRegistration obtainMoteRegistration(String spaHardwareId, String macAddress) {
        return sendRegistration(spaHardwareId, "mote", ImmutableMap.of("mac", macAddress));
    }

    private void setControllerTemps(Map<String, String> meta, Map<String, Double> measurement) {
        // TODO should be passing the controller panel update message into here, it has the pre/post temps
        double pre = new Random().nextDouble();
        double post = new Random().nextDouble();

        if (oidProperties.getPreHeaterTemp() != null) {
            measurement.put(oidProperties.getPreHeaterTemp(), pre);
        }
        if (oidProperties.getPostHeaterTemp() != null) {
            measurement.put(oidProperties.getPostHeaterTemp(), post);
        }
        meta.put("heater_temp_delta", Double.toString(Math.abs(pre - post)));
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
        // only send reg if the device has not reg'd yet
        String registrationHashCode = generateRegistrationKey(parentHwId, deviceTypeName, identityAttributes);
        if (registeredHwIds.containsKey(registrationHashCode)) {
            DeviceRegistration registeredDevice = registeredHwIds.get(registrationHashCode);
            // if the reg is too old, send another one
            if (registeredDevice.getHardwareId() != null || System.currentTimeMillis() - registeredDevice.getLastTime() < MAX_REG_WAIT_TIME) {
                return registeredDevice;
            }
        }

        DeviceRegistration registeredDevice = new DeviceRegistration();
        registeredHwIds.put(registrationHashCode, registeredDevice);
        mapDb.commit();
        super.sendRegistration(parentHwId, deviceTypeName, identityAttributes, registrationHashCode);
        return registeredDevice;
    }
}
