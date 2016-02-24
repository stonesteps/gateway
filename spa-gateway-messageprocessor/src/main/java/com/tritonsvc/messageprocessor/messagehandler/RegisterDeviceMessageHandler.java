package com.tritonsvc.messageprocessor.messagehandler;

import com.bwg.iot.model.Spa;
import com.tritonsvc.messageprocessor.mongo.repository.SpaRepository;
import com.tritonsvc.messageprocessor.mqtt.MqttSendService;
import com.tritonsvc.messageprocessor.util.StringUtil;
import com.tritonsvc.spa.communication.proto.Bwg;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.Model.RegisterDevice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
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

        final String serialNumber = getMetadataValue("serialNumber", metadata);
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
                final byte[] registerAck = buildRegisterAck(header.getOriginator(), spa, newSpa ? Bwg.Downlink.Model.RegistrationAckState.NEW_REGISTRATION : Bwg.Downlink.Model.RegistrationAckState.ALREADY_REGISTERED);
                mqttSendService.sendMessage(downlinkTopic, registerAck);
            } catch (Exception e) {
                log.error("error while sending downlink message", e);
            }
        } else {
            try {
                final byte[] registerAck = buildRegisterAck(header.getOriginator(), null, Bwg.Downlink.Model.RegistrationAckState.REGISTRATION_ERROR);
                mqttSendService.sendMessage(downlinkTopic, registerAck);
            } catch (Exception e) {
                log.error("error while sending downlink message", e);
            }
        }
    }

    private String generateRandomString() {
        return StringUtil.randomString(32);
    }

    private byte[] buildRegisterAck(final String originator, final Spa spa, final Bwg.Downlink.Model.RegistrationAckState state) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final Bwg.Header.Builder builder = Bwg.Header.newBuilder()
                .setCommand(Bwg.CommandType.DOWNLINK)
                .setSentTimestamp(System.currentTimeMillis());
        if (originator != null) {
            builder.setOriginator(originator);
        }
        final Bwg.Header header = builder.build();
        final Bwg.Downlink.DownlinkHeader.Builder dlBuilder = Bwg.Downlink.DownlinkHeader.newBuilder();
        if (spa != null) {
            dlBuilder.setHardwareId(spa.getId());
        }
        final Bwg.Downlink.DownlinkHeader downlinkHeader = dlBuilder
                .setCommandType(Bwg.Downlink.DownlinkCommandType.ACK)
                .build();
        header.writeDelimitedTo(out);
        downlinkHeader.writeDelimitedTo(out);
        final Bwg.Downlink.Model.RegistrationResponse.Builder msgBuilder = Bwg.Downlink.Model.RegistrationResponse.newBuilder();
        msgBuilder.setState(state);
        msgBuilder.setP2PAPSSID(spa.getP2pAPSSID());
        msgBuilder.setP2PAPPassword(spa.getP2pAPPassword());

        Bwg.Downlink.Model.RegistrationResponse msg = msgBuilder.build();
        msg.writeDelimitedTo(out);

        return out.toByteArray();
    }

    private String getMetadataValue(final String name, final List<Bwg.Metadata> metadata) {
        String value = null;
        if (metadata != null && metadata.size() > 0) {
            for (final Bwg.Metadata metadataElem : metadata) {
                if (metadataElem.hasName() && metadataElem.getName().equals(name)) {
                    value = metadataElem.getValue();
                    break;
                }
            }
        }
        return value;
    }

    private void handleControllerRegistration(final Bwg.Uplink.UplinkHeader uplinkHeader, final RegisterDevice registerDeviceMessage) {
        // FIXME todo
    }

    private void handleMoteRegistration(final Bwg.Uplink.UplinkHeader uplinkHeader, final RegisterDevice registerDeviceMessage) {
        // FIXME todo
    }
}
