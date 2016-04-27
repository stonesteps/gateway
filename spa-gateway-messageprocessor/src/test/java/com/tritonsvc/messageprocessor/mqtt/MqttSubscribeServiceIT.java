package com.tritonsvc.messageprocessor.mqtt;

import com.tritonsvc.messageprocessor.SpaGatewayMessageProcessorApplication;
import com.tritonsvc.messageprocessor.UnitTestHelper;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.SpringApplicationConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

/**
 * Purpose of this test is to make sure that all three messages are processed. At some point I had issue with
 * future connection that is used right now and I was able to process only one message - next messages were
 * ignored or skipped.
 * Created by holow on 4/27/2016.
 */
@RunWith(SpringJUnit4ClassRunner.class)
@SpringApplicationConfiguration(classes = {SpaGatewayMessageProcessorApplication.class, UnitTestHelper.class})
public class MqttSubscribeServiceIT {

    @Autowired
    private MqttSubscribeService mqttSubscribeService;

    @Autowired
    private MqttSendService mqttSendService;

    @Test
    public void testSendSubscribe() throws Exception {
        final CountingMessageListener countingMessageListener = new CountingMessageListener();
        mqttSubscribeService.subscribe("/random", countingMessageListener);

        Thread.sleep(5000);

        mqttSendService.sendMessage("/random", new byte[]{0, 1, 2, 3});
        mqttSendService.sendMessage("/random", new byte[]{0, 1, 2, 3});
        mqttSendService.sendMessage("/random", new byte[]{0, 1, 2, 3});

        Thread.sleep(5000);

        Assert.assertEquals(3, countingMessageListener.getCount());
    }

    private class CountingMessageListener implements MessageListener {

        private int count = 0;

        @Override
        public void processMessage(byte[] payload) {
            count++;
        }

        public int getCount() {
            return count;
        }
    }
}
