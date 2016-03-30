package com.tritonsvc.agent;

import org.fusesource.mqtt.client.BlockingConnection;
import org.fusesource.mqtt.client.MQTT;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;

import static org.mockito.Matchers.eq;
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
        doReturn(processor).when(agent).createProcessor(null);
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
        verify(mqttSub).setHost(eq("localhost"), eq(1883));
        verify(mqttPub).setHost(eq("localhost"), eq(1883));
        verify(subConnection).connect();
        verify(pubConnection).connect();
        // verify(processor).executeStartup();
    }
}
