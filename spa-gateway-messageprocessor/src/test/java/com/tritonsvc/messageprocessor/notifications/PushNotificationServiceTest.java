package com.tritonsvc.messageprocessor.notifications;

import com.bwg.iot.model.Alert;
import org.junit.Test;
import org.mockito.Mockito;

/**
 * Created by holow on 18.10.2016.
 */
public class PushNotificationServiceTest {

    @Test
    public void testPushNotificationService() throws Exception {
        final ApnsSenderBuilder builder = Mockito.mock(ApnsSenderBuilder.class);
        final ApnsSender sender = Mockito.mock(ApnsSender.class);

        Mockito.when(builder.build()).thenReturn(sender);

        final PushNotificationService service = new PushNotificationService();
        service.setApnsSenderBuilder(builder);

        final Alert alert = new Alert();
        alert.setShortDescription("Water too hot");
        alert.setSeverityLevel("ERROR");
        service.pushApnsAlertNotification("token", alert);

        Mockito.verify(sender).pushPayload("token", "{\"aps\":{\"alert\":\"Water too hot\",\"sound\":\"default\",\"category\":\"SPA ALERT\"}}");
    }

}
