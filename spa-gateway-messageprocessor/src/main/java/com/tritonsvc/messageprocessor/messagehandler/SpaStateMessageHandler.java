package com.tritonsvc.messageprocessor.messagehandler;

import com.bwg.iot.model.ComponentState;
import com.bwg.iot.model.SpaState;
import com.tritonsvc.messageprocessor.mongo.repository.SpaStateRepository;
import com.tritonsvc.spa.communication.proto.Bwg;
import org.apache.commons.lang3.ObjectUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * process downlink acks from spa systems
 */
@Component
public class SpaStateMessageHandler extends AbstractMessageHandler<Bwg.Uplink.Model.SpaState> {

    private static final Logger log = LoggerFactory.getLogger(SpaStateMessageHandler.class);

    @Autowired
    private SpaStateRepository spaStateRepository;

    @Override
    public Class<Bwg.Uplink.Model.SpaState> handles() {
        return Bwg.Uplink.Model.SpaState.class;
    }

    @Override
    public void processMessage(final Bwg.Header header, final Bwg.Uplink.UplinkHeader uplinkHeader, final Bwg.Uplink.Model.SpaState spaState) {
        log.info("Processing spa state message for originator {}, and spa {}", header.getOriginator(), uplinkHeader.getHardwareId());

        SpaState spaStateEntity = spaStateRepository.findOne(uplinkHeader.getHardwareId());
        if (spaStateEntity == null) {
            spaStateEntity = new SpaState();
        }

        if (spaState.hasComponents()) {
            updateComponents(spaStateEntity, spaState.getComponents());
        }
    }

