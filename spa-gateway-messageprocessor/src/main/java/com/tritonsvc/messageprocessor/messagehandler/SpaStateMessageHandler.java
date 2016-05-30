package com.tritonsvc.messageprocessor.messagehandler;

import com.bwg.iot.model.*;
import com.bwg.iot.model.Component.ComponentType;
import com.tritonsvc.messageprocessor.mongo.repository.ComponentRepository;
import com.tritonsvc.messageprocessor.mongo.repository.SpaRepository;
import com.tritonsvc.spa.communication.proto.Bwg;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.Model.Constants;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static com.google.common.collect.Lists.newArrayList;
import static java.util.stream.Collectors.toList;

/**
 * process downlink acks from spa systems
 */
@Component
public class SpaStateMessageHandler extends AbstractMessageHandler<Bwg.Uplink.Model.SpaState> {

    private static final Logger log = LoggerFactory.getLogger(SpaStateMessageHandler.class);

    @Autowired
    private SpaRepository spaRepository;

    @Autowired
    private ComponentRepository componentRepository;

    @Override
    public Class<Bwg.Uplink.Model.SpaState> handles() {
        return Bwg.Uplink.Model.SpaState.class;
    }

    @Override
    public void processMessage(final Bwg.Header header, final Bwg.Uplink.UplinkHeader uplinkHeader, final Bwg.Uplink.Model.SpaState spaState) {
        log.info("Processing spa state message from spa {}", header.getOriginator(), uplinkHeader.getHardwareId());

        Spa spa = spaRepository.findOne(uplinkHeader.getHardwareId());
        if (spa == null) {
            log.error("received spa state for unknown spa id {}", uplinkHeader.getHardwareId());
            return;
        }

        SpaState spaStateEntity = spa.getCurrentState();
        if (spaStateEntity == null) {
            spaStateEntity = new SpaState();
            spa.setCurrentState(spaStateEntity);
        }

        spaStateEntity.setDesiredTemp(spaState.hasController() && spaState.getController().hasTargetWaterTemperature() ? Integer.toString(spaState.getController().getTargetWaterTemperature()) : null);
        spaStateEntity.setCurrentTemp(spaState.hasController() && spaState.getController().hasCurrentWaterTemp() ? Integer.toString(spaState.getController().getCurrentWaterTemp()) : null);
        spaStateEntity.setCleanupCycle(spaState.hasController() && spaState.getController().hasCleanupCycle() ? spaState.getController().getCleanupCycle() : false);
        spaStateEntity.setErrorCode(spaState.hasController() && spaState.getController().hasErrorCode() ? spaState.getController().getErrorCode() : 0);
        spaStateEntity.setMessageSeverity(spaState.hasController() && spaState.getController().hasMessageSeverity() ? spaState.getController().getMessageSeverity() : null);
        spaStateEntity.setUplinkTimestamp(spaState.hasLastUpdateTimestamp() ? new Date(spaState.getLastUpdateTimestamp()) : new Date());
        if (spaState.hasUpdateInterval()) {
            spaStateEntity.setUpdateIntervalSeconds(spaState.getUpdateInterval());
        }

        if (spaState.hasWifiUpdateInterval()) {
            spaStateEntity.setWifiUpdateIntervalSeconds(spaState.getWifiUpdateInterval());
        }

        if (spaState.hasController()) {
            updateOtherControllerParams(spaStateEntity, spaState.getController());
        }

        if (spaState.hasSetupParams()) {
            spaStateEntity.setSetupParams(buildSetupParams(spaState.getSetupParams()));
        } else if (spaStateEntity.getSetupParams() != null) {
            spaStateEntity.getSetupParams().setLastUpdateTimestamp(null);
        }
        if (spaState.hasSystemInfo()) {
            spaStateEntity.setSystemInfo(buildSystemInfo(spaState.getSystemInfo(), spaStateEntity.getSystemInfo()));
        } else if (spaStateEntity.getSystemInfo() != null) {
            spaStateEntity.getSystemInfo().setLastUpdateTimestamp(null);
        }

        // all component states get initialized to non-registered, then as the registerable components get processed
        // they'll put register dates on where needed
        spaStateEntity.getComponents().stream().forEach(state -> state.setRegisteredTimestamp(null));
        if (spaState.hasComponents()) {
            updateComponents(spa.get_id(), spaStateEntity, spaState.getComponents());
        }
        updateComponentState(spa.get_id(), spaStateEntity, ComponentType.GATEWAY.toString(), null, newArrayList(), null);
        updateComponentState(spa.get_id(), spaStateEntity, ComponentType.CONTROLLER.toString(), null, newArrayList(), null);

        // wifi signal strength
        if (spaState.hasWifiState()) {
            spaStateEntity.setWifiConnectionHealth(WifiConnectionHealth.toEnum(spaState.getWifiState().getNumber()));
        }
        if (spaState.hasEthernetPluggedIn()) {
            spaStateEntity.setEthernetPluggedIn(spaState.getEthernetPluggedIn());
        }

        spaRepository.save(spa);
    }

