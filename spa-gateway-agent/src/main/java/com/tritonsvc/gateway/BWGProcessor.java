/*
 * Copyright (c) Triton Services. All rights reserved. http://www.tritonsvc.com
 *
 */
package com.tritonsvc.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tritonsvc.agent.MQTTCommandProcessor;
import com.tritonsvc.gateway.wsn.WsnData;
import com.tritonsvc.gateway.wsn.WsnValue;
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
import java.util.Set;
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
	private Set<String> registeredHwIds;
	private Properties configProps = null;
    private String spaSystemId = null;
    private String spaControllerId = null;
    private OidProperties oidProperties = new OidProperties();
	private static final String DYNAMIC_DEVICE_OID_PROPERTY = "device.MAC.DEVICE_NAME.oid";
	private static final String DATA_HARVEST_SUBSCRIPTION_ADDRESS = "wsn.data.harvest.subscription.address";

    @Override
    public void handleShutdown() {
        mapDb.close();
        context.term();
    }

	@Override
	public void handleStartup(String hardwareId, Properties configProps, String homePath, ScheduledExecutorService executorService) {
        spaSystemId = hardwareId;
        spaControllerId = "controller_" + spaSystemId;

        // persistent key/value db
		mapDb = DBMaker.fileDB(new File(homePath + File.separator + "spa_repo.dat"))
        .closeOnJvmShutdown()
        .encryptionEnable("password")
        .make();

        registeredHwIds = mapDb.hashSet("registeredHwIds");
        validateOidProperties();

		sendRegistration(null, hardwareId, "gateway");
		LOGGER.info("Sent registration information.");

        this.configProps = configProps;
        executorService.execute(new WSNDataHarvestRunner());
	}

    @Override
	public void handleRegistrationAck(RegistrationResponse response, String originatorId) {
		//TODO
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
        // TODO - error handling if cloud side push fails -> Cacheing locally with mapDb
        // TODO the pump will be registered in here once the RFID tag for the pump appears
        // TODO get the controller board state message from rs-485, fill out properties and measurements
        Map<String, String> meta = newHashMap();
        Map<String, Double> measurement = newHashMap();
        setControllerTemps(meta, measurement);
        meta.put("comment", "controller poll");

        sendRegistration(spaSystemId, spaControllerId, "controller");
        sendMeasurements(spaControllerId, null, measurement, new Date().getTime(), meta);

        // TODO get the nfc tag data for pumps and register pumps
        LOGGER.info("Sent harvest periodic reports");
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

    private void sendRegistration(String parentHwId, String hwId, String deviceTypeName) {
        // only send reg if the device has not reg'd yet
        if (registeredHwIds.contains(hwId)) {
            return;
        }

        registeredHwIds.add(hwId);
        mapDb.commit();
        sendRegistration(parentHwId, hwId, deviceTypeName, newHashMap());
    }

	private class WSNDataHarvestRunner implements Runnable {
        public void run() {
            Socket subscriber = null;
            while(stillRunning()) {
                try {
                    if (subscriber == null) {
                        subscriber = createSubscriberSocket();
                    }

                    while(true) {
                        String data = waitForWSNData(subscriber, 10000);
                        if (data != null) {
                            sendDataToCloud(data);
                            continue;
                        }
                        break;
                    }
                }
                catch (Throwable ex) {
                    LOGGER.info("harvest data listener got exception " + ex.getMessage());
                    if (subscriber != null) {
                        subscriber.close();
                        subscriber = null;
                    }
                    try {Thread.sleep(10000);} catch (InterruptedException ex2){}
                }
            }
            if (subscriber != null) {
                subscriber.close();
            }
        }

        private Socket createSubscriberSocket() {
            Socket subscriber = context.socket(ZMQ.SUB);
            subscriber.setRcvHWM(0);
            subscriber.connect("tcp://" + configProps.getProperty(DATA_HARVEST_SUBSCRIPTION_ADDRESS));
            subscriber.subscribe("".getBytes());
            return subscriber;
        }
	}

	private void sendDataToCloud(String json) throws IOException {
		ObjectMapper mapper = new ObjectMapper();
        WsnData wsnData = mapper.readValue(json, WsnData.class);

		Map<String, String> meta = newHashMap();
		meta.put("receivedDate", Long.toString(wsnData.getReceivedUnixTimestamp() * 1000));
		if (wsnData.getRssi() != null) {
			meta.put("rssi_quality", Double.toString(wsnData.getRssi().getQuality()));
			meta.put("rssi_ul", Double.toString(wsnData.getRssi().getUplink()));
			meta.put("rssi_dl", Double.toString(wsnData.getRssi().getDownlink()));
		}

        String moteId = wsnData.getMac() + "_" + spaSystemId;

		String moteHardwareId = "sensor_mote_" + moteId;
		sendRegistration(spaSystemId, moteHardwareId, "mote");

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
			String sensorHardwareId = wsnValue.getDeviceName() + "_" + moteId;
			sendRegistration(moteId, sensorHardwareId, "sensor");

			Map<String, Double> measurement = newHashMap();
			measurement.put(oid, wsnValue.getValue());
            long timestamp = wsnData.getRecordedUnixTimestamp() != null ? wsnData.getRecordedUnixTimestamp() * 1000 : wsnData.getReceivedUnixTimestamp() * 1000;
            sendMeasurements(sensorHardwareId, null, measurement, timestamp, meta);
		    LOGGER.info(new Date().toString() + " sent measurement for " + sensorHardwareId + ": " + oid + " " + Double.toString(wsnValue.getValue()));
        }
	}

	private String waitForWSNData(Socket client, int timeout) {
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
}
