package com.tritonsvc.messageprocessor;

import com.bwg.iot.model.*;
import com.bwg.iot.model.Component.ComponentType;
import com.bwg.iot.model.WifiConnectionHealth;
import com.tritonsvc.gateway.FaultLogEntry;
import com.tritonsvc.gateway.FaultLogManager;
import com.tritonsvc.messageprocessor.mongo.repository.*;
import com.tritonsvc.messageprocessor.mqtt.MqttSendService;
import com.tritonsvc.spa.communication.proto.Bwg;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.Model.Components;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.Model.Components.ToggleComponent;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.Model.Constants.*;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.Model.Constants.PanelMode;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.Model.Constants.SwimSpaMode;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.Model.Constants.TempRange;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.Model.Controller;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.Model.SpaState;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.UplinkCommandType;
import com.tritonsvc.spa.communication.proto.BwgHelper;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.SpringApplicationConfiguration;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

import java.util.*;

import static com.google.common.collect.Lists.newArrayList;
import static java.util.stream.Collectors.toList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.junit.Assert.*;

@RunWith(SpringJUnit4ClassRunner.class)
@SpringApplicationConfiguration(classes = {SpaGatewayMessageProcessorApplication.class, UnitTestHelper.class})
public class UplinkProcessorTest {

    @Autowired
    private SpaRepository spaRepository;

    @Autowired
    private SpaCommandRepository spaCommandRepository;

    @Autowired
    private ComponentRepository componentRepository;

    @Autowired
    private MqttSendService mqttSendService;

    @Autowired
    private MessageProcessorConfiguration messageProcessorConfiguration;

    @Autowired
    private FaultLogRepository faultLogRepository;

    @Autowired
    private WifiStatRepository wifiStatRepository;

    @After
    @Before
    public void cleanup() {
        spaRepository.deleteAll();
        spaCommandRepository.deleteAll();
        componentRepository.deleteAll();
    }

    @Test
    public void handleGatewayRegisterDevice() throws Exception {
        // send register message
        final Collection<Bwg.Metadata> metadata = new ArrayList<>();
        metadata.add(BwgHelper.buildMetadata("serialName", "ABC"));
        final Bwg.Uplink.Model.RegisterDevice registerDevice = BwgHelper.buildRegisterDevice(null, "gateway", "1", metadata);
        mqttSendService.sendMessage(messageProcessorConfiguration.getUplinkTopicName(), BwgHelper.buildUplinkMessage("1", "1", Bwg.Uplink.UplinkCommandType.REGISTRATION, registerDevice));

        // wait for message to be delivered and processed
        Thread.sleep(2000);

        final Page<Component> gateway = componentRepository.findByComponentTypeAndSerialNumber(ComponentType.GATEWAY.name(), "1", new PageRequest(0, 1));
        assertEquals(gateway.getContent().get(0).getSerialNumber(), "1");

        final Spa spa = spaRepository.findOne(gateway.getContent().get(0).getSpaId());
        assertNotNull(spa);
        // if no spa record existed prior to reg, then the spa serial number gets set to gateway serial number
        assertEquals(spa.getSerialNumber(), "1");
    }

    @Test
    public void handleControllerRegisterDevice() throws Exception {
        // send register message
        Spa spa = new Spa();
        spa.set_id("spaId");
        spaRepository.save(spa);

        final Collection<Bwg.Metadata> metadata = newArrayList();
        final Bwg.Uplink.Model.RegisterDevice registerDevice = BwgHelper.buildRegisterDevice("spaId", "controller", "1", metadata);
        mqttSendService.sendMessage(messageProcessorConfiguration.getUplinkTopicName(), BwgHelper.buildUplinkMessage("1", "spaId", Bwg.Uplink.UplinkCommandType.REGISTRATION, registerDevice));

        // wait for message to be delivered and processed
        Thread.sleep(1000);

        final Page<Component> controller = componentRepository.findBySpaIdAndComponentType("spaId", ComponentType.CONTROLLER.name(), new PageRequest(0, 1));
        assertNull(controller.getContent().get(0).getSerialNumber());
        assertEquals(controller.getContent().get(0).getSpaId(), "spaId");
    }