    private void updateComponents(final SpaState spaStateEntity, final Bwg.Uplink.Model.Components components) {
        // heater
        if (components.hasHeater1()) { updateComponentState(spaStateEntity, Bwg.Uplink.Model.Constants.ComponentType.HEATER.toString(), components.getHeater1().toString(), 0);} else { removeComponentState(spaStateEntity, Bwg.Uplink.Model.Constants.ComponentType.HEATER.toString(), 0); }
        if (components.hasHeater2()) { updateComponentState(spaStateEntity, Bwg.Uplink.Model.Constants.ComponentType.HEATER.toString(), components.getHeater2().toString(), 0);} else { removeComponentState(spaStateEntity, Bwg.Uplink.Model.Constants.ComponentType.HEATER.toString(), 1); }

        // ozone
        if (components.hasOzone()) { updateComponentState(spaStateEntity, Bwg.Uplink.Model.Constants.ComponentType.OZONE.toString(), components.getOzone().toString());} else { removeComponentState(spaStateEntity, Bwg.Uplink.Model.Constants.ComponentType.OZONE.toString()); }

        // microsilk
        if (components.hasMicroSilk()) { updateComponentState(spaStateEntity, Bwg.Uplink.Model.Constants.ComponentType.MICROSILK.toString(), components.getMicroSilk().toString());} else { removeComponentState(spaStateEntity, Bwg.Uplink.Model.Constants.ComponentType.MICROSILK.toString()); }

        // aux
        if (components.hasAux1()) { updateComponentState(spaStateEntity, com.bwg.iot.model.Component.ComponentType.AUX.toString(), components.getAux1().toString(), 0);} else { removeComponentState(spaStateEntity, Bwg.Uplink.Model.Constants.ComponentType.AUX.toString(), 0); }
        if (components.hasAux2()) { updateComponentState(spaStateEntity, com.bwg.iot.model.Component.ComponentType.AUX.toString(), components.getAux2().toString(), 1);} else { removeComponentState(spaStateEntity, Bwg.Uplink.Model.Constants.ComponentType.AUX.toString(), 1); }
        if (components.hasAux3()) { updateComponentState(spaStateEntity, com.bwg.iot.model.Component.ComponentType.AUX.toString(), components.getAux3().toString(), 2);} else { removeComponentState(spaStateEntity, Bwg.Uplink.Model.Constants.ComponentType.AUX.toString(), 2); }
        if (components.hasAux4()) { updateComponentState(spaStateEntity, com.bwg.iot.model.Component.ComponentType.AUX.toString(), components.getAux4().toString(), 3);} else { removeComponentState(spaStateEntity, Bwg.Uplink.Model.Constants.ComponentType.AUX.toString(), 3); }

        // mister
        if (components.hasMister1()) { updateComponentState(spaStateEntity, com.bwg.iot.model.Component.ComponentType.MISTER.toString(), components.getMister1().toString(), 0);} else { removeComponentState(spaStateEntity, Bwg.Uplink.Model.Constants.ComponentType.MISTER.toString(), 0); }
        if (components.hasMister2()) { updateComponentState(spaStateEntity, com.bwg.iot.model.Component.ComponentType.MISTER.toString(), components.getMister2().toString(), 1);} else { removeComponentState(spaStateEntity, Bwg.Uplink.Model.Constants.ComponentType.MISTER.toString(), 1); }
        if (components.hasMister3()) { updateComponentState(spaStateEntity, com.bwg.iot.model.Component.ComponentType.MISTER.toString(), components.getMister3().toString(), 2);} else { removeComponentState(spaStateEntity, Bwg.Uplink.Model.Constants.ComponentType.MISTER.toString(), 2); }

        // pumps
        if (components.hasPump1()) { updateComponentState(spaStateEntity, com.bwg.iot.model.Component.ComponentType.PUMP.toString(), components.getPump1().toString(), 0);} else { removeComponentState(spaStateEntity, Bwg.Uplink.Model.Constants.ComponentType.PUMP.toString(), 0); }
        if (components.hasPump2()) { updateComponentState(spaStateEntity, com.bwg.iot.model.Component.ComponentType.PUMP.toString(), components.getPump2().toString(), 1);} else { removeComponentState(spaStateEntity, Bwg.Uplink.Model.Constants.ComponentType.PUMP.toString(), 1); }
        if (components.hasPump3()) { updateComponentState(spaStateEntity, com.bwg.iot.model.Component.ComponentType.PUMP.toString(), components.getPump3().toString(), 2);} else { removeComponentState(spaStateEntity, Bwg.Uplink.Model.Constants.ComponentType.PUMP.toString(), 2); }
        if (components.hasPump4()) { updateComponentState(spaStateEntity, com.bwg.iot.model.Component.ComponentType.PUMP.toString(), components.getPump4().toString(), 3);} else { removeComponentState(spaStateEntity, Bwg.Uplink.Model.Constants.ComponentType.PUMP.toString(), 3); }
        if (components.hasPump5()) { updateComponentState(spaStateEntity, com.bwg.iot.model.Component.ComponentType.PUMP.toString(), components.getPump5().toString(), 4);} else { removeComponentState(spaStateEntity, Bwg.Uplink.Model.Constants.ComponentType.PUMP.toString(), 4); }
        if (components.hasPump6()) { updateComponentState(spaStateEntity, com.bwg.iot.model.Component.ComponentType.PUMP.toString(), components.getPump6().toString(), 5);} else { removeComponentState(spaStateEntity, Bwg.Uplink.Model.Constants.ComponentType.PUMP.toString(), 5); }
        if (components.hasPump7()) { updateComponentState(spaStateEntity, com.bwg.iot.model.Component.ComponentType.PUMP.toString(), components.getPump7().toString(), 6);} else { removeComponentState(spaStateEntity, Bwg.Uplink.Model.Constants.ComponentType.PUMP.toString(), 6); }
        if (components.hasPump8()) { updateComponentState(spaStateEntity, com.bwg.iot.model.Component.ComponentType.PUMP.toString(), components.getPump8().toString(), 7);} else { removeComponentState(spaStateEntity, Bwg.Uplink.Model.Constants.ComponentType.PUMP.toString(), 7); }

        // circulation pump
        if (components.hasCirculationPump()) { updateComponentState(spaStateEntity, Bwg.Uplink.Model.Constants.ComponentType.CIRCULATION_PUMP.toString(), components.getCirculationPump().toString());} else { removeComponentState(spaStateEntity, Bwg.Uplink.Model.Constants.ComponentType.CIRCULATION_PUMP.toString()); }

        // blower
        if (components.hasBlower1()) { updateComponentState(spaStateEntity, com.bwg.iot.model.Component.ComponentType.BLOWER.toString(), components.getBlower1().toString(), 0);} else { removeComponentState(spaStateEntity, Bwg.Uplink.Model.Constants.ComponentType.BLOWER.toString(), 0); }
        if (components.hasBlower2()) { updateComponentState(spaStateEntity, com.bwg.iot.model.Component.ComponentType.BLOWER.toString(), components.getBlower2().toString(), 1);} else { removeComponentState(spaStateEntity, Bwg.Uplink.Model.Constants.ComponentType.BLOWER.toString(), 1); }

        // light
        if (components.hasLight1()) { updateComponentState(spaStateEntity, com.bwg.iot.model.Component.ComponentType.LIGHT.toString(), components.getLight1().toString(), 0);} else { removeComponentState(spaStateEntity, Bwg.Uplink.Model.Constants.ComponentType.LIGHT.toString(), 0); }
        if (components.hasLight2()) { updateComponentState(spaStateEntity, com.bwg.iot.model.Component.ComponentType.LIGHT.toString(), components.getLight2().toString(), 1);} else { removeComponentState(spaStateEntity, Bwg.Uplink.Model.Constants.ComponentType.LIGHT.toString(), 1); }
        if (components.hasLight3()) { updateComponentState(spaStateEntity, com.bwg.iot.model.Component.ComponentType.LIGHT.toString(), components.getLight3().toString(), 2);} else { removeComponentState(spaStateEntity, Bwg.Uplink.Model.Constants.ComponentType.LIGHT.toString(), 2); }
        if (components.hasLight4()) { updateComponentState(spaStateEntity, com.bwg.iot.model.Component.ComponentType.LIGHT.toString(), components.getLight4().toString(), 3);} else { removeComponentState(spaStateEntity, Bwg.Uplink.Model.Constants.ComponentType.LIGHT.toString(), 3); }
    }

