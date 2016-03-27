package com.tritonsvc.gateway;

import com.tritonsvc.spa.communication.proto.Bwg;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Created by holow on 3/25/2016.
 */
public class SpaStateHolder {

    private static final Logger log = LoggerFactory.getLogger(SpaStateHolder.class);

    private final Bwg.Uplink.Model.Controller.Builder controllerBuilder = Bwg.Uplink.Model.Controller.newBuilder();
    private final Bwg.Uplink.Model.SystemInfo.Builder systemInfoBuilder = Bwg.Uplink.Model.SystemInfo.newBuilder();
    private final Bwg.Uplink.Model.SetupParams.Builder setupParamsBuilder = Bwg.Uplink.Model.SetupParams.newBuilder();
    private final Bwg.Uplink.Model.Components.Builder componentsBuilder = Bwg.Uplink.Model.Components.newBuilder();

    private final Map<Bwg.Uplink.Model.Constants.ComponentType, Map<Integer, Object>> builderMap = new HashMap<>();

    public SpaStateHolder() {
        initControllerBuilder();
        initSystemInfoBuilder();
        initSetupParamsBuilder();
        initComponentBuilders();
    }

    public Bwg.Uplink.Model.SpaState buildSpaState() {
        final long timestamp = System.currentTimeMillis();
        final Bwg.Uplink.Model.SpaState.Builder builder = Bwg.Uplink.Model.SpaState.newBuilder();
        builder.setController(buildController(timestamp));
        builder.setSystemInfo(buildSystemInfo(timestamp));
        builder.setSetupParams(buildSetupParams(timestamp));
        builder.setComponents(buildComponents(timestamp));
        builder.setLastUpdateTimestamp(timestamp);

        final Bwg.Uplink.Model.SpaState state = builder.build();
        return state;
    }

    public void updateHeater(final Integer temperature) {
        log.info("Updating spa state, setting temperature to {}", temperature);
        controllerBuilder.setCurrentWaterTemp(temperature.intValue());
        controllerBuilder.setTargetWaterTemperature(temperature.intValue());

        componentsBuilder.setHeater1(Bwg.Uplink.Model.Components.HeaterState.HEATER_ON);
        componentsBuilder.setHeater2(Bwg.Uplink.Model.Components.HeaterState.HEATER_ON);
    }

    public void updateComponentState(final Bwg.Uplink.Model.Constants.ComponentType componentType, final Integer port, final String desiredState) {
        log.info("Updating spa state of component {} at port {} with value {}", componentType, port, desiredState);
        final Map<Integer, Object> builders = builderMap.get(componentType);
        if (builders != null) {
            final Object builder = builders.get(port);
            switch (componentType) {
                case OZONE:
                case MICROSILK:
                case AUX:
                case MISTER:
                    updateToggleComponent(builder, port, desiredState);
                    break;
                case PUMP:
                    updatePumpComponent(builder, port, desiredState);
                    break;
                case CIRCULATION_PUMP:
                    updatePumpComponent(builder, port, desiredState);
                    break;
                case BLOWER:
                    updateBlowerComponent(builder, port, desiredState);
                    break;
                case LIGHT:
                    updateLightComponent(builder, port, desiredState);
                    break;
            }
        }
    }