    private void updateOtherControllerParams(final SpaState spaStateEntity, final Bwg.Uplink.Model.Controller controller) {
        spaStateEntity.setHeaterMode(controller.getHeaterMode().name());
        spaStateEntity.setRunMode(controller.getHeaterMode().equals(Constants.HeaterMode.REST) ? "Rest" : "Ready");
        spaStateEntity.setControllerType(controller.getPackType());
        spaStateEntity.setHour(controller.getHour());
        spaStateEntity.setMinute(controller.getMinute());
        spaStateEntity.setCelsius(controller.getCelsius());
        spaStateEntity.setDemoMode(controller.getDemoMode());
        spaStateEntity.setTimeNotSet(controller.getTimeNotSet());
        spaStateEntity.setSettingsLock(controller.getSettingsLock());
        spaStateEntity.setSpaOverheatDisabled(controller.getSpaOverheatDisabled());
        spaStateEntity.setBluetoothStatus(controller.getBluetoothStatus().name());

        spaStateEntity.setUiCode(controller.hasUiCode() ? controller.getUiCode() : null);
        spaStateEntity.setUiSubCode(controller.hasUiSubCode() ? controller.getUiSubCode() : null);
        spaStateEntity.setInvert(controller.hasInvert() ? controller.getInvert() : null);
        spaStateEntity.setAllSegsOn(controller.hasAllSegsOn() ? controller.getAllSegsOn() : null);
        spaStateEntity.setPanelLock(controller.hasPanelLock() ? controller.getPanelLock() : null);
        spaStateEntity.setMilitary(controller.hasMilitary() ? controller.getMilitary() : null);
        spaStateEntity.setTempRange(controller.hasTempRange() ? TempRange.valueOf(controller.getTempRange().toString()) : null);
        spaStateEntity.setPrimingMode(controller.hasPrimingMode() ? controller.getPrimingMode() : null);
        spaStateEntity.setSoundAlarm(controller.hasSoundAlarm() ? controller.getSoundAlarm() : null);
        spaStateEntity.setRepeat(controller.hasRepeat() ? controller.getRepeat() : null);
        spaStateEntity.setPanelMode(controller.hasPanelMode() ? PanelMode.valueOf(controller.getPanelMode().toString()) : null);
        spaStateEntity.setSwimSpaMode(controller.hasSwimSpaMode() ? SwimSpaMode.valueOf(controller.getSwimSpaMode().toString()) : null);
        spaStateEntity.setSwimSpaModeChanging(controller.hasSwimSpaModeChanging() ? controller.getSwimSpaModeChanging() : null);
        spaStateEntity.setHeaterCooling(controller.hasHeaterCooling() ? controller.getHeaterCooling() : null);
        spaStateEntity.setLatchingMessage(controller.hasLatchingMessage() ? controller.getLatchingMessage() : null);
        spaStateEntity.setLightCycle(controller.hasLightCycle() ? controller.getLightCycle() : null);
        spaStateEntity.setElapsedTimeDisplay(controller.hasElapsedTimeDisplay() ? controller.getElapsedTimeDisplay() : null);
        spaStateEntity.setTvLiftState(controller.hasTvLiftState() ? controller.getTvLiftState() : null);
        spaStateEntity.setSpecialTimeouts(controller.hasSpecialTimeouts() ? controller.getSpecialTimeouts() : null);
        spaStateEntity.setABDisplay(controller.hasABDisplay() ? controller.getABDisplay() : null);
        spaStateEntity.setStirring(controller.hasStirring() ? controller.getStirring() : null);
        spaStateEntity.setEcoMode(controller.hasEcoMode() ? controller.getEcoMode() : null);
        spaStateEntity.setSoakMode(controller.hasSoakMode() ? controller.getSoakMode() : null);
        spaStateEntity.setOverrangeEnabled(controller.hasOverrangeEnabled() ? controller.getOverrangeEnabled() : null);
        spaStateEntity.setHeatExternallyDisabled(controller.hasHeatExternallyDisabled() ? controller.getHeatExternallyDisabled() : null);
        spaStateEntity.setTestMode(controller.hasTestMode() ? controller.getTestMode() : null);
        spaStateEntity.setTempLock(controller.hasTempLock() ? controller.getTempLock() : null);

        spaStateEntity.setSecondaryFiltrationMode(controller.hasSecondaryFiltrationMode() ? FiltrationMode.valueOf(controller.getSecondaryFiltrationMode().name()) : null);
        spaStateEntity.setSpaRunState(controller.hasSpaState() ? SpaRunState.valueOf(controller.getSpaState().name()) : null);
        spaStateEntity.setAmbientTemp(controller.hasAmbientTemp() ? controller.getAmbientTemp() : null);
        spaStateEntity.setDay(controller.hasDay() ? controller.getDay() : null);
        spaStateEntity.setMonth(controller.hasMonth() ? controller.getMonth() : null);
        spaStateEntity.setYear(controller.hasYear() ? controller.getYear() : null);
        spaStateEntity.setReminderCode(controller.hasReminderCode() ? ReminderCode.valueOf(controller.getReminderCode().name()) : null);
        spaStateEntity.setReminderDaysClearRay(controller.hasReminderDaysClearRay() ? controller.getReminderDaysClearRay() : null);
        spaStateEntity.setReminderDaysWater(controller.hasReminderDaysWater() ? controller.getReminderDaysWater() : null);
        spaStateEntity.setReminderDaysFilter1(controller.hasReminderDaysFilter1() ? controller.getReminderDaysFilter1() : null);
        spaStateEntity.setReminderDaysFilter2(controller.hasReminderDaysFilter2() ? controller.getReminderDaysFilter2() : null);
        spaStateEntity.setBlowout(controller.hasBlowout() ? controller.getBlowout() : null);
        spaStateEntity.setWaterLevel1(controller.hasWaterLevel1() ? controller.getWaterLevel1() : null);
        spaStateEntity.setWaterLevel2(controller.hasWaterLevel2() ? controller.getWaterLevel2() : null);
        spaStateEntity.setFlowSwitchClosed(controller.hasFlowSwitchClosed() ? controller.getFlowSwitchClosed() : null);
        spaStateEntity.setChangeUV(controller.hasChangeUV() ? controller.getChangeUV() : null);
        spaStateEntity.setHiLimitTemp(controller.hasHiLimitTemp() ? controller.getHiLimitTemp() : null);
        spaStateEntity.setRegistrationLockout(controller.hasRegistrationLockout() ? controller.getRegistrationLockout() : null);
        spaStateEntity.setEngineeringMode(controller.hasEngineeringMode() ? controller.getEngineeringMode() : null);
        spaStateEntity.setAccessLocked(controller.hasAccessLocked() ? controller.getAccessLocked() : null);
        spaStateEntity.setMaintenanceLocked(controller.hasMaintenanceLocked() ? controller.getMaintenanceLocked() : null);
    }

