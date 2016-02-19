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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Date;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;

import static com.google.common.collect.Maps.newHashMap;

/**
 * A very simple mocked out Spa Controller, will register controller and send some fake
 * temperature data
 * 
 */
public class MockProcessor extends MQTTCommandProcessor {

	private static Logger LOGGER = LoggerFactory.getLogger(MockProcessor.class);
    private DeviceRegistration registeredSpa = new DeviceRegistration();
    private DeviceRegistration registeredController = new DeviceRegistration();
    private DeviceRegistration registeredMote = new DeviceRegistration();

    @Override
    public void handleShutdown() {}

	@Override
	public void handleStartup(String hardwareId, Properties configProps, String homePath, ScheduledExecutorService executorService) {
        sendRegistration(null, "gateway", ImmutableMap.of("serialNumber", "mockSerialNumber"),"spa_originatorid");
		LOGGER.info("Sent registration information.");
	}

    @Override
	public void handleRegistrationAck(RegistrationResponse response, String originatorId, String hardwareId) {
        if (response.getState() == RegistrationAckState.REGISTRATION_ERROR) {
            LOGGER.info("received registration error state %s", response.getErrorMessage());
            return;
        }

        if (originatorId.equals("spa_originatorid")) {
            registeredSpa.setHardwareId(hardwareId);
            LOGGER.info("received registration success for originator %s on hardwareid %s ", originatorId, hardwareId);
        }

        if (originatorId.equals("controller_originatorid")) {
            registeredController.setHardwareId(hardwareId);
            LOGGER.info("received registration success for controller originator %s on hardwareid %s ", originatorId, hardwareId);
        }

        if (originatorId.equals("mote_originatorid")) {
            registeredMote.setHardwareId(hardwareId);
            LOGGER.info("received registration success for mote originator %s on hardwareid %s ", originatorId, hardwareId);
        }

        LOGGER.info("received registration %s for hardwareid %s that did not have a previous code for ", originatorId, hardwareId);

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

        if (registeredSpa.getHardwareId() == null) {
            sendRegistration(null, "gateway", ImmutableMap.of("serialNumber", "mockSerialNumber"),"spa_originatorid");
            LOGGER.info("resent spa gateway registration");
            return;
        }

        if (registeredController.getHardwareId() == null) {
            sendRegistration(registeredSpa.getHardwareId(), "controller", newHashMap(),"controller_originatorid");
            LOGGER.info("resent controller registration");
            return;
        }

        if (registeredMote.getHardwareId() == null) {
            sendRegistration(registeredSpa.getHardwareId(), "mote", ImmutableMap.of("mac", "mockMAC"),"mote_originatorid");
            LOGGER.info("resent mote registration");
            return;
        }

        Map<String, String> meta = newHashMap();
        Map<String, Double> measurement = newHashMap();
        double pre = new Random().nextDouble();
        double post = new Random().nextDouble();
        measurement.put("0.4.0.0.0.0.1.1.0.0.0", pre); // pre-heat temp
        measurement.put("0.4.0.0.1.0.1.1.0.0.0", post); // post-heat temp
        meta.put("heat_delta", Double.toString(Math.abs(pre - post)));
        meta.put("comment", "controller temp probes");

        sendMeasurements(registeredMote.getHardwareId(), null, measurement, new Date().getTime(), meta);

        LOGGER.info("Sent harvest periodic reports");
    }
}
