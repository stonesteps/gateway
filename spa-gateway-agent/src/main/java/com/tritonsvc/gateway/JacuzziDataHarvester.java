package com.tritonsvc.gateway;

import com.google.common.base.Throwables;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.Model.Components;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.Model.Components.BlowerComponent;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.Model.Components.LightComponent;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.Model.Components.LightComponent.State;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.Model.Components.PumpComponent;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.Model.Components.ToggleComponent;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.Model.Constants;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.Model.Constants.BluetoothStatus;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.Model.Constants.HeaterMode;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.Model.Constants.PanelDisplayCode;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.Model.Controller;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.Model.DipSwitch;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.Model.SpaState;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.Model.SystemInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Date;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Implementation of serial comms for BWG NGSC Protocol
 */
public class JacuzziDataHarvester extends RS485DataHarvester {
    private static Logger LOGGER = LoggerFactory.getLogger(JacuzziDataHarvester.class);
    private BWGProcessor processor;
    private JacuzziMessagePublisher rs485MessagePublisher;
    private AtomicReference<Boolean> isCelsius = new AtomicReference<>();
    private byte[] lightInfo = new byte[]{};
    private boolean populatedSystemInfo;

    /**
     * Constructor
     *
     * @param processor
     */
    public JacuzziDataHarvester(BWGProcessor processor, JacuzziMessagePublisher rs485MessagePublisher) {
        super(processor, rs485MessagePublisher);
        this.rs485MessagePublisher = rs485MessagePublisher;
        this.processor = processor;
    }

    @Override
    public void processMessage(byte[] message) {
        int packetType = message[3];

        if (packetType == 0x0) {
            processUnassignedDevicePoll();
        } else if (packetType == 0x2) {
            processAddressAssignment(message);
        } else if (packetType == 0x4) {
            processDevicePresentQuery();
        } else if (packetType == 0x6) {
            processDevicePollForDownlink();
        } else if (packetType == 0x16) {
            isCelsius.set((0x01 & message[17]) > 0);
            processPanelUpdateMessage(message);
            if (!populatedSystemInfo) {
                processSystemInfoMessage(message);
                populatedSystemInfo = true;
            }
        } else if (packetType == 0x23) {
            processLightStatusMessage(message);
        } else if (packetType == 0x1D) {
            processDeviceConfigsMessage(message);
            processSystemInfoMessage(message);
        }
    }

    @Override
    public void populateComponentStateFromPanelUpdate(Components.Builder compsBuilder, byte[] message) {
        if (compsBuilder.hasAux1()) {
            compsBuilder.setAux1(ToggleComponent.newBuilder(compsBuilder.getAux1()).setCurrentState(ToggleComponent.State.valueOf((0x02 & message[16]) >> 1)));
        }
        if (compsBuilder.hasAux2()) {
            compsBuilder.setAux2(ToggleComponent.newBuilder(compsBuilder.getAux2()).setCurrentState(ToggleComponent.State.valueOf((0x04 & message[16]) >> 2)));
        }

        if (compsBuilder.hasOzone()) {
            compsBuilder.setOzone(ToggleComponent.newBuilder(compsBuilder.getOzone()).setCurrentState(ToggleComponent.State.valueOf((0x02 & message[15]) >> 1)));
        }
        if (compsBuilder.hasUv()) {
            compsBuilder.setUv(ToggleComponent.newBuilder(compsBuilder.getUv()).setCurrentState(ToggleComponent.State.valueOf((0x04 & message[15]) >> 2)));
        }
        if (compsBuilder.hasHeater1()) {
            compsBuilder.setHeater1(Components.HeaterState.valueOf(0x01 & message[15]));
        }

        if (compsBuilder.hasFilterCycle1()) {
            compsBuilder.setFilterCycle1(ToggleComponent.newBuilder(compsBuilder.getFilterCycle1()).setCurrentState(ToggleComponent.State.valueOf((0x10 & message[15]) >> 4)));
        }
        if (compsBuilder.hasFilterCycle2()) {
            compsBuilder.setFilterCycle2(ToggleComponent.newBuilder(compsBuilder.getFilterCycle2()).setCurrentState(ToggleComponent.State.valueOf((0x20 & message[15]) >> 5)));
        }

        if (compsBuilder.hasPump1()) {
            compsBuilder.setPump1(PumpComponent.newBuilder(compsBuilder.getPump1()).setCurrentState(PumpComponent.State.valueOf((0x0C & message[14]) >> 2)));
        }
        if (compsBuilder.hasPump2()) {
            compsBuilder.setPump2(PumpComponent.newBuilder(compsBuilder.getPump2()).setCurrentState(PumpComponent.State.valueOf((0x30 & message[14]) >> 4)));
        }
        if (compsBuilder.hasPump3()) {
            compsBuilder.setPump3(PumpComponent.newBuilder(compsBuilder.getPump3()).setCurrentState(PumpComponent.State.valueOf((0xC0 & message[14]) >> 6)));
        }
        if (compsBuilder.hasCirculationPump()) {
            compsBuilder.setCirculationPump(PumpComponent.newBuilder(compsBuilder.getCirculationPump()).setCurrentState(PumpComponent.State.valueOf(0x03 & message[14])));
        }
        if (compsBuilder.hasBlower1()) {
            compsBuilder.setBlower1(BlowerComponent.newBuilder(compsBuilder.getBlower1()).setCurrentState(BlowerComponent.State.valueOf((0x08 & message[15]) >> 3)));
        }
    }

