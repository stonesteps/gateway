package com.tritonsvc.gateway;

import com.tritonsvc.spa.communication.proto.Bwg;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.Model.Constants.BluetoothStatus;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.Model.Constants.ComponentType;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.Model.Constants.HeaterMode;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.Model.Constants.WifiConnectionHealth;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Created by holow on 3/25/2016.
 */
public class MockSpaStateHolder {

    private static final Logger log = LoggerFactory.getLogger(MockSpaStateHolder.class);

    private final Bwg.Uplink.Model.Controller.Builder controllerBuilder = Bwg.Uplink.Model.Controller.newBuilder();
    private final Bwg.Uplink.Model.SystemInfo.Builder systemInfoBuilder = Bwg.Uplink.Model.SystemInfo.newBuilder();
    private final Bwg.Uplink.Model.SetupParams.Builder setupParamsBuilder = Bwg.Uplink.Model.SetupParams.newBuilder();
    private final Bwg.Uplink.Model.Components.Builder componentsBuilder = Bwg.Uplink.Model.Components.newBuilder();

    private final Map<Bwg.Uplink.Model.Constants.ComponentType, Map<Integer, Object>> builderMap = new HashMap<>();

    private final ScheduledExecutorService scheduledExecutorService = Executors.newScheduledThreadPool(2);
    private final Map<Integer, ScheduledFuture> filterCycleUpdateFutureMap = new HashMap<>();

    public MockSpaStateHolder() {
        this(1, 1, 4, 3, 8, 1, 2, 4, 1);
    }

    // ozone 0-1, microsilk 0-1, aux 0-4, mister 0-3, pump 0-8, circpump 0-1, blower 0-2, light 0-4, filter 0-2
    public MockSpaStateHolder(final int ozoneN, final int microsilkN, final int auxN, final int misterN,
                              final int pumpN, final int circPumpN, final int blowerN, final int lightN,
                              final int filterN) {
        initBuilders(ozoneN, microsilkN, auxN, misterN, pumpN, circPumpN, blowerN, lightN, filterN);
    }

    public MockSpaStateHolder(final Properties props) {
        final int ozoneN = getInt(props, "mock.ozoneNumber", 1);
        final int microsilkN = getInt(props, "mock.microsilkNumber", 1);
        final int auxN = getInt(props, "mock.auxNumber", 4);
        final int misterN = getInt(props, "mock.misterNumber", 3);
        final int pumpN = getInt(props, "mock.pumpNumber", 8);
        final int circPumpN = getInt(props, "mock.circulationPumpNumber", 1);
        final int blowerN = getInt(props, "mock.blowerNumber", 2);
        final int lightN = getInt(props, "mock.lightNumber", 4);
        final int filterN = getInt(props, "mock.filterNumber", 1);

        initBuilders(ozoneN, microsilkN, auxN, misterN, pumpN, circPumpN, blowerN, lightN, filterN);
    }

    private void initBuilders(int ozoneN, int microsilkN, int auxN, int misterN, int pumpN, int circPumpN, int blowerN, int lightN, int filterN) {
        initControllerBuilder();
        initSystemInfoBuilder();
        initSetupParamsBuilder();
        initComponentBuilders(ozoneN, microsilkN, auxN, misterN, pumpN, circPumpN, blowerN, lightN, filterN);
    }

    public Bwg.Uplink.Model.SpaState buildSpaState() {
        final long timestamp = System.currentTimeMillis();
        final Bwg.Uplink.Model.SpaState.Builder builder = Bwg.Uplink.Model.SpaState.newBuilder();
        builder.setController(buildController(timestamp));
        builder.setSystemInfo(buildSystemInfo(timestamp));
        builder.setSetupParams(buildSetupParams(timestamp));
        builder.setComponents(buildComponents(timestamp));
        builder.setLastUpdateTimestamp(timestamp);
        builder.setWifiState(WifiConnectionHealth.AVG);
        builder.setEthernetPluggedIn(false);
        builder.setUpdateInterval(60);

        final Bwg.Uplink.Model.SpaState state = builder.build();
        return state;
    }

    public void updateHeater(final Integer temperature) {
        log.info("Updating spa state, setting temperature to {}", temperature);
        controllerBuilder.setCurrentWaterTemp(temperature.intValue());
        controllerBuilder.setTargetWaterTemperature(temperature.intValue());

        componentsBuilder.setHeater1(Bwg.Uplink.Model.Components.HeaterState.ON);
        componentsBuilder.setHeater2(Bwg.Uplink.Model.Components.HeaterState.ON);
    }

