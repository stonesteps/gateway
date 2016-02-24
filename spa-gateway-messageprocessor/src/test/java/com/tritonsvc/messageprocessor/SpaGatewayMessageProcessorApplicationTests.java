package com.tritonsvc.messageprocessor;

import com.google.protobuf.AbstractMessageLite;
import com.tritonsvc.messageprocessor.mqtt.MqttSendService;
import com.tritonsvc.spa.communication.proto.Bwg;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.SpringApplicationConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

import java.io.ByteArrayOutputStream;

@RunWith(SpringJUnit4ClassRunner.class)
@SpringApplicationConfiguration(classes = SpaGatewayMessageProcessorApplication.class)
public class SpaGatewayMessageProcessorApplicationTests {

    @Autowired
    private MqttSendService mqttSendService;

    //@Test
    public void contextLoads() {
    }

    @Test
    public void sendReceive() throws Exception {

        Bwg.Uplink.Model.RegisterDevice.Builder rdb = Bwg.Uplink.Model.RegisterDevice.newBuilder();
        rdb.setDeviceTypeName("gateway");
        // rdb.setParentDeviceHardwareId(null);
        final Bwg.Uplink.Model.RegisterDevice registerDevice = rdb.build();

        Thread.sleep(5000);

        sendUplink("1", "2", Bwg.Uplink.UplinkCommandType.REGISTRATION, registerDevice);

        Thread.sleep(10000);
    }

    private void sendUplink(final String hardwareId,
                            final String originator,
                            final Bwg.Uplink.UplinkCommandType uplinkCommandType,
                            final AbstractMessageLite msg) throws Exception {

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Bwg.Header.Builder builder = Bwg.Header.newBuilder()
                .setCommand(Bwg.CommandType.UPLINK)
                .setSentTimestamp(System.currentTimeMillis());

        if (originator != null) {
            builder.setOriginator(originator);
        }
        Bwg.Header header = builder.build();

        Bwg.Uplink.UplinkHeader.Builder ulBuilder = Bwg.Uplink.UplinkHeader.newBuilder();
        if (hardwareId != null) {
            ulBuilder.setHardwareId(hardwareId);
        }

        Bwg.Uplink.UplinkHeader uplinkHeader = ulBuilder
                .setCommand(uplinkCommandType)
                .build();

        header.writeDelimitedTo(out);
        uplinkHeader.writeDelimitedTo(out);
        if (msg != null) {
            msg.writeDelimitedTo(out);
        }

        mqttSendService.sendMessage("uplink", out.toByteArray());
    }
}
