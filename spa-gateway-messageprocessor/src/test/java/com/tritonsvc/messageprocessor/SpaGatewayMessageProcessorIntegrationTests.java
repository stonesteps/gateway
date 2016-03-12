package com.tritonsvc.messageprocessor;

import com.bwg.iot.model.Spa;
import com.bwg.iot.model.SpaCommand;
import com.tritonsvc.messageprocessor.mongo.repository.SpaCommandRepository;
import com.tritonsvc.messageprocessor.mongo.repository.SpaRepository;
import com.tritonsvc.messageprocessor.mqtt.MqttSendService;
import com.tritonsvc.messageprocessor.util.SpaDataHelper;
import com.tritonsvc.spa.communication.proto.Bwg;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.SpringApplicationConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

import java.util.HashMap;

import static junit.framework.TestCase.assertNotNull;

@RunWith(SpringJUnit4ClassRunner.class)
@SpringApplicationConfiguration(classes = SpaGatewayMessageProcessorApplication.class)
public class SpaGatewayMessageProcessorIntegrationTests {

    @Autowired
    private SpaRepository spaRepository;

    @Autowired
    private SpaCommandRepository spaCommandRepository;

    @Autowired
    private MqttSendService mqttSendService;

    @Autowired
    private MessageProcessorConfiguration messageProcessorConfiguration;

    @Test
    public void handleRegisterDevice() throws Exception {

        // wait some time
        Thread.sleep(5000);

        // send register message
        final Bwg.Uplink.Model.RegisterDevice registerDevice = SpaDataHelper.buildRegisterDevice(null, "gateway", "ABC", null);
        mqttSendService.sendMessage(messageProcessorConfiguration.getUplinkTopicName(), SpaDataHelper.buildUplinkMessage("1", null, Bwg.Uplink.UplinkCommandType.REGISTRATION, registerDevice));

        Thread.sleep(10000);

        final Spa processed = spaRepository.findBySerialNumber("ABC");
        assertNotNull(processed);
    }

    @Test
    public void processHeaterCommand() throws Exception {
        spaRepository.deleteAll();
        spaCommandRepository.deleteAll();

        // create spa (with serialNumber)
        final Spa spa = createSpa("1");
        // and command with metadata
        final HashMap<String, String> values = new HashMap<>();
        values.put(SpaCommand.ValueKeyName.DESIRED_TEMP.getKeyName(), "78.0");
        final SpaCommand command = createSpaCommand(spa, SpaCommand.RequestType.HEATER.getCode(), values);

        // wait some time
        Thread.sleep(10000);

        final SpaCommand processed = spaCommandRepository.findOne(command.get_id());
        Assert.assertNotNull(processed);
        Assert.assertNotNull(processed.getProcessedTimestamp());
    }

    @Test
    public void processLightsCommand() throws Exception {
        spaRepository.deleteAll();
        spaCommandRepository.deleteAll();

        // create spa (with serialNumber)
        final Spa spa = createSpa("1");
        // and command with metadata
        final HashMap<String, String> values = new HashMap<>();
        values.put(SpaCommand.ValueKeyName.DESIRED_STATE.getKeyName(), SpaCommand.OnOff.ON.toString());
        values.put(SpaCommand.ValueKeyName.PORT.getKeyName(), String.valueOf(0));
        final SpaCommand command = createSpaCommand(spa, SpaCommand.RequestType.LIGHTS.getCode(), values);

        // wait some time
        Thread.sleep(10000);

        final SpaCommand processed = spaCommandRepository.findOne(command.get_id());
        Assert.assertNotNull(processed);
        Assert.assertNotNull(processed.getProcessedTimestamp());
    }

    private Spa createSpa(final String serialNumber) {
        final Spa spa = new Spa();
        spa.setSerialNumber(serialNumber);
        spaRepository.save(spa);
        return spa;
    }

    private SpaCommand createSpaCommand(Spa spa, int requestType, HashMap<String, String> values) {
        final SpaCommand command = new SpaCommand();
        command.setSpaId(spa.get_id());
        command.setSentTimestamp(String.valueOf(System.currentTimeMillis()));
        command.setRequestTypeId(Integer.valueOf(requestType));
        command.setValues(values);
        spaCommandRepository.save(command);
        return command;
    }
}