    public void updateFilterCycle(final Integer port, final Integer durationMinutes) {
        // create a cancellable task that will turn off the filter cycle at the end of duration
        ScheduledFuture updateTask = filterCycleUpdateFutureMap.get(port);
        if (updateTask != null) {
            updateTask.cancel(true);
        }
        if (durationMinutes > 0) {
            updateFilterCycleState(port.intValue(), true);
            updateTask = scheduledExecutorService.schedule(() -> {
                updateFilterCycleState(port.intValue(), false);
            }, durationMinutes.intValue(), TimeUnit.MINUTES);
            filterCycleUpdateFutureMap.put(port, updateTask);
        } else {
            updateFilterCycleState(port.intValue(), false);
        }
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
                    updateToggleComponent(builder, desiredState);
                    break;
                case PUMP:
                    updatePumpComponent(builder, desiredState);
                    break;
                case CIRCULATION_PUMP:
                    updatePumpComponent(builder, desiredState);
                    break;
                case BLOWER:
                    updateBlowerComponent(builder, desiredState);
                    break;
                case LIGHT:
                    updateLightComponent(builder, desiredState);
                    break;
            }
        }
    }

    private void initControllerBuilder() {
        controllerBuilder.setPackType("NGSC");
        controllerBuilder.setHeaterMode(HeaterMode.REST);
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
        controllerBuilder.setBluetoothStatus(BluetoothStatus.OFF);
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

    private void initComponentBuilders(int ozoneN, int microsilkN, int auxN, int misterN, int pumpN, int circPumpN, int blowerN, int lightN, int filterN) {
        // ozone
        for (int i = 0; i < ozoneN; i++) {
            addToggleComponent(Bwg.Uplink.Model.Constants.ComponentType.OZONE, i);
        }

        // microsilk
        for (int i = 0; i < microsilkN; i++) {
            addToggleComponent(Bwg.Uplink.Model.Constants.ComponentType.MICROSILK, i);
        }

        // aux
        for (int i = 0; i < auxN; i++) {
            addToggleComponent(Bwg.Uplink.Model.Constants.ComponentType.AUX, i);
        }

        // mister
        for (int i = 0; i < misterN; i++) {
            addToggleComponent(Bwg.Uplink.Model.Constants.ComponentType.MISTER, i);
        }

        // filter cycle
        for (int i = 0; i < filterN; i++) {
            addToggleComponent(Bwg.Uplink.Model.Constants.ComponentType.FILTER, i);
        }

        // pump
        for (int i = 0; i < pumpN; i++) {
            addPumpComponent(Bwg.Uplink.Model.Constants.ComponentType.PUMP, i);
        }

        // circulation pump
        for (int i = 0; i < circPumpN; i++) {
            addPumpComponent(Bwg.Uplink.Model.Constants.ComponentType.CIRCULATION_PUMP, i);
        }

        // blower
        for (int i = 0; i < blowerN; i++) {
            addBlowerComponent(Bwg.Uplink.Model.Constants.ComponentType.BLOWER, i);
        }

        // light
        for (int i = 0; i < lightN; i++) {
            addLightComponent(Bwg.Uplink.Model.Constants.ComponentType.LIGHT, i);
        }

        componentsBuilder.setHeater1(Bwg.Uplink.Model.Components.HeaterState.OFF);
        componentsBuilder.setHeater2(Bwg.Uplink.Model.Components.HeaterState.OFF);

        setupPeripherlalBuilders();

        componentsBuilder.setFiberWheel(0);
        componentsBuilder.setLastUpdateTimestamp(0);
    }

    private void addToggleComponent(final Bwg.Uplink.Model.Constants.ComponentType type, final int index) {
        final Bwg.Uplink.Model.Components.ToggleComponent.Builder builder = Bwg.Uplink.Model.Components.ToggleComponent.newBuilder();
        builder.setCurrentState(Bwg.Uplink.Model.Components.ToggleComponent.State.OFF);
        builder.addAvailableStates(Bwg.Uplink.Model.Components.ToggleComponent.State.ON);
        builder.addAvailableStates(Bwg.Uplink.Model.Components.ToggleComponent.State.OFF);
        addBuilder(type, index, builder);
    }

    private void addPumpComponent(final Bwg.Uplink.Model.Constants.ComponentType type, final int index) {
        final Bwg.Uplink.Model.Components.PumpComponent.Builder builder = Bwg.Uplink.Model.Components.PumpComponent.newBuilder();
        builder.setCurrentState(Bwg.Uplink.Model.Components.PumpComponent.State.OFF);
        builder.addAvailableStates(Bwg.Uplink.Model.Components.PumpComponent.State.OFF);
        builder.addAvailableStates(Bwg.Uplink.Model.Components.PumpComponent.State.LOW);
        builder.addAvailableStates(Bwg.Uplink.Model.Components.PumpComponent.State.HIGH);
        addBuilder(type, index, builder);
    }

    private void addBlowerComponent(final Bwg.Uplink.Model.Constants.ComponentType type, final int index) {
        final Bwg.Uplink.Model.Components.BlowerComponent.Builder builder = Bwg.Uplink.Model.Components.BlowerComponent.newBuilder();
        builder.setCurrentState(Bwg.Uplink.Model.Components.BlowerComponent.State.OFF);
        builder.addAvailableStates(Bwg.Uplink.Model.Components.BlowerComponent.State.OFF);
        builder.addAvailableStates(Bwg.Uplink.Model.Components.BlowerComponent.State.LOW);
        builder.addAvailableStates(Bwg.Uplink.Model.Components.BlowerComponent.State.MED);
        builder.addAvailableStates(Bwg.Uplink.Model.Components.BlowerComponent.State.HIGH);
        addBuilder(type, index, builder);
    }

    private void addLightComponent(final Bwg.Uplink.Model.Constants.ComponentType type, final int index) {
        final Bwg.Uplink.Model.Components.LightComponent.Builder builder = Bwg.Uplink.Model.Components.LightComponent.newBuilder();
        builder.setCurrentState(Bwg.Uplink.Model.Components.LightComponent.State.OFF);
        builder.addAvailableStates(Bwg.Uplink.Model.Components.LightComponent.State.OFF);
        builder.addAvailableStates(Bwg.Uplink.Model.Components.LightComponent.State.LOW);
        builder.addAvailableStates(Bwg.Uplink.Model.Components.LightComponent.State.MED);
        builder.addAvailableStates(Bwg.Uplink.Model.Components.LightComponent.State.HIGH);
        addBuilder(type, index, builder);
    }

    private void setupPeripherlalBuilders() {
        if (getBuilder(Bwg.Uplink.Model.Constants.ComponentType.OZONE, 0) != null)
            componentsBuilder.setOzone((Bwg.Uplink.Model.Components.ToggleComponent.Builder) getBuilder(Bwg.Uplink.Model.Constants.ComponentType.OZONE, 0));
        if (getBuilder(Bwg.Uplink.Model.Constants.ComponentType.MICROSILK, 0) != null)
            componentsBuilder.setMicroSilk((Bwg.Uplink.Model.Components.ToggleComponent.Builder) getBuilder(Bwg.Uplink.Model.Constants.ComponentType.MICROSILK, 0));
        if (getBuilder(Bwg.Uplink.Model.Constants.ComponentType.FILTER, 0) != null)
            componentsBuilder.setFilterCycle1((Bwg.Uplink.Model.Components.ToggleComponent.Builder) getBuilder(Bwg.Uplink.Model.Constants.ComponentType.FILTER, 0));
        if (getBuilder(Bwg.Uplink.Model.Constants.ComponentType.FILTER, 1) != null)
            componentsBuilder.setFilterCycle2((Bwg.Uplink.Model.Components.ToggleComponent.Builder) getBuilder(Bwg.Uplink.Model.Constants.ComponentType.FILTER, 1));
        if (getBuilder(Bwg.Uplink.Model.Constants.ComponentType.AUX, 0) != null)
            componentsBuilder.setAux1((Bwg.Uplink.Model.Components.ToggleComponent.Builder) getBuilder(Bwg.Uplink.Model.Constants.ComponentType.AUX, 0));
        if (getBuilder(Bwg.Uplink.Model.Constants.ComponentType.AUX, 1) != null)
            componentsBuilder.setAux2((Bwg.Uplink.Model.Components.ToggleComponent.Builder) getBuilder(Bwg.Uplink.Model.Constants.ComponentType.AUX, 1));
        if (getBuilder(Bwg.Uplink.Model.Constants.ComponentType.AUX, 2) != null)
            componentsBuilder.setAux3((Bwg.Uplink.Model.Components.ToggleComponent.Builder) getBuilder(Bwg.Uplink.Model.Constants.ComponentType.AUX, 2));
        if (getBuilder(Bwg.Uplink.Model.Constants.ComponentType.AUX, 3) != null)
            componentsBuilder.setAux4((Bwg.Uplink.Model.Components.ToggleComponent.Builder) getBuilder(Bwg.Uplink.Model.Constants.ComponentType.AUX, 3));
        if (getBuilder(Bwg.Uplink.Model.Constants.ComponentType.MISTER, 0) != null)
            componentsBuilder.setMister1((Bwg.Uplink.Model.Components.ToggleComponent.Builder) getBuilder(Bwg.Uplink.Model.Constants.ComponentType.MISTER, 0));
        if (getBuilder(Bwg.Uplink.Model.Constants.ComponentType.MISTER, 1) != null)
            componentsBuilder.setMister2((Bwg.Uplink.Model.Components.ToggleComponent.Builder) getBuilder(Bwg.Uplink.Model.Constants.ComponentType.MISTER, 1));
        if (getBuilder(Bwg.Uplink.Model.Constants.ComponentType.MISTER, 2) != null)
            componentsBuilder.setMister3((Bwg.Uplink.Model.Components.ToggleComponent.Builder) getBuilder(Bwg.Uplink.Model.Constants.ComponentType.MISTER, 2));
        if (getBuilder(Bwg.Uplink.Model.Constants.ComponentType.PUMP, 0) != null)
            componentsBuilder.setPump1((Bwg.Uplink.Model.Components.PumpComponent.Builder) getBuilder(Bwg.Uplink.Model.Constants.ComponentType.PUMP, 0));
        if (getBuilder(Bwg.Uplink.Model.Constants.ComponentType.PUMP, 1) != null)
            componentsBuilder.setPump2((Bwg.Uplink.Model.Components.PumpComponent.Builder) getBuilder(Bwg.Uplink.Model.Constants.ComponentType.PUMP, 1));
        if (getBuilder(Bwg.Uplink.Model.Constants.ComponentType.PUMP, 2) != null)
            componentsBuilder.setPump3((Bwg.Uplink.Model.Components.PumpComponent.Builder) getBuilder(Bwg.Uplink.Model.Constants.ComponentType.PUMP, 2));
        if (getBuilder(Bwg.Uplink.Model.Constants.ComponentType.PUMP, 3) != null)
            componentsBuilder.setPump4((Bwg.Uplink.Model.Components.PumpComponent.Builder) getBuilder(Bwg.Uplink.Model.Constants.ComponentType.PUMP, 3));
        if (getBuilder(Bwg.Uplink.Model.Constants.ComponentType.PUMP, 4) != null)
            componentsBuilder.setPump5((Bwg.Uplink.Model.Components.PumpComponent.Builder) getBuilder(Bwg.Uplink.Model.Constants.ComponentType.PUMP, 4));
        if (getBuilder(Bwg.Uplink.Model.Constants.ComponentType.PUMP, 5) != null)
            componentsBuilder.setPump6((Bwg.Uplink.Model.Components.PumpComponent.Builder) getBuilder(Bwg.Uplink.Model.Constants.ComponentType.PUMP, 5));
        if (getBuilder(Bwg.Uplink.Model.Constants.ComponentType.PUMP, 6) != null)
            componentsBuilder.setPump7((Bwg.Uplink.Model.Components.PumpComponent.Builder) getBuilder(Bwg.Uplink.Model.Constants.ComponentType.PUMP, 6));
        if (getBuilder(Bwg.Uplink.Model.Constants.ComponentType.PUMP, 7) != null)
            componentsBuilder.setPump8((Bwg.Uplink.Model.Components.PumpComponent.Builder) getBuilder(Bwg.Uplink.Model.Constants.ComponentType.PUMP, 7));
        if (getBuilder(Bwg.Uplink.Model.Constants.ComponentType.CIRCULATION_PUMP, 0) != null)
            componentsBuilder.setCirculationPump((Bwg.Uplink.Model.Components.PumpComponent.Builder) getBuilder(Bwg.Uplink.Model.Constants.ComponentType.CIRCULATION_PUMP, 0));
        if (getBuilder(Bwg.Uplink.Model.Constants.ComponentType.BLOWER, 0) != null)
            componentsBuilder.setBlower1((Bwg.Uplink.Model.Components.BlowerComponent.Builder) getBuilder(Bwg.Uplink.Model.Constants.ComponentType.BLOWER, 0));
        if (getBuilder(Bwg.Uplink.Model.Constants.ComponentType.BLOWER, 1) != null)
            componentsBuilder.setBlower2((Bwg.Uplink.Model.Components.BlowerComponent.Builder) getBuilder(Bwg.Uplink.Model.Constants.ComponentType.BLOWER, 1));
        if (getBuilder(Bwg.Uplink.Model.Constants.ComponentType.LIGHT, 0) != null)
            componentsBuilder.setLight1((Bwg.Uplink.Model.Components.LightComponent.Builder) getBuilder(Bwg.Uplink.Model.Constants.ComponentType.LIGHT, 0));
        if (getBuilder(Bwg.Uplink.Model.Constants.ComponentType.LIGHT, 1) != null)
            componentsBuilder.setLight2((Bwg.Uplink.Model.Components.LightComponent.Builder) getBuilder(Bwg.Uplink.Model.Constants.ComponentType.LIGHT, 1));
        if (getBuilder(Bwg.Uplink.Model.Constants.ComponentType.LIGHT, 2) != null)
            componentsBuilder.setLight3((Bwg.Uplink.Model.Components.LightComponent.Builder) getBuilder(Bwg.Uplink.Model.Constants.ComponentType.LIGHT, 2));
        if (getBuilder(Bwg.Uplink.Model.Constants.ComponentType.LIGHT, 3) != null)
            componentsBuilder.setLight4((Bwg.Uplink.Model.Components.LightComponent.Builder) getBuilder(Bwg.Uplink.Model.Constants.ComponentType.LIGHT, 3));
    }

    private Object getBuilder(final Bwg.Uplink.Model.Constants.ComponentType type, final Integer port) {
        final Map<Integer, Object> builders = builderMap.get(type);
        return builders != null ? builders.get(port) : null;
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
        setupPeripherlalBuilders();
        return componentsBuilder.build();
    }

    private void updateToggleComponent(final Object builderObject, final String desiredState) {
        final Bwg.Uplink.Model.Components.ToggleComponent.Builder builder = (Bwg.Uplink.Model.Components.ToggleComponent.Builder) builderObject;
        builder.setCurrentState(Bwg.Uplink.Model.Components.ToggleComponent.State.valueOf(desiredState));
    }

    private void updatePumpComponent(final Object builderObject, final String desiredState) {
        final Bwg.Uplink.Model.Components.PumpComponent.Builder builder = (Bwg.Uplink.Model.Components.PumpComponent.Builder) builderObject;
        builder.setCurrentState(Bwg.Uplink.Model.Components.PumpComponent.State.valueOf(desiredState));
    }

    private void updateBlowerComponent(final Object builderObject, final String desiredState) {
        final Bwg.Uplink.Model.Components.BlowerComponent.Builder builder = (Bwg.Uplink.Model.Components.BlowerComponent.Builder) builderObject;
        builder.setCurrentState(Bwg.Uplink.Model.Components.BlowerComponent.State.valueOf(desiredState));
    }

    private void updateLightComponent(final Object builderObject, final String desiredState) {
        final Bwg.Uplink.Model.Components.LightComponent.Builder builder = (Bwg.Uplink.Model.Components.LightComponent.Builder) builderObject;
        builder.setCurrentState(Bwg.Uplink.Model.Components.LightComponent.State.valueOf(desiredState));
    }

    private void updateFilterCycleState(final int number, boolean state) {
        final Map<Integer, Object> builders = builderMap.get(ComponentType.FILTER);
        if (builders == null) {
            return;
        }
        final Object builder = builders.get(number);
        updateToggleComponent(builder, state ? "ON" : "OFF");
    }

    private int getInt(final Properties props, final String name, int defaultValue) {
        int value = defaultValue;
        final String strValue = props.getProperty(name);
        if (strValue != null) {
            try {
                value = Integer.parseInt(strValue);
            } catch (final NumberFormatException e) {
                // ignore
            }
        }
        return value;
    }

    public void shutdown() {
        scheduledExecutorService.shutdown();
    }
}
