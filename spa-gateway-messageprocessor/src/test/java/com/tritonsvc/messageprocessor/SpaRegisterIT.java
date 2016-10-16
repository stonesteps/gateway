package com.tritonsvc.messageprocessor;

import com.bwg.iot.model.Spa;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tritonsvc.agent.Agent;
import com.tritonsvc.messageprocessor.mongo.repository.SpaCommandRepository;
import com.tritonsvc.messageprocessor.mongo.repository.SpaRepository;
import com.tritonsvc.messageprocessor.mqtt.MqttSendService;
import com.tritonsvc.model.RegisterUserResponse;
import org.apache.commons.io.IOUtils;
import org.apache.commons.io.output.ByteArrayOutputStream;
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
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Created by holow on 3/26/2016.
 */
@RunWith(SpringJUnit4ClassRunner.class)
@SpringApplicationConfiguration(classes = {SpaGatewayMessageProcessorApplication.class, UnitTestHelper.class})
public class SpaRegisterIT {

    private static final Logger log = LoggerFactory.getLogger(SpaRegisterIT.class);

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
                    IOUtils.copy(SpaRegisterIT.class.getResourceAsStream("/agent-config-http.properties"), new FileOutputStream(new File(agentFolder, "config.properties")));

                    agent = new Agent();
                    agent.start(agentFolder.getAbsolutePath());
                } catch (IOException e) {
                    log.error("Error starting up the agent");
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
    public void checkRegistration() throws Exception {
        // wait for spa to be created and configured
        Thread.sleep(3000);

        log.info("+++++++++++++++++++++++++");
        log.info("Checking spa registration");

        final Spa registeredSpa = spaRepository.findOneBySerialNumber("demo_2872_ep_gateway");

        final URL url = new URL("http://localhost:8080/registerUserToSpa");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        final String response = getContent(conn);

        final ObjectMapper objectMapper = new ObjectMapper();
        final RegisterUserResponse registerUserResponse = objectMapper.readValue(response, RegisterUserResponse.class);

        Assert.assertEquals(registerUserResponse.getRegKey(), registeredSpa.getRegKey());
    }

    private String getContent(final HttpURLConnection conn) throws IOException {
        String response = null;
        if (conn != null) {
            final ByteArrayOutputStream bos = new ByteArrayOutputStream();
            IOUtils.copy(conn.getInputStream(), bos);
            response = bos.toString();
        }
        return response;
    }
}
