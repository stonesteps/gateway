package com.tritonsvc.httpd;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tritonsvc.model.Ethernet;
import com.tritonsvc.model.NetworkSettings;
import com.tritonsvc.model.RegisterUserResponse;
import com.tritonsvc.model.Wifi;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.util.Properties;

/**
 * Created by holow on 4/6/2016.
 */
public class WebServerTest extends WebServerTestBase {

    private static WebServer webServer;
    private static BaseRegistrationInfoHolder registrationInfoHolder;
    private static BaseNetworkSettingsHolder networkSettingsHolder;

    @BeforeClass
    public static void init() throws Exception {
        final Properties props = new Properties();
        props.setProperty("webserver.port", "8001");

        registrationInfoHolder = new BaseRegistrationInfoHolder();
        networkSettingsHolder = new BaseNetworkSettingsHolder();

        webServer = new WebServer(props, registrationInfoHolder, networkSettingsHolder, 0);
        webServer.start();
    }

    @AfterClass
    public static void cleanup() {
        webServer.stop();
    }

    @Test
    public void setAndGetNetworkSettings() throws Exception {
        final ObjectMapper objectMapper = new ObjectMapper();
        final NetworkSettings networkSettings = new NetworkSettings();
        final Wifi wifi = new Wifi();
        final Ethernet eth = new Ethernet();
        eth.setDhcp(true);
        wifi.setSsid("spa001");
        wifi.setPassword("passwd");
        networkSettings.setWifi(wifi);
        networkSettings.setEthernet(eth);

        final URL url = new URL("http://localhost:8001/networkSettings");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setDoOutput(true);
        conn.setRequestMethod("POST");
        final OutputStream os = conn.getOutputStream();
        os.write(objectMapper.writeValueAsBytes(networkSettings));
        os.flush();

        final int responseCode = conn.getResponseCode();
        Assert.assertEquals(200, responseCode);

        conn = (HttpURLConnection) url.openConnection();
        final String response = getContent(conn);

        final NetworkSettings saved = objectMapper.readValue(response, NetworkSettings.class);

        Assert.assertNotNull(saved);
        Assert.assertNotNull(saved.getWifi());
        Assert.assertNotNull(saved.getEthernet());
        Assert.assertEquals("spa001", saved.getWifi().getSsid());
        Assert.assertEquals("passwd", saved.getWifi().getPassword());
        Assert.assertEquals(true, saved.getEthernet().isDhcp());
    }

    @Test
    public void testRegisterUserToSpaNotInCloud() throws Exception {
        registrationInfoHolder.setSpaId(null);
        registrationInfoHolder.setRegKey(null);
        registrationInfoHolder.setRegUserId(null);

        final URL url = new URL("http://localhost:8001/registerUserToSpa");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        final String response = getContent(conn);

        final ObjectMapper objectMapper = new ObjectMapper();
        final RegisterUserResponse registerUserResponse = objectMapper.readValue(response, RegisterUserResponse.class);

        Assert.assertNotNull(registerUserResponse);
        Assert.assertTrue(registerUserResponse.isError());
        Assert.assertEquals("Spa not registered to cloud", registerUserResponse.getErrorMessage());
    }

    @Test
    public void testRegisterUserToSpaSpaInCloudUserRegistered() throws Exception {
        registrationInfoHolder.setSpaId("1");
        registrationInfoHolder.setRegKey("3");
        registrationInfoHolder.setRegUserId("2");
        registrationInfoHolder.setSerialNumber("4");

        final URL url = new URL("http://localhost:8001/registerUserToSpa");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        final String response = getContent(conn);

        final ObjectMapper objectMapper = new ObjectMapper();
        final RegisterUserResponse registerUserResponse = objectMapper.readValue(response, RegisterUserResponse.class);

        Assert.assertNotNull(registerUserResponse);
        Assert.assertTrue(registerUserResponse.isError());
        Assert.assertEquals("Spa already registered to User", registerUserResponse.getErrorMessage());
    }

    @Test
    public void testRegisterUserToSpaInCloudUserNotRegistered() throws Exception {
        registrationInfoHolder.setSpaId("1");
        registrationInfoHolder.setRegKey("3");
        registrationInfoHolder.setRegUserId(null);
        registrationInfoHolder.setSerialNumber("4");

        final URL url = new URL("http://localhost:8001/registerUserToSpa");
        URLConnection conn = url.openConnection();
        final String response = getContent(conn);

        final ObjectMapper objectMapper = new ObjectMapper();
        final RegisterUserResponse registerUserResponse = objectMapper.readValue(response, RegisterUserResponse.class);

        Assert.assertNotNull(registerUserResponse);
        Assert.assertFalse(registerUserResponse.isError());
        Assert.assertNull(registerUserResponse.getErrorMessage());
        Assert.assertEquals("1", registerUserResponse.getSpaId());
        Assert.assertEquals("3", registerUserResponse.getRegKey());
        Assert.assertEquals("4", registerUserResponse.getSerialNumber());
    }
}
