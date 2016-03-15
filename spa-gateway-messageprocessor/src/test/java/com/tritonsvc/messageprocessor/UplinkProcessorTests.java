package com.tritonsvc.messageprocessor;

import com.bwg.iot.model.Spa;
import com.tritonsvc.messageprocessor.mongo.repository.SpaCommandRepository;
import com.tritonsvc.messageprocessor.mongo.repository.SpaRepository;
import com.tritonsvc.messageprocessor.mqtt.MqttSendService;
import com.tritonsvc.messageprocessor.util.SpaDataHelper;
import com.tritonsvc.spa.communication.proto.Bwg;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.SpringApplicationConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

import java.util.ArrayList;
import java.util.Collection;

@RunWith(SpringJUnit4ClassRunner.class)
@SpringApplicationConfiguration(classes = {SpaGatewayMessageProcessorApplication.class, UnitTestHelper.class})
public class UplinkProcessorTests {

    @Autowired
    private SpaRepository spaRepository;

    @Autowired
    private SpaCommandRepository spaCommandRepository;

    @Autowired
    private MqttSendService mqttSendService;

    @Autowired
    private MessageProcessorConfiguration messageProcessorConfiguration;

    @After
    @Before
    public void cleanup() {
        spaRepository.deleteAll();
        spaCommandRepository.deleteAll();
    }

    @Test
    public void handleRegisterDevice() throws Exception {
        // send register message
        final Collection<Bwg.Metadata> metadata = new ArrayList<>();
        metadata.add(SpaDataHelper.buildMetadata("serialName", "ABC"));
        final Bwg.Uplink.Model.RegisterDevice registerDevice = SpaDataHelper.buildRegisterDevice(null, "gateway", "1", metadata);
        mqttSendService.sendMessage(messageProcessorConfiguration.getUplinkTopicName(), SpaDataHelper.buildUplinkMessage("1", "1", Bwg.Uplink.UplinkCommandType.REGISTRATION, registerDevice));

        // wait for message to be delivered and processed
        Thread.sleep(5000);

        final Spa spa = spaRepository.findOneBySerialNumber("1");
        Assert.assertNotNull(spa);
        Assert.assertEquals("1", spa.getSerialNumber());
    }
}
