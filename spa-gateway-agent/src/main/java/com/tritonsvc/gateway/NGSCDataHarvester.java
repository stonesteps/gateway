package com.tritonsvc.gateway;

import com.google.common.base.Throwables;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.Model.Components;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.Model.Components.BlowerComponent;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.Model.Components.LightComponent;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.Model.Components.PumpComponent;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.Model.Components.ToggleComponent;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.Model.Constants.ComponentType;
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

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static com.google.common.collect.Lists.newArrayList;
import static javax.xml.bind.DatatypeConverter.printHexBinary;

/**
 * Implementation of serial comms for BWG NGSC Protocol
 */
public class NGSCDataHarvester extends RS485DataHarvester {
    private static Logger LOGGER = LoggerFactory.getLogger(NGSCDataHarvester.class);
    private BWGProcessor processor;
    private NGSCMessagePublisher rs485MessagePublisher;
    private AtomicReference<Boolean> isCelsius = new AtomicReference<>();
    private AtomicInteger HighLow = new AtomicInteger(0);
    private AtomicInteger HighHigh = new AtomicInteger(0);
    private AtomicInteger LowHigh = new AtomicInteger(0);
    private AtomicInteger LowLow = new AtomicInteger(0);

    /**
     * Constructor
     *
     * @param processor
     */
    public NGSCDataHarvester(BWGProcessor processor, NGSCMessagePublisher rs485MessagePublisher) {
        super(processor, rs485MessagePublisher);
        this.rs485MessagePublisher = rs485MessagePublisher;
        this.processor = processor;
    }