    private SetupParams buildSetupParams(final Bwg.Uplink.Model.SetupParams setupParams) {
        final SetupParams setupParamsEntity = new SetupParams();

        setupParamsEntity.setLowRangeLow(setupParams.getLowRangeLow());
        setupParamsEntity.setLowRangeHigh(setupParams.getLowRangeHigh());
        setupParamsEntity.setHighRangeLow(setupParams.getHighRangeLow());
        setupParamsEntity.setHighRangeHigh(setupParams.getHighRangeHigh());
        setupParamsEntity.setGfciEnabled(setupParams.getGfciEnabled());
        setupParamsEntity.setDrainModeEnabled(setupParams.getDrainModeEnabled());

        setupParamsEntity.setLastUpdateTimestamp(new Date(setupParams.getLastUpdateTimestamp()));

        return setupParamsEntity;
    }

    private SystemInfo buildSystemInfo(final Bwg.Uplink.Model.SystemInfo systemInfo, SystemInfo systemInfoEntity) {
        if (systemInfoEntity == null) {
            systemInfoEntity = new SystemInfo();
        }

        if (systemInfo.hasHeaterPower()) systemInfoEntity.setHeaterPower(systemInfo.getHeaterPower());
        if (systemInfo.hasMfrSSID()) systemInfoEntity.setMfrSSID(systemInfo.getMfrSSID());
        if (systemInfo.hasModelSSID()) systemInfoEntity.setModelSSID(systemInfo.getModelSSID());
        if (systemInfo.hasVersionSSID()) systemInfoEntity.setVersionSSID(systemInfo.getVersionSSID());
        if (systemInfo.hasPackMinorVersion()) systemInfoEntity.setMinorVersion(systemInfo.getMinorVersion());
        if (systemInfo.hasSwSignature()) systemInfoEntity.setSwSignature(systemInfo.getSwSignature());
        if (systemInfo.hasHeaterType()) systemInfoEntity.setHeaterType(systemInfo.getHeaterType());
        if (systemInfo.hasCurrentSetup()) systemInfoEntity.setCurrentSetup(systemInfo.getCurrentSetup());
        if (systemInfo.hasPackMinorVersion()) systemInfoEntity.setPackMinorVersion(systemInfo.getPackMinorVersion());
        if (systemInfo.hasPackMajorVersion()) systemInfoEntity.setPackMajorVersion(systemInfo.getPackMajorVersion());
        if (systemInfo.hasSerialNumber()) systemInfoEntity.setSerialNumber((long) systemInfo.getSerialNumber());
        systemInfoEntity.getDipSwitches().clear();
        systemInfoEntity.getDipSwitches().addAll(systemInfo.getDipSwitchesList().stream().map(dip -> new DipSwitch(dip.getSlotNumber(), dip.getOn())).collect(toList()));
        systemInfoEntity.setLastUpdateTimestamp(new Date(systemInfo.getLastUpdateTimestamp()));

        return systemInfoEntity;
    }

