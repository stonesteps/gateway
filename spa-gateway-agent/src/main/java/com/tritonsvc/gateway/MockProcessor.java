/*
 * Copyright (c) Triton Services. All rights reserved. http://www.tritonsvc.com
 *
 */
package com.tritonsvc.gateway;

import com.tritonsvc.agent.MQTTCommandProcessor;
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
    private String controllerId;

    @Override
    public void handleShutdown() {}

	@Override
	public void handleStartup(String hardwareId, Properties configProps, String homePath, ScheduledExecutorService executorService) {
        // can add metadata to registrations, could add device parent id as meta key/value
        // instead of doing device groups, then queries can aggregate on parent foreign key
		sendRegistration(null, hardwareId, "gateway", newHashMap());
        controllerId = hardwareId + "_controller";
        sendRegistration(hardwareId, controllerId , "controller", newHashMap());
		LOGGER.info("Sent registration information.");
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
        Map<String, String> meta = newHashMap();
        Map<String, Double> measurement = newHashMap();
        double pre = new Random().nextDouble();
        double post = new Random().nextDouble();
        measurement.put("0.4.0.0.0.0.1.1.0.0.0", pre); // pre-heat temp
        measurement.put("0.4.0.0.1.0.1.1.0.0.0", post); // post-heat temp
        meta.put("heat_delta", Double.toString(Math.abs(pre - post)));
        meta.put("comment", "controller temp probes");

        sendMeasurements(controllerId, null, measurement, new Date().getTime(), meta);

        LOGGER.info("Sent harvest periodic reports");
    }
}
