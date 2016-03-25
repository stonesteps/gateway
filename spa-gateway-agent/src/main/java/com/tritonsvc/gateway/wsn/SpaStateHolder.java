package com.tritonsvc.gateway.wsn;

import com.tritonsvc.spa.communication.proto.Bwg;

/**
 * Created by holow on 3/25/2016.
 */
public class SpaStateHolder {

    private final Bwg.Uplink.Model.Controller.Builder controllerBuilder = Bwg.Uplink.Model.Controller.newBuilder();
    private final Bwg.Uplink.Model.SystemInfo.Builder systemInfoBuilder = Bwg.Uplink.Model.SystemInfo.newBuilder();
    private final Bwg.Uplink.Model.SetupParams.Builder setupParamsBuilder = Bwg.Uplink.Model.SetupParams.newBuilder();
    private final Bwg.Uplink.Model.Components.Builder componentsBuilder = Bwg.Uplink.Model.Components.newBuilder();
    private final Bwg.Uplink.Model.Components.ToggleComponent.Builder ozoneBuilder = Bwg.Uplink.Model.Components.ToggleComponent.newBuilder();
    private final Bwg.Uplink.Model.Components.ToggleComponent.Builder microsilkBuilder = Bwg.Uplink.Model.Components.ToggleComponent.newBuilder();
    private final Bwg.Uplink.Model.Components.ToggleComponent.Builder aux1Builder = Bwg.Uplink.Model.Components.ToggleComponent.newBuilder();
    private final Bwg.Uplink.Model.Components.ToggleComponent.Builder aux2Builder = Bwg.Uplink.Model.Components.ToggleComponent.newBuilder();
    private final Bwg.Uplink.Model.Components.ToggleComponent.Builder aux3Builder = Bwg.Uplink.Model.Components.ToggleComponent.newBuilder();
    private final Bwg.Uplink.Model.Components.ToggleComponent.Builder aux4Builder = Bwg.Uplink.Model.Components.ToggleComponent.newBuilder();
    private final Bwg.Uplink.Model.Components.ToggleComponent.Builder mister1Builder = Bwg.Uplink.Model.Components.ToggleComponent.newBuilder();
    private final Bwg.Uplink.Model.Components.ToggleComponent.Builder mister2Builder = Bwg.Uplink.Model.Components.ToggleComponent.newBuilder();
    private final Bwg.Uplink.Model.Components.ToggleComponent.Builder mister3Builder = Bwg.Uplink.Model.Components.ToggleComponent.newBuilder();
    private final Bwg.Uplink.Model.Components.PumpComponent.Builder pump1Builder = Bwg.Uplink.Model.Components.PumpComponent.newBuilder();
    private final Bwg.Uplink.Model.Components.PumpComponent.Builder pump2Builder = Bwg.Uplink.Model.Components.PumpComponent.newBuilder();
    private final Bwg.Uplink.Model.Components.PumpComponent.Builder pump3Builder = Bwg.Uplink.Model.Components.PumpComponent.newBuilder();
    private final Bwg.Uplink.Model.Components.PumpComponent.Builder pump4Builder = Bwg.Uplink.Model.Components.PumpComponent.newBuilder();
    private final Bwg.Uplink.Model.Components.PumpComponent.Builder pump5Builder = Bwg.Uplink.Model.Components.PumpComponent.newBuilder();
    private final Bwg.Uplink.Model.Components.PumpComponent.Builder pump6Builder = Bwg.Uplink.Model.Components.PumpComponent.newBuilder();
    private final Bwg.Uplink.Model.Components.PumpComponent.Builder pump7Builder = Bwg.Uplink.Model.Components.PumpComponent.newBuilder();
    private final Bwg.Uplink.Model.Components.PumpComponent.Builder pump8Builder = Bwg.Uplink.Model.Components.PumpComponent.newBuilder();
    private final Bwg.Uplink.Model.Components.PumpComponent.Builder circulationPumpBuilder = Bwg.Uplink.Model.Components.PumpComponent.newBuilder();
    private final Bwg.Uplink.Model.Components.BlowerComponent.Builder blower1Builder = Bwg.Uplink.Model.Components.BlowerComponent.newBuilder();
    private final Bwg.Uplink.Model.Components.BlowerComponent.Builder blower2Builder = Bwg.Uplink.Model.Components.BlowerComponent.newBuilder();
    private final Bwg.Uplink.Model.Components.LightComponent.Builder light1Builder = Bwg.Uplink.Model.Components.LightComponent.newBuilder();
    private final Bwg.Uplink.Model.Components.LightComponent.Builder light2Builder = Bwg.Uplink.Model.Components.LightComponent.newBuilder();
    private final Bwg.Uplink.Model.Components.LightComponent.Builder light3Builder = Bwg.Uplink.Model.Components.LightComponent.newBuilder();
    private final Bwg.Uplink.Model.Components.LightComponent.Builder light4Builder = Bwg.Uplink.Model.Components.LightComponent.newBuilder();

