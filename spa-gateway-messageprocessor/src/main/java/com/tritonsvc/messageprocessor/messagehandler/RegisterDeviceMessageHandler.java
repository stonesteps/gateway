package com.tritonsvc.messageprocessor.messagehandler;

import com.bwg.iot.model.Component.ComponentType;
import com.bwg.iot.model.Spa;
import com.tritonsvc.messageprocessor.MessageProcessorConfiguration;
import com.tritonsvc.messageprocessor.mongo.repository.ComponentRepository;
import com.tritonsvc.messageprocessor.mongo.repository.SpaRepository;
import com.tritonsvc.messageprocessor.mqtt.MqttSendService;
import com.tritonsvc.messageprocessor.util.StringUtil;
import com.tritonsvc.spa.communication.proto.Bwg;
import com.tritonsvc.spa.communication.proto.Bwg.Downlink.DownlinkCommandType;
import com.tritonsvc.spa.communication.proto.Bwg.Downlink.Model.RegistrationResponse;
import com.tritonsvc.spa.communication.proto.Bwg.Downlink.Model.SpaRegistrationResponse;
import com.tritonsvc.spa.communication.proto.Bwg.Metadata;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.Model.RegisterDevice;
import com.tritonsvc.spa.communication.proto.BwgHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import static java.util.stream.Collectors.toList;

/**
 * Created by holow on 2/22/2016.
 */
@Component
public class RegisterDeviceMessageHandler extends AbstractMessageHandler<RegisterDevice> {

    private static final Logger log = LoggerFactory.getLogger(RegisterDeviceMessageHandler.class);

    private static final String DEVICE_TYPE_GATEWAY = "gateway";
    private static final String DEVICE_TYPE_CONTROLLER = "controller";
    private static final String DEVICE_TYPE_MOTE = "mote";

    private static final String PREFIX_P2PAPSSID = "BWG_SPA_";
    private static final String DEFAULT_P2PAP_PASSWORD = "";

    @Autowired
    private MqttSendService mqttSendService;

    @Autowired
    private SpaRepository spaRepository;

    @Autowired
    private ComponentRepository componentRepository;

    @Autowired
    private MessageProcessorConfiguration messageProcessorConfiguration;

    @Override
    public Class<Bwg.Uplink.Model.RegisterDevice> handles() {
        return Bwg.Uplink.Model.RegisterDevice.class;
    }

    @Override
    public void processMessage(final Bwg.Header header, final Bwg.Uplink.UplinkHeader uplinkHeader, final Bwg.Uplink.Model.RegisterDevice registerDeviceMessage) {
        final String deviceTypeName = registerDeviceMessage.getDeviceTypeName();
        final String parentDeviceHardwareId = registerDeviceMessage.getParentDeviceHardwareId();

        log.info("Registering device type name: {}", deviceTypeName);
        log.info("And with parent device hardware id: {}", parentDeviceHardwareId);

        if (DEVICE_TYPE_GATEWAY.equals(deviceTypeName)) {
            handleGatewayRegistration(header, uplinkHeader, registerDeviceMessage);
        } else if (DEVICE_TYPE_CONTROLLER.equals(deviceTypeName)) {
            handleControllerRegistration(header, uplinkHeader, registerDeviceMessage);
        } else if (DEVICE_TYPE_MOTE.equals(deviceTypeName)) {
            handleMoteRegistration(header, uplinkHeader, registerDeviceMessage);
        }
    }

