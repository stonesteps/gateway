/*
 * Copyright (c) Triton Services. All rights reserved. http://www.tritonsvc.com
 *
 */
package com.tritonsvc.gateway;

import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableMap;
import com.tritonsvc.agent.MQTTCommandProcessor;
import com.tritonsvc.httpd.RegistrationInfoHolder;
import com.tritonsvc.httpd.WebServer;
import com.tritonsvc.spa.communication.proto.Bwg;
import com.tritonsvc.spa.communication.proto.Bwg.Downlink.Model.*;
import com.tritonsvc.spa.communication.proto.Bwg.Metadata;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.Model.Constants.EventType;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.Model.Event;
import com.tritonsvc.spa.communication.proto.BwgHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ScheduledExecutorService;

import static com.google.common.collect.Lists.newArrayList;
import static com.google.common.collect.Maps.newHashMap;

/**
 * A very simple mocked out Spa Controller, will register controller and send some fake
 * temperature data
 */
public class MockProcessor extends MQTTCommandProcessor implements RegistrationInfoHolder {

    private static final long RANDOM_DATA_SEND_INTERVAL = 3600000; // 1 hour in milliseconds
    private static Logger LOGGER = LoggerFactory.getLogger(MockProcessor.class);
    private DeviceRegistration registeredSpa = new DeviceRegistration();
    private DeviceRegistration registeredController = new DeviceRegistration();
    private DeviceRegistration registeredMote = new DeviceRegistration();

    private String gwSerialNumber;

    private MockSpaStateHolder spaStateHolder = null;
    private WebServer webServer = null;

    // enabled by default
    private boolean sendRandomFaultLogs = true;
    private long lastFaultLogsSendTime = 0L;

    private boolean sendRandomWifiStats = true;
    private long lastWifiStatsSendTime = 0L;

    @Override
    public void handleShutdown() {
        spaStateHolder.shutdown();

        if (webServer != null) {
            webServer.stop();
        }
    }

    @Override
    public void handleStartup(String hardwareId, Properties configProps, String homePath, ScheduledExecutorService executorService) {
        init(configProps, homePath);
        setupWebServer(configProps);
        this.gwSerialNumber = hardwareId;
        if (registeredSpa.getHardwareId() == null) {
            sendRegistration(null, this.gwSerialNumber, "gateway", newHashMap(), "spa_originatorid");
            LOGGER.info("Sent registration information.");
        } else {
            LOGGER.info("Spa already registered.");
        }
    }

