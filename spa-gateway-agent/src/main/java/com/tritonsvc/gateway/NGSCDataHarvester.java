package com.tritonsvc.gateway;

import com.google.common.base.Throwables;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.Model.Components;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.Model.Components.BlowerComponent;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.Model.Components.LightComponent;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.Model.Components.PumpComponent;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.Model.Components.ToggleComponent;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.Model.Constants.BluetoothStatus;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.Model.Constants.HeaterMode;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.Model.Constants.PanelDisplayCode;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.Model.Constants.PanelMode;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.Model.Constants.SwimSpaMode;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.Model.Constants.TempRange;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.Model.Controller;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.Model.SetupParams;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.Model.SpaState;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.Model.SystemInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Calendar;
import java.util.Date;
import java.util.concurrent.atomic.AtomicReference;

import static com.google.common.collect.Lists.newArrayList;

/**
 * Implementation of serial comms for BWG NGSC Protocol
 */
public class NGSCDataHarvester extends RS485DataHarvester {
    private static Logger LOGGER = LoggerFactory.getLogger(NGSCDataHarvester.class);
    private NGSCMessagePublisher rs485MessagePublisher;
    private AtomicReference<Boolean> isCelsius = new AtomicReference<>();

    /**
     * Constructor
     *
     * @param processor
     * @param rs485MessagePublisher
     * @param faultLogManager
     */
    public NGSCDataHarvester(BWGProcessor processor, NGSCMessagePublisher rs485MessagePublisher, FaultLogManager faultLogManager) {
        super(processor, rs485MessagePublisher, faultLogManager);
        this.rs485MessagePublisher = rs485MessagePublisher;
    }

    @Override
    public void processMessage(byte[] message) {
        int packetType = message[3];
        // FCS already checked

        if (packetType == 0x0) {
            processUnassignedDevicePoll();
        } else if (packetType == 0x2) {
            processAddressAssignment(message);
        } else if (packetType == 0x4) {
            processDevicePresentQuery();
        } else if (packetType == 0x6) {
            processDevicePollForDownlink();
        } else if (packetType == 0x13) {
            isCelsius.set((0x01 & message[13]) > 0);
            processPanelUpdateMessage(message);
        } else if (packetType == 0x23) {
            processFilterCycleInfoMessage(message);
        } else if (packetType == 0x24) {
            processSystemInfoMessage(message);
        } else if (packetType == 0x25) {
            processSetupParamsMessage(message);
        } else if (packetType == 0x28) {
            processFaultLogMessage(message);
        } else if (packetType == 0x2E) {
            processDeviceConfigsMessage(message);
        }
    }

