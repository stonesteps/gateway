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
    private MQTT mqtt;
    private BlockingConnection connection;

    @Rule
    public TemporaryFolder folder= new TemporaryFolder();


    @Before
    public void setUp() throws IOException {
        agent = spy(new Agent());
        processor = mock(AgentMessageProcessor.class);
        mqtt = mock(MQTT.class);
        connection = mock(BlockingConnection.class);
        doReturn(processor).when(agent).createProcessor();
        doReturn(mqtt).when(agent).createMQTT();
        when(mqtt.blockingConnection()).thenReturn(connection);
    }

    @Test
    public void itStarts() throws Exception {
        File createdFile= folder.newFile("config.properties");
        PrintWriter pw = new PrintWriter(new FileWriter(createdFile));
        pw.println("command.processor.classname=com.whatever");
        pw.println("device.hardware.id=spatime");
        pw.flush();
        pw.close();

        agent.start(folder.getRoot().getAbsolutePath());
        verify(mqtt).setHost(eq("localhost"), eq(1883));
        verify(connection).connect();
        verify(processor).executeStartup();
    }
}