    private void init(final Properties props, String homePath) {
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

        final String sendRandomFaultLogsStr = props.getProperty("mock.sendRandomFaultLogs");
        if (sendRandomFaultLogsStr != null) {
            sendRandomFaultLogs = "true".equalsIgnoreCase(sendRandomFaultLogsStr);
        }

        final String sendRandomWifiStatsStr = props.getProperty("mock.sendRandomWifiStats");
        if (sendRandomWifiStatsStr != null) {
            sendRandomWifiStats = "true".equalsIgnoreCase(sendRandomWifiStatsStr);
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

        List<Event> events = newArrayList();
        Event.Builder eb = Event.newBuilder();
        eb.setEventOccuredTimestamp(new Date().getTime());
        eb.setEventReceivedTimestamp(new Date().getTime());
        eb.setEventType(EventType.MEASUREMENT);
        eb.setDescription("this is a fake measurement");
        for (Map.Entry<String, String> entry : meta.entrySet()) {
            eb.addMetadata(Metadata.newBuilder().setName(entry.getKey()).setValue(entry.getValue()).build());
        }

        events.add(eb.build());

        sendEvents(registeredMote.getHardwareId(), events);

        // send spa info
        LOGGER.info("Sending spa info");
        sendSpaState(registeredSpa.getHardwareId(), spaStateHolder.buildSpaState());

        sendFaultLogs();

        sendWifiStats();

        LOGGER.info("Sent harvest periodic reports");
    }

    private void sendFaultLogs() {
        if (!sendRandomFaultLogs) return;

        if (System.currentTimeMillis() > lastFaultLogsSendTime + RANDOM_DATA_SEND_INTERVAL) {
            final Bwg.Uplink.Model.FaultLogs randomFaultLogs = buildRandomFaultLogs();
            getCloudDispatcher().sendUplink(registeredSpa.getHardwareId(), null, Bwg.Uplink.UplinkCommandType.FAULT_LOGS, randomFaultLogs, false);
            lastFaultLogsSendTime = System.currentTimeMillis();
        }
    }

    private Bwg.Uplink.Model.FaultLogs buildRandomFaultLogs() {
        final FaultLogManager faultLogManager = new FaultLogManager(new Properties());

        final Random rnd = new Random();

        int randomCode = rnd.nextInt(25);
        int targetTemp = rnd.nextInt(100) + 20;
        int tempA = rnd.nextInt(100) + 20;
        int tempB = rnd.nextInt(100) + 20;

        faultLogManager.addFaultLogEntry(new FaultLogEntry(0, randomCode, System.currentTimeMillis(), targetTemp, tempA, tempB, false));
        return faultLogManager.getUnsentFaultLogs();
    }

    private void sendWifiStats() {
        if (!sendRandomWifiStats) return;

        if (System.currentTimeMillis() > lastWifiStatsSendTime + RANDOM_DATA_SEND_INTERVAL) {
            sendWifiStats(registeredSpa.getHardwareId(), buildRandomWifiStats());
            lastWifiStatsSendTime = System.currentTimeMillis();
        }
    }

    private List<Bwg.Uplink.Model.WifiStat> buildRandomWifiStats() {
        Bwg.Uplink.Model.WifiStat.Builder builder = Bwg.Uplink.Model.WifiStat.newBuilder();

        Bwg.Uplink.Model.WifiStat.WifiConnectionDiagnostics.Builder diagBuilder = Bwg.Uplink.Model.WifiStat.WifiConnectionDiagnostics.newBuilder();
        diagBuilder.setFrequency("2.437 GHz");
        diagBuilder.setRawDataRate("11 Mb/s");
        diagBuilder.setDataRate(1100000);
        diagBuilder.setDeltaDataRate(0);
        diagBuilder.setLinkQualityPercentage(100);
        diagBuilder.setDeltaLinkQualityPercentage(0);
        diagBuilder.setLinkQualityRaw("88/100");
        diagBuilder.setSignalLevelUnits(40);
        diagBuilder.setSignalLevelUnitsRaw("40/100");
        diagBuilder.setDeltaSignalLevelUnits(0);
        diagBuilder.setRxOtherAPPacketCount(0);
        diagBuilder.setDeltaRxOtherAPPacketCount(0);
        diagBuilder.setRxInvalidCryptPacketCount(0);
        diagBuilder.setDeltaRxInvalidCryptPacketCount(0);
        diagBuilder.setRxInvalidFragPacketCount(0);
        diagBuilder.setDeltaRxInvalidFragPacketCount(0);
        diagBuilder.setTxExcessiveRetries(0);
        diagBuilder.setDeltaTxExcessiveRetries(0);
        diagBuilder.setLostBeaconCount(0);
        diagBuilder.setDeltaLostBeaconCount(0);
        diagBuilder.setNoiseLevel(10);
        diagBuilder.setNoiseLevelRaw("10/100");
        diagBuilder.setDeltaNoiseLevel(0);

        Random rnd = new Random();
        builder.setWifiConnectionHealth(Bwg.Uplink.Model.Constants.WifiConnectionHealth.valueOf(rnd.nextInt(4)));
        builder.setApMacAddress("00:24:17:44:35:28");
        builder.setMode("Managed");
        builder.setConnectedDiag(diagBuilder);
        builder.setFragConfig("1536 B");
        builder.setElapsedDeltaMilliseconds(100);
        builder.setPowerMgmtConfig("off");
        builder.setRecordedDate(System.currentTimeMillis());
        builder.setRetryLimitPhraseConfig("0");
        builder.setRetryLimitValueConfig("8");
        builder.setRtsConfig("1536 B");
        builder.setSSID("test");
        builder.setTxPowerDbm(15);
        builder.setSensitivity("30/100");

        List<Bwg.Uplink.Model.WifiStat> list = new ArrayList<>();
        list.add(builder.build());
        return list;
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
    public String getSerialNumber() {
        return gwSerialNumber;
    }
}