    private void handleGatewayRegistration(final Bwg.Header header, final Bwg.Uplink.UplinkHeader uplinkHeader, final RegisterDevice registerDeviceMessage) {
        // create spa
        final String serialNumber = registerDeviceMessage.getGatewaySerialNumber();
        final String downlinkTopic = messageProcessorConfiguration.getDownlinkTopicName(serialNumber);

        if (StringUtils.isEmpty(serialNumber)) {
            try {
                final SpaRegistrationResponse registrationResponse = BwgHelper.buildSpaRegistrationResponse(Bwg.Downlink.Model.RegistrationAckState.REGISTRATION_ERROR, null, null, null);
                mqttSendService.sendMessage(downlinkTopic, BwgHelper.buildDownlinkMessage(header.getOriginator(), "invalid", DownlinkCommandType.SPA_REGISTRATION_RESPONSE, registrationResponse));
            } catch (Exception e) {
                log.error("Error while sending downlink gateway registration message", e);
                return;
            }
        }

        Date regTimestamp = new Date();
        boolean dirtyGateway = false;
        Page<com.bwg.iot.model.Component> results = componentRepository.findByComponentTypeAndSerialNumber(ComponentType.GATEWAY.name(), serialNumber, new PageRequest(0, 1));
        com.bwg.iot.model.Component gatewayComponent;
        if (results.getTotalElements() < 1) {
            gatewayComponent = new com.bwg.iot.model.Component();
            gatewayComponent.setName(ComponentType.GATEWAY.name());
            gatewayComponent.setComponentType(ComponentType.GATEWAY.name());
            gatewayComponent.setSerialNumber(serialNumber);
            gatewayComponent.setRegistrationDate(regTimestamp);
            dirtyGateway = true;
        } else {
            gatewayComponent = results.iterator().next();
            if (gatewayComponent.getRegistrationDate() == null || gatewayComponent.getSerialNumber() == null || !gatewayComponent.getSerialNumber().equals(serialNumber)) {
                gatewayComponent.setRegistrationDate(regTimestamp);
                gatewayComponent.setName(ComponentType.GATEWAY.name());
                gatewayComponent.setSerialNumber(serialNumber);
                dirtyGateway = true;
            }
        }

        processMetaDataValue(gatewayComponent.getMetaValues(), "BWG-Agent-Version", registerDeviceMessage.getMetadataList());
        processMetaDataValue(gatewayComponent.getMetaValues(), "BWG-Agent-Build-Number", registerDeviceMessage.getMetadataList());
        processMetaDataValue(gatewayComponent.getMetaValues(), "BWG-Agent-SCM-Revision", registerDeviceMessage.getMetadataList());
        processMetaDataValue(gatewayComponent.getMetaValues(), "BWG-Agent-RS485-Controller-Type", registerDeviceMessage.getMetadataList());

        Spa spa = (gatewayComponent.getSpaId() != null ? spaRepository.findOne(gatewayComponent.getSpaId()) : null);

        if (spa == null) {
            log.info("Creating new spa object");
            spa = new Spa();
            spa.setSerialNumber(serialNumber);
        }

        if (spa.getRegistrationDate() == null || spa.getP2pAPPassword() == null || spa.getP2pAPSSID() == null) {
            spa.setRegistrationDate(regTimestamp);
            spa.setP2pAPSSID(generateP2pAPSSID(serialNumber));
            spa.setP2pAPPassword(DEFAULT_P2PAP_PASSWORD);
        }

        // generate new registration key with each registration call
        spa.setRegKey(generateRandomString(16));
        spaRepository.save(spa);
        gatewayComponent.setSpaId(spa.get_id());
        componentRepository.save(gatewayComponent);

        try {
            final SpaRegistrationResponse registrationResponse = BwgHelper.buildSpaRegistrationResponse(
                    dirtyGateway ? Bwg.Downlink.Model.RegistrationAckState.NEW_REGISTRATION : Bwg.Downlink.Model.RegistrationAckState.ALREADY_REGISTERED,
                    spa.getRegKey(), spa.getOwner() != null ? spa.getOwner().get_id() : null, getSwUpgradeUrl());
            mqttSendService.sendMessage(downlinkTopic, BwgHelper.buildDownlinkMessage(
                    header.getOriginator(), spa.get_id(), DownlinkCommandType.SPA_REGISTRATION_RESPONSE, registrationResponse));
            log.info("sent spa registration response {} {}", spa.get_id(), serialNumber);
        } catch (Exception e) {
            log.error("Error while sending downlink message", e);
        }
    }

    private String getSwUpgradeUrl() {
        return messageProcessorConfiguration.getSwUpgradeUrl();
    }

