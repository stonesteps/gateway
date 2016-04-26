package com.tritonsvc.httpd;

import com.tritonsvc.httpd.model.*;
import com.tritonsvc.httpd.util.AgentSettingsPersister;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;

/**
 * Created by holow on 4/14/2016.
 */
public class SettingsPersisterTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void testLoadSave() throws IOException {
        final AgentSettings agentSettings = new AgentSettings();
        final NetworkSettings networkSettings = new NetworkSettings();
        agentSettings.setNetworkSettings(networkSettings);
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

        final GenericSettings genericSettings = new GenericSettings();
        agentSettings.setGenericSettings(genericSettings);
        genericSettings.setUpdateInterval(12);

        final File agentSettingsFile = folder.newFile("agentSettings.properties");
        AgentSettingsPersister.save(agentSettingsFile, agentSettings);

        final AgentSettings loaded = AgentSettingsPersister.load(agentSettingsFile);

        Assert.assertEquals(agentSettings.getNetworkSettings().getWifi().getPassword(), loaded.getNetworkSettings().getWifi().getPassword());
        Assert.assertEquals(agentSettings.getNetworkSettings().getWifi().getSecurity(), loaded.getNetworkSettings().getWifi().getSecurity());
        Assert.assertEquals(agentSettings.getNetworkSettings().getWifi().getSsid(), loaded.getNetworkSettings().getWifi().getSsid());

        Assert.assertEquals(agentSettings.getNetworkSettings().getEthernet().getDnsServer(), loaded.getNetworkSettings().getEthernet().getDnsServer());
        Assert.assertEquals(agentSettings.getNetworkSettings().getEthernet().getGateway(), loaded.getNetworkSettings().getEthernet().getGateway());
        Assert.assertEquals(agentSettings.getNetworkSettings().getEthernet().getIpAddress(), loaded.getNetworkSettings().getEthernet().getIpAddress());
        Assert.assertEquals(agentSettings.getNetworkSettings().getEthernet().getNetmask(), loaded.getNetworkSettings().getEthernet().getNetmask());
        Assert.assertEquals(agentSettings.getNetworkSettings().getEthernet().isDhcp(), loaded.getNetworkSettings().getEthernet().isDhcp());

        Assert.assertNotNull(loaded.getGenericSettings().getUpdateInterval());
        Assert.assertEquals(agentSettings.getGenericSettings().getUpdateInterval(), loaded.getGenericSettings().getUpdateInterval());
    }

    @Test
    public void testNullUpdateIntervalSettings() throws IOException {
        final AgentSettings agentSettings = new AgentSettings();
        final GenericSettings genericSettings = new GenericSettings();
        agentSettings.setGenericSettings(genericSettings);
        genericSettings.setUpdateInterval(12);

        final File agentSettingsFile = folder.newFile("agentSettings.properties");
        AgentSettingsPersister.save(agentSettingsFile, agentSettings);

        final AgentSettings loaded = AgentSettingsPersister.load(agentSettingsFile);
        Assert.assertNotNull(loaded.getGenericSettings().getUpdateInterval());
        Assert.assertEquals(agentSettings.getGenericSettings().getUpdateInterval(), loaded.getGenericSettings().getUpdateInterval());

        loaded.getGenericSettings().setUpdateInterval(null);
        AgentSettingsPersister.save(agentSettingsFile, loaded);

        final AgentSettings reloaded = AgentSettingsPersister.load(agentSettingsFile);
        Assert.assertNull(reloaded.getGenericSettings().getUpdateInterval());
    }
}