    private void initControllerBuilder() {
        controllerBuilder.setHeaterMode(0);
        controllerBuilder.setCurrentWaterTemp(0);
        controllerBuilder.setHour(0);
        controllerBuilder.setMinute(0);
        controllerBuilder.setLastUpdateTimestamp(0);
        controllerBuilder.setErrorCode(0);
        controllerBuilder.setUiCode(0);
        controllerBuilder.setUiSubCode(0);
        controllerBuilder.setInvert(false);
        controllerBuilder.setAllSegsOn(false);
        controllerBuilder.setPanelLock(false);
        controllerBuilder.setFilter1(false);
        controllerBuilder.setFilter2(false);
        controllerBuilder.setMilitary(false);
        controllerBuilder.setCelsius(false);
        controllerBuilder.setTempRange(Bwg.Uplink.Model.Constants.TempRange.LOW);
        controllerBuilder.setPrimingMode(false);
        controllerBuilder.setSoundAlarm(false);
        controllerBuilder.setRepeat(false);
        controllerBuilder.setPanelMode(Bwg.Uplink.Model.Constants.PanelMode.PANEL_MODE_SWIM_SPA);
        controllerBuilder.setSwimSpaMode(Bwg.Uplink.Model.Constants.SwimSpaMode.SWIM_MODE_SPA);
        controllerBuilder.setMessageSeverity(0);
        controllerBuilder.setSwimSpaModeChanging(false);
        controllerBuilder.setHeaterCooling(false);
        controllerBuilder.setLatchingMessage(false);
        controllerBuilder.setDemoMode(false);
        controllerBuilder.setCleanupCycle(false);
        controllerBuilder.setTimeNotSet(false);
        controllerBuilder.setLightCycle(false);
        controllerBuilder.setTargetWaterTemperature(0);
        controllerBuilder.setElapsedTimeDisplay(false);
        controllerBuilder.setTvLiftState(0);
        controllerBuilder.setSettingsLock(false);
        controllerBuilder.setSpaOverheatDisabled(false);
        controllerBuilder.setSpecialTimeouts(false);
        controllerBuilder.setABDisplay(false);
        controllerBuilder.setStirring(false);
        controllerBuilder.setEcoMode(false);
        controllerBuilder.setSoakMode(false);
        controllerBuilder.setBluetoothStatus(0);
        controllerBuilder.setOverrangeEnabled(false);
        controllerBuilder.setHeatExternallyDisabled(false);
        controllerBuilder.setTestMode(false);
        controllerBuilder.setTempLock(false);
    }

    private void initSystemInfoBuilder() {
        systemInfoBuilder.setHeaterPower(0);
        systemInfoBuilder.setMfrSSID(0);
        systemInfoBuilder.setModelSSID(0);
        systemInfoBuilder.setVersionSSID(0);
        systemInfoBuilder.setMinorVersion(0);
        systemInfoBuilder.setSwSignature(0);
        systemInfoBuilder.setHeaterType(0);
        systemInfoBuilder.setCurrentSetup(0);
        systemInfoBuilder.setLastUpdateTimestamp(0);
    }

    private void initSetupParamsBuilder() {
        setupParamsBuilder.setLowRangeLow(0);
        setupParamsBuilder.setLowRangeHigh(0);
        setupParamsBuilder.setHighRangeLow(0);
        setupParamsBuilder.setHighRangeHigh(0);
        setupParamsBuilder.setGfciEnabled(false);
        setupParamsBuilder.setDrainModeEnabled(false);
        setupParamsBuilder.setLastUpdateTimestamp(0);
    }

