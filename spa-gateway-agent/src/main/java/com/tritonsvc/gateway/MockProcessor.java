/*
 * Copyright (c) Triton Services. All rights reserved. http://www.tritonsvc.com
 *
 */
package com.tritonsvc.gateway;

import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableMap;
import com.tritonsvc.agent.MQTTCommandProcessor;
import com.tritonsvc.httpd.NetworkSettingsHolder;
import com.tritonsvc.httpd.RegistrationInfoHolder;
import com.tritonsvc.httpd.WebServer;
import com.tritonsvc.httpd.model.NetworkSettings;
import com.tritonsvc.spa.communication.proto.Bwg;
import com.tritonsvc.spa.communication.proto.Bwg.Downlink.Model.*;
import com.tritonsvc.spa.communication.proto.BwgHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ScheduledExecutorService;

import static com.google.common.collect.Maps.newHashMap;

/**
 * A very simple mocked out Spa Controller, will register controller and send some fake
 * temperature data
 */
public class MockProcessor extends MQTTCommandProcessor implements RegistrationInfoHolder, NetworkSettingsHolder {

    private static Logger LOGGER = LoggerFactory.getLogger(MockProcessor.class);
    private DeviceRegistration registeredSpa = new DeviceRegistration();
    private DeviceRegistration registeredController = new DeviceRegistration();
    private DeviceRegistration registeredMote = new DeviceRegistration();

    private String gwSerialNumber;

    private MockSpaStateHolder spaStateHolder = null;
    private WebServer webServer = null;
    private NetworkSettings networkSettings = new NetworkSettings();

    @Override
    public void handleShutdown() {
        spaStateHolder.shutdown();

        if (webServer != null) {
            webServer.stop();
        }
    }

    @Override
    public void handleStartup(String hardwareId, Properties configProps, String homePath, ScheduledExecutorService executorService) {
        init(configProps);
        setupWebServer(configProps);
        this.gwSerialNumber = hardwareId;
        if (registeredSpa.getHardwareId() == null) {
            sendRegistration(null, this.gwSerialNumber, "gateway", newHashMap(), "spa_originatorid");
            LOGGER.info("Sent registration information.");
        } else {
            LOGGER.info("Spa already registered.");
        }
    }

    private void init(final Properties props) {
        spaStateHolder = new MockSpaStateHolder(props);

        final String spaId = props.getProperty("mock.spaId");
        if (spaId != null) {
            registeredSpa.setHardwareId(spaId);
        }
        final String apSSID = props.getProperty("mock.apSSID");
        if (apSSID != null) {
            registeredSpa.getMeta().put("apSSID", apSSID);
        }
        final String apPassword = props.getProperty("mock.apPassword");
        if (apPassword != null) {
            registeredSpa.getMeta().put("apPassword", apPassword);
        }
        final String regKey = props.getProperty("mock.regKey");
        if (regKey != null) {
            registeredSpa.getMeta().put("regKey", regKey);
        }
        final String regUserId = props.getProperty("mock.regUserId");
        if (regUserId != null) {
            registeredSpa.getMeta().put("regUserId", regUserId);
        }

        final String controllerId = props.getProperty("mock.controllerId");
        if (controllerId != null) {
            registeredController.setHardwareId(controllerId);
        }
        final String moteId = props.getProperty("mock.moteId");
        if (moteId != null) {
            registeredMote.setHardwareId(moteId);
        }
    }

    private void setupWebServer(Properties props) {
        try {
            this.webServer = new WebServer(props, this, this);
            if ("true".equalsIgnoreCase(props.getProperty("mock.webServer.runOnStart"))) {
                this.webServer.start();
            }
        } catch (final Exception e) {
            LOGGER.error("Could not instantiate web serwer", e);
            throw Throwables.propagate(e);
        }
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
            registeredSpa.getMeta().put("apSSID", response.hasP2PAPSSID() ? response.getP2PAPPassword() : null);
            registeredSpa.getMeta().put("apPassword", response.hasP2PAPPassword() ? response.getP2PAPPassword() : null);
            registeredSpa.getMeta().put("regKey", response.hasRegKey() ? response.getRegKey() : null);
            registeredSpa.getMeta().put("regUserId", response.hasRegUserId() ? response.getRegUserId() : null);
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
                    case CIRCULATION_PUMP:
                        updatePeripherlal(request.getMetadataList(), originatorId, hardwareId, Bwg.Uplink.Model.Constants.ComponentType.CIRCULATION_PUMP);
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
        } catch (Exception ex) {
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

    @Override
    public void sendMeasurements(String hardwareId, String originator, Map<String, Double> measurements, long measurementTimestampMillis, Map<String, String> meta) {
        super.sendMeasurements(hardwareId, originator, measurements, measurementTimestampMillis, meta);
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
        Integer port = null;
        Integer durationMinutes = null;

        if (metadataList != null && metadataList.size() > 0) {
            for (final Bwg.Downlink.Model.RequestMetadata metadata : metadataList) {
                if (Bwg.Downlink.Model.SpaCommandAttribName.PORT.equals(metadata.getName())) {
                    port = new Integer(metadata.getValue());
                }
                if (Bwg.Downlink.Model.SpaCommandAttribName.FILTER_DURATION_15MINUTE_INTERVALS.equals(metadata.getName())) {
                    durationMinutes = new Integer(metadata.getValue()) * 15;
                }
            }
        }

        if (port != null || durationMinutes != null) {
            spaStateHolder.updateFilterCycle(port, durationMinutes);
        } else {
            LOGGER.error("Can not update filter cycle - port or duration minutes is null");
        }
    }

    @Override
    public void handleUplinkAck(UplinkAcknowledge ack, String originatorId) {
        // not required here?
    }

    @Override
    public void processDataHarvestIteration() {

        if (registeredSpa.getHardwareId() == null) {
            sendRegistration(null, this.gwSerialNumber, "gateway", newHashMap(), "spa_originatorid");
            return;
        }

        if (registeredController.getHardwareId() == null) {
            sendRegistration(registeredSpa.getHardwareId(), this.gwSerialNumber, "controller", newHashMap(), "controller_originatorid");
            return;
        }

        if (registeredMote.getHardwareId() == null) {
            sendRegistration(registeredSpa.getHardwareId(), this.gwSerialNumber, "mote", ImmutableMap.of("mac", "mockMAC"), "mote_originatorid");
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

    @Override
    public String getRegKey() {
        return registeredSpa.getMeta().get("regKey");
    }

    @Override
    public String getRegUserId() {
        return registeredSpa.getMeta().get("regUserId");
    }

    @Override
    public String getSpaId() {
        return registeredSpa.getHardwareId();
    }

    @Override
    public NetworkSettings getNetworkSettings() {
        return networkSettings;
    }

    @Override
    public void setNetworkSettings(final NetworkSettings networkSettings) {
        this.networkSettings = networkSettings;
    }
}