    @Override
    public void populateComponentStateFromPanelUpdate(Components.Builder compsBuilder, byte[] message) {
        if (compsBuilder.hasAux1()) {
            compsBuilder.setAux1(ToggleComponent.newBuilder(compsBuilder.getAux1()).setCurrentState(ToggleComponent.State.valueOf((0x08 & message[19]) >> 3)));
        }
        if (compsBuilder.hasAux2()) {
            compsBuilder.setAux2(ToggleComponent.newBuilder(compsBuilder.getAux2()).setCurrentState(ToggleComponent.State.valueOf((0x10 & message[19]) >> 4)));
        }
        if (compsBuilder.hasAux3()) {
            compsBuilder.setAux3(ToggleComponent.newBuilder(compsBuilder.getAux3()).setCurrentState(ToggleComponent.State.valueOf((0x20 & message[19]) >> 5)));
        }
        if (compsBuilder.hasAux4()) {
            compsBuilder.setAux4(ToggleComponent.newBuilder(compsBuilder.getAux4()).setCurrentState(ToggleComponent.State.valueOf((0x40 & message[19]) >> 6)));
        }

        if (compsBuilder.hasMister3()) {
            compsBuilder.setMister3(ToggleComponent.newBuilder(compsBuilder.getMister3()).setCurrentState(ToggleComponent.State.valueOf((0x04 & message[19]) >> 2)));
        }
        if (compsBuilder.hasMister2()) {
            compsBuilder.setMister2(ToggleComponent.newBuilder(compsBuilder.getMister2()).setCurrentState(ToggleComponent.State.valueOf((0x02 & message[19]) >> 1)));
        }
        if (compsBuilder.hasMister1()) {
            compsBuilder.setMister1(ToggleComponent.newBuilder(compsBuilder.getMister1()).setCurrentState(ToggleComponent.State.valueOf(0x01 & message[19])));
        }

        if (compsBuilder.hasMicroSilk()) {
            compsBuilder.setMicroSilk(ToggleComponent.newBuilder(compsBuilder.getMicroSilk()).setCurrentState(ToggleComponent.State.valueOf((0x02 & message[26]) >> 1)));
        }
        if (compsBuilder.hasOzone()) {
            compsBuilder.setOzone(ToggleComponent.newBuilder(compsBuilder.getOzone()).setCurrentState(ToggleComponent.State.valueOf((0x04 & message[14]) >> 2)));
        }

        if (compsBuilder.hasHeater1()) {
            compsBuilder.setHeater1(Components.HeaterState.valueOf((0x30 & message[14]) >> 4));
        }
        if (compsBuilder.hasHeater2()) {
            compsBuilder.setHeater2(Components.HeaterState.valueOf((0xC0 & message[14]) >> 6));
        }

        if (compsBuilder.hasFilterCycle1()) {
            compsBuilder.setFilterCycle1(ToggleComponent.newBuilder(compsBuilder.getFilterCycle1()).setCurrentState(ToggleComponent.State.valueOf((0x04 & message[13]) >> 2)));
        }
        if (compsBuilder.hasFilterCycle2()) {
            compsBuilder.setFilterCycle2(ToggleComponent.newBuilder(compsBuilder.getFilterCycle2()).setCurrentState(ToggleComponent.State.valueOf((0x08 & message[13]) >> 3)));
        }

        if (compsBuilder.hasPump1()) {
            compsBuilder.setPump1(PumpComponent.newBuilder(compsBuilder.getPump1()).setCurrentState(PumpComponent.State.valueOf(0x03 & message[15])));
        }
        if (compsBuilder.hasPump2()) {
            compsBuilder.setPump2(PumpComponent.newBuilder(compsBuilder.getPump2()).setCurrentState(PumpComponent.State.valueOf((0x0C & message[15]) >> 2)));
        }
        if (compsBuilder.hasPump3()) {
            compsBuilder.setPump3(PumpComponent.newBuilder(compsBuilder.getPump3()).setCurrentState(PumpComponent.State.valueOf((0x30 & message[15]) >> 4)));
        }
        if (compsBuilder.hasPump4()) {
            compsBuilder.setPump4(PumpComponent.newBuilder(compsBuilder.getPump4()).setCurrentState(PumpComponent.State.valueOf((0xC0 & message[15]) >> 6)));
        }
        if (compsBuilder.hasPump5()) {
            compsBuilder.setPump5(PumpComponent.newBuilder(compsBuilder.getPump5()).setCurrentState(PumpComponent.State.valueOf(0x03 & message[16])));
        }
        if (compsBuilder.hasPump6()) {
            compsBuilder.setPump6(PumpComponent.newBuilder(compsBuilder.getPump6()).setCurrentState(PumpComponent.State.valueOf((0x0C & message[16]) >> 2)));
        }
        if (compsBuilder.hasPump7()) {
            compsBuilder.setPump7(PumpComponent.newBuilder(compsBuilder.getPump7()).setCurrentState(PumpComponent.State.valueOf((0x30 & message[16]) >> 4)));
        }
        if (compsBuilder.hasPump8()) {
            compsBuilder.setPump8(PumpComponent.newBuilder(compsBuilder.getPump8()).setCurrentState(PumpComponent.State.valueOf((0xC0 & message[16]) >> 6)));
        }
        boolean circPumpOn = (0x03 & message[17]) > 0;
        if (compsBuilder.hasCirculationPump()) {
            compsBuilder.setCirculationPump(PumpComponent.newBuilder(compsBuilder.getCirculationPump()).setCurrentState(circPumpOn ? PumpComponent.State.HIGH : PumpComponent.State.OFF));
        }

        if (compsBuilder.hasBlower1()) {
            compsBuilder.setBlower1(BlowerComponent.newBuilder(compsBuilder.getBlower1()).setCurrentState(BlowerComponent.State.valueOf((0x0C & message[17]) >> 2)));
        }
        if (compsBuilder.hasBlower2()) {
            compsBuilder.setBlower2(BlowerComponent.newBuilder(compsBuilder.getBlower2()).setCurrentState(BlowerComponent.State.valueOf((0x30 & message[17]) >> 4)));
        }
        if (compsBuilder.hasFiberWheel()) {
            compsBuilder.setFiberWheel(0xC0 & message[17] >> 6);
        }

        if (compsBuilder.hasLight1()) {
            compsBuilder.setLight1(LightComponent.newBuilder(compsBuilder.getLight1()).setCurrentState(LightComponent.State.valueOf(0x03 & message[18])));
        }
        if (compsBuilder.hasLight2()) {
            compsBuilder.setLight2(LightComponent.newBuilder(compsBuilder.getLight2()).setCurrentState(LightComponent.State.valueOf((0x0C & message[18]) >> 2)));
        }
        if (compsBuilder.hasLight3()) {
            compsBuilder.setLight3(LightComponent.newBuilder(compsBuilder.getLight3()).setCurrentState(LightComponent.State.valueOf((0x30 & message[18]) >> 4)));
        }
        if (compsBuilder.hasLight4()) {
            compsBuilder.setLight4(LightComponent.newBuilder(compsBuilder.getLight4()).setCurrentState(LightComponent.State.valueOf((0xC0 & message[18]) >> 6)));
        }
    }

