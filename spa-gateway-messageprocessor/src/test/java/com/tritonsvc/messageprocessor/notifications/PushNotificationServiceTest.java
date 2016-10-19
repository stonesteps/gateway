package com.tritonsvc.messageprocessor.notifications;

import com.bwg.iot.model.Alert;
import org.junit.Test;
import org.mockito.Mockito;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.mockito.Matchers.any;
import static org.mockito.Mockito.doAnswer;

/**
 * Created by holow on 18.10.2016.
 */
public class PushNotificationServiceTest {

    @Test
    public void testPushNotificationService() throws Exception {
        final ApnsSenderBuilder builder = Mockito.mock(ApnsSenderBuilder.class);
        final ApnsSender sender = Mockito.mock(ApnsSender.class);

        // with this, test will wait for pushPayload method to be called
        final CountDownLatch cdl = new CountDownLatch(1);
        doAnswer(invocation -> {
            cdl.countDown();
            return null;
        }).when(sender).pushPayload(any(), any());

        Mockito.when(builder.build()).thenReturn(sender);

        final PushNotificationService service = new PushNotificationService();
        service.setApnsSenderBuilder(builder);

        final Alert alert = new Alert();
        alert.setShortDescription("Water too hot");
        alert.setSeverityLevel("ERROR");
        service.pushApnsAlertNotification("token", alert);

        // wait for pushPayload method to be called
        cdl.await(5, TimeUnit.SECONDS);

        Mockito.verify(sender).pushPayload("token", "{\"aps\":{\"alert\":\"Water too hot\",\"sound\":\"default\",\"category\":\"SPA ALERT\"}}");
    }
}
