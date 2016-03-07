package com.tritonsvc.messageprocessor.messagehandler;

import com.bwg.iot.model.Spa;
import com.tritonsvc.messageprocessor.mongo.repository.SpaRepository;
import com.tritonsvc.messageprocessor.mqtt.MqttSendService;
import com.tritonsvc.messageprocessor.util.SpaDataHelper;
import com.tritonsvc.messageprocessor.util.StringUtil;
import com.tritonsvc.spa.communication.proto.Bwg;
import com.tritonsvc.spa.communication.proto.Bwg.Downlink.DownlinkCommandType;
import com.tritonsvc.spa.communication.proto.Bwg.Downlink.Model.SpaRegistrationResponse;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.Model.RegisterDevice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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

    @Value("${downlinkTopicName:BWG/spa/downlink}")
    private String downlinkTopicName;

    @Autowired
    private MqttSendService mqttSendService;

    @Autowired
    private SpaRepository spaRepository;

    @Override
    public Class<Bwg.Uplink.Model.RegisterDevice> handles() {
        return Bwg.Uplink.Model.RegisterDevice.class;
    }

    @Override
    public void processMessage(final Bwg.Header header, final Bwg.Uplink.UplinkHeader uplinkHeader, final Bwg.Uplink.Model.RegisterDevice registerDeviceMessage) {
        log.info("processing register device message");

        final String deviceTypeName = registerDeviceMessage.getDeviceTypeName();
        final String parentDeviceHardwareId = registerDeviceMessage.getParentDeviceHardwareId();

        log.info("registering device type name: {}", deviceTypeName);
        log.info("with parent device hw id: {}", parentDeviceHardwareId);

        if (DEVICE_TYPE_GATEWAY.equals(deviceTypeName)) {
            handleGatewayRegistration(header, uplinkHeader, registerDeviceMessage);
        } else if (DEVICE_TYPE_CONTROLLER.equals(deviceTypeName)) {
            handleControllerRegistration(uplinkHeader, registerDeviceMessage);
        } else if (DEVICE_TYPE_MOTE.equals(deviceTypeName)) {
            handleMoteRegistration(uplinkHeader, registerDeviceMessage);
        }
    }

    private void handleGatewayRegistration(final Bwg.Header header, final Bwg.Uplink.UplinkHeader uplinkHeader, final RegisterDevice registerDeviceMessage) {
        // create spa
        final List<Bwg.Metadata> metadata = registerDeviceMessage.getMetadataList();

        final String serialNumber = SpaDataHelper.getMetadataValue("serialNumber", metadata);
        final String downlinkTopic = downlinkTopicName + (serialNumber != null ? "/"+serialNumber : "");

        if (StringUtils.isEmpty(serialNumber)) {
            try {
                final SpaRegistrationResponse registrationResponse = SpaDataHelper.buildSpaRegistrationResponse(Bwg.Downlink.Model.RegistrationAckState.REGISTRATION_ERROR, null);
                mqttSendService.sendMessage(downlinkTopic, SpaDataHelper.buildDownlinkMessage(header.getOriginator(), null, DownlinkCommandType.SPA_REGISTRATION_RESPONSE, registrationResponse));
            } catch (Exception e) {
                log.error("error while sending downlink message", e);
            }
        }

        boolean newSpa = false;
        Spa spa = spaRepository.findBySerialNumber(serialNumber);

        if (spa == null) {
            log.info("creating new spa object");
            spa = new Spa();
            newSpa = true;
        }

        spa.setP2pAPPassword(generateRandomString());
        spa.setP2pAPSSID(generateRandomString());
        spa.setSerialNumber(serialNumber);
        spa.setRegistrationDate(new SimpleDateFormat(DATE_FORMAT).format(new Date()));
        spaRepository.save(spa);

        try {
            final SpaRegistrationResponse registrationResponse = SpaDataHelper.buildSpaRegistrationResponse(newSpa ? Bwg.Downlink.Model.RegistrationAckState.NEW_REGISTRATION : Bwg.Downlink.Model.RegistrationAckState.ALREADY_REGISTERED, spa);
            mqttSendService.sendMessage(downlinkTopic, SpaDataHelper.buildDownlinkMessage(header.getOriginator(), spa.get_id(), DownlinkCommandType.SPA_REGISTRATION_RESPONSE, registrationResponse));
        } catch (Exception e) {
            log.error("error while sending downlink message", e);
        }
    }

    private String generateRandomString() {
        return StringUtil.randomString(32);
    }

    private void handleControllerRegistration(final Bwg.Uplink.UplinkHeader uplinkHeader, final RegisterDevice registerDeviceMessage) {
        // FIXME todo
    }

    private void handleMoteRegistration(final Bwg.Uplink.UplinkHeader uplinkHeader, final RegisterDevice registerDeviceMessage) {
        // FIXME todo
    }
}