    @Override
    public Controller populateControllerStateFromPanelUpdate(byte[] message) {
        return Controller.newBuilder()
                .setPackType("NGSC")
                .setErrorCode(0xFF & message[10])
                .setHour(0xFF & message[7])
                .setABDisplay((0x01 & message[25]) > 0)
                .setAllSegsOn((0x40 & message[13]) > 0)
                .setBluetoothStatus(BluetoothStatus.valueOf((0xF0 & message[27]) >> 4) != null ? BluetoothStatus.valueOf((0xF0 & message[27]) >> 4) : BluetoothStatus.NOT_PRESENT)
                .setCelsius(isCelsius.get())
                .setCleanupCycle((0x08 & message[23]) > 0)
                .setCurrentWaterTemp(bwgTempToFahrenheit((0xFF & message[6])))
                .setDemoMode((0x10 & message[23]) > 0)
                .setEcoMode((0x04 & message[26]) > 0)
                .setElapsedTimeDisplay((0x80 & message[25]) > 0)
                .setHeaterCooling((0x40 & message[23]) > 0)
                .setHeatExternallyDisabled((0x01 & message[27]) > 0)
                .setInvert((0x80 & message[13]) > 0)
                .setLastUpdateTimestamp(new Date().getTime())
                .setLatchingMessage((0x20 & message[23]) > 0)
                .setLightCycle((0x01 & message[23]) > 0)
                .setMessageSeverity((0x0F & message[22]))
                .setMilitary((0x02 & message[13]) > 0)
                .setMinute(0xFF & message[8])
                .setOverrangeEnabled((0x02 & message[27]) > 0)
                .setUiCode(0xFF & message[4])
                .setUiSubCode(0xFF & message[5])
                .setPanelLock((0x20 & message[13]) > 0)
                .setTempLock((0x10 & message[13]) > 0)
                .setPanelMode(PanelMode.valueOf((0xC0 & message[13])))
                .setPrimingMode((0x02 & message[14]) > 0)
                .setRepeat((0x80 & message[19]) > 0)
                .setHeaterMode(HeaterMode.valueOf(0xFF & message[9]) == null ? HeaterMode.REST : HeaterMode.valueOf(0xFF & message[9]))
                .setSettingsLock((0x08 & message[25]) > 0)
                .setSoakMode((0x01 & message[26]) > 0)
                .setSoundAlarm((0x01 & message[14]) > 0)
                .setSpaOverheatDisabled((0x04 & message[25]) > 0)
                .setSpecialTimeouts((0x02 & message[25]) > 0)
                .setStirring((0x08 & message[26]) > 0)
                .setSwimSpaMode(SwimSpaMode.valueOf((0x30 & message[22]) >> 4))
                .setSwimSpaModeChanging((0x08 & message[23]) > 0)
                .setTargetWaterTemperature(bwgTempToFahrenheit((0xFF & message[24])))
                .setTempRange(TempRange.valueOf((0x04 & message[14]) >> 2))
                .setTestMode((0x04 & message[23]) > 0)
                .setTimeNotSet((0x02 & message[23]) > 0)
                .setTvLiftState((0x70 & message[25]) >> 4)
                .build();
    }