    @Override
    public Controller populateControllerStateFromPanelUpdate(byte[] message) {
        BluetoothStatus btStatus = BluetoothStatus.NOT_PRESENT;
        if ((0x0F & message[31]) == 1) {
            btStatus = BluetoothStatus.CONNECTED;
        } else if ((0x0F & message[31]) == 15) {
            btStatus = BluetoothStatus.AMPLIFIER_COMMS_LOST;
        }

        Controller.Builder builder =  Controller.newBuilder()
                .setLastUpdateTimestamp(new Date().getTime())
                .setHour(0xFF & message[4])
                .setMinute(0xFF & message[5])
                .setErrorCode(0xFF & message[10])
                .setCurrentWaterTemp(bwgTempToFahrenheit((0xFF & message[11])))
                .setTargetWaterTemperature(bwgTempToFahrenheit((0xFF & message[12])))
                .setCelsius(isCelsius.get())
                .setCleanupCycle((0x08 & message[23]) > 0)
                .setDemoMode((0x08 & message[17]) > 0)
                .setSettingsLock((0x04 & message[19]) > 0)
                .setTimeNotSet((0x04 & message[17]) > 0)
                .setSpaOverheatDisabled((0x80 & message[16]) > 0)
                .setHeaterMode(HeaterMode.valueOf((0x30 & message[9]) >> 4) == null ? HeaterMode.REST : HeaterMode.valueOf((0x30 & message[9]) >> 4))
                .setBluetoothStatus(btStatus)
                // now add some optionals that jacuzzi kicks in
                .setRegistrationLockout((message[17] & 0x20) > 0)
                .setEngineeringMode((message[17] & 0x10) > 0)
                .setAccessLocked((message[19] & 0x02) > 0)
                .setMaintenanceLocked((message[19] & 0x01) > 0)
                .setAmbientTemp(0xFF & message[12])
                .setDay(0x1F & message[6])
                .setMonth(0xFF & message[7])
                .setYear(2000 + (0xFF & message[8]))
                .setReminderDaysClearRay((0xFF & message[23]) << 8 | (0xFF & message[24]))
                .setReminderDaysFilter1((0xFF & message[27]) << 8 | (0xFF & message[28]))
                .setReminderDaysFilter1((0xFF & message[29]) << 8 | (0xFF & message[30]))
                .setReminderDaysWater((0xFF & message[25]) << 8 | (0xFF & message[26]))
                .setBlowout((message[15] & 0x80) > 0)
                .setWaterLevel2((message[16] & 0x10) > 0)
                .setWaterLevel1((message[16] & 0x08) > 0)
                .setFlowSwitchClosed((message[16] & 0x01) > 0)
                .setChangeUV((message[17] & 0x80) > 0)
                .setHiLimitTemp(0xFF & message[20]);

        if (Constants.ReminderCode.valueOf((0xFF & message[32])) != null) {
            builder.setReminderCode(Constants.ReminderCode.valueOf((0xFF & message[32])));
        }
        if (Constants.SpaState.valueOf(0x0F & message[9]) != null) {
            builder.setSpaState(Constants.SpaState.valueOf(0x0F & message[9]));
        }
        if (Constants.Filtration.FiltrationMode.valueOf((0xC0 & message[9]) >> 6) != null) {
            builder.setSecondaryFiltrationMode(Constants.Filtration.FiltrationMode.valueOf((0xC0 & message[9]) >> 6));
        }

        return builder.build();
    }

    @Override
    public Boolean usesCelsius() {
        return isCelsius.get();
    }

