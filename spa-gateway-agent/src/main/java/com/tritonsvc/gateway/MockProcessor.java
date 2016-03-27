/*
 * Copyright (c) Triton Services. All rights reserved. http://www.tritonsvc.com
 *
 */
package com.tritonsvc.gateway;

import com.google.common.collect.ImmutableMap;
import com.tritonsvc.agent.MQTTCommandProcessor;
import com.tritonsvc.spa.communication.proto.Bwg;
import com.tritonsvc.spa.communication.proto.Bwg.Downlink.Model.RegistrationAckState;
import com.tritonsvc.spa.communication.proto.Bwg.Downlink.Model.RegistrationResponse;
import com.tritonsvc.spa.communication.proto.Bwg.Downlink.Model.Request;
import com.tritonsvc.spa.communication.proto.Bwg.Downlink.Model.SpaRegistrationResponse;
import com.tritonsvc.spa.communication.proto.Bwg.Downlink.Model.UplinkAcknowledge;
import com.tritonsvc.spa.communication.proto.BwgHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
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

    private String gwSerialNumber;

    private SpaStateHolder spaStateHolder = new SpaStateHolder();

    @Override
    public void handleShutdown() {}

	@Override
	public void handleStartup(String hardwareId, Properties configProps, String homePath, ScheduledExecutorService executorService) {
        this.gwSerialNumber = hardwareId;
        sendRegistration(null, this.gwSerialNumber, "gateway", newHashMap(), "spa_originatorid");
		LOGGER.info("Sent registration information.");
	}

    @Override
	public void handleRegistrationAck(RegistrationResponse response, String originatorId, String hardwareId) {
        if (response.getState() == RegistrationAckState.REGISTRATION_ERROR) {
            LOGGER.info("received registration error state {}", response.getErrorMessage());
            return;
        }

        if (originatorId.equals("controller_originatorid")) {
            registeredController.setHardwareId(hardwareId);
            LOGGER.info("received registration success for controller originator {} on hardwareid {} ", originatorId, hardwareId);
        }

        if (originatorId.equals("mote_originatorid")) {
            registeredMote.setHardwareId(hardwareId);
            LOGGER.info("received registration success for mote originator {} on hardwareid {} ", originatorId, hardwareId);
        }

        LOGGER.info("received registration {} for hardwareid {} that did not have a previous code for ", originatorId, hardwareId);
	}

    @Override
    public void handleSpaRegistrationAck(SpaRegistrationResponse response, String originatorId, String hardwareId) {
        if (response.getState() == RegistrationAckState.REGISTRATION_ERROR) {
            LOGGER.info("received spa registration error state {}", response.getErrorMessage());
            return;
        }

        if (originatorId.equals("spa_originatorid")) {
            registeredSpa.setHardwareId(hardwareId);
            registeredSpa.getMeta().put("apSSID", response.getP2PAPSSID());
            registeredSpa.getMeta().put("apPassword", response.getP2PAPPassword());
            LOGGER.info("received spa registration success for originator {} on hardwareid {} ", originatorId, hardwareId);
        }

        LOGGER.info("received spa registration {} for hardwareid {} that did not have a previous code for ", originatorId, hardwareId);
    }

    @Override
	public void handleDownlinkCommand(Request request, String hardwareId, String originatorId) {
        if (request == null || !request.hasRequestType()) {
            LOGGER.error("Request is null, not processing, []", originatorId);
            sendAck(hardwareId, originatorId, Bwg.AckResponseCode.ERROR, "gateway has not registered with controller yet");
            return;
        }

        if (registeredSpa.getHardwareId() == null) {
            LOGGER.error("received request {}, gateway has not registered with controller yet ", request.getRequestType().name());
            sendAck(hardwareId, originatorId, Bwg.AckResponseCode.ERROR, "gateway has not registered with controller yet");
            return;
        }

        LOGGER.info("received downlink command from cloud {}, originatorid = {}", request.getRequestType().name(), originatorId);
        sendAck(hardwareId, originatorId, Bwg.AckResponseCode.RECEIVED, null);

        try {
            if (request.getRequestType().equals(Bwg.Downlink.Model.RequestType.HEATER)) {
                updateHeater(request.getMetadataList(), originatorId, hardwareId);
            } else {
                switch (request.getRequestType()) {
                    case PUMPS:
                        updatePeripherlal(request.getMetadataList(), originatorId, hardwareId, Bwg.Uplink.Model.Constants.ComponentType.PUMP);
                        break;
                    case LIGHTS:
                        updatePeripherlal(request.getMetadataList(), originatorId, hardwareId, Bwg.Uplink.Model.Constants.ComponentType.LIGHT);
                        break;
                    case BLOWER:
                        updatePeripherlal(request.getMetadataList(), originatorId, hardwareId, Bwg.Uplink.Model.Constants.ComponentType.BLOWER);
                        break;
                    case MISTER:
                        updatePeripherlal(request.getMetadataList(), originatorId, hardwareId, Bwg.Uplink.Model.Constants.ComponentType.MISTER);
                        break;
                    case FILTER:
                        updateFilter(request.getMetadataList(), originatorId, hardwareId);
                        break;
                    case OZONE:
                        updatePeripherlal(request.getMetadataList(), originatorId, hardwareId, Bwg.Uplink.Model.Constants.ComponentType.OZONE);
                        break;
                    case MICROSILK:
                        updatePeripherlal(request.getMetadataList(), originatorId, hardwareId, Bwg.Uplink.Model.Constants.ComponentType.MICROSILK);
                        break;
                    case AUX:
                        updatePeripherlal(request.getMetadataList(), originatorId, hardwareId, Bwg.Uplink.Model.Constants.ComponentType.AUX);
                        break;
                    default:
                        sendAck(hardwareId, originatorId, Bwg.AckResponseCode.ERROR, "not supported");
                }
            }
        }
        catch (Exception ex) {
            LOGGER.error("had problem when sending a command ", ex);
            sendAck(hardwareId, originatorId, Bwg.AckResponseCode.ERROR, ex.getMessage());
            return;
        }
	}

    private void updateHeater(List<Bwg.Downlink.Model.RequestMetadata> metadataList, String originatorId, String hardwareId) {
        final String tempStr = BwgHelper.getRequestMetadataValue(Bwg.Downlink.Model.SpaCommandAttribName.DESIREDTEMP.name(), metadataList);
        LOGGER.info("Setting new temperature {}", tempStr);
        if (tempStr != null) {
            final int temp = Integer.parseInt(tempStr);
            spaStateHolder.updateHeater((int) temp);
            sendAck(hardwareId, originatorId, Bwg.AckResponseCode.OK, null);
        }
    }

    private void updatePeripherlal(List<Bwg.Downlink.Model.RequestMetadata> metadataList, String originatorId, String hardwareId, Bwg.Uplink.Model.Constants.ComponentType componentType) {
        final String desiredState = BwgHelper.getRequestMetadataValue(Bwg.Downlink.Model.SpaCommandAttribName.DESIREDSTATE.name(), metadataList);
        final String portStr = BwgHelper.getRequestMetadataValue(Bwg.Downlink.Model.SpaCommandAttribName.PORT.name(), metadataList);
        final Integer port;
        if (portStr != null) {
            port = Integer.decode(portStr);
        } else {
            port = Integer.valueOf(0);
        }

        spaStateHolder.updateComponentState(componentType, port, desiredState);
        sendAck(hardwareId, originatorId, Bwg.AckResponseCode.OK, null);
    }

    private void updateFilter(List<Bwg.Downlink.Model.RequestMetadata> metadataList, String originatorId, String hardwareId) {
        // not implemented yet
    }

    @Override
	public void handleUplinkAck(UplinkAcknowledge ack, String originatorId) {
		// not required here?
	}

    @Override
    public void processDataHarvestIteration() {

        if (registeredSpa.getHardwareId() == null) {
            sendRegistration(null, this.gwSerialNumber, "gateway", newHashMap(),"spa_originatorid");
            LOGGER.info("resent spa gateway registration");
            return;
        }

        if (registeredController.getHardwareId() == null) {
            sendRegistration(registeredSpa.getHardwareId(), this.gwSerialNumber, "controller", newHashMap(),"controller_originatorid");
            LOGGER.info("resent controller registration");
            return;
        }

        if (registeredMote.getHardwareId() == null) {
            sendRegistration(registeredSpa.getHardwareId(), this.gwSerialNumber, "mote", ImmutableMap.of("mac", "mockMAC"),"mote_originatorid");
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

        // send spa info
        LOGGER.info("Sending spa info");
        sendSpaState(registeredSpa.getHardwareId(), spaStateHolder.buildSpaState());

        LOGGER.info("Sent harvest periodic reports");
    }
}
