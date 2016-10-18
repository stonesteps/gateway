package com.tritonsvc.messageprocessor.notifications;

import com.notnoop.apns.APNS;
import com.notnoop.exceptions.NetworkIOException;
import org.junit.Assert;
import org.junit.Test;

import java.util.Date;
import java.util.Map;

/**
 * Created by holow on 17.10.2016.
 */
public class ApnsSenderIT {

    private static final String DEVICE_TOKEN = "e43e9d6fc0dc5581ccb38d34bb6fc85b7713433a4efbbef611da2f0717bb127d";
    private static final String CERT_PATH = "/ControlMySpa.p12";
    private static final String CERT_PASSWORD = "SpaOwner1.0";
    private static final boolean USE_PROD = false;

    @Test
    public void testPush() throws Exception {
        final NotnoopApnsSender apnsSender = new NotnoopApnsSender(CERT_PATH, CERT_PASSWORD, USE_PROD);

        final String payload = APNS.newPayload().alertBody("Water too hot").category("SPA ALERT").sound("default").build();

        try {
            apnsSender.pushPayload(DEVICE_TOKEN, payload);
        } catch (final NetworkIOException e) {
            Assert.fail("network io exception");
        }

        Thread.sleep(5999);

        // device is already inactive
        final Map<String, Date> inactiveDevices = apnsSender.getInactiveDevices();
        Assert.assertTrue(inactiveDevices != null && inactiveDevices.size() == 1);
    }
}