    private void initComponentBuilders() {
        final Bwg.Uplink.Model.Components.ToggleComponent.Builder ozoneBuilder = Bwg.Uplink.Model.Components.ToggleComponent.newBuilder();
        final Bwg.Uplink.Model.Components.ToggleComponent.Builder microsilkBuilder = Bwg.Uplink.Model.Components.ToggleComponent.newBuilder();
        final Bwg.Uplink.Model.Components.ToggleComponent.Builder aux1Builder = Bwg.Uplink.Model.Components.ToggleComponent.newBuilder();
        final Bwg.Uplink.Model.Components.ToggleComponent.Builder aux2Builder = Bwg.Uplink.Model.Components.ToggleComponent.newBuilder();
        final Bwg.Uplink.Model.Components.ToggleComponent.Builder aux3Builder = Bwg.Uplink.Model.Components.ToggleComponent.newBuilder();
        final Bwg.Uplink.Model.Components.ToggleComponent.Builder aux4Builder = Bwg.Uplink.Model.Components.ToggleComponent.newBuilder();
        final Bwg.Uplink.Model.Components.ToggleComponent.Builder mister1Builder = Bwg.Uplink.Model.Components.ToggleComponent.newBuilder();
        final Bwg.Uplink.Model.Components.ToggleComponent.Builder mister2Builder = Bwg.Uplink.Model.Components.ToggleComponent.newBuilder();
        final Bwg.Uplink.Model.Components.ToggleComponent.Builder mister3Builder = Bwg.Uplink.Model.Components.ToggleComponent.newBuilder();
        final Bwg.Uplink.Model.Components.PumpComponent.Builder pump1Builder = Bwg.Uplink.Model.Components.PumpComponent.newBuilder();
        final Bwg.Uplink.Model.Components.PumpComponent.Builder pump2Builder = Bwg.Uplink.Model.Components.PumpComponent.newBuilder();
        final Bwg.Uplink.Model.Components.PumpComponent.Builder pump3Builder = Bwg.Uplink.Model.Components.PumpComponent.newBuilder();
        final Bwg.Uplink.Model.Components.PumpComponent.Builder pump4Builder = Bwg.Uplink.Model.Components.PumpComponent.newBuilder();
        final Bwg.Uplink.Model.Components.PumpComponent.Builder pump5Builder = Bwg.Uplink.Model.Components.PumpComponent.newBuilder();
        final Bwg.Uplink.Model.Components.PumpComponent.Builder pump6Builder = Bwg.Uplink.Model.Components.PumpComponent.newBuilder();
        final Bwg.Uplink.Model.Components.PumpComponent.Builder pump7Builder = Bwg.Uplink.Model.Components.PumpComponent.newBuilder();
        final Bwg.Uplink.Model.Components.PumpComponent.Builder pump8Builder = Bwg.Uplink.Model.Components.PumpComponent.newBuilder();
        final Bwg.Uplink.Model.Components.PumpComponent.Builder circulationPumpBuilder = Bwg.Uplink.Model.Components.PumpComponent.newBuilder();
        final Bwg.Uplink.Model.Components.BlowerComponent.Builder blower1Builder = Bwg.Uplink.Model.Components.BlowerComponent.newBuilder();
        final Bwg.Uplink.Model.Components.BlowerComponent.Builder blower2Builder = Bwg.Uplink.Model.Components.BlowerComponent.newBuilder();
        final Bwg.Uplink.Model.Components.LightComponent.Builder light1Builder = Bwg.Uplink.Model.Components.LightComponent.newBuilder();
        final Bwg.Uplink.Model.Components.LightComponent.Builder light2Builder = Bwg.Uplink.Model.Components.LightComponent.newBuilder();
        final Bwg.Uplink.Model.Components.LightComponent.Builder light3Builder = Bwg.Uplink.Model.Components.LightComponent.newBuilder();
        final Bwg.Uplink.Model.Components.LightComponent.Builder light4Builder = Bwg.Uplink.Model.Components.LightComponent.newBuilder();

        ozoneBuilder.setCurrentState(Bwg.Uplink.Model.Components.ToggleComponent.State.OFF);
        ozoneBuilder.addAvailableStates(Bwg.Uplink.Model.Components.ToggleComponent.State.ON);
        ozoneBuilder.addAvailableStates(Bwg.Uplink.Model.Components.ToggleComponent.State.OFF);
        addBuilder(Bwg.Uplink.Model.Constants.ComponentType.OZONE, 0, ozoneBuilder);

        microsilkBuilder.setCurrentState(Bwg.Uplink.Model.Components.ToggleComponent.State.OFF);
        microsilkBuilder.addAvailableStates(Bwg.Uplink.Model.Components.ToggleComponent.State.ON);
        microsilkBuilder.addAvailableStates(Bwg.Uplink.Model.Components.ToggleComponent.State.OFF);
        addBuilder(Bwg.Uplink.Model.Constants.ComponentType.MICROSILK, 0, microsilkBuilder);

        aux1Builder.setCurrentState(Bwg.Uplink.Model.Components.ToggleComponent.State.OFF);
        aux1Builder.addAvailableStates(Bwg.Uplink.Model.Components.ToggleComponent.State.ON);
        aux1Builder.addAvailableStates(Bwg.Uplink.Model.Components.ToggleComponent.State.OFF);
        addBuilder(Bwg.Uplink.Model.Constants.ComponentType.AUX, 0, aux1Builder);
        aux2Builder.setCurrentState(Bwg.Uplink.Model.Components.ToggleComponent.State.OFF);
        aux2Builder.addAvailableStates(Bwg.Uplink.Model.Components.ToggleComponent.State.ON);
        aux2Builder.addAvailableStates(Bwg.Uplink.Model.Components.ToggleComponent.State.OFF);
        addBuilder(Bwg.Uplink.Model.Constants.ComponentType.AUX, 1, aux2Builder);
        aux3Builder.setCurrentState(Bwg.Uplink.Model.Components.ToggleComponent.State.OFF);
        aux3Builder.addAvailableStates(Bwg.Uplink.Model.Components.ToggleComponent.State.ON);
        aux3Builder.addAvailableStates(Bwg.Uplink.Model.Components.ToggleComponent.State.OFF);
        addBuilder(Bwg.Uplink.Model.Constants.ComponentType.AUX, 2, aux2Builder);
        aux4Builder.setCurrentState(Bwg.Uplink.Model.Components.ToggleComponent.State.OFF);
        aux4Builder.addAvailableStates(Bwg.Uplink.Model.Components.ToggleComponent.State.ON);
        aux4Builder.addAvailableStates(Bwg.Uplink.Model.Components.ToggleComponent.State.OFF);
        addBuilder(Bwg.Uplink.Model.Constants.ComponentType.AUX, 3, aux4Builder);

        mister1Builder.setCurrentState(Bwg.Uplink.Model.Components.ToggleComponent.State.OFF);
        mister1Builder.addAvailableStates(Bwg.Uplink.Model.Components.ToggleComponent.State.ON);
        mister1Builder.addAvailableStates(Bwg.Uplink.Model.Components.ToggleComponent.State.OFF);
        addBuilder(Bwg.Uplink.Model.Constants.ComponentType.MISTER, 0, mister1Builder);
        mister2Builder.setCurrentState(Bwg.Uplink.Model.Components.ToggleComponent.State.OFF);
        mister2Builder.addAvailableStates(Bwg.Uplink.Model.Components.ToggleComponent.State.ON);
        mister2Builder.addAvailableStates(Bwg.Uplink.Model.Components.ToggleComponent.State.OFF);
        addBuilder(Bwg.Uplink.Model.Constants.ComponentType.MISTER, 1, mister2Builder);
        mister3Builder.setCurrentState(Bwg.Uplink.Model.Components.ToggleComponent.State.OFF);
        mister3Builder.addAvailableStates(Bwg.Uplink.Model.Components.ToggleComponent.State.ON);
        mister3Builder.addAvailableStates(Bwg.Uplink.Model.Components.ToggleComponent.State.OFF);
        addBuilder(Bwg.Uplink.Model.Constants.ComponentType.MISTER, 2, mister3Builder);

        pump1Builder.setCurrentState(Bwg.Uplink.Model.Components.PumpComponent.State.OFF);
        pump1Builder.addAvailableStates(Bwg.Uplink.Model.Components.PumpComponent.State.OFF);
        pump1Builder.addAvailableStates(Bwg.Uplink.Model.Components.PumpComponent.State.LOW);
        pump1Builder.addAvailableStates(Bwg.Uplink.Model.Components.PumpComponent.State.HIGH);
        addBuilder(Bwg.Uplink.Model.Constants.ComponentType.PUMP, 0, pump1Builder);
        pump2Builder.setCurrentState(Bwg.Uplink.Model.Components.PumpComponent.State.OFF);
        pump2Builder.addAvailableStates(Bwg.Uplink.Model.Components.PumpComponent.State.OFF);
        pump2Builder.addAvailableStates(Bwg.Uplink.Model.Components.PumpComponent.State.LOW);
        pump2Builder.addAvailableStates(Bwg.Uplink.Model.Components.PumpComponent.State.HIGH);
        addBuilder(Bwg.Uplink.Model.Constants.ComponentType.PUMP, 1, pump2Builder);
        pump3Builder.setCurrentState(Bwg.Uplink.Model.Components.PumpComponent.State.OFF);
        pump3Builder.addAvailableStates(Bwg.Uplink.Model.Components.PumpComponent.State.OFF);
        pump3Builder.addAvailableStates(Bwg.Uplink.Model.Components.PumpComponent.State.LOW);
        pump3Builder.addAvailableStates(Bwg.Uplink.Model.Components.PumpComponent.State.HIGH);
        addBuilder(Bwg.Uplink.Model.Constants.ComponentType.PUMP, 2, pump3Builder);
        pump4Builder.setCurrentState(Bwg.Uplink.Model.Components.PumpComponent.State.OFF);
        pump4Builder.addAvailableStates(Bwg.Uplink.Model.Components.PumpComponent.State.OFF);
        pump4Builder.addAvailableStates(Bwg.Uplink.Model.Components.PumpComponent.State.LOW);
        pump4Builder.addAvailableStates(Bwg.Uplink.Model.Components.PumpComponent.State.HIGH);
        addBuilder(Bwg.Uplink.Model.Constants.ComponentType.PUMP, 3, pump4Builder);
        pump5Builder.setCurrentState(Bwg.Uplink.Model.Components.PumpComponent.State.OFF);
        pump5Builder.addAvailableStates(Bwg.Uplink.Model.Components.PumpComponent.State.OFF);
        pump5Builder.addAvailableStates(Bwg.Uplink.Model.Components.PumpComponent.State.LOW);
        pump5Builder.addAvailableStates(Bwg.Uplink.Model.Components.PumpComponent.State.HIGH);
        addBuilder(Bwg.Uplink.Model.Constants.ComponentType.PUMP, 4, pump5Builder);
        pump6Builder.setCurrentState(Bwg.Uplink.Model.Components.PumpComponent.State.OFF);
        pump6Builder.addAvailableStates(Bwg.Uplink.Model.Components.PumpComponent.State.OFF);
        pump6Builder.addAvailableStates(Bwg.Uplink.Model.Components.PumpComponent.State.LOW);
        pump6Builder.addAvailableStates(Bwg.Uplink.Model.Components.PumpComponent.State.HIGH);
        addBuilder(Bwg.Uplink.Model.Constants.ComponentType.PUMP, 5, pump6Builder);
        pump7Builder.setCurrentState(Bwg.Uplink.Model.Components.PumpComponent.State.OFF);
        pump7Builder.addAvailableStates(Bwg.Uplink.Model.Components.PumpComponent.State.OFF);
        pump7Builder.addAvailableStates(Bwg.Uplink.Model.Components.PumpComponent.State.LOW);
        pump7Builder.addAvailableStates(Bwg.Uplink.Model.Components.PumpComponent.State.HIGH);
        addBuilder(Bwg.Uplink.Model.Constants.ComponentType.PUMP, 6, pump7Builder);
        pump8Builder.setCurrentState(Bwg.Uplink.Model.Components.PumpComponent.State.OFF);
        pump8Builder.addAvailableStates(Bwg.Uplink.Model.Components.PumpComponent.State.OFF);
        pump8Builder.addAvailableStates(Bwg.Uplink.Model.Components.PumpComponent.State.LOW);
        pump8Builder.addAvailableStates(Bwg.Uplink.Model.Components.PumpComponent.State.HIGH);
        addBuilder(Bwg.Uplink.Model.Constants.ComponentType.PUMP, 7, pump8Builder);
        circulationPumpBuilder.setCurrentState(Bwg.Uplink.Model.Components.PumpComponent.State.OFF);
        circulationPumpBuilder.addAvailableStates(Bwg.Uplink.Model.Components.PumpComponent.State.OFF);
        circulationPumpBuilder.addAvailableStates(Bwg.Uplink.Model.Components.PumpComponent.State.LOW);
        circulationPumpBuilder.addAvailableStates(Bwg.Uplink.Model.Components.PumpComponent.State.HIGH);
        addBuilder(Bwg.Uplink.Model.Constants.ComponentType.CIRCULATION_PUMP, 0, circulationPumpBuilder);

        blower1Builder.setCurrentState(Bwg.Uplink.Model.Components.BlowerComponent.State.OFF);
        blower1Builder.addAvailableStates(Bwg.Uplink.Model.Components.BlowerComponent.State.OFF);
        blower1Builder.addAvailableStates(Bwg.Uplink.Model.Components.BlowerComponent.State.LOW);
        blower1Builder.addAvailableStates(Bwg.Uplink.Model.Components.BlowerComponent.State.MED);
        blower1Builder.addAvailableStates(Bwg.Uplink.Model.Components.BlowerComponent.State.HIGH);
        addBuilder(Bwg.Uplink.Model.Constants.ComponentType.BLOWER, 0, blower1Builder);
        blower2Builder.setCurrentState(Bwg.Uplink.Model.Components.BlowerComponent.State.OFF);
        blower2Builder.addAvailableStates(Bwg.Uplink.Model.Components.BlowerComponent.State.OFF);
        blower2Builder.addAvailableStates(Bwg.Uplink.Model.Components.BlowerComponent.State.LOW);
        blower2Builder.addAvailableStates(Bwg.Uplink.Model.Components.BlowerComponent.State.MED);
        blower2Builder.addAvailableStates(Bwg.Uplink.Model.Components.BlowerComponent.State.HIGH);
        addBuilder(Bwg.Uplink.Model.Constants.ComponentType.BLOWER, 1, blower2Builder);

        light1Builder.setCurrentState(Bwg.Uplink.Model.Components.LightComponent.State.OFF);
        light1Builder.addAvailableStates(Bwg.Uplink.Model.Components.LightComponent.State.OFF);
        light1Builder.addAvailableStates(Bwg.Uplink.Model.Components.LightComponent.State.LOW);
        light1Builder.addAvailableStates(Bwg.Uplink.Model.Components.LightComponent.State.MED);
        light1Builder.addAvailableStates(Bwg.Uplink.Model.Components.LightComponent.State.HIGH);
        addBuilder(Bwg.Uplink.Model.Constants.ComponentType.LIGHT, 0, light1Builder);
        light2Builder.setCurrentState(Bwg.Uplink.Model.Components.LightComponent.State.OFF);
        light2Builder.addAvailableStates(Bwg.Uplink.Model.Components.LightComponent.State.OFF);
        light2Builder.addAvailableStates(Bwg.Uplink.Model.Components.LightComponent.State.LOW);
        light2Builder.addAvailableStates(Bwg.Uplink.Model.Components.LightComponent.State.MED);
        light2Builder.addAvailableStates(Bwg.Uplink.Model.Components.LightComponent.State.HIGH);
        addBuilder(Bwg.Uplink.Model.Constants.ComponentType.LIGHT, 1, light2Builder);
        light3Builder.setCurrentState(Bwg.Uplink.Model.Components.LightComponent.State.OFF);
        light3Builder.addAvailableStates(Bwg.Uplink.Model.Components.LightComponent.State.OFF);
        light3Builder.addAvailableStates(Bwg.Uplink.Model.Components.LightComponent.State.LOW);
        light3Builder.addAvailableStates(Bwg.Uplink.Model.Components.LightComponent.State.MED);
        light3Builder.addAvailableStates(Bwg.Uplink.Model.Components.LightComponent.State.HIGH);
        addBuilder(Bwg.Uplink.Model.Constants.ComponentType.LIGHT, 2, light3Builder);
        light4Builder.setCurrentState(Bwg.Uplink.Model.Components.LightComponent.State.OFF);
        light4Builder.addAvailableStates(Bwg.Uplink.Model.Components.LightComponent.State.OFF);
        light4Builder.addAvailableStates(Bwg.Uplink.Model.Components.LightComponent.State.LOW);
        light4Builder.addAvailableStates(Bwg.Uplink.Model.Components.LightComponent.State.MED);
        light4Builder.addAvailableStates(Bwg.Uplink.Model.Components.LightComponent.State.HIGH);
        addBuilder(Bwg.Uplink.Model.Constants.ComponentType.LIGHT, 3, light4Builder);

        componentsBuilder.setHeater1(Bwg.Uplink.Model.Components.HeaterState.HEATER_OFF);
        componentsBuilder.setHeater2(Bwg.Uplink.Model.Components.HeaterState.HEATER_OFF);

        componentsBuilder.setOzone(ozoneBuilder);
        componentsBuilder.setMicroSilk(microsilkBuilder);
        componentsBuilder.setAux1(aux1Builder);
        componentsBuilder.setAux2(aux2Builder);
        componentsBuilder.setAux3(aux3Builder);
        componentsBuilder.setAux4(aux4Builder);
        componentsBuilder.setMister1(mister1Builder);
        componentsBuilder.setMister2(mister2Builder);
        componentsBuilder.setMister3(mister3Builder);
        componentsBuilder.setPump1(pump1Builder);
        componentsBuilder.setPump2(pump2Builder);
        componentsBuilder.setPump3(pump3Builder);
        componentsBuilder.setPump4(pump4Builder);
        componentsBuilder.setPump5(pump5Builder);
        componentsBuilder.setPump6(pump6Builder);
        componentsBuilder.setPump7(pump7Builder);
        componentsBuilder.setPump8(pump8Builder);
        componentsBuilder.setCirculationPump(circulationPumpBuilder);
        componentsBuilder.setBlower1(blower1Builder);
        componentsBuilder.setBlower2(blower2Builder);
        componentsBuilder.setLight1(light1Builder);
        componentsBuilder.setLight2(light2Builder);
        componentsBuilder.setLight3(light3Builder);
        componentsBuilder.setLight4(light4Builder);

        componentsBuilder.setFiberWheel(0);
        componentsBuilder.setLastUpdateTimestamp(0);
    }

