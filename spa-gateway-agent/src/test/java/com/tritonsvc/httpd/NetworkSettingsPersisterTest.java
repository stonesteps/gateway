package com.tritonsvc.httpd;

import com.tritonsvc.httpd.model.Ethernet;
import com.tritonsvc.httpd.model.NetworkSettings;
import com.tritonsvc.httpd.model.Wifi;
import com.tritonsvc.httpd.model.WifiSecurity;
import com.tritonsvc.httpd.util.SettingsPersister;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;

/**
 * Created by holow on 4/14/2016.
 */
public class NetworkSettingsPersisterTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void testLoadSave() throws IOException {
        final NetworkSettings networkSettings = new NetworkSettings();
        final Wifi wifi = new Wifi();
        networkSettings.setWifi(wifi);
        wifi.setPassword("passwd");
        wifi.setSsid("ssid1");
        wifi.setSecurity(WifiSecurity.WPA2);

        final Ethernet ethernet = new Ethernet();
        networkSettings.setEthernet(ethernet);
        ethernet.setDhcp(true);
        ethernet.setIpAddress("1.1.1.1");
        ethernet.setDnsServer("1.1.1.2");
        ethernet.setGateway("1.1.1.3");
        ethernet.setNetmask("1.1.1.4");

        final File networkSettingsFile = folder.newFile("networkSettings.properties");
        SettingsPersister.save(networkSettingsFile, networkSettings);

        final NetworkSettings loaded = SettingsPersister.load(networkSettingsFile);

        Assert.assertEquals(networkSettings.getWifi().getPassword(), loaded.getWifi().getPassword());
        Assert.assertEquals(networkSettings.getWifi().getSecurity(), loaded.getWifi().getSecurity());
        Assert.assertEquals(networkSettings.getWifi().getSsid(), loaded.getWifi().getSsid());

        Assert.assertEquals(networkSettings.getEthernet().getDnsServer(), loaded.getEthernet().getDnsServer());
        Assert.assertEquals(networkSettings.getEthernet().getGateway(), loaded.getEthernet().getGateway());
        Assert.assertEquals(networkSettings.getEthernet().getIpAddress(), loaded.getEthernet().getIpAddress());
        Assert.assertEquals(networkSettings.getEthernet().getNetmask(), loaded.getEthernet().getNetmask());
        Assert.assertEquals(networkSettings.getEthernet().isDhcp(), loaded.getEthernet().isDhcp());
    }
}
