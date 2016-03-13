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
        // pumps
        if (components.hasPump1()) { updateComponent(spaStateEntity, com.bwg.iot.model.Component.ComponentType.PUMP, components.getPump1(), 0);} else { removeComponent(com.bwg.iot.model.Component.ComponentType.PUMP, 0); }

    }

    private void updateComponent(final SpaState spaStateEntity, final com.bwg.iot.model.Component.ComponentType componentType, final Bwg.Uplink.Model.Components.PumpComponent pump, final int port) {
        // build componentState
        final ComponentState componentState = new ComponentState();
        componentState.setComponentType(componentType.toString());
        componentState.setPort(String.valueOf(port));
        componentState.setValue(pump.getCurrentState().toString());

        // replace old with new
        replaceComponentState(spaStateEntity, componentState);

        // find, should already be there
        com.bwg.iot.model.Component component = new com.bwg.iot.model.Component();
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

    private void removeComponent(com.bwg.iot.model.Component.ComponentType componentType, int i) {
    }
}
