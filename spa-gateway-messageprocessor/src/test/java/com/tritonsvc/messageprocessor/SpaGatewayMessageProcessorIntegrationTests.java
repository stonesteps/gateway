package com.tritonsvc.messageprocessor;

import com.tritonsvc.messageprocessor.mqtt.MqttSendService;
import com.tritonsvc.messageprocessor.util.SpaDataHelper;
import com.tritonsvc.spa.communication.proto.Bwg;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.SpringApplicationConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

import java.util.ArrayList;
import java.util.Collection;

@RunWith(SpringJUnit4ClassRunner.class)
@SpringApplicationConfiguration(classes = SpaGatewayMessageProcessorApplication.class)
public class SpaGatewayMessageProcessorIntegrationTests {

    @Autowired
    private MqttSendService mqttSendService;

    @Value("${uplinkTopicName:BWG/spa/uplink}")
    private String uplinkTopicName;

    @Test
    @Ignore
    public void sendReceive() throws Exception {

        // wait some time
        Thread.sleep(5000);

        // send register message
        final Collection<Bwg.Metadata> metadata = new ArrayList<>();
        metadata.add(SpaDataHelper.buildMetadata("serialName", "ABC"));
        final Bwg.Uplink.Model.RegisterDevice registerDevice = SpaDataHelper.buildRegisterDevice(null, "gateway", metadata);
        mqttSendService.sendMessage(uplinkTopicName, SpaDataHelper.buildUplinkMessage("1", "1", Bwg.Uplink.UplinkCommandType.REGISTRATION, registerDevice));

        Thread.sleep(10000);
    }
}