    private void addBuilder(Bwg.Uplink.Model.Constants.ComponentType type, Integer port, Object builder) {
        Map<Integer, Object> builders = builderMap.get(type);
        if (builders == null) {
            builders = new HashMap<>();
            builderMap.put(type, builders);
        }
        builders.put(port, builder);
    }

    private Bwg.Uplink.Model.Controller buildController(final long timestamp) {
        controllerBuilder.setLastUpdateTimestamp(timestamp);
        return controllerBuilder.build();
    }

    private Bwg.Uplink.Model.SystemInfo buildSystemInfo(final long timestamp) {
        systemInfoBuilder.setLastUpdateTimestamp(timestamp);
        return systemInfoBuilder.build();
    }

    private Bwg.Uplink.Model.SetupParams buildSetupParams(final long timestamp) {
        setupParamsBuilder.setLastUpdateTimestamp(timestamp);
        return setupParamsBuilder.build();
    }

    private Bwg.Uplink.Model.Components buildComponents(final long timestamp) {
        componentsBuilder.setLastUpdateTimestamp(timestamp);

        componentsBuilder.setOzone((Bwg.Uplink.Model.Components.ToggleComponent.Builder) getBuilder(Bwg.Uplink.Model.Constants.ComponentType.OZONE, 0));
        componentsBuilder.setMicroSilk((Bwg.Uplink.Model.Components.ToggleComponent.Builder) getBuilder(Bwg.Uplink.Model.Constants.ComponentType.MICROSILK, 0));
        componentsBuilder.setAux1((Bwg.Uplink.Model.Components.ToggleComponent.Builder) getBuilder(Bwg.Uplink.Model.Constants.ComponentType.AUX, 0));
        componentsBuilder.setAux2((Bwg.Uplink.Model.Components.ToggleComponent.Builder) getBuilder(Bwg.Uplink.Model.Constants.ComponentType.AUX, 1));
        componentsBuilder.setAux3((Bwg.Uplink.Model.Components.ToggleComponent.Builder) getBuilder(Bwg.Uplink.Model.Constants.ComponentType.AUX, 2));
        componentsBuilder.setAux4((Bwg.Uplink.Model.Components.ToggleComponent.Builder) getBuilder(Bwg.Uplink.Model.Constants.ComponentType.AUX, 3));
        componentsBuilder.setMister1((Bwg.Uplink.Model.Components.ToggleComponent.Builder) getBuilder(Bwg.Uplink.Model.Constants.ComponentType.MISTER, 0));
        componentsBuilder.setMister2((Bwg.Uplink.Model.Components.ToggleComponent.Builder) getBuilder(Bwg.Uplink.Model.Constants.ComponentType.MISTER, 1));
        componentsBuilder.setMister3((Bwg.Uplink.Model.Components.ToggleComponent.Builder) getBuilder(Bwg.Uplink.Model.Constants.ComponentType.MISTER, 2));
        componentsBuilder.setPump1((Bwg.Uplink.Model.Components.PumpComponent.Builder) getBuilder(Bwg.Uplink.Model.Constants.ComponentType.PUMP, 0));
        componentsBuilder.setPump2((Bwg.Uplink.Model.Components.PumpComponent.Builder) getBuilder(Bwg.Uplink.Model.Constants.ComponentType.PUMP, 1));
        componentsBuilder.setPump3((Bwg.Uplink.Model.Components.PumpComponent.Builder) getBuilder(Bwg.Uplink.Model.Constants.ComponentType.PUMP, 2));
        componentsBuilder.setPump4((Bwg.Uplink.Model.Components.PumpComponent.Builder) getBuilder(Bwg.Uplink.Model.Constants.ComponentType.PUMP, 3));
        componentsBuilder.setPump5((Bwg.Uplink.Model.Components.PumpComponent.Builder) getBuilder(Bwg.Uplink.Model.Constants.ComponentType.PUMP, 4));
        componentsBuilder.setPump6((Bwg.Uplink.Model.Components.PumpComponent.Builder) getBuilder(Bwg.Uplink.Model.Constants.ComponentType.PUMP, 5));
        componentsBuilder.setPump7((Bwg.Uplink.Model.Components.PumpComponent.Builder) getBuilder(Bwg.Uplink.Model.Constants.ComponentType.PUMP, 6));
        componentsBuilder.setPump8((Bwg.Uplink.Model.Components.PumpComponent.Builder) getBuilder(Bwg.Uplink.Model.Constants.ComponentType.PUMP, 7));
        componentsBuilder.setCirculationPump((Bwg.Uplink.Model.Components.PumpComponent.Builder) getBuilder(Bwg.Uplink.Model.Constants.ComponentType.CIRCULATION_PUMP, 0));
        componentsBuilder.setBlower1((Bwg.Uplink.Model.Components.BlowerComponent.Builder) getBuilder(Bwg.Uplink.Model.Constants.ComponentType.BLOWER, 0));
        componentsBuilder.setBlower2((Bwg.Uplink.Model.Components.BlowerComponent.Builder) getBuilder(Bwg.Uplink.Model.Constants.ComponentType.BLOWER, 1));
        componentsBuilder.setLight1((Bwg.Uplink.Model.Components.LightComponent.Builder) getBuilder(Bwg.Uplink.Model.Constants.ComponentType.LIGHT, 0));
        componentsBuilder.setLight2((Bwg.Uplink.Model.Components.LightComponent.Builder) getBuilder(Bwg.Uplink.Model.Constants.ComponentType.LIGHT, 1));
        componentsBuilder.setLight3((Bwg.Uplink.Model.Components.LightComponent.Builder) getBuilder(Bwg.Uplink.Model.Constants.ComponentType.LIGHT, 2));
        componentsBuilder.setLight4((Bwg.Uplink.Model.Components.LightComponent.Builder) getBuilder(Bwg.Uplink.Model.Constants.ComponentType.LIGHT, 3));

        return componentsBuilder.build();
    }