    private void updateComponents(final String spaId, final SpaState spaStateEntity, final Bwg.Uplink.Model.Components components) {
        // heater
        if (components.hasHeater1()) { updateComponentState(spaId, spaStateEntity, Bwg.Uplink.Model.Constants.ComponentType.HEATER.toString(), components.getHeater1().toString(), null, 0); }
        if (components.hasHeater2()) { updateComponentState(spaId, spaStateEntity, Bwg.Uplink.Model.Constants.ComponentType.HEATER.toString(), components.getHeater2().toString(), null, 1); }

        // filter cycles
        if (components.hasFilterCycle1()) { updateComponentState(spaId, spaStateEntity, Constants.ComponentType.FILTER.toString(), components.getFilterCycle1().getCurrentState().toString(), toStringList(components.getFilterCycle1().getAvailableStatesList()), 0); }
        if (components.hasFilterCycle2()) { updateComponentState(spaId, spaStateEntity, Constants.ComponentType.FILTER.toString(), components.getFilterCycle2().getCurrentState().toString(), toStringList(components.getFilterCycle2().getAvailableStatesList()), 1); }

        // ozone
        if (components.hasOzone()) { updateComponentState(spaId, spaStateEntity, Bwg.Uplink.Model.Constants.ComponentType.OZONE.toString(), components.getOzone().getCurrentState().toString(), toStringList(components.getOzone().getAvailableStatesList())); }

        // uv
        if (components.hasUv()) { updateComponentState(spaId, spaStateEntity, Bwg.Uplink.Model.Constants.ComponentType.UV.toString(), components.getUv().getCurrentState().toString(), toStringList(components.getUv().getAvailableStatesList())); }

        // audio/visual
        if (components.hasAudioVisual()) { updateComponentState(spaId, spaStateEntity, Bwg.Uplink.Model.Constants.ComponentType.AV.toString(), Integer.toString(components.getAudioVisual()), null); }

        // microsilk
        if (components.hasMicroSilk()) { updateComponentState(spaId, spaStateEntity, Bwg.Uplink.Model.Constants.ComponentType.MICROSILK.toString(), components.getMicroSilk().getCurrentState().toString(), toStringList(components.getMicroSilk().getAvailableStatesList())); }

        // aux
        if (components.hasAux1()) { updateComponentState(spaId, spaStateEntity, com.bwg.iot.model.Component.ComponentType.AUX.toString(), components.getAux1().getCurrentState().toString(), toStringList(components.getAux1().getAvailableStatesList()), 0); }
        if (components.hasAux2()) { updateComponentState(spaId, spaStateEntity, com.bwg.iot.model.Component.ComponentType.AUX.toString(), components.getAux2().getCurrentState().toString(), toStringList(components.getAux2().getAvailableStatesList()), 1); }
        if (components.hasAux3()) { updateComponentState(spaId, spaStateEntity, com.bwg.iot.model.Component.ComponentType.AUX.toString(), components.getAux3().getCurrentState().toString(), toStringList(components.getAux3().getAvailableStatesList()), 2); }
        if (components.hasAux4()) { updateComponentState(spaId, spaStateEntity, com.bwg.iot.model.Component.ComponentType.AUX.toString(), components.getAux4().getCurrentState().toString(), toStringList(components.getAux4().getAvailableStatesList()), 3); }

        // mister
        if (components.hasMister1()) { updateComponentState(spaId, spaStateEntity, com.bwg.iot.model.Component.ComponentType.MISTER.toString(), components.getMister1().getCurrentState().toString(), toStringList(components.getMister1().getAvailableStatesList()), 0); }
        if (components.hasMister2()) { updateComponentState(spaId, spaStateEntity, com.bwg.iot.model.Component.ComponentType.MISTER.toString(), components.getMister2().getCurrentState().toString(), toStringList(components.getMister2().getAvailableStatesList()), 1); }
        if (components.hasMister3()) { updateComponentState(spaId, spaStateEntity, com.bwg.iot.model.Component.ComponentType.MISTER.toString(), components.getMister3().getCurrentState().toString(), toStringList(components.getMister3().getAvailableStatesList()), 2); }

        // pumps
        if (components.hasPump1()) { updateComponentState(spaId, spaStateEntity, com.bwg.iot.model.Component.ComponentType.PUMP.toString(), components.getPump1().getCurrentState().toString(), toStringList(components.getPump1().getAvailableStatesList()), 0); }
        if (components.hasPump2()) { updateComponentState(spaId, spaStateEntity, com.bwg.iot.model.Component.ComponentType.PUMP.toString(), components.getPump2().getCurrentState().toString(), toStringList(components.getPump2().getAvailableStatesList()), 1); }
        if (components.hasPump3()) { updateComponentState(spaId, spaStateEntity, com.bwg.iot.model.Component.ComponentType.PUMP.toString(), components.getPump3().getCurrentState().toString(), toStringList(components.getPump3().getAvailableStatesList()), 2); }
        if (components.hasPump4()) { updateComponentState(spaId, spaStateEntity, com.bwg.iot.model.Component.ComponentType.PUMP.toString(), components.getPump4().getCurrentState().toString(), toStringList(components.getPump4().getAvailableStatesList()), 3); }
        if (components.hasPump5()) { updateComponentState(spaId, spaStateEntity, com.bwg.iot.model.Component.ComponentType.PUMP.toString(), components.getPump5().getCurrentState().toString(), toStringList(components.getPump5().getAvailableStatesList()), 4); }
        if (components.hasPump6()) { updateComponentState(spaId, spaStateEntity, com.bwg.iot.model.Component.ComponentType.PUMP.toString(), components.getPump6().getCurrentState().toString(), toStringList(components.getPump6().getAvailableStatesList()), 5); }
        if (components.hasPump7()) { updateComponentState(spaId, spaStateEntity, com.bwg.iot.model.Component.ComponentType.PUMP.toString(), components.getPump7().getCurrentState().toString(), toStringList(components.getPump7().getAvailableStatesList()), 6); }
        if (components.hasPump8()) { updateComponentState(spaId, spaStateEntity, com.bwg.iot.model.Component.ComponentType.PUMP.toString(), components.getPump8().getCurrentState().toString(), toStringList(components.getPump8().getAvailableStatesList()), 7); }

        // circulation pump
        if (components.hasCirculationPump()) { updateComponentState(spaId, spaStateEntity, Bwg.Uplink.Model.Constants.ComponentType.CIRCULATION_PUMP.toString(), components.getCirculationPump().getCurrentState().toString(), toStringList(components.getCirculationPump().getAvailableStatesList())); }

        // blower
        if (components.hasBlower1()) { updateComponentState(spaId, spaStateEntity, com.bwg.iot.model.Component.ComponentType.BLOWER.toString(), components.getBlower1().getCurrentState().toString(), toStringList(components.getBlower1().getAvailableStatesList()), 0); }
        if (components.hasBlower2()) { updateComponentState(spaId, spaStateEntity, com.bwg.iot.model.Component.ComponentType.BLOWER.toString(), components.getBlower2().getCurrentState().toString(), toStringList(components.getBlower2().getAvailableStatesList()), 1); }

        // light
        if (components.hasLight1()) { updateComponentState(spaId, spaStateEntity, com.bwg.iot.model.Component.ComponentType.LIGHT.toString(), components.getLight1().getCurrentState().toString(), toStringList(components.getLight1().getAvailableStatesList()), 0); }
        if (components.hasLight2()) { updateComponentState(spaId, spaStateEntity, com.bwg.iot.model.Component.ComponentType.LIGHT.toString(), components.getLight2().getCurrentState().toString(), toStringList(components.getLight2().getAvailableStatesList()), 1); }
        if (components.hasLight3()) { updateComponentState(spaId, spaStateEntity, com.bwg.iot.model.Component.ComponentType.LIGHT.toString(), components.getLight3().getCurrentState().toString(), toStringList(components.getLight3().getAvailableStatesList()), 2); }
        if (components.hasLight4()) { updateComponentState(spaId, spaStateEntity, com.bwg.iot.model.Component.ComponentType.LIGHT.toString(), components.getLight4().getCurrentState().toString(), toStringList(components.getLight4().getAvailableStatesList()), 3); }
    }

