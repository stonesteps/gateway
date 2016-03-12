package com.tritonsvc.messageprocessor.messagehandler;

import com.bwg.iot.model.Spa;
import com.tritonsvc.messageprocessor.MessageProcessorConfiguration;
import com.tritonsvc.messageprocessor.mongo.repository.ComponentRepository;
import com.tritonsvc.messageprocessor.mongo.repository.SpaRepository;
import com.tritonsvc.messageprocessor.mqtt.MqttSendService;
import com.tritonsvc.messageprocessor.util.SpaDataHelper;
import com.tritonsvc.messageprocessor.util.StringUtil;
import com.tritonsvc.spa.communication.proto.Bwg;
import com.tritonsvc.spa.communication.proto.Bwg.Downlink.DownlinkCommandType;
import com.tritonsvc.spa.communication.proto.Bwg.Downlink.Model.RegistrationResponse;
import com.tritonsvc.spa.communication.proto.Bwg.Downlink.Model.SpaRegistrationResponse;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.Model.RegisterDevice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

/**
 * Created by holow on 2/22/2016.
 */
@Component
public class RegisterDeviceMessageHandler extends AbstractMessageHandler<RegisterDevice> {

    private static final Logger log = LoggerFactory.getLogger(RegisterDeviceMessageHandler.class);

    private static final String DEVICE_TYPE_GATEWAY = "gateway";
    private static final String DEVICE_TYPE_CONTROLLER = "controller";
    private static final String DEVICE_TYPE_MOTE = "mote";

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
        log.info("Processing register device message");

        final String deviceTypeName = registerDeviceMessage.getDeviceTypeName();
        final String parentDeviceHardwareId = registerDeviceMessage.getParentDeviceHardwareId();

        log.info("Registering device type name: {}", deviceTypeName);
        log.info("And with parent device hardware id: {}", parentDeviceHardwareId);

        if (DEVICE_TYPE_GATEWAY.equals(deviceTypeName)) {
            handleGatewayRegistration(header, uplinkHeader, registerDeviceMessage);
        } else if (DEVICE_TYPE_CONTROLLER.equals(deviceTypeName)) {
            handleControllerRegistration(header, uplinkHeader, registerDeviceMessage);
        } else if (DEVICE_TYPE_MOTE.equals(deviceTypeName)) {
            handleMoteRegistration(uplinkHeader, registerDeviceMessage);
        }
    }

    private void handleGatewayRegistration(final Bwg.Header header, final Bwg.Uplink.UplinkHeader uplinkHeader, final RegisterDevice registerDeviceMessage) {
        // create spa
        final String serialNumber = registerDeviceMessage.getSpaSerialNumber();
        final String downlinkTopic = messageProcessorConfiguration.getDownlinkTopicName(serialNumber);

        if (StringUtils.isEmpty(serialNumber)) {
            try {
                final SpaRegistrationResponse registrationResponse = SpaDataHelper.buildSpaRegistrationResponse(Bwg.Downlink.Model.RegistrationAckState.REGISTRATION_ERROR, null);
                mqttSendService.sendMessage(downlinkTopic, SpaDataHelper.buildDownlinkMessage(header.getOriginator(), null, DownlinkCommandType.SPA_REGISTRATION_RESPONSE, registrationResponse));
            } catch (Exception e) {
                log.error("Error while sending downlink gateway registration message", e);
            }
        }

        boolean newSpa = false;
        Spa spa = spaRepository.findBySerialNumber(serialNumber);

        if (spa == null) {
            log.info("Creating new spa object");
            spa = new Spa();
            newSpa = true;

            spa.setP2pAPPassword(generateRandomString());
            spa.setP2pAPSSID(generateRandomString());
            spa.setSerialNumber(serialNumber);
        }

        if (spa.getRegistrationDate() == null) {
            spa.setRegistrationDate(new SimpleDateFormat(DATE_FORMAT).format(new Date()));
        }
        spaRepository.save(spa);

        try {
            final SpaRegistrationResponse registrationResponse = SpaDataHelper.buildSpaRegistrationResponse(newSpa ? Bwg.Downlink.Model.RegistrationAckState.NEW_REGISTRATION : Bwg.Downlink.Model.RegistrationAckState.ALREADY_REGISTERED, spa);
            mqttSendService.sendMessage(downlinkTopic, SpaDataHelper.buildDownlinkMessage(header.getOriginator(), spa.get_id(), DownlinkCommandType.SPA_REGISTRATION_RESPONSE, registrationResponse));
            log.info("sent spa registration response {} {}",spa.get_id(), serialNumber);
        } catch (Exception e) {
            log.error("Error while sending downlink message", e);
        }
    }

    private String generateRandomString() {
        return StringUtil.randomString(32);
    }

    private void handleControllerRegistration(final Bwg.Header header, final Bwg.Uplink.UplinkHeader uplinkHeader, final RegisterDevice registerDeviceMessage) {
        Spa spa = spaRepository.findBySerialNumber(registerDeviceMessage.getSpaSerialNumber());
        final String downlinkTopic = messageProcessorConfiguration.getDownlinkTopicName(registerDeviceMessage.getSpaSerialNumber());

        if (spa == null) {
            try {
                final SpaRegistrationResponse registrationResponse = SpaDataHelper.buildSpaRegistrationResponse(Bwg.Downlink.Model.RegistrationAckState.REGISTRATION_ERROR, null);
                mqttSendService.sendMessage(downlinkTopic, SpaDataHelper.buildDownlinkMessage(header.getOriginator(), null, DownlinkCommandType.SPA_REGISTRATION_RESPONSE, registrationResponse));
            } catch (Exception e) {
                log.error("Error while sending downlink gateway registration message", e);
            }
            log.error("received controller reg for serial number {}, which does not exist.", registerDeviceMessage.getSpaSerialNumber());
            return;
        }

        boolean newController = false;
        Page<com.bwg.iot.model.Component> page = componentRepository.findBySpaIdAndComponentType(spa.get_id(), registerDeviceMessage.getDeviceTypeName(), new PageRequest(0,1));
        com.bwg.iot.model.Component controller;

        if (page.getTotalElements() < 1) {
            log.info("Creating new controller object");
            controller = new com.bwg.iot.model.Component();
            controller.setComponentType(registerDeviceMessage.getDeviceTypeName());
            controller.setDealerId(spa.getDealerId());
            controller.setOemId(spa.getOemId());
            controller.setSpaId(spa.get_id());
            newController = true;
        } else {
            controller = page.getContent().get(0);
        }

        if (controller.getRegistrationDate() == null) {
            controller.setRegistrationDate(new SimpleDateFormat(DATE_FORMAT).format(new Date()));
        }
        componentRepository.save(controller);

        try {
            final RegistrationResponse registrationResponse = SpaDataHelper.buildComponentRegistrationResponse(newController ? Bwg.Downlink.Model.RegistrationAckState.NEW_REGISTRATION : Bwg.Downlink.Model.RegistrationAckState.ALREADY_REGISTERED);
            mqttSendService.sendMessage(downlinkTopic, SpaDataHelper.buildDownlinkMessage(header.getOriginator(), controller.get_id(), DownlinkCommandType.REGISTRATION_RESPONSE, registrationResponse));
            log.info("sent controller registration response spaid {} controllerid {}", spa.get_id(), controller.get_id() );
        } catch (Exception e) {
            log.error("Error while sending controller reg downlink message", e);
        }
    }

    private void handleMoteRegistration(final Bwg.Uplink.UplinkHeader uplinkHeader, final RegisterDevice registerDeviceMessage) {
        // FIXME todo
    }
}