    @Override
    public void processMessage(byte[] message) {
        int packetType = message[3];
        if (!HdlcCrc.isValidFCS(message)) {
            LOGGER.debug("Invalid rs485 data message, failed FCS check {}", printHexBinary(message));
            return;
        }

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
                .setErrorCode(0xFF & message[10])
                .setHour(0xFF & message[7])
                .setABDisplay((0x01 & message[25]) > 0)
                .setAllSegsOn((0x40 & message[13]) > 0)
                .setBluetoothStatus((0xF0 & message[27]) >> 4)
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
                .setHeaterMode(0xFF & message[9])
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
    public Boolean requiresCelsius() {
        return isCelsius.get();
    }

    @Override
    public boolean withinHighRange(int tempFahr) {
        return tempFahr >= HighLow.get() && tempFahr <= HighHigh.get();
    }

    @Override
    public boolean withinLowRange(int tempFahr) {
        return tempFahr >= LowLow.get() && tempFahr <= LowHigh.get();
    }


    private void processSystemInfoMessage(byte[] message) {
        long signature = (0xFF & message[17]) << 24;
        signature |= (0xFF & message[18]) << 16;
        signature |= (0xFF & message[18]) << 8;
        signature |= (0xFF & message[18]);

        SystemInfo systemInfo = SystemInfo.newBuilder()
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

        boolean wLocked = false;
        try {
            getLatestSpaInfoLock().writeLock().lockInterruptibly();
            wLocked = true;
            SpaState.Builder builder = SpaState.newBuilder(getLatestSpaInfo());
            builder.setSystemInfo(systemInfo);
            builder.setLastUpdateTimestamp(new Date().getTime());
            setLatestSpaInfo(builder.build());
        } catch (Exception ex) {
            throw Throwables.propagate(ex);
        } finally {
            if (wLocked) {
                getLatestSpaInfoLock().writeLock().unlock();
            }
        }
        LOGGER.info("processed system info message");
    }

    private void processSetupParamsMessage(byte[] message) {
        HighLow.set(0xFF & message[8]);
        HighHigh.set(0xFF & message[9]);
        LowLow.set(0xFF & message[6]);
        LowHigh.set(0xFF & message[7]);

        SetupParams setupParams = SetupParams.newBuilder()
                .setLowRangeLow(LowLow.get())
                .setLowRangeHigh(LowHigh.get())
                .setHighRangeHigh(HighHigh.get())
                .setHighRangeLow(HighLow.get())
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
            builder.setLastUpdateTimestamp(new Date().getTime());
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

    private List<ToggleComponent.State> getAvailableToggleStates() {
        return newArrayList(ToggleComponent.State.OFF, ToggleComponent.State.ON);
    }

    private void processDeviceConfigsMessage(byte[] message) {
        boolean rLocked = false;
        Components.Builder compsBuilder = Components.newBuilder();
        Components components;
        try {
            getLatestSpaInfoLock().readLock().lockInterruptibly();
            rLocked = true;
            if (getLatestSpaInfo().hasComponents()) {
                compsBuilder.mergeFrom(getLatestSpaInfo().getComponents());
            }
        } catch (Exception ex) {
            throw Throwables.propagate(ex);
        } finally {
            if (rLocked) {
                getLatestSpaInfoLock().readLock().unlock();
            }
        }

        int value = (0x03 & message[4]);
        if (value > 0) {
            compsBuilder.setPump1(PumpComponent.newBuilder().addAllAvailableStates(getAvailablePumpStates(value)));
        } else {
            compsBuilder.clearPump1();
        }

        value = ((0x0C & message[4]) >> 2);
        if (value > 0) {
            compsBuilder.setPump2(PumpComponent.newBuilder().addAllAvailableStates(getAvailablePumpStates(value)));
        } else {
            compsBuilder.clearPump2();
        }

        value = ((0x30 & message[4]) >> 4);
        if (value > 0) {
            compsBuilder.setPump3(PumpComponent.newBuilder().addAllAvailableStates(getAvailablePumpStates(value)));
        } else {
            compsBuilder.clearPump3();
        }

        value = ((0xC0 & message[4]) >> 6);
        if (value > 0) {
            compsBuilder.setPump4(PumpComponent.newBuilder().addAllAvailableStates(getAvailablePumpStates(value)));
        } else {
            compsBuilder.clearPump4();
        }

        value = (0x03 & message[5]);
        if (value > 0) {
            compsBuilder.setPump5(PumpComponent.newBuilder().addAllAvailableStates(getAvailablePumpStates(value)));
        } else {
            compsBuilder.clearPump5();
        }

        value = ((0x0C & message[5]) >> 2);
        if (value > 0) {
            compsBuilder.setPump6(PumpComponent.newBuilder().addAllAvailableStates(getAvailablePumpStates(value)));
        } else {
            compsBuilder.clearPump6();
        }

        value = ((0x30 & message[5]) >> 4);
        if (value > 0) {
            compsBuilder.setPump7(PumpComponent.newBuilder().addAllAvailableStates(getAvailablePumpStates(value)));
        } else {
            compsBuilder.clearPump7();
        }

        value = ((0xC0 & message[5]) >> 6);
        if (value > 0) {
            compsBuilder.setPump8(PumpComponent.newBuilder().addAllAvailableStates(getAvailablePumpStates(value)));
        } else {
            compsBuilder.clearPump8();
        }

        value = (0x03 & message[6]);
        if (value > 0) {
            compsBuilder.setLight1(LightComponent.newBuilder().addAllAvailableStates(getAvailableLightStates(value)));
        } else {
            compsBuilder.clearLight1();
        }

        value = ((0x0C & message[6]) >> 2);
        if (value > 0) {
            compsBuilder.setLight2(LightComponent.newBuilder().addAllAvailableStates(getAvailableLightStates(value)));
        } else {
            compsBuilder.clearLight2();
        }

        value = ((0x30 & message[6]) >> 4);
        if (value > 0) {
            compsBuilder.setLight3(LightComponent.newBuilder().addAllAvailableStates(getAvailableLightStates(value)));
        } else {
            compsBuilder.clearLight3();
        }

        value = ((0xC0 & message[6]) >> 6);
        if (value > 0) {
            compsBuilder.setLight4(LightComponent.newBuilder().addAllAvailableStates(getAvailableLightStates(value)));
        } else {
            compsBuilder.clearLight4();
        }

        value = (0x03 & message[7]);
        if (value > 0) {
            compsBuilder.setBlower1(BlowerComponent.newBuilder().addAllAvailableStates(getAvailableBlowerStates(value)));
        } else {
            compsBuilder.clearBlower1();
        }

        value = ((0x0C & message[7]) >> 2);
        if (value > 0) {
            compsBuilder.setBlower2(BlowerComponent.newBuilder().addAllAvailableStates(getAvailableBlowerStates(value)));
        } else {
            compsBuilder.clearBlower2();
        }

        if ((0x10 & message[7]) > 0) {
            compsBuilder.setHeater1(Components.HeaterState.OFF);
        } else {
            compsBuilder.clearHeater1();
        }

        if ((0x20 & message[7]) > 0) {
            compsBuilder.setHeater2(Components.HeaterState.OFF);
        } else {
            compsBuilder.clearHeater2();
        }

        if ((0x40 & message[7]) > 0) {
            compsBuilder.setOzone(ToggleComponent.newBuilder().addAllAvailableStates(getAvailableToggleStates()));
        } else {
            compsBuilder.clearOzone();
        }

        if ((0x80 & message[7]) > 0) {
            compsBuilder.setCirculationPump(PumpComponent.newBuilder().addAllAvailableStates(getAvailablePumpStates(2)));
        } else {
            compsBuilder.clearCirculationPump();
        }

        if ((0x01 & message[8]) > 0) {
            compsBuilder.setAux1(ToggleComponent.newBuilder().addAllAvailableStates(getAvailableToggleStates()));
        } else {
            compsBuilder.clearAux1();
        }

        if ((0x02 & message[8]) > 0) {
            compsBuilder.setAux2(ToggleComponent.newBuilder().addAllAvailableStates(getAvailableToggleStates()));
        } else {
            compsBuilder.clearAux2();
        }

        if ((0x04 & message[8]) > 0) {
            compsBuilder.setAux3(ToggleComponent.newBuilder().addAllAvailableStates(getAvailableToggleStates()));
        } else {
            compsBuilder.clearAux3();
        }

        if ((0x08 & message[8]) > 0) {
            compsBuilder.setAux4(ToggleComponent.newBuilder().addAllAvailableStates(getAvailableToggleStates()));
        } else {
            compsBuilder.clearAux4();
        }

        if ((0x10 & message[8]) > 0) {
            compsBuilder.setMister1(ToggleComponent.newBuilder().addAllAvailableStates(getAvailableToggleStates()));
        } else {
            compsBuilder.clearMister1();
        }

        if ((0x20 & message[8]) > 0) {
            compsBuilder.setMister2(ToggleComponent.newBuilder().addAllAvailableStates(getAvailableToggleStates()));
        } else {
            compsBuilder.clearMister2();
        }

        if ((0x40 & message[8]) > 0) {
            compsBuilder.setMister3(ToggleComponent.newBuilder().addAllAvailableStates(getAvailableToggleStates()));
        } else {
            compsBuilder.clearMister3();
        }

        if ((0x80 & message[8]) > 0) {
            compsBuilder.setMicroSilk(ToggleComponent.newBuilder().addAllAvailableStates(getAvailableToggleStates()));
        } else {
            compsBuilder.clearMicroSilk();
        }

        if ((0xFF & message[9]) > 0) {
            compsBuilder.setFiberWheel((0xFF & message[9]));
        } else {
            compsBuilder.clearFiberWheel();
        }

        compsBuilder.setLastUpdateTimestamp(new Date().getTime());
        components = compsBuilder.build();
        boolean wLocked = false;
        try {
            getLatestSpaInfoLock().writeLock().lockInterruptibly();
            wLocked = true;
            SpaState.Builder builder = SpaState.newBuilder(getLatestSpaInfo());
            builder.setComponents(components);
            builder.setLastUpdateTimestamp(new Date().getTime());
            setLatestSpaInfo(builder.build());
        } catch (Exception ex) {
            throw Throwables.propagate(ex);
        } finally {
            if (wLocked) {
                getLatestSpaInfoLock().writeLock().unlock();
            }
        }
        LOGGER.info("processed device config message");
    }
}