    private void updateComponentState(final SpaState spaStateEntity, final String componentType, final String state, final int port) {
        // build componentState
        final ComponentState componentState = new ComponentState();
        componentState.setComponentType(componentType);
        componentState.setPort(String.valueOf(port));
        componentState.setValue(state);

        // replace old with new
        replaceComponentState(spaStateEntity, componentState);
    }

    private void updateComponentState(final SpaState spaStateEntity, final String componentType, final String state) {
        // build componentState
        final ComponentState componentState = new ComponentState();
        componentState.setComponentType(componentType);
        componentState.setValue(state);

        // replace old with new
        replaceComponentState(spaStateEntity, componentState);
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

    private void removeComponentState(final SpaState spaStateEntity, final String componentType, final int port) {
        final List<ComponentState> componentStates = spaStateEntity.getComponents();
        boolean found = false;
        int index = 0;
        if (componentStates != null) {
            for (final ComponentState state: componentStates) {
                if (ObjectUtils.compare(componentType, state.getComponentType()) == 0 && ObjectUtils.compare(String.valueOf(port), state.getPort()) == 0) {
                    found = true;
                    break;
                }
                index++;
            }
        }
        if (found) {
            componentStates.remove(index);
        }
    }

    private void removeComponentState(final SpaState spaStateEntity, final String componentType) {
        final List<ComponentState> componentStates = spaStateEntity.getComponents();
        boolean found = false;
        int index = 0;
        if (componentStates != null) {
            for (final ComponentState state: componentStates) {
                if (ObjectUtils.compare(componentType, state.getComponentType()) == 0) {
                    found = true;
                    break;
                }
                index++;
            }
        }
        if (found) {
            componentStates.remove(index);
        }
    }
}