    @Test
    public void handleBaseSpaStateConditions() throws Exception {
        // send register message
        Spa spa = new Spa();
        spa.set_id("spaId");
        spaRepository.save(spa);

        SpaState state = SpaState.newBuilder()
                .setComponents(Components.newBuilder().setLastUpdateTimestamp(1L).setFilterCycle1(ToggleComponent.newBuilder().setCurrentState(ToggleComponent.State.ON).addAllAvailableStates(newArrayList(ToggleComponent.State.OFF, ToggleComponent.State.ON))))
                .setController(Controller.newBuilder()
                        .setPackType("NGSC")
                        .setErrorCode(0)
                        .setHour(0)
                        .setABDisplay(false)
                        .setAllSegsOn(false)
                        .setBluetoothStatus(BluetoothStatus.NOT_PRESENT)
                        .setCelsius(false)
                        .setCleanupCycle(false)
                        .setCurrentWaterTemp(5)
                        .setDemoMode(false)
                        .setEcoMode(false)
                        .setElapsedTimeDisplay(false)
                        .setHeaterCooling(false)
                        .setHeatExternallyDisabled(false)
                        .setInvert(false)
                        .setLastUpdateTimestamp(new Date().getTime())
                        .setLatchingMessage(false)
                        .setLightCycle(false)
                        .setMessageSeverity(0)
                        .setMilitary(false)
                        .setMinute(0)
                        .setOverrangeEnabled(false)
                        .setUiCode(0)
                        .setUiSubCode(0)
                        .setPanelLock(false)
                        .setTempLock(false)
                        .setPanelMode(PanelMode.PANEL_MODE_NGSC)
                        .setPrimingMode(false)
                        .setRepeat(false)
                        .setHeaterMode(HeaterMode.REST)
                        .setSettingsLock(false)
                        .setSoakMode(false)
                        .setSoundAlarm(false)
                        .setSpaOverheatDisabled(false)
                        .setSpecialTimeouts(false)
                        .setStirring(false)
                        .setSwimSpaMode(SwimSpaMode.SWIM_MODE_SWIM)
                        .setSwimSpaModeChanging(false)
                        .setTargetWaterTemperature(9)
                        .setTempRange(TempRange.LOW)
                        .setTestMode(false)
                        .setTimeNotSet(false)
                        .setTvLiftState(0)
                        .build())
                .build();

        byte[] payload = BwgHelper.buildUplinkMessage("originator", "spaId", UplinkCommandType.SPA_STATE, state);
        mqttSendService.sendMessage(messageProcessorConfiguration.getUplinkTopicName(), payload);

        // wait for message to be delivered and processed
        Thread.sleep(5000);

        // check that default state components were added for gateway and controller
        spa = spaRepository.findOne("spaId");
        assertEquals(spa.getCurrentState().getCurrentTemp(), "5");
        assertThat(spa.getCurrentState()
                .getComponents()
                .stream()
                .map(ComponentState::getComponentType).collect(toList()), containsInAnyOrder("GATEWAY", "CONTROLLER", "FILTER"));

        spa.getCurrentState()
                .getComponents()
                .stream()
                .forEach(compState -> {
                    if (compState.getRegisteredTimestamp() == null) {
                        fail("component type should havea registered timestamp");
                    }
                });
    }

    @Test
    public void handleDownlinkAck() throws Exception {
        // build command
        final SpaCommand command = new SpaCommand();
        command.setOriginatorId("1");
        command.setSpaId("1");
        spaCommandRepository.save(command);

        // build message
        final Bwg.Uplink.Model.DownlinkAcknowledge ackMessage = BwgHelper.buildDownlinkAcknowledge(Bwg.AckResponseCode.OK, "All ok");
        final byte[] payload = BwgHelper.buildUplinkMessage("1", "1", UplinkCommandType.ACKNOWLEDGEMENT, ackMessage);
        mqttSendService.sendMessage(messageProcessorConfiguration.getUplinkTopicName(), payload);

        // wait for message to be delivered and processed
        Thread.sleep(1000);

        // command is processed, ack OK response state shall be set on it
        final SpaCommand processedCommand = spaCommandRepository.findByOriginatorIdAndSpaId("1", "1");
        assertNotNull(processedCommand);
        assertEquals("OK", processedCommand.getAckResponseCode());
    }

    @Test
    public void handleFaultLogs() throws Exception {
        faultLogRepository.deleteAll();

        // send register message
        Spa spa = new Spa();
        spa.set_id("spaId");
        spaRepository.save(spa);

        FaultLogManager faultLogManager = new FaultLogManager(new Properties());
        faultLogManager.addFaultLogEntry(new FaultLogEntry(0, 1, new Date().getTime(), 100, 101, 102, false));

        final Bwg.Uplink.Model.FaultLogs faultLogs = faultLogManager.getUnsentFaultLogs();
        mqttSendService.sendMessage(messageProcessorConfiguration.getUplinkTopicName(), BwgHelper.buildUplinkMessage("1", "spaId", UplinkCommandType.FAULT_LOGS, faultLogs));

        // wait for message to be delivered and processed
        Thread.sleep(1000);

        final List<FaultLog> logs = faultLogRepository.findAll();
        assertNotNull(logs);
        assertEquals(1, logs.size());
        assertEquals("spaId", logs.get(0).getSpaId());
        assertEquals(1, logs.get(0).getCode());
        assertEquals(100, logs.get(0).getTargetTemp());
        assertEquals(101, logs.get(0).getSensorATemp());
        assertEquals(102, logs.get(0).getSensorBTemp());
    }

    @Test
    public void handleWifiStats() throws Exception {
        wifiStatRepository.deleteAll();

        // send register message
        Spa spa = new Spa();
        spa.set_id("spaId");
        spaRepository.save(spa);

        final Bwg.Uplink.Model.WifiStat wifiStat = Bwg.Uplink.Model.WifiStat.newBuilder().
                setMode("mode").
                setWifiConnectionHealth(Bwg.Uplink.Model.Constants.WifiConnectionHealth.AVG).
                setRecordedDate(System.currentTimeMillis()).
                build();
        final Bwg.Uplink.Model.WifiStats wifiStats = Bwg.Uplink.Model.WifiStats.newBuilder().addWifiStats(wifiStat).build();

        mqttSendService.sendMessage(messageProcessorConfiguration.getUplinkTopicName(), BwgHelper.buildUplinkMessage("1", "spaId", UplinkCommandType.WIFI_STATS, wifiStats));

        // wait for message to be delivered and processed
        Thread.sleep(1000);

        final List<WifiStat> stats = wifiStatRepository.findAll();
        assertNotNull(stats);
        assertEquals(1, stats.size());
        assertEquals("spaId", stats.get(0).getSpaId());
        assertEquals("mode", stats.get(0).getMode());
        assertEquals(WifiConnectionHealth.AVG, stats.get(0).getWifiConnectionHealth());
    }
}
