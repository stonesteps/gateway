package com.tritonsvc.messageprocessor.mqtt;


import com.tritonsvc.messageprocessor.MessageProcessorConfiguration;
import com.tritonsvc.messageprocessor.PkiInfo;
import org.fusesource.mqtt.client.Future;
import org.fusesource.mqtt.client.FutureConnection;
import org.fusesource.mqtt.client.MQTT;
import org.fusesource.mqtt.client.Message;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class MqttSubscribeServiceTest {

    private MqttSubscribeService service;
    private Future<Message> futureMessage;
    private CountDownLatch cdl;
    private FutureConnection conn;

    @Before
    public void setUp() {
        service = spy(new MqttSubscribeService());
        MQTT mqtt = mock(MQTT.class);
        conn = mock(FutureConnection.class);
        when(mqtt.futureConnection()).thenReturn(conn);
        doReturn(mqtt).when(service).acquireMQTT();
        futureMessage = mock(Future.class);
        when(conn.receive()).thenReturn(futureMessage);
        cdl = new CountDownLatch(2);
        MessageProcessorConfiguration config = mock(MessageProcessorConfiguration.class);
        when(config.obtainPKIArtifacts()).thenReturn(mock(PkiInfo.class));
        ReflectionTestUtils.setField(service, "messageProcessorConfiguration",config);
    }

    @After
    public void cleanUp() {
        service.cleanup();
    }

    @Test
    public void itHandlesNoMessageTimeout() throws Exception{
        doAnswer(invocation -> {
            cdl.countDown();
            if (cdl.getCount() == 1) {
                throw new TimeoutException();
            } else {
                throw new InterruptedException();
            }
        }).when(futureMessage).await(anyInt(), any());

        service.subscribe("anyTopic", mock(MessageListener.class));

        assertThat("did not get timeout exception", cdl.await(2,TimeUnit.SECONDS));
        // should only be called twice, first time and then the second is because of timeout excep
        verify(conn, times(2)).subscribe(any());
    }
}