    private Object getBuilder(final Bwg.Uplink.Model.Constants.ComponentType type, final Integer port) {
        final Map<Integer, Object> builders = builderMap.get(type);
        return builders.get(port);
    }

    private void updateToggleComponent(final Object builderObject, final Integer port, final String desiredState) {
        final Bwg.Uplink.Model.Components.ToggleComponent.Builder builder = (Bwg.Uplink.Model.Components.ToggleComponent.Builder) builderObject;
        builder.setCurrentState(Bwg.Uplink.Model.Components.ToggleComponent.State.valueOf(desiredState));
    }

    private void updatePumpComponent(final Object builderObject, final Integer port, final String desiredState) {
        final Bwg.Uplink.Model.Components.PumpComponent.Builder builder = (Bwg.Uplink.Model.Components.PumpComponent.Builder) builderObject;
        builder.setCurrentState(Bwg.Uplink.Model.Components.PumpComponent.State.valueOf(desiredState));
    }

    private void updateBlowerComponent(final Object builderObject, final Integer port, final String desiredState) {
        final Bwg.Uplink.Model.Components.BlowerComponent.Builder builder = (Bwg.Uplink.Model.Components.BlowerComponent.Builder) builderObject;
        builder.setCurrentState(Bwg.Uplink.Model.Components.BlowerComponent.State.valueOf(desiredState));
    }

    private void updateLightComponent(final Object builderObject, final Integer port, final String desiredState) {
        final Bwg.Uplink.Model.Components.LightComponent.Builder builder = (Bwg.Uplink.Model.Components.LightComponent.Builder) builderObject;
        builder.setCurrentState(Bwg.Uplink.Model.Components.LightComponent.State.valueOf(desiredState));
    }
}
