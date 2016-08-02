/*
 * Copyright (c) Triton Services. All rights reserved. http://www.tritonsvc.com
 *
 */
package com.tritonsvc.gateway;

import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableMap;
import com.tritonsvc.agent.Agent;
import com.tritonsvc.agent.AgentSettingsPersister;
import com.tritonsvc.agent.MQTTCommandProcessor;
import com.tritonsvc.httpd.RegistrationInfoHolder;
import com.tritonsvc.httpd.WebServer;
import com.tritonsvc.spa.communication.proto.Bwg;
import com.tritonsvc.spa.communication.proto.Bwg.Downlink.Model.*;
import com.tritonsvc.spa.communication.proto.Bwg.Metadata;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.Model.Constants.EventType;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.Model.Event;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.Model.Measurement.DataType;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.Model.Measurement.QualityType;
import com.tritonsvc.spa.communication.proto.BwgHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

import static com.google.common.collect.Lists.newArrayList;
import static com.google.common.collect.Maps.newHashMap;

/**
 * A very simple mocked out Spa Controller, will register controller and send some fake
 * temperature data
 */
public class MockProcessor extends MQTTCommandProcessor implements RegistrationInfoHolder {

    private static final long RANDOM_DATA_SEND_INTERVAL = 1800000; // .5 hour in milliseconds
    private static final long MAX_REG_LIFETIME = Agent.MAX_SUBSCRIPTION_INACTIVITY_TIME - 30000; // set this to the same value
    private static Logger LOGGER = LoggerFactory.getLogger(MockProcessor.class);
    private DeviceRegistration registeredSpa = new DeviceRegistration();
    private DeviceRegistration registeredController = new DeviceRegistration();
    private DeviceRegistration registeredTemp = new DeviceRegistration();
    private DeviceRegistration registeredCurrent = new DeviceRegistration();
    private long lastRegSendTime = 0L;

    private String gwSerialNumber;

    private MockSpaStateHolder spaStateHolder = null;
    private WebServer webServer = null;

    // enabled by default
    private boolean sendRandomFaultLogs = true;
    private long lastFaultLogsSendTime = 0L;
    private long lastSpaSendTime = 0L;

    private boolean sendRandomWifiStats = true;
    private long lastWifiStatsSendTime = 0L;

    private boolean sendRandomMeasurementReadings = true;
    private long lastMeasurementReadingsSendTime = 0L;

    private Integer wifiState;

    /**
     * Constructor
     *
     * @param persister
     */
    public MockProcessor(AgentSettingsPersister persister) {
        super(persister);
    }

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

    @Override
    public synchronized void processEventsHandler() {
    }