    private void processMetaDataValue(Map<String, String> componentMeta, String key, List<Metadata> metadata) {
        List<String> values = metadata
                .stream()
                .filter(metaEntry -> metaEntry.hasName() && metaEntry.getName().equals(key) && metaEntry.hasValue())
                .map(Metadata::getValue)
                .collect(toList());

        if (values.size() > 0) {
            componentMeta.put(key, values.get(0));
        } else {
            componentMeta.remove(key);
        }
    }

    private String generateP2pAPSSID(final String serialNumber) {
        return new StringBuilder(PREFIX_P2PAPSSID).append(serialNumber).toString();
    }

    private String generateRandomString(final int length) {
        return StringUtil.randomString(length);
    }

    private void handleControllerRegistration(final Bwg.Header header, final Bwg.Uplink.UplinkHeader uplinkHeader, final RegisterDevice registerDeviceMessage) {
        registerComponent(header, uplinkHeader, registerDeviceMessage, ComponentType.CONTROLLER);
    }

    private void handleMoteRegistration(final Bwg.Header header, final Bwg.Uplink.UplinkHeader uplinkHeader, final RegisterDevice registerDeviceMessage) {
        registerMoteComponent(header, registerDeviceMessage);
    }

    private void registerComponent(final Bwg.Header header, final Bwg.Uplink.UplinkHeader uplinkHeader, final RegisterDevice registerDeviceMessage, final ComponentType componentType) {
        final String downlinkTopic = messageProcessorConfiguration.getDownlinkTopicName(registerDeviceMessage.getGatewaySerialNumber());
        Spa spa = spaRepository.findOne(registerDeviceMessage.getParentDeviceHardwareId());

        if (spa == null) {
            try {
                final RegistrationResponse registrationResponse = BwgHelper.buildComponentRegistrationResponse(Bwg.Downlink.Model.RegistrationAckState.REGISTRATION_ERROR);
                mqttSendService.sendMessage(downlinkTopic, BwgHelper.buildDownlinkMessage(header.getOriginator(), "invalid", DownlinkCommandType.REGISTRATION_RESPONSE, registrationResponse));
            } catch (Exception e) {
                log.error("Error while sending downlink {} registration message", componentType);
                log.error("Exception stacktrace", e);
            }
            log.error("Received {} reg for spa id {}, which does not exist.", componentType, registerDeviceMessage.getParentDeviceHardwareId());
            return;
        }

        boolean newComponent = false;
        Page<com.bwg.iot.model.Component> page = componentRepository.findBySpaIdAndComponentType(spa.get_id(), componentType.name(), new PageRequest(0, 1));
        com.bwg.iot.model.Component component;

        if (page.getTotalElements() < 1) {
            log.info("Creating new {} object", componentType);
            component = new com.bwg.iot.model.Component();
            component.setName(componentType.name());
            component.setComponentType(componentType.name());
            component.setDealerId(spa.getDealerId());
            component.setOemId(spa.getOemId());
            component.setSpaId(spa.get_id());
            newComponent = true;
        } else {
            component = page.getContent().get(0);
        }

        if (component.getRegistrationDate() == null) {
            component.setRegistrationDate(new Date());
        }
        componentRepository.save(component);

        try {
            final RegistrationResponse registrationResponse = BwgHelper.buildComponentRegistrationResponse(newComponent ? Bwg.Downlink.Model.RegistrationAckState.NEW_REGISTRATION : Bwg.Downlink.Model.RegistrationAckState.ALREADY_REGISTERED);
            mqttSendService.sendMessage(downlinkTopic, BwgHelper.buildDownlinkMessage(header.getOriginator(), component.get_id(), DownlinkCommandType.REGISTRATION_RESPONSE, registrationResponse));
            log.info("sent {} registration response spaid {} controllerid {}", componentType, spa.get_id(), component.get_id());
        } catch (Exception e) {
            log.error("Error while sending {} reg downlink message", componentType);
            log.error("Exception stacktrace", e);
        }
    }