    @Override
    public Boolean usesCelsius() {
        return isCelsius.get();
    }

    @Override
    public SystemInfo populateSystemInfoFromMessage(byte[] message, SystemInfo.Builder builder) {
        long signature = (0xFF & message[17]) << 24;
        signature |= (0xFF & message[18]) << 16;
        signature |= (0xFF & message[18]) << 8;
        signature |= (0xFF & message[18]);

        return builder
                .setLastUpdateTimestamp(new Date().getTime())
                .setCurrentSetup(0xFF & message[16])
                .setHeaterPower(0x0F & message[21])
                .setHeaterType((0xF0 & message[21]) >> 4)
                .setMfrSSID(0xFF & message[4])
                .setSwSignature((int) (0xFFFFFFFF & signature))
                .setMinorVersion(0xFF & message[7])
                .setModelSSID(0xFF & message[5])
                .setVersionSSID(0xFF & message[6])
                .build();
    }

    @Override
    public boolean hasAllConfigState() {
        if (getLatestSpaInfo().hasComponents() &&
                getLatestSpaInfo().hasSystemInfo() &&
                getLatestSpaInfo().hasSetupParams() &&
                getLatestSpaInfo().getComponents().hasFilterCycle1()) {
            return true;
        }
        LOGGER.info("do not have all DeviceConfig, SystemInfo, SetupParams, FilterCycle yet, will send panel request");
        return false;
    }

    @Override
    public void verifyPanelCommandsAreReadyToExecute(boolean checkTemp) throws RS485Exception {
        if (!getLatestSpaInfo().hasController() ||
                !getLatestSpaInfo().hasComponents() ||
                !getLatestSpaInfo().hasSetupParams()) {
            throw new RS485Exception("Spa state has not been populated, cannot process requests yet");
        }
        if (getLatestSpaInfo().getController().getUiCode() == PanelDisplayCode.DEMO.getNumber() ||
                getLatestSpaInfo().getController().getUiCode() == PanelDisplayCode.STANDBY.getNumber() ||
                getLatestSpaInfo().getController().getPrimingMode() ||
                getLatestSpaInfo().getController().getPanelLock() ||
                (checkTemp && getLatestSpaInfo().getController().getTempLock())){
            throw new RS485Exception("Spa is locked out, no requests are allowed.");
        }
    }

