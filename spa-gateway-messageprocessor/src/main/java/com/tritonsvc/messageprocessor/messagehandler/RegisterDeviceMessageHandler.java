package com.tritonsvc.messageprocessor.messagehandler;

import com.bwg.iot.model.Spa;
import com.tritonsvc.messageprocessor.mongo.repository.SpaRepository;
import com.tritonsvc.messageprocessor.mqtt.MqttSendService;
import com.tritonsvc.messageprocessor.util.SpaDataHelper;
import com.tritonsvc.messageprocessor.util.StringUtil;
import com.tritonsvc.spa.communication.proto.Bwg;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.Model.RegisterDevice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

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

        // type names:
        // gateway, controller, mote
        // gateway metadata: serialNumber
        // mote metadata: mac


        // Doing device registration  may be a good starting point for message processing.
        // It's a bit more involved than other uplinks in that it requires a downlink ack to be
        // sent with the newly registered hardwareId.
        //
        // inspect the message.deviceTypeName, parentDeviceHardwareId, and meta collection properties to determine what
        // mongodb collection to add the device registration inoto and also determine the device's idenity within
        // that collection, in some cases you'll get a deviceTypeName of 'pump' or 'mote', then you'll need to
        // look at the keys in meta collection also because there's multiple of these types per single spa system
        // such as pump1, pump2, mote1,mote2,mote3, so they'll have a meta property of 'port' with 1,2,3,etc for value
        //
        // check the target mongodb collection if the device is not there generate a new document, if
        // device is already present, no need to update. In either case, need to get the unique device id for the document in mongodb
        // and send that id back down to spa system as a downlink message in IDL called Downlink.Model.RegistrationResponse
        //
        // also, make sure to pass the originatorId present on any uplink message onto any downlink messages that get sent back
        // to spa as a result, this is required for the RegistrationResponse message. Refer to BWGProcessor.java to
        // see how the 'other' half processes the same downlink/uplink messages.
        // ...

    }

    private void handleGatewayRegistration(final Bwg.Header header, final Bwg.Uplink.UplinkHeader uplinkHeader, final RegisterDevice registerDeviceMessage) {

        // FIXME any validation of incomming message?
        // FIXME serial number required?

        // create spa
        final String spaId = registerDeviceMessage.getParentDeviceHardwareId();
        final List<Bwg.Metadata> metadata = registerDeviceMessage.getMetadataList();

        final String serialNumber = SpaDataHelper.getMetadataValue("serialNumber", metadata);
        final String downlinkTopic = downlinkTopicName + (serialNumber != null ? "/"+serialNumber : "");

        // FIXME find also by serial number?

        boolean newSpa = false;
        Spa spa = null;
        if (StringUtils.isEmpty(spaId)) {
            log.info("creating new spa object");
            spa = new Spa();
            spa.setP2pAPPassword(generateRandomString());
            spa.setP2pAPSSID(generateRandomString());
            spa.setSerialNumber(serialNumber);
            spaRepository.save(spa);
            newSpa = true;
        } else {
            log.info("looking for spa with id {}", spaId);
            spa = spaRepository.findOne(spaId);
        }
        if (spa != null) {
            try {
                final Bwg.Downlink.Model.RegistrationResponse registrationResponse = SpaDataHelper.buildRegistrationResponse(newSpa ? Bwg.Downlink.Model.RegistrationAckState.NEW_REGISTRATION : Bwg.Downlink.Model.RegistrationAckState.ALREADY_REGISTERED, spa);
                mqttSendService.sendMessage(downlinkTopic, SpaDataHelper.buildDownlinkMessage(header.getOriginator(), spa.getId(), Bwg.Downlink.DownlinkCommandType.ACK, registrationResponse));
            } catch (Exception e) {
                log.error("error while sending downlink message", e);
            }
        } else {
            try {
                final Bwg.Downlink.Model.RegistrationResponse registrationResponse = SpaDataHelper.buildRegistrationResponse(Bwg.Downlink.Model.RegistrationAckState.REGISTRATION_ERROR, null);
                mqttSendService.sendMessage(downlinkTopic, SpaDataHelper.buildDownlinkMessage(header.getOriginator(), null, Bwg.Downlink.DownlinkCommandType.ACK, registrationResponse));
            } catch (Exception e) {
                log.error("error while sending downlink message", e);
            }
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