    private void registerMoteComponent(final Bwg.Header header, final RegisterDevice registerDeviceMessage) {
        final String downlinkTopic = messageProcessorConfiguration.getDownlinkTopicName(registerDeviceMessage.getGatewaySerialNumber());
        Spa spa = spaRepository.findOne(registerDeviceMessage.getParentDeviceHardwareId());

        if (spa == null) {
            try {
                final RegistrationResponse registrationResponse = BwgHelper.buildComponentRegistrationResponse(Bwg.Downlink.Model.RegistrationAckState.REGISTRATION_ERROR);
                mqttSendService.sendMessage(downlinkTopic, BwgHelper.buildDownlinkMessage(header.getOriginator(), "invalid", DownlinkCommandType.REGISTRATION_RESPONSE, registrationResponse));
            } catch (Exception e) {
                log.error("Error while sending downlink {} registration message", ComponentType.MOTE);
                log.error("Exception stacktrace", e);
            }
            log.error("Received {} reg for spa id {}, which does not exist.", ComponentType.MOTE, registerDeviceMessage.getParentDeviceHardwareId());
            return;
        }

        List<String> moteTypeValues = registerDeviceMessage.getMetadataList()
                .stream()
                .filter(metaEntry -> metaEntry.hasName() && metaEntry.getName().equals("mote_type") && metaEntry.hasValue())
                .map(Metadata::getValue)
                .collect(toList());

        List<String> moteMacValues = registerDeviceMessage.getMetadataList()
                .stream()
                .filter(metaEntry -> metaEntry.hasName() && metaEntry.getName().equals("mac") && metaEntry.hasValue())
                .map(Metadata::getValue)
                .collect(toList());

        if (moteTypeValues.isEmpty()) {
            log.error("Received {} mote reg for spa id {}, did not include required mote_type metadata param, cannot register the mote", registerDeviceMessage.getParentDeviceHardwareId());
            return;
        }
        if (moteMacValues.isEmpty()) {
            log.error("Received {} mote reg for spa id {}, did not include required mac metadata param, cannot register the mote", registerDeviceMessage.getParentDeviceHardwareId());
            return;
        }
        String registrationMoteType = moteTypeValues.get(0);
        String registrationMac = moteMacValues.get(0);

        boolean newComponent = false;
        com.bwg.iot.model.Component mote = componentRepository.findOneBySpaIdAndComponentTypeAndSerialNumber(spa.get_id(), ComponentType.MOTE.name(), registrationMac);

        Map<String, String> metaValues = registerDeviceMessage.getMetadataList().stream().collect(
                Collectors.toMap(Metadata::getName, Metadata::getValue));

        if (mote == null) {
            // this spa system never had a sensor planner invoked on it, i.e. there was no sensor component
            // found that matched up to the a sensor that the gateway is reporting, so add a compoent
            // for this sensor on the fly here
            log.info("Creating new mote object");
            mote = new com.bwg.iot.model.Component();
            mote.setName(ComponentType.MOTE.name() + " " + registrationMoteType);
            mote.setComponentType(ComponentType.MOTE.name());
            mote.setDealerId(spa.getDealerId());
            mote.setOemId(spa.getOemId());
            mote.setSpaId(spa.get_id());
            mote.setMetaValues(metaValues);
            mote.setSerialNumber(registrationMac);
            newComponent = true;
        }

        mote.setRegistrationDate(new Date());
        componentRepository.save(mote);

        try {
            final RegistrationResponse registrationResponse = BwgHelper.buildComponentRegistrationResponse(newComponent ? Bwg.Downlink.Model.RegistrationAckState.NEW_REGISTRATION : Bwg.Downlink.Model.RegistrationAckState.ALREADY_REGISTERED);
            mqttSendService.sendMessage(downlinkTopic, BwgHelper.buildDownlinkMessage(header.getOriginator(), mote.get_id(), DownlinkCommandType.REGISTRATION_RESPONSE, registrationResponse));
            log.info("sent MOTE registration response for spaid {} moteId {} mote mac/serial {}", spa.get_id(), mote.get_id(), registrationMac);
        } catch (Exception e) {
            log.error("Error while sending MOTE registration downlink message", e);
        }
    }
}