    private void processFilterCycleInfoMessage(byte[] message) {
        boolean wLocked = false;
        Components.Builder compsBuilder = Components.newBuilder();
        try {
            getLatestSpaInfoLock().writeLock().lockInterruptibly();
            wLocked = true;
            if (getLatestSpaInfo().hasComponents()) {
                compsBuilder.mergeFrom(getLatestSpaInfo().getComponents());
            }

            // filter cycle 1 always there
            ToggleComponent.Builder builder = ToggleComponent.newBuilder().addAllAvailableStates(getAvailableToggleStates());
            if (compsBuilder.hasFilterCycle1()) {
                builder.setCurrentState(compsBuilder.getFilterCycle1().getCurrentState());
            }
            compsBuilder.setFilterCycle1(builder);

            if ( (0x80 & message[8]) > 0) {
                builder = ToggleComponent.newBuilder().addAllAvailableStates(getAvailableToggleStates());
                if (compsBuilder.hasFilterCycle2()) {
                    builder.setCurrentState(compsBuilder.getFilterCycle2().getCurrentState());
                }
                compsBuilder.setFilterCycle2(builder);

            } else {
                compsBuilder.clearFilterCycle2();
            }
            compsBuilder.setLastUpdateTimestamp(new Date().getTime());
            SpaState.Builder stateBuilder = SpaState.newBuilder(getLatestSpaInfo());
            stateBuilder.setComponents(compsBuilder.build());
            stateBuilder.setLastUpdateTimestamp(compsBuilder.getLastUpdateTimestamp());
            setLatestSpaInfo(stateBuilder.build());
        } catch (Exception ex) {
            LOGGER.error("problem while updated filter state", ex);
        } finally {
            if (wLocked) {
                getLatestSpaInfoLock().writeLock().unlock();
            }
        }

        LOGGER.info("processed filter cycle info message");
        rs485MessagePublisher.sendFilterCycleRequestIfPending(message, getSpaClock());
    }

    private void processSetupParamsMessage(byte[] message) {
        SetupParams setupParams = SetupParams.newBuilder()
                .setLowRangeLow(0xFF & message[6])
                .setLowRangeHigh(0xFF & message[7])
                .setHighRangeHigh(0xFF & message[9])
                .setHighRangeLow(0xFF & message[8])
                .setGfciEnabled((0x08 & message[10]) > 0)
                .setDrainModeEnabled((0x04 & message[10]) > 0)
                .setLastUpdateTimestamp(new Date().getTime())
                .build();

        boolean wLocked = false;
        try {
            getLatestSpaInfoLock().writeLock().lockInterruptibly();
            wLocked = true;
            SpaState.Builder builder = SpaState.newBuilder(getLatestSpaInfo());
            builder.setSetupParams(setupParams);
            builder.setLastUpdateTimestamp(setupParams.getLastUpdateTimestamp());
            setLatestSpaInfo(builder.build());
        } catch (Exception ex) {
            throw Throwables.propagate(ex);
        } finally {
            if (wLocked) {
                getLatestSpaInfoLock().writeLock().unlock();
            }
        }
        LOGGER.info("processed setup params message");
    }