    private void updateComponentState(final String spaId, final SpaState spaStateEntity, final String componentType, final String state, final List<String> availableStates, final Integer port) {
        // build componentState
        final ComponentState componentState = new ComponentState();
        // setting default name
        componentState.setName(componentType);
        componentState.setComponentType(componentType);
        componentState.setPort(port != null ? port.toString() : null);
        componentState.setValue(state);
        componentState.setAvailableValues(availableStates);
        componentState.setRegisteredTimestamp(new Date());

        com.bwg.iot.model.Component component = null;
        if (port != null) {
            component = componentRepository.findOneBySpaIdAndComponentTypeAndPort(spaId, componentType, port.toString());
        } else {
            Page<com.bwg.iot.model.Component> componentResults = componentRepository.findBySpaIdAndComponentType(spaId, componentType, new PageRequest(0,1));
            if (componentResults.getTotalElements() > 0) {
                component = componentResults.iterator().next();
            }
        }
        if (component != null) {
            // FIXME just to fix db, can be removed with one of next iterations
            if (StringUtils.isBlank(component.getName())) {
                component.setName(component.getComponentType());
                componentRepository.save(component);
            }
            componentState.setName(component.getName());
            componentState.setSerialNumber(component.getSerialNumber());
        }

        // replace old with new
        replaceComponentState(spaStateEntity, componentState);
    }