    @Override
    public Components populateDeviceConfigsFromMessage(byte[] message, Components.Builder compsBuilder) {
        int value = (0x0C & message[10]) >> 2;
        if (value > 0) {
            compsBuilder.setPump1(PumpComponent.newBuilder(compsBuilder.getPump1()).clearAvailableStates().addAllAvailableStates(getAvailablePumpStates(value)));
        } else {
            compsBuilder.clearPump1();
        }

        value = ((0x30 & message[10]) >> 4);
        if (value > 0) {
            compsBuilder.setPump2(PumpComponent.newBuilder(compsBuilder.getPump2()).clearAvailableStates().addAllAvailableStates(getAvailablePumpStates(value)));
        } else {
            compsBuilder.clearPump2();
        }

        value = ((0xC0 & message[10]) >> 6);
        if (value > 0) {
            compsBuilder.setPump3(PumpComponent.newBuilder(compsBuilder.getPump3()).clearAvailableStates().addAllAvailableStates(getAvailablePumpStates(value)));
        } else {
            compsBuilder.clearPump3();
        }

        value = (0xF0 & message[12]);
        if (value > 0) {
            compsBuilder.setLight1(LightComponent.newBuilder(compsBuilder.getLight1()).clearAvailableStates().addAllAvailableStates(getAvailableLightStates(3)));
            compsBuilder.setLight2(LightComponent.newBuilder(compsBuilder.getLight2()).clearAvailableStates().addAllAvailableStates(getAvailableLightStates(3)));
            compsBuilder.setLight3(LightComponent.newBuilder(compsBuilder.getLight3()).clearAvailableStates().addAllAvailableStates(getAvailableLightStates(3)));
            compsBuilder.setLight4(LightComponent.newBuilder(compsBuilder.getLight4()).clearAvailableStates().addAllAvailableStates(getAvailableLightStates(3)));
        } else {
            compsBuilder.clearLight1();
            compsBuilder.clearLight2();
            compsBuilder.clearLight3();
            compsBuilder.clearLight4();
        }

        value = (0x0F & message[12]);
        if (value > 0) {
            compsBuilder.setAudioVisual(1);
        } else {
            compsBuilder.clearAudioVisual();
        }

        value = (0x01 & message[11]);
        if (value > 0) {
            compsBuilder.setBlower1(BlowerComponent.newBuilder(compsBuilder.getBlower1()).clearAvailableStates().addAllAvailableStates(getAvailableBlowerStates(1)));
        } else {
            compsBuilder.clearBlower1();
        }

        if (!compsBuilder.hasHeater1()) {
            compsBuilder.setHeater1(Components.HeaterState.OFF);
        }

        if (!compsBuilder.hasOzone()) {
            compsBuilder.setOzone(ToggleComponent.newBuilder().addAllAvailableStates(getAvailableToggleStates()));
        }

        if (!compsBuilder.hasUv()) {
            compsBuilder.setUv(ToggleComponent.newBuilder().addAllAvailableStates(getAvailableToggleStates()));
        }

        value = (0x03 & message[10]);
        if ( value > 0) {
            compsBuilder.setCirculationPump(PumpComponent.newBuilder(compsBuilder.getCirculationPump()).clearAvailableStates().addAllAvailableStates(getAvailablePumpStates(value)));
        } else {
            compsBuilder.clearCirculationPump();
        }

        if (!compsBuilder.hasAux1()) {
            compsBuilder.setAux1(ToggleComponent.newBuilder().addAllAvailableStates(getAvailableToggleStates()));
        }

        if (!compsBuilder.hasAux2()) {
            compsBuilder.setAux2(ToggleComponent.newBuilder().addAllAvailableStates(getAvailableToggleStates()));
        }

        // filter cycle 1 and 2 always there
        if (!compsBuilder.hasFilterCycle1()) {
            compsBuilder.setFilterCycle1(ToggleComponent.newBuilder().addAllAvailableStates(getAvailableToggleStates()));
        }

        if (!compsBuilder.hasFilterCycle2()) {
            compsBuilder.setFilterCycle2(ToggleComponent.newBuilder().addAllAvailableStates(getAvailableToggleStates()));
        }

        compsBuilder.setLastUpdateTimestamp(new Date().getTime());
        return compsBuilder.build();
    }