    @Override
    public Components populateDeviceConfigsFromMessage(byte[] message, Components.Builder compsBuilder) {
        int value = (0x03 & message[4]);
        if (value > 0) {
            compsBuilder.setPump1(PumpComponent.newBuilder(compsBuilder.getPump1()).clearAvailableStates().addAllAvailableStates(getAvailablePumpStates(value)));
        } else {
            compsBuilder.clearPump1();
        }

        value = ((0x0C & message[4]) >> 2);
        if (value > 0) {
            compsBuilder.setPump2(PumpComponent.newBuilder(compsBuilder.getPump2()).clearAvailableStates().addAllAvailableStates(getAvailablePumpStates(value)));
        } else {
            compsBuilder.clearPump2();
        }

        value = ((0x30 & message[4]) >> 4);
        if (value > 0) {
            compsBuilder.setPump3(PumpComponent.newBuilder(compsBuilder.getPump3()).clearAvailableStates().addAllAvailableStates(getAvailablePumpStates(value)));
        } else {
            compsBuilder.clearPump3();
        }

        value = ((0xC0 & message[4]) >> 6);
        if (value > 0) {
            compsBuilder.setPump4(PumpComponent.newBuilder(compsBuilder.getPump4()).clearAvailableStates().addAllAvailableStates(getAvailablePumpStates(value)));
        } else {
            compsBuilder.clearPump4();
        }

        value = (0x03 & message[5]);
        if (value > 0) {
            compsBuilder.setPump5(PumpComponent.newBuilder(compsBuilder.getPump5()).clearAvailableStates().addAllAvailableStates(getAvailablePumpStates(value)));
        } else {
            compsBuilder.clearPump5();
        }

        value = ((0x0C & message[5]) >> 2);
        if (value > 0) {
            compsBuilder.setPump6(PumpComponent.newBuilder(compsBuilder.getPump6()).clearAvailableStates().addAllAvailableStates(getAvailablePumpStates(value)));
        } else {
            compsBuilder.clearPump6();
        }

        value = ((0x30 & message[5]) >> 4);
        if (value > 0) {
            compsBuilder.setPump7(PumpComponent.newBuilder(compsBuilder.getPump7()).clearAvailableStates().addAllAvailableStates(getAvailablePumpStates(value)));
        } else {
            compsBuilder.clearPump7();
        }

        value = ((0xC0 & message[5]) >> 6);
        if (value > 0) {
            compsBuilder.setPump8(PumpComponent.newBuilder(compsBuilder.getPump8()).clearAvailableStates().addAllAvailableStates(getAvailablePumpStates(value)));
        } else {
            compsBuilder.clearPump8();
        }

        value = (0x03 & message[6]);
        if (value > 0) {
            compsBuilder.setLight1(LightComponent.newBuilder(compsBuilder.getLight1()).clearAvailableStates().addAllAvailableStates(getAvailableLightStates(value)));
        } else {
            compsBuilder.clearLight1();
        }

        value = ((0x0C & message[6]) >> 2);
        if (value > 0) {
            compsBuilder.setLight2(LightComponent.newBuilder(compsBuilder.getLight2()).clearAvailableStates().addAllAvailableStates(getAvailableLightStates(value)));
        } else {
            compsBuilder.clearLight2();
        }

        value = ((0x30 & message[6]) >> 4);
        if (value > 0) {
            compsBuilder.setLight3(LightComponent.newBuilder(compsBuilder.getLight3()).clearAvailableStates().addAllAvailableStates(getAvailableLightStates(value)));
        } else {
            compsBuilder.clearLight3();
        }

        value = ((0xC0 & message[6]) >> 6);
        if (value > 0) {
            compsBuilder.setLight4(LightComponent.newBuilder(compsBuilder.getLight4()).clearAvailableStates().addAllAvailableStates(getAvailableLightStates(value)));
        } else {
            compsBuilder.clearLight4();
        }

        value = (0x03 & message[7]);
        if (value > 0) {
            compsBuilder.setBlower1(BlowerComponent.newBuilder(compsBuilder.getBlower1()).clearAvailableStates().addAllAvailableStates(getAvailableBlowerStates(value)));
        } else {
            compsBuilder.clearBlower1();
        }

        value = ((0x0C & message[7]) >> 2);
        if (value > 0) {
            compsBuilder.setBlower2(BlowerComponent.newBuilder(compsBuilder.getBlower2()).clearAvailableStates().addAllAvailableStates(getAvailableBlowerStates(value)));
        } else {
            compsBuilder.clearBlower2();
        }

        if ((0x10 & message[7]) > 0) {
            if (!compsBuilder.hasHeater1()) {
                compsBuilder.setHeater1(Components.HeaterState.OFF);
            }
        } else {
            compsBuilder.clearHeater1();
        }

        if ((0x20 & message[7]) > 0) {
            if (!compsBuilder.hasHeater2()) {
                compsBuilder.setHeater2(Components.HeaterState.OFF);
            }
        } else {
            compsBuilder.clearHeater2();
        }

        if ((0x40 & message[7]) > 0) {
            compsBuilder.setOzone(ToggleComponent.newBuilder(compsBuilder.getOzone()).clearAvailableStates().addAllAvailableStates(getAvailableToggleStates()));
        } else {
            compsBuilder.clearOzone();
        }

        if ((0x80 & message[7]) > 0) {
            compsBuilder.setCirculationPump(PumpComponent.newBuilder(compsBuilder.getCirculationPump()).clearAvailableStates().addAllAvailableStates(getAvailablePumpStates(2)));
        } else {
            compsBuilder.clearCirculationPump();
        }

        if ((0x01 & message[8]) > 0) {
            compsBuilder.setAux1(ToggleComponent.newBuilder(compsBuilder.getAux1()).clearAvailableStates().addAllAvailableStates(getAvailableToggleStates()));
        } else {
            compsBuilder.clearAux1();
        }

        if ((0x02 & message[8]) > 0) {
            compsBuilder.setAux2(ToggleComponent.newBuilder(compsBuilder.getAux2()).clearAvailableStates().addAllAvailableStates(getAvailableToggleStates()));
        } else {
            compsBuilder.clearAux2();
        }

        if ((0x04 & message[8]) > 0) {
            compsBuilder.setAux3(ToggleComponent.newBuilder(compsBuilder.getAux3()).clearAvailableStates().addAllAvailableStates(getAvailableToggleStates()));
        } else {
            compsBuilder.clearAux3();
        }

        if ((0x08 & message[8]) > 0) {
            compsBuilder.setAux4(ToggleComponent.newBuilder(compsBuilder.getAux4()).clearAvailableStates().addAllAvailableStates(getAvailableToggleStates()));
        } else {
            compsBuilder.clearAux4();
        }

        if ((0x10 & message[8]) > 0) {
            compsBuilder.setMister1(ToggleComponent.newBuilder(compsBuilder.getMister1()).clearAvailableStates().addAllAvailableStates(getAvailableToggleStates()));
        } else {
            compsBuilder.clearMister1();
        }

        if ((0x20 & message[8]) > 0) {
            compsBuilder.setMister2(ToggleComponent.newBuilder(compsBuilder.getMister2()).clearAvailableStates().addAllAvailableStates(getAvailableToggleStates()));
        } else {
            compsBuilder.clearMister2();
        }

        if ((0x40 & message[8]) > 0) {
            compsBuilder.setMister3(ToggleComponent.newBuilder(compsBuilder.getMister3()).clearAvailableStates().addAllAvailableStates(getAvailableToggleStates()));
        } else {
            compsBuilder.clearMister3();
        }

        if ((0x80 & message[8]) > 0) {
            compsBuilder.setMicroSilk(ToggleComponent.newBuilder(compsBuilder.getMicroSilk()).clearAvailableStates().addAllAvailableStates(getAvailableToggleStates()));
        } else {
            compsBuilder.clearMicroSilk();
        }

        if ((0xFF & message[9]) > 0) {
            if (!compsBuilder.hasFiberWheel()) {
                compsBuilder.setFiberWheel((0xFF & message[9]));
            }
        } else {
            compsBuilder.clearFiberWheel();
        }

        compsBuilder.setLastUpdateTimestamp(new Date().getTime());
        return compsBuilder.build();
    }