    public SpaStateHolder() {
        ozoneBuilder.setCurrentState(Bwg.Uplink.Model.Components.ToggleComponent.State.OFF);
        ozoneBuilder.addAvailableStates(Bwg.Uplink.Model.Components.ToggleComponent.State.ON);
        ozoneBuilder.addAvailableStates(Bwg.Uplink.Model.Components.ToggleComponent.State.OFF);

        microsilkBuilder.setCurrentState(Bwg.Uplink.Model.Components.ToggleComponent.State.OFF);
        microsilkBuilder.addAvailableStates(Bwg.Uplink.Model.Components.ToggleComponent.State.ON);
        microsilkBuilder.addAvailableStates(Bwg.Uplink.Model.Components.ToggleComponent.State.OFF);

        aux1Builder.setCurrentState(Bwg.Uplink.Model.Components.ToggleComponent.State.OFF);
        aux1Builder.addAvailableStates(Bwg.Uplink.Model.Components.ToggleComponent.State.ON);
        aux1Builder.addAvailableStates(Bwg.Uplink.Model.Components.ToggleComponent.State.OFF);
        aux2Builder.setCurrentState(Bwg.Uplink.Model.Components.ToggleComponent.State.OFF);
        aux2Builder.addAvailableStates(Bwg.Uplink.Model.Components.ToggleComponent.State.ON);
        aux2Builder.addAvailableStates(Bwg.Uplink.Model.Components.ToggleComponent.State.OFF);
        aux3Builder.setCurrentState(Bwg.Uplink.Model.Components.ToggleComponent.State.OFF);
        aux3Builder.addAvailableStates(Bwg.Uplink.Model.Components.ToggleComponent.State.ON);
        aux3Builder.addAvailableStates(Bwg.Uplink.Model.Components.ToggleComponent.State.OFF);
        aux4Builder.setCurrentState(Bwg.Uplink.Model.Components.ToggleComponent.State.OFF);
        aux4Builder.addAvailableStates(Bwg.Uplink.Model.Components.ToggleComponent.State.ON);
        aux4Builder.addAvailableStates(Bwg.Uplink.Model.Components.ToggleComponent.State.OFF);

        mister1Builder.setCurrentState(Bwg.Uplink.Model.Components.ToggleComponent.State.OFF);
        mister1Builder.addAvailableStates(Bwg.Uplink.Model.Components.ToggleComponent.State.ON);
        mister1Builder.addAvailableStates(Bwg.Uplink.Model.Components.ToggleComponent.State.OFF);
        mister2Builder.setCurrentState(Bwg.Uplink.Model.Components.ToggleComponent.State.OFF);
        mister2Builder.addAvailableStates(Bwg.Uplink.Model.Components.ToggleComponent.State.ON);
        mister2Builder.addAvailableStates(Bwg.Uplink.Model.Components.ToggleComponent.State.OFF);
        mister3Builder.setCurrentState(Bwg.Uplink.Model.Components.ToggleComponent.State.OFF);
        mister3Builder.addAvailableStates(Bwg.Uplink.Model.Components.ToggleComponent.State.ON);
        mister3Builder.addAvailableStates(Bwg.Uplink.Model.Components.ToggleComponent.State.OFF);

        pump1Builder.setCurrentState(Bwg.Uplink.Model.Components.PumpComponent.State.OFF);
        pump1Builder.addAvailableStates(Bwg.Uplink.Model.Components.PumpComponent.State.OFF);
        pump1Builder.addAvailableStates(Bwg.Uplink.Model.Components.PumpComponent.State.LOW);
        pump1Builder.addAvailableStates(Bwg.Uplink.Model.Components.PumpComponent.State.HIGH);
        pump2Builder.setCurrentState(Bwg.Uplink.Model.Components.PumpComponent.State.OFF);
        pump2Builder.addAvailableStates(Bwg.Uplink.Model.Components.PumpComponent.State.OFF);
        pump2Builder.addAvailableStates(Bwg.Uplink.Model.Components.PumpComponent.State.LOW);
        pump2Builder.addAvailableStates(Bwg.Uplink.Model.Components.PumpComponent.State.HIGH);
        pump3Builder.setCurrentState(Bwg.Uplink.Model.Components.PumpComponent.State.OFF);
        pump3Builder.addAvailableStates(Bwg.Uplink.Model.Components.PumpComponent.State.OFF);
        pump3Builder.addAvailableStates(Bwg.Uplink.Model.Components.PumpComponent.State.LOW);
        pump3Builder.addAvailableStates(Bwg.Uplink.Model.Components.PumpComponent.State.HIGH);
        pump4Builder.setCurrentState(Bwg.Uplink.Model.Components.PumpComponent.State.OFF);
        pump4Builder.addAvailableStates(Bwg.Uplink.Model.Components.PumpComponent.State.OFF);
        pump4Builder.addAvailableStates(Bwg.Uplink.Model.Components.PumpComponent.State.LOW);
        pump4Builder.addAvailableStates(Bwg.Uplink.Model.Components.PumpComponent.State.HIGH);
        pump5Builder.setCurrentState(Bwg.Uplink.Model.Components.PumpComponent.State.OFF);
        pump5Builder.addAvailableStates(Bwg.Uplink.Model.Components.PumpComponent.State.OFF);
        pump5Builder.addAvailableStates(Bwg.Uplink.Model.Components.PumpComponent.State.LOW);
        pump5Builder.addAvailableStates(Bwg.Uplink.Model.Components.PumpComponent.State.HIGH);
        pump6Builder.setCurrentState(Bwg.Uplink.Model.Components.PumpComponent.State.OFF);
        pump6Builder.addAvailableStates(Bwg.Uplink.Model.Components.PumpComponent.State.OFF);
        pump6Builder.addAvailableStates(Bwg.Uplink.Model.Components.PumpComponent.State.LOW);
        pump6Builder.addAvailableStates(Bwg.Uplink.Model.Components.PumpComponent.State.HIGH);
        pump7Builder.setCurrentState(Bwg.Uplink.Model.Components.PumpComponent.State.OFF);
        pump7Builder.addAvailableStates(Bwg.Uplink.Model.Components.PumpComponent.State.OFF);
        pump7Builder.addAvailableStates(Bwg.Uplink.Model.Components.PumpComponent.State.LOW);
        pump7Builder.addAvailableStates(Bwg.Uplink.Model.Components.PumpComponent.State.HIGH);
        pump8Builder.setCurrentState(Bwg.Uplink.Model.Components.PumpComponent.State.OFF);
        pump8Builder.addAvailableStates(Bwg.Uplink.Model.Components.PumpComponent.State.OFF);
        pump8Builder.addAvailableStates(Bwg.Uplink.Model.Components.PumpComponent.State.LOW);
        pump8Builder.addAvailableStates(Bwg.Uplink.Model.Components.PumpComponent.State.HIGH);
        circulationPumpBuilder.setCurrentState(Bwg.Uplink.Model.Components.PumpComponent.State.OFF);
        circulationPumpBuilder.addAvailableStates(Bwg.Uplink.Model.Components.PumpComponent.State.OFF);
        circulationPumpBuilder.addAvailableStates(Bwg.Uplink.Model.Components.PumpComponent.State.LOW);
        circulationPumpBuilder.addAvailableStates(Bwg.Uplink.Model.Components.PumpComponent.State.HIGH);

        blower1Builder.setCurrentState(Bwg.Uplink.Model.Components.BlowerComponent.State.OFF);
        blower1Builder.addAvailableStates(Bwg.Uplink.Model.Components.BlowerComponent.State.OFF);
        blower1Builder.addAvailableStates(Bwg.Uplink.Model.Components.BlowerComponent.State.LOW);
        blower1Builder.addAvailableStates(Bwg.Uplink.Model.Components.BlowerComponent.State.MED);
        blower1Builder.addAvailableStates(Bwg.Uplink.Model.Components.BlowerComponent.State.HIGH);
        blower2Builder.setCurrentState(Bwg.Uplink.Model.Components.BlowerComponent.State.OFF);
        blower2Builder.addAvailableStates(Bwg.Uplink.Model.Components.BlowerComponent.State.OFF);
        blower2Builder.addAvailableStates(Bwg.Uplink.Model.Components.BlowerComponent.State.LOW);
        blower2Builder.addAvailableStates(Bwg.Uplink.Model.Components.BlowerComponent.State.MED);
        blower2Builder.addAvailableStates(Bwg.Uplink.Model.Components.BlowerComponent.State.HIGH);

        light1Builder.setCurrentState(Bwg.Uplink.Model.Components.LightComponent.State.OFF);
        light1Builder.addAvailableStates(Bwg.Uplink.Model.Components.LightComponent.State.OFF);
        light1Builder.addAvailableStates(Bwg.Uplink.Model.Components.LightComponent.State.LOW);
        light1Builder.addAvailableStates(Bwg.Uplink.Model.Components.LightComponent.State.MED);
        light1Builder.addAvailableStates(Bwg.Uplink.Model.Components.LightComponent.State.HIGH);
        light2Builder.setCurrentState(Bwg.Uplink.Model.Components.LightComponent.State.OFF);
        light2Builder.addAvailableStates(Bwg.Uplink.Model.Components.LightComponent.State.OFF);
        light2Builder.addAvailableStates(Bwg.Uplink.Model.Components.LightComponent.State.LOW);
        light2Builder.addAvailableStates(Bwg.Uplink.Model.Components.LightComponent.State.MED);
        light2Builder.addAvailableStates(Bwg.Uplink.Model.Components.LightComponent.State.HIGH);
        light3Builder.setCurrentState(Bwg.Uplink.Model.Components.LightComponent.State.OFF);
        light3Builder.addAvailableStates(Bwg.Uplink.Model.Components.LightComponent.State.OFF);
        light3Builder.addAvailableStates(Bwg.Uplink.Model.Components.LightComponent.State.LOW);
        light3Builder.addAvailableStates(Bwg.Uplink.Model.Components.LightComponent.State.MED);
        light3Builder.addAvailableStates(Bwg.Uplink.Model.Components.LightComponent.State.HIGH);
        light4Builder.setCurrentState(Bwg.Uplink.Model.Components.LightComponent.State.OFF);
        light4Builder.addAvailableStates(Bwg.Uplink.Model.Components.LightComponent.State.OFF);
        light4Builder.addAvailableStates(Bwg.Uplink.Model.Components.LightComponent.State.LOW);
        light4Builder.addAvailableStates(Bwg.Uplink.Model.Components.LightComponent.State.MED);
        light4Builder.addAvailableStates(Bwg.Uplink.Model.Components.LightComponent.State.HIGH);
    }