    private void init(final Properties props, String homePath) {
        spaStateHolder = new MockSpaStateHolder(props);

        final String spaId = props.getProperty("mock.spaId");
        if (spaId != null) {
            registeredSpa.setHardwareId(spaId);
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

        final String tempMoteId = props.getProperty("mock.tempMoteId");
        if (tempMoteId != null) {
            registeredTemp.setHardwareId(tempMoteId);
        }

        final String currentMoteId = props.getProperty("mock.currentMoteId");
        if (currentMoteId != null) {
            registeredCurrent.setHardwareId(currentMoteId);
        }

        final String sendRandomFaultLogsStr = props.getProperty("mock.sendRandomFaultLogs");
        if (sendRandomFaultLogsStr != null) {
            sendRandomFaultLogs = "true".equalsIgnoreCase(sendRandomFaultLogsStr);
        }

        final String sendRandomWifiStatsStr = props.getProperty("mock.sendRandomWifiStats");
        if (sendRandomWifiStatsStr != null) {
            sendRandomWifiStats = "true".equalsIgnoreCase(sendRandomWifiStatsStr);
        }

        // wifiState(WifiConnectionHealth) 0 - 4
        final String wifiStateStr = props.getProperty("mock.wifiState");
        if (wifiStateStr != null) {
            try {
                wifiState = Integer.valueOf(wifiStateStr);
            } catch (final NumberFormatException e) {
                LOGGER.error("Property mock.wifiState is invalid {}", wifiStateStr);
            }
        }
    }

    private void setupWebServer(Properties props) {
        try {
            this.webServer = new WebServer(props, this, this, 0);
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
        } else if (originatorId.equals("mote_temp")) {
            registeredTemp.setHardwareId(hardwareId);
            LOGGER.info("received registration success for temp mote originator {} on hardwareid {} ", originatorId, hardwareId);
        } else if (originatorId.equals("mote_current")) {
            registeredCurrent.setHardwareId(hardwareId);
            LOGGER.info("received registration success for current mote originator {} on hardwareid {} ", originatorId, hardwareId);
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

        if (originatorId.equals("spa_originatorid")) {
            registeredSpa.setHardwareId(hardwareId);
            registeredSpa.getMeta().put("regKey", response.hasRegKey() ? response.getRegKey() : null);
            registeredSpa.getMeta().put("regUserId", response.hasRegUserId() ? response.getRegUserId() : null);
            registeredSpa.getMeta().put("swUpgradeUrl", response.hasSwUpgradeUrl() ? response.getSwUpgradeUrl() : null);
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
        long receivedTime = System.currentTimeMillis();
        Event event = Event.newBuilder()
                .setEventOccuredTimestamp(receivedTime)
                .setEventReceivedTimestamp(receivedTime)
                .setEventType(EventType.REQUEST)
                .setDescription("Received " + request.getRequestType().name() + " request")
                .addAllMetadata(convertRequestToMetaData(request.getMetadataList()))
                .addMetadata(Metadata.newBuilder().setName("originatorId").setValue(originatorId))
                .build();

        sendEvents(hardwareId, newArrayList(event));

        try {
            if (request.getRequestType().equals(Bwg.Downlink.Model.RequestType.HEATER)) {
                updateHeater(request.getMetadataList(), originatorId, hardwareId);
            } else {
                switch (request.getRequestType()) {
                    case PUMP:
                        updatePeripherlal(request.getMetadataList(), originatorId, hardwareId, Bwg.Uplink.Model.Constants.ComponentType.PUMP);
                        break;
                    case CIRCULATION_PUMP:
                        updatePeripherlal(request.getMetadataList(), originatorId, hardwareId, Bwg.Uplink.Model.Constants.ComponentType.CIRCULATION_PUMP);
                        break;
                    case LIGHT:
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

    private List<Metadata> convertRequestToMetaData(Collection<RequestMetadata> requestMetadata) {
        return requestMetadata.stream()
                .map(request -> Metadata.newBuilder().setName(request.getName().name()).setValue(request.getValue()).build())
                .collect(Collectors.toList());
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

        boolean sendReg = false;
        if (System.currentTimeMillis() > lastRegSendTime + MAX_REG_LIFETIME) {
            lastRegSendTime = System.currentTimeMillis();
            sendReg = true;
        }

        if (sendReg) {
            sendRegistration(null, this.gwSerialNumber, "gateway", newHashMap(), "spa_originatorid");
        }

        if (sendReg) {
            sendRegistration(registeredSpa.getHardwareId(), this.gwSerialNumber, "controller", newHashMap(), "controller_originatorid");
        }

        if (sendReg) {
            sendRegistration(registeredSpa.getHardwareId(), this.gwSerialNumber, "mote", ImmutableMap.of("mac", "mockTemperatureMAC", "mote_type", "temperature sensor"), "mote_temp");
        }

        if (sendReg) {
            sendRegistration(registeredSpa.getHardwareId(), this.gwSerialNumber, "mote", ImmutableMap.of("mac", "mockCurrentMAC", "mote_type", "current sensor"), "mote_current");
        }

        if (registeredSpa.getHardwareId() == null || registeredController.getHardwareId() == null || registeredTemp.getHardwareId() == null || registeredCurrent.getHardwareId() == null) {
            return;
        }

        if (System.currentTimeMillis() > lastSpaSendTime + 60000) {
            // send spa info
            LOGGER.info("Sending spa info");
            sendSpaState(registeredSpa.getHardwareId(), spaStateHolder.buildSpaState());
            sendFaultLogs();
            sendWifiStats();
            sendMeasurementReadings();
            LOGGER.info("Sent harvest periodic reports");
            lastSpaSendTime = System.currentTimeMillis();
        }
    }

    @Override
    public String getOsType() {
        return "standard";
    }

    @Override
    public String getEthernetDeviceName() {
        return "eth0";
    }

    @Override
    public String getWifiDeviceName() {
        return "wlan0";
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

        int randomCode;

        while ((randomCode = rnd.nextInt(20)) < 1) {
        }
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

        Random random = new Random();
        int newValue;
        Bwg.Uplink.Model.WifiStat.WifiConnectionDiagnostics.Builder diagBuilder = Bwg.Uplink.Model.WifiStat.WifiConnectionDiagnostics.newBuilder();
        diagBuilder.setFrequency("2.437 GHz");
        diagBuilder.setRawDataRate("11 Mb/s");
        diagBuilder.setDataRate(1100000);
        diagBuilder.setDeltaDataRate(0);
        while ((newValue = random.nextInt(100)) < 30) {
        }
        diagBuilder.setLinkQualityPercentage(random.nextInt(newValue));
        diagBuilder.setDeltaLinkQualityPercentage(0);
        diagBuilder.setLinkQualityRaw("88/100");

        while ((newValue = random.nextInt(80)) < 30) {
        }
        diagBuilder.setSignalLevelUnits(newValue * -1);
        diagBuilder.setSignalLevelUnitsRaw("40/100");
        diagBuilder.setDeltaSignalLevelUnits(0);
        diagBuilder.setRxOtherAPPacketCount(random.nextInt(5));
        diagBuilder.setDeltaRxOtherAPPacketCount(0);
        diagBuilder.setRxInvalidCryptPacketCount(random.nextInt(10));
        diagBuilder.setDeltaRxInvalidCryptPacketCount(0);
        diagBuilder.setRxInvalidFragPacketCount(random.nextInt(10));
        diagBuilder.setDeltaRxInvalidFragPacketCount(0);
        diagBuilder.setTxExcessiveRetries(random.nextInt(10));
        diagBuilder.setDeltaTxExcessiveRetries(0);
        diagBuilder.setLostBeaconCount(random.nextInt(5));
        diagBuilder.setDeltaLostBeaconCount(0);
        diagBuilder.setNoiseLevel(10);
        diagBuilder.setNoiseLevelRaw("10/100");
        diagBuilder.setDeltaNoiseLevel(0);

        Random rnd = new Random();
        builder.setWifiConnectionHealth(Bwg.Uplink.Model.Constants.WifiConnectionHealth.valueOf(wifiState != null ? wifiState.intValue() : rnd.nextInt(4)));
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

    private void sendMeasurementReadings() {
        if (!sendRandomMeasurementReadings) return;

        if (registeredTemp.getHardwareId() == null) {
            LOGGER.info("skipping measurement submittal, mote {} has not been registered yet with cloud", "temp_sensor_mac_1");
            return;
        }

        if (registeredCurrent.getHardwareId() == null) {
            LOGGER.info("skipping measurement submittal, mote {} has not been registered yet with cloud", "current_sensor_mac_2");
            return;
        }

        if (System.currentTimeMillis() > lastMeasurementReadingsSendTime + RANDOM_DATA_SEND_INTERVAL) {
            sendMeasurements(registeredTemp.getHardwareId(), buildRandomMeasurementReadings(true));
            sendMeasurements(registeredCurrent.getHardwareId(), buildRandomMeasurementReadings(false));
            lastMeasurementReadingsSendTime = System.currentTimeMillis();
        }
    }

    private List<Bwg.Uplink.Model.Measurement> buildRandomMeasurementReadings(boolean isTemp) {
        final List<Bwg.Uplink.Model.Measurement> list = new ArrayList<>();
        if (isTemp) {
            list.add(buildRandomMeasurementReading(Bwg.Uplink.Model.Measurement.DataType.AMBIENT_TEMP, "fahrenheit", "1"));
            list.add(buildRandomMeasurementReading(DataType.AMBIENT_HUMIDITY, "percentage", "2"));
        } else {
            list.add(buildRandomMeasurementReading(Bwg.Uplink.Model.Measurement.DataType.PUMP_AC_CURRENT, "amps", "1"));
        }
        return list;
    }

    private Bwg.Uplink.Model.Measurement buildRandomMeasurementReading(final Bwg.Uplink.Model.Measurement.DataType dataType, final String uom, String identifier) {
        final Bwg.Uplink.Model.Measurement.Builder builder = Bwg.Uplink.Model.Measurement.newBuilder();
        builder.setTimestamp(System.currentTimeMillis());
        builder.setType(dataType);
        builder.setUom(uom);
        builder.setQuality(QualityType.VALID);
        builder.setValue(ThreadLocalRandom.current().nextDouble(10.0d, 90.0d));
        builder.setSensorIdentifier(identifier);
        return builder.build();
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
