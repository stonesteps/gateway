package com.tritonsvc.messageprocessor;

import com.bwg.iot.model.ProcessedResult;
import com.bwg.iot.model.Spa;
import com.bwg.iot.model.SpaCommand;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tritonsvc.agent.Agent;
import com.tritonsvc.httpd.model.RegisterUserResponse;
import com.tritonsvc.messageprocessor.mongo.repository.SpaCommandRepository;
import com.tritonsvc.messageprocessor.mongo.repository.SpaRepository;
import com.tritonsvc.messageprocessor.mqtt.MqttSendService;
import com.tritonsvc.spa.communication.proto.Bwg;
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

import javax.net.ssl.*;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URL;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.X509Certificate;
import java.util.HashMap;

/**
 * Created by holow on 3/26/2016.
 */
@RunWith(SpringJUnit4ClassRunner.class)
@SpringApplicationConfiguration(classes = {SpaGatewayMessageProcessorApplication.class, UnitTestHelper.class})
public class SpaRegisterIT {

    private static final Logger log = LoggerFactory.getLogger(SpaRegisterIT.class);
    private static final TrustManager[] trustAllCerts = new TrustManager[]{
            new X509TrustManager() {
                public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }

                public void checkClientTrusted(
                        java.security.cert.X509Certificate[] certs, String authType) {
                }

                public void checkServerTrusted(
                        java.security.cert.X509Certificate[] certs, String authType) {
                }
            }
    };
    static {
        try {
            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(null, trustAllCerts, new java.security.SecureRandom());
            HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
            HttpsURLConnection.setDefaultHostnameVerifier(new HostnameVerifier() {
                public boolean verify(String string, SSLSession ssls) {
                    return true;
                }
            });
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("Unable to initialise SSL context", e);
        } catch (KeyManagementException e) {
            throw new RuntimeException("Unable to initialise SSL context", e);
        }
    }

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
                    IOUtils.copy(SpaRegisterIT.class.getResourceAsStream("/agent-config.properties"), new FileOutputStream(new File(agentFolder, "config.properties")));

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

        final URL url = new URL("https://localhost:8000/registerUserToSpa");
        HttpsURLConnection conn = (HttpsURLConnection) url.openConnection();
        final String response = getContent(conn);

        final ObjectMapper objectMapper = new ObjectMapper();
        final RegisterUserResponse registerUserResponse = objectMapper.readValue(response, RegisterUserResponse.class);

        Assert.assertEquals(registerUserResponse.getRegKey(), registeredSpa.getRegKey());
    }

    private String getContent(final HttpsURLConnection conn) throws IOException {
        String response = null;
        if (conn != null) {
            final ByteArrayOutputStream bos = new ByteArrayOutputStream();
            IOUtils.copy(conn.getInputStream(), bos);
            response = bos.toString();
        }
        return response;
    }
}