    public Bwg.Uplink.Model.SpaState buildSpaState() {
        Bwg.Uplink.Model.SpaState.Builder builder = Bwg.Uplink.Model.SpaState.newBuilder();
        builder.setController(buildController());
        builder.setSystemInfo(buildSystemInfo());
        builder.setSetupParams(buildSetupParams());
        builder.setComponents(buildComponents());

        return builder.build();
    }

    private Bwg.Uplink.Model.Controller buildController() {
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

        return controllerBuilder.build();
    }

    private Bwg.Uplink.Model.SystemInfo buildSystemInfo() {
        systemInfoBuilder.setHeaterPower(0);
        systemInfoBuilder.setMfrSSID(0);
        systemInfoBuilder.setModelSSID(0);
        systemInfoBuilder.setVersionSSID(0);
        systemInfoBuilder.setMinorVersion(0);
        systemInfoBuilder.setSwSignature(0);
        systemInfoBuilder.setHeaterType(0);
        systemInfoBuilder.setCurrentSetup(0);
        systemInfoBuilder.setLastUpdateTimestamp(0);

        return systemInfoBuilder.build();
    }

    private Bwg.Uplink.Model.SetupParams buildSetupParams() {
        setupParamsBuilder.setLowRangeLow(0);
        setupParamsBuilder.setLowRangeHigh(0);
        setupParamsBuilder.setHighRangeLow(0);
        setupParamsBuilder.setHighRangeHigh(0);
        setupParamsBuilder.setGfciEnabled(false);
        setupParamsBuilder.setDrainModeEnabled(false);
        setupParamsBuilder.setLastUpdateTimestamp(0);

        return setupParamsBuilder.build();
    }

    private Bwg.Uplink.Model.Components buildComponents() {
        final Bwg.Uplink.Model.Components.Builder builder = Bwg.Uplink.Model.Components.newBuilder();

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
        // Multi-state components
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
        // not sure about fiber wheel, leaving in here, but wouldn't refer to it in upper levels yet
        componentsBuilder.setFiberWheel(0);
        componentsBuilder.setLastUpdateTimestamp(0);

        return builder.build();
    }
}