    private void processFaultLogMessage(byte[] message) {
        int number = message[5];
        int code = message[6];

        int daysAgo = message[7];
        int hour = message[8]; // 0-23
        int minute = message[9]; // 0-59
        long timestamp = buildTimestamp(daysAgo, hour, minute);

        boolean celcius = (0x08 & message[10] >> 3) == 1;
        int targetTemp = toFahrenheit(celcius, message[11]);
        int sensorATemp = toFahrenheit(celcius, message[12]);
        int sensorBTemp = toFahrenheit(celcius, message[13]);

        final FaultLogEntry entry = new FaultLogEntry(number, code, timestamp, targetTemp, sensorATemp, sensorBTemp, celcius);
        getFaultLogManager().addFaultLogEntry(entry);
        LOGGER.info("received fault log, code = {}, number = {}", code, number);
    }

    private long buildTimestamp(final int daysAgo, final int hour, final int minute) {
        final Calendar c = Calendar.getInstance();
        c.add(Calendar.DAY_OF_YEAR, -daysAgo);
        c.set(Calendar.HOUR_OF_DAY, hour);
        c.set(Calendar.MINUTE, minute);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    public static void main(String... args) {
        byte message = (byte) 0b11111111;
        System.out.print((0x08 & message) >> 3);
    }
}

