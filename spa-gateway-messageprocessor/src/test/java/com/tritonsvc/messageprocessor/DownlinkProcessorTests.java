package com.tritonsvc.messageprocessor;

import com.bwg.iot.model.Spa;
import com.bwg.iot.model.SpaCommand;
import com.tritonsvc.messageprocessor.mongo.repository.SpaCommandRepository;
import com.tritonsvc.messageprocessor.mongo.repository.SpaRepository;
import com.tritonsvc.messageprocessor.mqtt.MqttSendService;
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
public class DownlinkProcessorTests {

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
        final Spa spa = unitTestHelper.createSpa("1");
        // and command with metadata
        final HashMap<String, String> values = new HashMap<>();
        values.put(SpaCommand.ValueKeyName.DESIRED_TEMP.getKeyName(), "78.0");
        final SpaCommand command = unitTestHelper.createSpaCommand(spa, SpaCommand.RequestType.HEATER.getCode(), values);

        // wait some time (commands processed every 5s)
        Thread.sleep(10000);

        final SpaCommand processed = spaCommandRepository.findOne(command.get_id());
        Assert.assertNotNull(processed);
        Assert.assertNotNull(processed.getProcessedTimestamp());
    }

    @Test
    public void processLightsCommand() throws Exception {
        // create spa (with serialNumber)
        final Spa spa = unitTestHelper.createSpa("1");
        // and command with metadata
        final HashMap<String, String> values = new HashMap<>();
        values.put(SpaCommand.ValueKeyName.DESIRED_STATE.getKeyName(), SpaCommand.LightState.HIGH.toString());
        final SpaCommand command = unitTestHelper.createSpaCommand(spa, SpaCommand.RequestType.LIGHTS.getCode(), values);

        // wait some time (commands processed every 5s)
        Thread.sleep(10000);

        final SpaCommand processed = spaCommandRepository.findOne(command.get_id());
        Assert.assertNotNull(processed);
        Assert.assertNotNull(processed.getProcessedTimestamp());
    }
}
