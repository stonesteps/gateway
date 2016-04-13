package com.tritonsvc.httpd;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tritonsvc.httpd.model.NetworkSettings;
import com.tritonsvc.httpd.model.RegisterUserResponse;
import com.tritonsvc.httpd.model.Wifi;
import com.tritonsvc.httpd.model.WifiSecurity;
import org.junit.*;

import javax.net.ssl.*;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.NoSuchAlgorithmException;
import java.security.cert.X509Certificate;
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
        props.setProperty("webServer.port", "8001");

        registrationInfoHolder = new BaseRegistrationInfoHolder();
        networkSettingsHolder = new BaseNetworkSettingsHolder();

        webServer = new WebServer(props, registrationInfoHolder, networkSettingsHolder);
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
        wifi.setSsid("spa001");
        wifi.setPassword("passwd");
        wifi.setSecurity(WifiSecurity.WPA2);
        networkSettings.setWifi(wifi);

        final URL url = new URL("https://localhost:8001/networkSettings");
        HttpsURLConnection conn = (HttpsURLConnection) url.openConnection();
        conn.setDoOutput(true);
        conn.setRequestMethod("POST");
        final OutputStream os = conn.getOutputStream();
        os.write(objectMapper.writeValueAsBytes(networkSettings));
        os.flush();

        final int responseCode = conn.getResponseCode();
        Assert.assertEquals(200, responseCode);

        conn = (HttpsURLConnection) url.openConnection();
        final String response = getContent(conn);

        final NetworkSettings saved = objectMapper.readValue(response, NetworkSettings.class);

        Assert.assertNotNull(saved);
        Assert.assertNotNull(saved.getWifi());
        Assert.assertNull(saved.getEthernet());
        Assert.assertEquals("spa001", saved.getWifi().getSsid());
        Assert.assertEquals("passwd", saved.getWifi().getPassword());
        Assert.assertEquals(WifiSecurity.WPA2, saved.getWifi().getSecurity());
    }

    @Test
    public void testRegisterUserToSpaNotInCloud() throws Exception {
        registrationInfoHolder.setSpaId(null);
        registrationInfoHolder.setRegKey(null);
        registrationInfoHolder.setRegUserId(null);

        final URL url = new URL("https://localhost:8001/registerUserToSpa");
        HttpsURLConnection conn = (HttpsURLConnection) url.openConnection();
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

        final URL url = new URL("https://localhost:8001/registerUserToSpa");
        HttpsURLConnection conn = (HttpsURLConnection) url.openConnection();
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

        final URL url = new URL("https://localhost:8001/registerUserToSpa");
        HttpsURLConnection conn = (HttpsURLConnection) url.openConnection();
        final String response = getContent(conn);

        final ObjectMapper objectMapper = new ObjectMapper();
        final RegisterUserResponse registerUserResponse = objectMapper.readValue(response, RegisterUserResponse.class);

        Assert.assertNotNull(registerUserResponse);
        Assert.assertFalse(registerUserResponse.isError());
        Assert.assertNull(registerUserResponse.getErrorMessage());
        Assert.assertEquals("1", registerUserResponse.getSpaId());
        Assert.assertEquals("3", registerUserResponse.getRegKey());
    }
}
