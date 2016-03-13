package com.tritonsvc.messageprocessor.mqtt;

import com.bwg.iot.model.ComponentState;
import com.bwg.iot.model.Spa;
import com.bwg.iot.model.SpaCommand;
import com.bwg.iot.model.SpaState;
import com.google.common.base.Objects;
import com.tritonsvc.messageprocessor.MessageProcessorConfiguration;
import com.tritonsvc.messageprocessor.mongo.repository.SpaRepository;
import com.tritonsvc.messageprocessor.util.NumberHelper;
import com.tritonsvc.messageprocessor.util.SpaDataHelper;
import com.tritonsvc.spa.communication.proto.Bwg;
import org.apache.commons.lang3.math.NumberUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Collection;

/**
 * Created by holow on 3/8/2016.
 */
@Component
public final class DownlinkRequestor {

    private static final Logger log = LoggerFactory.getLogger(DownlinkRequestor.class);

    @Autowired
    private SpaRepository spaRepository;

    @Autowired
    private MqttSendService mqttSendService;

    @Autowired
    private MessageProcessorConfiguration messageProcessorConfiguration;

    public boolean sendHeaterUpdateCommand(final SpaCommand command) {
        boolean sent = false;
        final Spa spa = spaRepository.findOne(command.getSpaId());
        if (spa == null) {
            log.error("Could not find related spa with id {}", command.getSpaId());
        } else {
            log.info("Building heater update downlink message");

            final String desiredTemp = command.getValues().get(SpaCommand.ValueKeyName.DESIRED_TEMP.getKeyName());
            if (!NumberHelper.isDouble(desiredTemp)) {
                log.error("Desired temp passed with command is invalid {}", desiredTemp);
            } else {
                final Bwg.Downlink.Model.Request request = SpaDataHelper.buildRequest(Bwg.Downlink.Model.RequestType.HEATER, command.getValues());
                final byte[] messageData = SpaDataHelper.buildDownlinkMessage(command.getOriginatorId(), command.getSpaId(), Bwg.Downlink.DownlinkCommandType.REQUEST, request);
                if (messageData != null && messageData.length > 0) {
                    final String serialNumber = spa.getSerialNumber();
                    final String downlinkTopic = messageProcessorConfiguration.getDownlinkTopicName(serialNumber);
                    log.info("Sending downlink message to topic {}", downlinkTopic);
                    try {
                        //TODO - need a targetTemp attrib on spa model, to hold last requested spa target temp, should update here
                        mqttSendService.sendMessage(downlinkTopic, messageData);
                        if (spa.getCurrentState() == null) {
                            spa.setCurrentState(new SpaState());
                        }
                        spa.getCurrentState().setTargetDesiredTemp(desiredTemp);
                        spaRepository.save(spa);
                        sent = true;
                    } catch (Exception e) {
                        log.error("Error while sending downlink message", e);
                    }
                } else {
                    log.error("Message data is empty - not sending anything");
                }
            }
        }
        return sent;
    }

    public boolean sendPeripheralStateUpdateCommand(final SpaCommand command) {
        boolean sent = false;

        final Spa spa = spaRepository.findOne(command.getSpaId());
        final Bwg.Downlink.Model.RequestType requestType = SpaDataHelper.getRequestTypeByCode(command.getRequestTypeId());

        if (spa == null) {
            log.error("Could not find related spa with id {}", command.getSpaId());
        } else if (requestType == null) {
            log.error("Unrecognized request type {}", command.getRequestTypeId());
        } else {
            log.info("Building device downlink message");

            final String port = command.getValues().get(SpaCommand.ValueKeyName.PORT.getKeyName());
            final String desiredState = command.getValues().get(SpaCommand.ValueKeyName.DESIRED_STATE.getKeyName());
            if (port != null && !NumberUtils.isNumber(port)) {
                // all other port/state validation will be done down on the gateway via the agent and spa controller
                // the controller reports which ports are valid and which states are available on each port
                log.error("Port passed with command is invalid {}", port);
            } else {
                final Bwg.Downlink.Model.Request request = SpaDataHelper.buildRequest(requestType, command.getValues());
                final byte[] messageData = SpaDataHelper.buildDownlinkMessage(command.getOriginatorId(), command.getSpaId(), Bwg.Downlink.DownlinkCommandType.REQUEST, request);
                if (messageData != null && messageData.length > 0) {
                    final String serialNumber = spa.getSerialNumber();
                    final String downlinkTopic = messageProcessorConfiguration.getDownlinkTopicName(serialNumber);
                    log.info("Sending downlink message to topic {}", downlinkTopic);
                    try {
                        mqttSendService.sendMessage(downlinkTopic, messageData);
                        if (spa.getCurrentState() == null) {
                            spa.setCurrentState(new SpaState());
                        }
                        com.bwg.iot.model.Component.ComponentType componentType = getComponentForRequestId(command.getRequestTypeId());
                        if (componentType != null) {
                            setComponentTargetState(componentType, port, desiredState, spa.getCurrentState().getComponents());
                            spaRepository.save(spa);
                        }
                        sent = true;
                    } catch (Exception e) {
                        log.error("Error while sending downlink message", e);
                    }
                } else {
                    log.error("Message data is empty - not sending anything");
                }
            }
        }
        return sent;
    }

    private com.bwg.iot.model.Component.ComponentType getComponentForRequestId(int requestTypeId) {
        switch (requestTypeId) {
            case 1: return com.bwg.iot.model.Component.ComponentType.PUMP;
            case 2: return com.bwg.iot.model.Component.ComponentType.LIGHT;
            case 3: return com.bwg.iot.model.Component.ComponentType.BLOWER;
            case 4: return com.bwg.iot.model.Component.ComponentType.MISTER;
            case 5: return com.bwg.iot.model.Component.ComponentType.FILTER;
            case 9: return com.bwg.iot.model.Component.ComponentType.OZONE;
            case 10: return com.bwg.iot.model.Component.ComponentType.MICROSILK;
            case 11: return com.bwg.iot.model.Component.ComponentType.AUX;
            default: return null;
        }
    }

    private void setComponentTargetState(com.bwg.iot.model.Component.ComponentType type, String port, String targetValue, Collection<ComponentState> componentStates) {
        for (ComponentState comp : componentStates) {
            if (Objects.equal(comp.getComponentType(), type.name()) && Objects.equal(comp.getPort(), port)) {
                comp.setTargetValue(targetValue);
                return;
            }
        }
        ComponentState state = new ComponentState();
        state.setTargetValue(targetValue);
        state.setComponentType(type.name());
        state.setPort(port);
        componentStates.add(state);
    }
}
