package com.tritonsvc.messageprocessor.mqtt;

import com.bwg.iot.model.Component.ComponentType;
import com.bwg.iot.model.ComponentState;
import com.bwg.iot.model.Spa;
import com.bwg.iot.model.SpaCommand;
import com.bwg.iot.model.SpaState;
import com.bwg.iot.model.util.SpaRequestUtil;
import com.google.common.base.Objects;
import com.tritonsvc.messageprocessor.MessageProcessorConfiguration;
import com.tritonsvc.messageprocessor.mongo.repository.ComponentRepository;
import com.tritonsvc.messageprocessor.mongo.repository.SpaRepository;
import com.tritonsvc.messageprocessor.util.NumberHelper;
import com.tritonsvc.spa.communication.proto.Bwg;
import com.tritonsvc.spa.communication.proto.BwgHelper;
import org.apache.commons.lang3.math.NumberUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.io.IOException;
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
    private ComponentRepository componentRepository;

    @Autowired
    private MqttSendService mqttSendService;

    @Autowired
    private MessageProcessorConfiguration messageProcessorConfiguration;

    public boolean sendHeaterUpdateCommand(final SpaCommand command) throws IOException {
        boolean sent = false;
        final Spa spa = spaRepository.findOne(command.getSpaId());
        if (spa == null) {
            log.error("Could not find related spa with id {}", command.getSpaId());
        } else {
            log.info("Building heater update downlink message");

            final String desiredTemp = command.getValues().get(Bwg.Downlink.Model.SpaCommandAttribName.DESIREDTEMP.name());
            if (!NumberHelper.isInt(desiredTemp)) {
                log.error("Desired temp passed with command is invalid {}", desiredTemp);
            } else {
                final Bwg.Downlink.Model.Request request = BwgHelper.buildRequest(Bwg.Downlink.Model.RequestType.HEATER, command.getValues());
                final byte[] messageData = BwgHelper.buildDownlinkMessage(command.getOriginatorId(), command.getSpaId(), Bwg.Downlink.DownlinkCommandType.REQUEST, request);
                if (messageData != null && messageData.length > 0) {
                    String serialNumber = getGatewaySerialNumber(spa.get_id());
                    if (serialNumber == null) {
                        log.error("Error, not gateway serial number is available for spa with db id {}", spa.get_id());
                        return false;
                    }
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

    public boolean sendPeripheralStateUpdateCommand(final SpaCommand command) throws IOException {
        boolean sent = false;

        final Spa spa = spaRepository.findOne(command.getSpaId());
        final Bwg.Downlink.Model.RequestType requestType = BwgHelper.getRequestTypeByCode(command.getRequestTypeId());

        if (spa == null) {
            log.error("Could not find related spa with id {}", command.getSpaId());
        } else if (requestType == null) {
            log.error("Unrecognized request type {}", command.getRequestTypeId());
        } else {
            log.info("Building device downlink message");

            final String port = command.getValues().get(Bwg.Downlink.Model.SpaCommandAttribName.PORT.name());
            final String desiredState = command.getValues().get(Bwg.Downlink.Model.SpaCommandAttribName.DESIREDSTATE.name());
            if (port == null && SpaRequestUtil.portRequired(command.getRequestTypeId())) {
                log.error("Port is required");
            } else if (port != null && SpaRequestUtil.portRequired(command.getRequestTypeId()) && !NumberUtils.isNumber(port)) {
                log.error("Port passed with command is invalid {}", port);
            } else if (port != null && SpaRequestUtil.portRequired(command.getRequestTypeId()) && !SpaRequestUtil.validPort(command.getRequestTypeId(), NumberUtils.toInt(port))) {
                log.error("Port passed with command is invalid {}, out of range", port);
            } else if (!SpaRequestUtil.validState(command.getRequestTypeId(), desiredState)) {
                log.error("Desired state passed with command is invalid {} for request {}", desiredState, requestType);
            } else {
                final Bwg.Downlink.Model.Request request = BwgHelper.buildRequest(requestType, command.getValues());
                final byte[] messageData = BwgHelper.buildDownlinkMessage(command.getOriginatorId(), command.getSpaId(), Bwg.Downlink.DownlinkCommandType.REQUEST, request);
                if (messageData != null && messageData.length > 0) {
                    String serialNumber = getGatewaySerialNumber(spa.get_id());
                    if (serialNumber == null) {
                        log.error("Error, not gateway serial number is available for spa with db id {}", spa.get_id());
                        return false;
                    }
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
            case 1:
                return com.bwg.iot.model.Component.ComponentType.PUMP;
            case 2:
                return com.bwg.iot.model.Component.ComponentType.LIGHT;
            case 3:
                return com.bwg.iot.model.Component.ComponentType.BLOWER;
            case 4:
                return com.bwg.iot.model.Component.ComponentType.MISTER;
            case 5:
                return com.bwg.iot.model.Component.ComponentType.FILTER;
            case 9:
                return com.bwg.iot.model.Component.ComponentType.OZONE;
            case 10:
                return com.bwg.iot.model.Component.ComponentType.MICROSILK;
            case 11:
                return com.bwg.iot.model.Component.ComponentType.AUX;
            default:
                return null;
        }
    }

    private String getGatewaySerialNumber(String spaId) {
        Page<com.bwg.iot.model.Component> results = componentRepository.findBySpaIdAndComponentType(spaId, ComponentType.GATEWAY.name(), new PageRequest(0, 1));
        if (results.getTotalElements() < 1) {
            return null;
        }
        return results.getContent().get(0).getSerialNumber();
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
