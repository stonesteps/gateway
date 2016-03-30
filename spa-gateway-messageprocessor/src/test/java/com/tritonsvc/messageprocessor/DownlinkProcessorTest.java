package com.tritonsvc.messageprocessor;

import com.bwg.iot.model.Component;
import com.bwg.iot.model.LightState;
import com.bwg.iot.model.ProcessedResult;
import com.bwg.iot.model.Spa;
import com.bwg.iot.model.SpaCommand;
import com.tritonsvc.messageprocessor.mongo.repository.SpaCommandRepository;
import com.tritonsvc.messageprocessor.mongo.repository.SpaRepository;
import com.tritonsvc.messageprocessor.mqtt.MqttSendService;
import com.tritonsvc.spa.communication.proto.Bwg;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.SpringApplicationConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

import java.util.HashMap;

@RunWith(SpringJUnit4ClassRunner.class)
@SpringApplicationConfiguration(classes = {SpaGatewayMessageProcessorApplication.class, UnitTestHelper.class})
public class DownlinkProcessorTest {

    @Autowired
    private SpaRepository spaRepository;

    @Autowired
    private SpaCommandRepository spaCommandRepository;

    @Autowired
    private UnitTestHelper unitTestHelper;

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
    public void processHeaterCommand() throws Exception {
        // create spa (with serialNumber)
        final Spa spa = unitTestHelper.createSpa();
        unitTestHelper.createGateway(spa, "1");
        // and command with metadata
        final HashMap<String, String> values = new HashMap<>();
        values.put(Bwg.Downlink.Model.SpaCommandAttribName.DESIREDTEMP.name(), "78");
        final SpaCommand command = unitTestHelper.createSpaCommand(spa, SpaCommand.RequestType.HEATER.getCode(), values);

        // wait some time (commands processed every 5s)
        Thread.sleep(10000);

        final SpaCommand processed = spaCommandRepository.findOne(command.get_id());
        Assert.assertNotNull(processed);
        Assert.assertNotNull(processed.getProcessedTimestamp());
        Assert.assertEquals(ProcessedResult.SENT, processed.getProcessedResult());
    }

    @Test
    public void processLightsCommand() throws Exception {
        // create spa (with serialNumber)
        final Spa spa = unitTestHelper.createSpa();
        unitTestHelper.createGateway(spa, "1");
        // and command with metadata
        final HashMap<String, String> values = new HashMap<>();
        values.put(Bwg.Downlink.Model.SpaCommandAttribName.DESIREDSTATE.name(), LightState.HIGH.toString());
        values.put(Bwg.Downlink.Model.SpaCommandAttribName.PORT.name(), String.valueOf(0));
        final SpaCommand command = unitTestHelper.createSpaCommand(spa, SpaCommand.RequestType.LIGHTS.getCode(), values);

        // wait some time (commands processed every 5s)
        Thread.sleep(10000);

        final SpaCommand processed = spaCommandRepository.findOne(command.get_id());
        Assert.assertNotNull(processed);
        Assert.assertNotNull(processed.getProcessedTimestamp());
        Assert.assertEquals(ProcessedResult.SENT, processed.getProcessedResult());
    }

    @Test
    public void processLightsCommandFailed() throws Exception {
        // create spa (with serialNumber)
        final Spa spa = unitTestHelper.createSpa();
        unitTestHelper.createGateway(spa, "1");
        // and command with metadata
        final HashMap<String, String> values = new HashMap<>();
        values.put(Bwg.Downlink.Model.SpaCommandAttribName.DESIREDSTATE.name(), LightState.HIGH.toString());
        final SpaCommand command = unitTestHelper.createSpaCommand(spa, SpaCommand.RequestType.LIGHTS.getCode(), values);

        // wait some time (commands processed every 5s)
        Thread.sleep(10000);

        final SpaCommand processed = spaCommandRepository.findOne(command.get_id());
        Assert.assertNotNull(processed);
        Assert.assertNotNull(processed.getProcessedTimestamp());
        Assert.assertEquals(ProcessedResult.INVALID, processed.getProcessedResult());
    }
}