    @Override
    public SystemInfo populateSystemInfoFromMessage(byte[] message, SystemInfo.Builder builder) {
        int packetType = message[3];

        if (packetType == 0x16) {
            builder.setPackMinorVersion(0xFF & message[22])
                    .setPackMajorVersion(0xFF & message[21])
                    .setLastUpdateTimestamp(new Date().getTime());
        }

        if (packetType == 0x1D) {
            long serialNumber = (0xFF & message[4]) << 24;
            serialNumber |= (0xFF & message[5]) << 16;
            serialNumber |= (0xFF & message[6]) << 8;
            serialNumber |= (0xFF & message[7]);

            builder.setSerialNumber((int)(0xFFFFFFFF & serialNumber))
                    .setVersionSSID(0xFF & message[8])
                    .setMinorVersion(0xFF & message[9])
                    .setLastUpdateTimestamp(new Date().getTime())
                    .clearDipSwitches()
                    .addDipSwitches(DipSwitch.newBuilder().setSlotNumber(10).setOn((0x08 & message[11]) > 0).build())
                    .addDipSwitches(DipSwitch.newBuilder().setSlotNumber(9).setOn((0x04 & message[11]) > 0).build())
                    .addDipSwitches(DipSwitch.newBuilder().setSlotNumber(8).setOn((0x80 & message[15]) > 0).build())
                    .addDipSwitches(DipSwitch.newBuilder().setSlotNumber(7).setOn((0x40 & message[15]) > 0).build())
                    .addDipSwitches(DipSwitch.newBuilder().setSlotNumber(6).setOn((0x20 & message[15]) > 0).build())
                    .addDipSwitches(DipSwitch.newBuilder().setSlotNumber(5).setOn((0x10 & message[15]) > 0).build())
                    .addDipSwitches(DipSwitch.newBuilder().setSlotNumber(4).setOn((0x08 & message[15]) > 0).build())
                    .addDipSwitches(DipSwitch.newBuilder().setSlotNumber(3).setOn((0x04 & message[15]) > 0).build())
                    .addDipSwitches(DipSwitch.newBuilder().setSlotNumber(2).setOn((0x02 & message[15]) > 0).build())
                    .addDipSwitches(DipSwitch.newBuilder().setSlotNumber(1).setOn((0x01 & message[15]) > 0).build());
        }
        return builder.build();
    }

    @Override
    public boolean hasAllConfigState() {
        if (getLatestSpaInfo().hasComponents() &&
                getLatestSpaInfo().hasSystemInfo() &&
                getLatestSpaInfo().getComponents().hasFilterCycle1()) {
            return true;
        }
        LOGGER.info("do not have DeviceConfig, SystemInfo yet, will send panel request");
        return false;
    }

    @Override
    public void verifyPanelCommandsAreReadyToExecute(boolean checkTemp) throws RS485Exception {
        if (!getLatestSpaInfo().hasController() ||
                !getLatestSpaInfo().hasComponents()) {
            throw new RS485Exception("Spa state has not been populated, cannot process requests yet");
        }
        if (getLatestSpaInfo().getController().getAccessLocked()){
            throw new RS485Exception("Spa is locked out, no requests are allowed.");
        }
    }

    private void processLightStatusMessage(byte[] message) {
        boolean wLocked = false;
        byte[] lightStateInfo = new byte[]{message[6], message[13], message[20], message[27]};

        if (Arrays.equals(lightInfo, lightStateInfo)) {
            return;
        } else {
            lightInfo = lightStateInfo;
        }

        try {
            Components.Builder compsBuilder = Components.newBuilder();
            getLatestSpaInfoLock().writeLock().lockInterruptibly();
            wLocked = true;
            if (getLatestSpaInfo().hasComponents()) {
                compsBuilder.mergeFrom(getLatestSpaInfo().getComponents());
            }

            if (compsBuilder.hasLight1()) {
                compsBuilder.setLight1(LightComponent.newBuilder(compsBuilder.getLight1()).setCurrentState(getLightState(0xFF & message[6])));
            }
            if (compsBuilder.hasLight2()) {
                compsBuilder.setLight2(LightComponent.newBuilder(compsBuilder.getLight2()).setCurrentState(getLightState(0xFF & message[13])));
            }
            if (compsBuilder.hasLight3()) {
                compsBuilder.setLight3(LightComponent.newBuilder(compsBuilder.getLight3()).setCurrentState(getLightState(0xFF & message[20])));
            }
            if (compsBuilder.hasLight4()) {
                compsBuilder.setLight4(LightComponent.newBuilder(compsBuilder.getLight4()).setCurrentState(getLightState(0xFF & message[27])));
            }
            compsBuilder.setLastUpdateTimestamp(new Date().getTime());

            SpaState.Builder builder = SpaState.newBuilder(getLatestSpaInfo());
            builder.setComponents(compsBuilder.build());
            builder.setLastUpdateTimestamp(compsBuilder.getLastUpdateTimestamp());
            setLatestSpaInfo(builder.build());
        } catch (Exception ex) {
            throw Throwables.propagate(ex);
        } finally {
            if (wLocked) {
                getLatestSpaInfoLock().writeLock().unlock();
            }
        }

        LOGGER.info("processed light status info message");
    }

    private LightComponent.State getLightState(int percentage) {
        if (percentage < 25) {
            return State.OFF;
        } else if (percentage > 24 && percentage < 50) {
            return State.LOW;
        } else if (percentage > 49 && percentage < 75) {
            return State.MED;
        } else if (percentage > 74 && percentage < 101) {
            return State.HIGH;
        } else {
            return State.OFF;
        }
    }
}

