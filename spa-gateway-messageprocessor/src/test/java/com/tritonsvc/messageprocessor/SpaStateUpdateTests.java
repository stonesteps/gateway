package com.tritonsvc.messageprocessor;

import com.bwg.iot.model.LightState;
import com.bwg.iot.model.ProcessedResult;
import com.bwg.iot.model.Spa;
import com.bwg.iot.model.SpaCommand;
import com.tritonsvc.agent.Agent;
import com.tritonsvc.messageprocessor.mongo.repository.SpaCommandRepository;
import com.tritonsvc.messageprocessor.mongo.repository.SpaRepository;
import com.tritonsvc.messageprocessor.mqtt.MqttSendService;
import org.apache.commons.io.IOUtils;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.SpringApplicationConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashMap;

/**
 * Created by holow on 3/26/2016.
 */
@RunWith(SpringJUnit4ClassRunner.class)
@SpringApplicationConfiguration(classes = {SpaGatewayMessageProcessorApplication.class, UnitTestHelper.class})
public class SpaStateUpdateTests {

    private static final Logger log = LoggerFactory.getLogger(SpaStateUpdateTests.class);

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

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private Agent agent = null;

    @Before
    public void startAgent() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final File agentFolder = tempFolder.newFolder("agent");
                    IOUtils.copy(SpaStateUpdateTests.class.getResourceAsStream("/agent-config.properties"), new FileOutputStream(new File(agentFolder, "config.properties")));

                    agent = new Agent();
                    agent.start(agentFolder.getAbsolutePath());
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }).start();
    }

    @After
    @Before
    public void cleanup() {
        spaRepository.deleteAll();
        spaCommandRepository.deleteAll();
    }

    @Test
    public void processHeaterCommand() throws Exception {
        // wait for spa to be created
        Thread.sleep(15000);

        // create spa (with serialNumber)
        final Spa spa = spaRepository.findOneBySerialNumber("demo_2872_ep_gateway");
        Assert.assertNotNull(spa);

        log.info("+++++++++++++++++++++++++++++++");
        log.info("Spa created properly - continue");

        log.info("++++++++++++++++++++++++++++");
        log.info("Creating temp update command");

        // and command with metadata
        final HashMap<String, String> values = new HashMap<>();
        values.put(SpaCommand.ValueKeyName.DESIRED_TEMP.getKeyName(), "78.0");
        final SpaCommand command = unitTestHelper.createSpaCommand(spa, SpaCommand.RequestType.HEATER.getCode(), values);

        // wait some time (commands processed every 5s)
        Thread.sleep(15000);

        log.info("++++++++++++++++++++++++++");
        log.info("Checking command execution");

        final SpaCommand processed = spaCommandRepository.findOne(command.get_id());
        Assert.assertNotNull(processed);
        Assert.assertNotNull(processed.getProcessedTimestamp());
        Assert.assertEquals(ProcessedResult.SENT, processed.getProcessedResult());

        final Spa updatedSpa = spaRepository.findOneBySerialNumber("demo_2872_ep_gateway");
        Assert.assertEquals(78, Integer.decode(updatedSpa.getCurrentState().getCurrentTemp()).intValue());
    }
}
