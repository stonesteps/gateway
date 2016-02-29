package com.tritonsvc.messageprocessor;

import com.bwg.iot.model.Spa;
import com.bwg.iot.model.SpaCommand;
import com.tritonsvc.messageprocessor.mongo.repository.SpaCommandRepository;
import com.tritonsvc.messageprocessor.mongo.repository.SpaRepository;
import com.tritonsvc.messageprocessor.mqtt.MqttSendService;
import com.tritonsvc.messageprocessor.util.SpaDataHelper;
import com.tritonsvc.spa.communication.proto.Bwg;
import org.junit.Assert;
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
    private SpaRepository spaRepository;

    @Autowired
    private SpaCommandRepository spaCommandRepository;

    @Autowired
    private MqttSendService mqttSendService;

    @Value("${uplinkTopicName:BWG/spa/uplink}")
    private String uplinkTopicName;

    @Test
    @Ignore
    public void handleRegisterDevice() throws Exception {

        // wait some time
        Thread.sleep(5000);

        // send register message
        final Collection<Bwg.Metadata> metadata = new ArrayList<>();
        metadata.add(SpaDataHelper.buildMetadata("serialName", "ABC"));
        final Bwg.Uplink.Model.RegisterDevice registerDevice = SpaDataHelper.buildRegisterDevice(null, "gateway", metadata);
        mqttSendService.sendMessage(uplinkTopicName, SpaDataHelper.buildUplinkMessage("1", "1", Bwg.Uplink.UplinkCommandType.REGISTRATION, registerDevice));

        Thread.sleep(10000);
    }

    @Test
    @Ignore
    public void processHeaterCommand() throws Exception {
        spaRepository.deleteAll();
        spaCommandRepository.deleteAll();

        // create spa (with serialNumber)
        final Spa spa = createSpa("1");
        // and command with metadata
        final SpaCommand command = createSpaCommand(spa);

        // wait some time 
        Thread.sleep(10000);

        final SpaCommand processed = spaCommandRepository.findOne(command.getId());
        Assert.assertNotNull(processed);
        Assert.assertNotNull(processed.getProcessedTimestamp());
    }

    private Spa createSpa(final String serialNumber) {
        final Spa spa = new Spa();
        spa.setSerialNumber(serialNumber);
        spaRepository.save(spa);
        return spa;
    }

    private SpaCommand createSpaCommand(Spa spa) {
        final SpaCommand command = new SpaCommand();
        command.setSpaId(spa.getId());
        command.setSentTimestamp(String.valueOf(System.currentTimeMillis()));
        command.setRequestTypeId(Integer.valueOf(SpaCommand.RequestType.HEATER.getCode()));
        spaCommandRepository.save(command);
        return command;
    }
}
