package com.tritonsvc.messageprocessor;

import com.bwg.iot.model.Component;
import com.bwg.iot.model.Component.ComponentType;
import com.bwg.iot.model.ComponentState;
import com.bwg.iot.model.Spa;
import com.bwg.iot.model.SpaCommand;
import com.tritonsvc.messageprocessor.mongo.repository.ComponentRepository;
import com.tritonsvc.messageprocessor.mongo.repository.SpaCommandRepository;
import com.tritonsvc.messageprocessor.mongo.repository.SpaRepository;
import com.tritonsvc.messageprocessor.mqtt.MqttSendService;
import com.tritonsvc.spa.communication.proto.Bwg;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.Model.Components;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.Model.Components.ToggleComponent;
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

import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;

import static com.google.common.collect.Lists.newArrayList;
import static java.util.stream.Collectors.toList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
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
                        .setErrorCode(0)
                        .setHour(0)
                        .setABDisplay(false)
                        .setAllSegsOn(false)
                        .setBluetoothStatus(0)
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
                        .setHeaterMode(0)
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
}