    private void updateComponentState(final String spaId, final SpaState spaStateEntity, final String componentType, final String state, final List<String> availableStates) {
        updateComponentState(spaId, spaStateEntity, componentType, state, availableStates, null);
    }

    private void replaceComponentState(final SpaState spaStateEntity, final ComponentState componentState) {
        List<ComponentState> componentStates = spaStateEntity.getComponents();
        if (componentStates == null) {
            componentStates = new ArrayList<>();
            spaStateEntity.setComponents(componentStates);
        }
        boolean found = false;
        int index = 0;
        for (final ComponentState state: componentStates) {
            if (state.requiresPort()) {
                if (ObjectUtils.compare(componentState.getComponentType(), state.getComponentType()) == 0 && ObjectUtils.compare(componentState.getPort(), state.getPort()) == 0) {
                    found = true;
                    break;
                }
            } else if (ObjectUtils.compare(componentState.getComponentType(), state.getComponentType()) == 0) {
                found = true;
                break;
            }
            index++;
        }
        if (found) {
            ComponentState targetState = componentStates.get(index);
            targetState.setSerialNumber(componentState.getSerialNumber());
            targetState.setName(componentState.getName());
            targetState.setValue(componentState.getValue());
            targetState.setAvailableValues(componentState.getAvailableValues());
            targetState.setRegisteredTimestamp(componentState.getRegisteredTimestamp());

        } else {
            componentStates.add(componentState);
        }
    }

    private List<String> toStringList(final List<?> availableStatesList) {
        if (availableStatesList == null) {
            return null;
        }
        final List<String> stringList = new ArrayList<>(availableStatesList.size());
        for (final Object availableState: availableStatesList) {
            stringList.add(availableState.toString());
        }
        return stringList;
    }
}
