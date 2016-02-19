package com.tritonsvc.messageprocessor;

import com.tritonsvc.spa.communication.proto.Bwg.Uplink.Model.RegisterDevice;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * This class transforms MQTT message payloads into MongoDB Documents.
 */
@Service
public class UplinkProcessor implements Runnable{
    @Autowired
    SpaCommandRepository spaCommandRepository;

    @Autowired
    SpaRepository spaRepository;

    @Override
    public void run() {
        //TODO - establish a loop that subscribes to MQTT
        //       the subscription should only block X configurable seconds for messages
        //       if none are received re-subscribe. Log all elapsed time events and received messages
        //       log output from this process is very important need to see log output that
        //       demonstrates this thread is active and never dead or locked up.
        // while (protobufs_message on MQTT uplink)
        //      if (message.type == UplinkCommandType.REGISTRATION)
        //          handleRegistration(message)
        // loop
    }

    public void handleRegistration(RegisterDevice registerMessage) {
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
}
