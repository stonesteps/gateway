package com.tritonsvc.agent;

import org.fusesource.mqtt.client.BlockingConnection;
import org.fusesource.mqtt.client.MQTT;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.ArgumentCaptor;

import javax.net.ssl.SSLContext;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.concurrent.ExecutorService;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Matchers.isNotNull;
import static org.mockito.Matchers.isNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class AgentTest {

    private Agent agent;
    private AgentMessageProcessor processor;
    private MQTT mqttSub;
    private MQTT mqttPub;
    private BlockingConnection subConnection;
    private BlockingConnection pubConnection;

    @Rule
    public TemporaryFolder folder= new TemporaryFolder();

    @Before
    public void setUp() throws IOException {
        agent = spy(new Agent());
        processor = mock(AgentMessageProcessor.class);
        mqttSub = mock(MQTT.class);
        mqttPub = mock(MQTT.class);
        subConnection = mock(BlockingConnection.class);
        pubConnection = mock(BlockingConnection.class);
        doReturn(processor).when(agent).createProcessor();
        // do not want a real thread executor getting into Agent during test,
        // it will result in mqtt listener getting in tight loop and OOM
        doReturn(mock(ExecutorService.class)).when(agent).getInboundExecutor();
        doReturn(mqttSub).doReturn(mqttPub).when(agent).createMQTT();
        when(mqttSub.blockingConnection()).thenReturn(subConnection);
        when(mqttPub.blockingConnection()).thenReturn(pubConnection);
    }

    @Test
    public void itStarts() throws Exception {
        File createdFile= folder.newFile("config.properties");
        PrintWriter pw = new PrintWriter(new FileWriter(createdFile));
        pw.println("command.processor.classname=com.tritonsvc.gateway.MockProcessor");
        pw.println("spa.gateway.serialnumber=spatime");
        pw.flush();
        pw.close();

        agent.start(folder.getRoot().getAbsolutePath());
        verify(mqttSub).setHost(eq("tcp://localhost:1883"));
        verify(mqttPub).setHost(eq("tcp://localhost:1883"));
        //verify(subConnection).connect();
        //verify(pubConnection).connect();
        verify(processor).setGwSerialNumber("spatime");
        verify(processor).executeStartup();
    }

    /**
     * these test pki files were created with openssl
     *
     * root
     * openssl genrsa -out ca_root_key.pem 2048
     * openssl req -x509 -new -nodes -key ca_root_key.pem -sha256 -days 18250 -out ca_root_cert.pem
     *
     * mqtt broker(server)
     * openssl genrsa -out broker_key.pem 2048
     * openssl req -new -key broker_key.pem -out broker.csr
     * openssl x509 -req -in broker.csr -CA ca_root_cert.pem -CAkey ca_root_key.pem -CAcreateserial -out broker_cert.pem -days 18250 -sha256
     *
     * gateway(client)
     * openssl genrsa -out gateway_key.pem 2048
     * openssl req -new -key gateway_key.pem -out gateway.csr
     * openssl x509 -req -in gateway.csr -CA ca_root_cert.pem -CAkey ca_root_key.pem -CAcreateserial -out gateway_cert.pem -days 18250 -sha256
     * openssl pkcs8 -topk8 -inform PEM -outform DER -in gateway_key.pem -out gateway_key.pkcs8 -nocrypt
     *
     * some mqtt broker's require the certs/keys to be in a keystore file:
     * Create PKCS12 keystore from private key and public certificate.
     * openssl pkcs12 -export -name brokercert -in broker_cert.pem -inkey broker_key.pem -out broker_keystore.p12
     * Convert PKCS12 keystore into a JKS keystore
     * keytool -importkeystore -destkeystore broker_ssl.jks -srckeystore broker_keystore.p12 -srcstoretype pkcs12 -alias brokercert
     *
     * keytool -import -trustcacerts -alias root -file ca_root.cert.pem -keystore broker_ssl.jks
     *
     */

    @Test
    public void itHandlesTrustedServerSSL() throws Exception {
        File createdFile= folder.newFile("config.properties");
        PrintWriter pw = new PrintWriter(new FileWriter(createdFile));
        pw.println("command.processor.classname=com.tritonsvc.gateway.MockProcessor");
        pw.println("spa.gateway.serialnumber=spatime");
        pw.flush();
        pw.close();

        Files.copy(AgentTest.class.getResourceAsStream("/" + Agent.CA_ROOT_PUBKEY_FILE), folder.newFile(Agent.CA_ROOT_PUBKEY_FILE).toPath(), StandardCopyOption.REPLACE_EXISTING);
        agent.start(folder.getRoot().getAbsolutePath());
        verify(mqttSub).setSslContext(isNotNull(SSLContext.class));
        verify(mqttPub).setSslContext(isNotNull(SSLContext.class));
        verify(mqttSub).setHost(eq("tls://localhost:8883"));
        verify(mqttPub).setHost(eq("tls://localhost:8883"));

        verify(processor).setPKI(isNull(X509Certificate.class), isNull(PrivateKey.class));
        verify(processor).setGwSerialNumber(eq("spatime"));
    }

    @Test
    public void itHandlesClientSSL() throws Exception {
        File createdFile= folder.newFile("config.properties");
        PrintWriter pw = new PrintWriter(new FileWriter(createdFile));
        pw.println("command.processor.classname=com.tritonsvc.gateway.MockProcessor");
        pw.println("spa.gateway.serialnumber=spatime");
        pw.flush();
        pw.close();

        Files.copy(AgentTest.class.getResourceAsStream("/" + Agent.CA_ROOT_PUBKEY_FILE), folder.newFile(Agent.CA_ROOT_PUBKEY_FILE).toPath(), StandardCopyOption.REPLACE_EXISTING);
        Files.copy(AgentTest.class.getResourceAsStream("/" + Agent.GATEWAY_PRIVKEY_FILE), folder.newFile(Agent.GATEWAY_PRIVKEY_FILE).toPath(), StandardCopyOption.REPLACE_EXISTING);
        Files.copy(AgentTest.class.getResourceAsStream("/" + Agent.GATEWAY_PUBKEY_FILE), folder.newFile(Agent.GATEWAY_PUBKEY_FILE).toPath(), StandardCopyOption.REPLACE_EXISTING);

        agent.start(folder.getRoot().getAbsolutePath());
        verify(mqttSub).setSslContext(isNotNull(SSLContext.class));
        verify(mqttPub).setSslContext(isNotNull(SSLContext.class));
        verify(mqttSub).setHost(eq("tls://localhost:8883"));
        verify(mqttPub).setHost(eq("tls://localhost:8883"));

        ArgumentCaptor<X509Certificate> certCaptor = ArgumentCaptor.forClass(X509Certificate.class);
        verify(processor).setPKI(certCaptor.capture(), any(PrivateKey.class));
        verify(processor).setGwSerialNumber(eq("test1"));
        assertThat("cert dn not obtained correctly", certCaptor.getValue().getSubjectDN().getName().equals("CN=test1, OU=unit_test, O=test, L=san diego, ST=CA, C=US"));
    }
}
