package com.tritonsvc.messageprocessor.messagehandler;

import com.bwg.iot.model.ComponentState;
import com.bwg.iot.model.Spa;
import com.bwg.iot.model.SpaState;
import com.tritonsvc.messageprocessor.mongo.repository.ComponentRepository;
import com.tritonsvc.messageprocessor.mongo.repository.SpaRepository;
import com.tritonsvc.spa.communication.proto.Bwg;
import org.apache.commons.lang3.ObjectUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

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
        log.info("Processing spa state message for originator {}, and spa {}", header.getOriginator(), uplinkHeader.getHardwareId());

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

        spaStateEntity.setDesiredTemp(Integer.toString(spaState.getController().getTargetWaterTemperature()));
        spaStateEntity.setCurrentTemp(Integer.toString(spaState.getController().getCurrentWaterTemp()));
        spaStateEntity.setFilterCycle1Active(spaState.getController().getFilter1());
        spaStateEntity.setFilterCycle2Active(spaState.getController().getFilter2());
        spaStateEntity.setCleanupCycle(spaState.getController().getCleanupCycle());
        spaStateEntity.setErrorCode(spaState.getController().getErrorCode());
        spaStateEntity.setMessageSeverity(spaState.getController().getMessageSeverity());
        spaStateEntity.setUplinkTimestamp(new SimpleDateFormat(DATE_FORMAT).format(new Date(spaState.getLastUpdateTimestamp())));

        if (spaState.hasComponents()) {
            updateComponents(spa.get_id(), spa.getCurrentState(), spaState.getComponents());
        }
        spaRepository.save(spa);
    }

    private void updateComponents(final String spaId, final SpaState spaStateEntity, final Bwg.Uplink.Model.Components components) {
        // heater
        if (components.hasHeater1()) { updateComponentState(spaId, spaStateEntity, Bwg.Uplink.Model.Constants.ComponentType.HEATER.toString(), components.getHeater1().toString(), null, 0); } else { nullRegisteredTimestampOfComponentState(spaStateEntity, Bwg.Uplink.Model.Constants.ComponentType.HEATER.toString(), 0); }
        if (components.hasHeater2()) { updateComponentState(spaId, spaStateEntity, Bwg.Uplink.Model.Constants.ComponentType.HEATER.toString(), components.getHeater2().toString(), null, 1); } else { nullRegisteredTimestampOfComponentState(spaStateEntity, Bwg.Uplink.Model.Constants.ComponentType.HEATER.toString(), 1); }

        // ozone
        if (components.hasOzone()) { updateComponentState(spaId, spaStateEntity, Bwg.Uplink.Model.Constants.ComponentType.OZONE.toString(), components.getOzone().getCurrentState().toString(), toStringList(components.getOzone().getAvailableStatesList())); } else { nullRegisteredTimestampOfComponentState(spaStateEntity, Bwg.Uplink.Model.Constants.ComponentType.OZONE.toString()); }

        // microsilk
        if (components.hasMicroSilk()) { updateComponentState(spaId, spaStateEntity, Bwg.Uplink.Model.Constants.ComponentType.MICROSILK.toString(), components.getMicroSilk().getCurrentState().toString(), toStringList(components.getMicroSilk().getAvailableStatesList())); } else { nullRegisteredTimestampOfComponentState(spaStateEntity, Bwg.Uplink.Model.Constants.ComponentType.MICROSILK.toString()); }

        // aux
        if (components.hasAux1()) { updateComponentState(spaId, spaStateEntity, com.bwg.iot.model.Component.ComponentType.AUX.toString(), components.getAux1().getCurrentState().toString(), toStringList(components.getAux1().getAvailableStatesList()), 0); } else { nullRegisteredTimestampOfComponentState(spaStateEntity, Bwg.Uplink.Model.Constants.ComponentType.AUX.toString(), 0); }
        if (components.hasAux2()) { updateComponentState(spaId, spaStateEntity, com.bwg.iot.model.Component.ComponentType.AUX.toString(), components.getAux2().getCurrentState().toString(), toStringList(components.getAux2().getAvailableStatesList()), 1); } else { nullRegisteredTimestampOfComponentState(spaStateEntity, Bwg.Uplink.Model.Constants.ComponentType.AUX.toString(), 1); }
        if (components.hasAux3()) { updateComponentState(spaId, spaStateEntity, com.bwg.iot.model.Component.ComponentType.AUX.toString(), components.getAux3().getCurrentState().toString(), toStringList(components.getAux3().getAvailableStatesList()), 2); } else { nullRegisteredTimestampOfComponentState(spaStateEntity, Bwg.Uplink.Model.Constants.ComponentType.AUX.toString(), 2); }
        if (components.hasAux4()) { updateComponentState(spaId, spaStateEntity, com.bwg.iot.model.Component.ComponentType.AUX.toString(), components.getAux4().getCurrentState().toString(), toStringList(components.getAux4().getAvailableStatesList()), 3); } else { nullRegisteredTimestampOfComponentState(spaStateEntity, Bwg.Uplink.Model.Constants.ComponentType.AUX.toString(), 3); }

        // mister
        if (components.hasMister1()) { updateComponentState(spaId, spaStateEntity, com.bwg.iot.model.Component.ComponentType.MISTER.toString(), components.getMister1().getCurrentState().toString(), toStringList(components.getMister1().getAvailableStatesList()), 0); } else { nullRegisteredTimestampOfComponentState(spaStateEntity, Bwg.Uplink.Model.Constants.ComponentType.MISTER.toString(), 0); }
        if (components.hasMister2()) { updateComponentState(spaId, spaStateEntity, com.bwg.iot.model.Component.ComponentType.MISTER.toString(), components.getMister2().getCurrentState().toString(), toStringList(components.getMister2().getAvailableStatesList()), 1); } else { nullRegisteredTimestampOfComponentState(spaStateEntity, Bwg.Uplink.Model.Constants.ComponentType.MISTER.toString(), 1); }
        if (components.hasMister3()) { updateComponentState(spaId, spaStateEntity, com.bwg.iot.model.Component.ComponentType.MISTER.toString(), components.getMister3().getCurrentState().toString(), toStringList(components.getMister3().getAvailableStatesList()), 2); } else { nullRegisteredTimestampOfComponentState(spaStateEntity, Bwg.Uplink.Model.Constants.ComponentType.MISTER.toString(), 2); }

        // pumps
        if (components.hasPump1()) { updateComponentState(spaId, spaStateEntity, com.bwg.iot.model.Component.ComponentType.PUMP.toString(), components.getPump1().getCurrentState().toString(), toStringList(components.getPump1().getAvailableStatesList()), 0); } else { nullRegisteredTimestampOfComponentState(spaStateEntity, Bwg.Uplink.Model.Constants.ComponentType.PUMP.toString(), 0); }
        if (components.hasPump2()) { updateComponentState(spaId, spaStateEntity, com.bwg.iot.model.Component.ComponentType.PUMP.toString(), components.getPump2().getCurrentState().toString(), toStringList(components.getPump2().getAvailableStatesList()), 1); } else { nullRegisteredTimestampOfComponentState(spaStateEntity, Bwg.Uplink.Model.Constants.ComponentType.PUMP.toString(), 1); }
        if (components.hasPump3()) { updateComponentState(spaId, spaStateEntity, com.bwg.iot.model.Component.ComponentType.PUMP.toString(), components.getPump3().getCurrentState().toString(), toStringList(components.getPump3().getAvailableStatesList()), 2); } else { nullRegisteredTimestampOfComponentState(spaStateEntity, Bwg.Uplink.Model.Constants.ComponentType.PUMP.toString(), 2); }
        if (components.hasPump4()) { updateComponentState(spaId, spaStateEntity, com.bwg.iot.model.Component.ComponentType.PUMP.toString(), components.getPump4().getCurrentState().toString(), toStringList(components.getPump4().getAvailableStatesList()), 3); } else { nullRegisteredTimestampOfComponentState(spaStateEntity, Bwg.Uplink.Model.Constants.ComponentType.PUMP.toString(), 3); }
        if (components.hasPump5()) { updateComponentState(spaId, spaStateEntity, com.bwg.iot.model.Component.ComponentType.PUMP.toString(), components.getPump5().getCurrentState().toString(), toStringList(components.getPump5().getAvailableStatesList()), 4); } else { nullRegisteredTimestampOfComponentState(spaStateEntity, Bwg.Uplink.Model.Constants.ComponentType.PUMP.toString(), 4); }
        if (components.hasPump6()) { updateComponentState(spaId, spaStateEntity, com.bwg.iot.model.Component.ComponentType.PUMP.toString(), components.getPump6().getCurrentState().toString(), toStringList(components.getPump6().getAvailableStatesList()), 5); } else { nullRegisteredTimestampOfComponentState(spaStateEntity, Bwg.Uplink.Model.Constants.ComponentType.PUMP.toString(), 5); }
        if (components.hasPump7()) { updateComponentState(spaId, spaStateEntity, com.bwg.iot.model.Component.ComponentType.PUMP.toString(), components.getPump7().getCurrentState().toString(), toStringList(components.getPump7().getAvailableStatesList()), 6); } else { nullRegisteredTimestampOfComponentState(spaStateEntity, Bwg.Uplink.Model.Constants.ComponentType.PUMP.toString(), 6); }
        if (components.hasPump8()) { updateComponentState(spaId, spaStateEntity, com.bwg.iot.model.Component.ComponentType.PUMP.toString(), components.getPump8().getCurrentState().toString(), toStringList(components.getPump8().getAvailableStatesList()), 7); } else { nullRegisteredTimestampOfComponentState(spaStateEntity, Bwg.Uplink.Model.Constants.ComponentType.PUMP.toString(), 7); }

        // circulation pump
        if (components.hasCirculationPump()) { updateComponentState(spaId, spaStateEntity, Bwg.Uplink.Model.Constants.ComponentType.CIRCULATION_PUMP.toString(), components.getCirculationPump().getCurrentState().toString(), toStringList(components.getCirculationPump().getAvailableStatesList())); } else { nullRegisteredTimestampOfComponentState(spaStateEntity, Bwg.Uplink.Model.Constants.ComponentType.CIRCULATION_PUMP.toString()); }

        // blower
        if (components.hasBlower1()) { updateComponentState(spaId, spaStateEntity, com.bwg.iot.model.Component.ComponentType.BLOWER.toString(), components.getBlower1().getCurrentState().toString(), toStringList(components.getBlower1().getAvailableStatesList()), 0); } else { nullRegisteredTimestampOfComponentState(spaStateEntity, Bwg.Uplink.Model.Constants.ComponentType.BLOWER.toString(), 0); }
        if (components.hasBlower2()) { updateComponentState(spaId, spaStateEntity, com.bwg.iot.model.Component.ComponentType.BLOWER.toString(), components.getBlower2().getCurrentState().toString(), toStringList(components.getBlower2().getAvailableStatesList()), 1); } else { nullRegisteredTimestampOfComponentState(spaStateEntity, Bwg.Uplink.Model.Constants.ComponentType.BLOWER.toString(), 1); }

        // light
        if (components.hasLight1()) { updateComponentState(spaId, spaStateEntity, com.bwg.iot.model.Component.ComponentType.LIGHT.toString(), components.getLight1().getCurrentState().toString(), toStringList(components.getLight1().getAvailableStatesList()), 0); } else { nullRegisteredTimestampOfComponentState(spaStateEntity, Bwg.Uplink.Model.Constants.ComponentType.LIGHT.toString(), 0); }
        if (components.hasLight2()) { updateComponentState(spaId, spaStateEntity, com.bwg.iot.model.Component.ComponentType.LIGHT.toString(), components.getLight2().getCurrentState().toString(), toStringList(components.getLight2().getAvailableStatesList()), 1); } else { nullRegisteredTimestampOfComponentState(spaStateEntity, Bwg.Uplink.Model.Constants.ComponentType.LIGHT.toString(), 1); }
        if (components.hasLight3()) { updateComponentState(spaId, spaStateEntity, com.bwg.iot.model.Component.ComponentType.LIGHT.toString(), components.getLight3().getCurrentState().toString(), toStringList(components.getLight3().getAvailableStatesList()), 2); } else { nullRegisteredTimestampOfComponentState(spaStateEntity, Bwg.Uplink.Model.Constants.ComponentType.LIGHT.toString(), 2); }
        if (components.hasLight4()) { updateComponentState(spaId, spaStateEntity, com.bwg.iot.model.Component.ComponentType.LIGHT.toString(), components.getLight4().getCurrentState().toString(), toStringList(components.getLight4().getAvailableStatesList()), 3); } else { nullRegisteredTimestampOfComponentState(spaStateEntity, Bwg.Uplink.Model.Constants.ComponentType.LIGHT.toString(), 3); }
    }

    private void updateComponentState(final String spaId, final SpaState spaStateEntity, final String componentType, final String state, final List<String> availableStates, final Integer port) {
        // build componentState
        final ComponentState componentState = new ComponentState();
        componentState.setComponentType(componentType);
        componentState.setPort(port != null ? port.toString() : null);
        componentState.setValue(state);
        componentState.setAvailableValues(availableStates);
        componentState.setRegisteredTimestamp(new SimpleDateFormat(DATE_FORMAT).format(new Date()));

        final com.bwg.iot.model.Component component;
        if (port != null) {
            component = componentRepository.findOneBySpaIdAndComponentTypeAndPort(spaId, componentType, port.toString());
        } else {
            component = componentRepository.findOneBySpaIdAndComponentTypeAndPortIsNull(spaId, componentType);
        }
        if (component != null) {
            componentState.setName(component.getName());
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
            if (ObjectUtils.compare(componentState.getComponentType(), state.getComponentType()) == 0 && ObjectUtils.compare(componentState.getPort(), state.getPort()) == 0) {
                found = true;
                break;
            }
            index++;
        }
        if (found) {
            componentStates.set(index, componentState);
        } else {
            componentStates.add(componentState);
        }
    }

    private void nullRegisteredTimestampOfComponentState(final SpaState spaStateEntity, final String componentType, final Integer port) {
        final List<ComponentState> componentStates = spaStateEntity.getComponents();
        final String portStr = port != null ? port.toString() : null;
        if (componentStates != null) {
            for (final ComponentState state: componentStates) {
                if (ObjectUtils.compare(componentType, state.getComponentType()) == 0 && ObjectUtils.compare(portStr, state.getPort()) == 0) {
                    state.setRegisteredTimestamp(null);
                    break;
                }
            }
        }
    }

    private void nullRegisteredTimestampOfComponentState(final SpaState spaStateEntity, final String componentType) {
        nullRegisteredTimestampOfComponentState(spaStateEntity, componentType, null);
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
