package com.tritonsvc.agent;

import com.tritonsvc.model.AgentSettings;
import com.tritonsvc.model.Ethernet;
import com.tritonsvc.model.GenericSettings;
import com.tritonsvc.model.NetworkSettings;
import com.tritonsvc.model.Wifi;
import org.apache.commons.io.FileUtils;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;

import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;

public class SettingsPersisterTest {
    private AgentSettingsPersister persister;
    private File ethFile;
    private File wpaFile;

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Before
    public void setUp() throws Exception {
        persister = spy(new AgentSettingsPersister());
        ethFile = new File(folder.getRoot(), "test1");
        wpaFile = new File(folder.getRoot(), "test2");
        FileUtils.copyInputStreamToFile(SettingsPersisterTest.class.getResourceAsStream("/interfaces"), ethFile);
        FileUtils.copyInputStreamToFile(SettingsPersisterTest.class.getResourceAsStream("/wpa_supplicant.conf"), wpaFile);
        doReturn(ethFile).when(persister).getSystemFile(eq("/etc/network/interfaces"));
        doReturn(wpaFile).when(persister).getSystemFile(eq("/etc/wpa_supplicant/wpa_supplicant.conf"));
        doReturn(mock(Process.class)).when(persister).executeUnixCommand(any());
    }

    @Test
    public void testLoadSave() throws IOException {
        final AgentSettings agentSettings = new AgentSettings();
        final NetworkSettings networkSettings = new NetworkSettings();
        agentSettings.setNetworkSettings(networkSettings);
        final Wifi wifi = new Wifi();
        networkSettings.setWifi(wifi);
        wifi.setPassword("passwd");
        wifi.setSsid("ssid1");

        final Ethernet ethernet = new Ethernet();
        networkSettings.setEthernet(ethernet);
        ethernet.setDhcp(false);
        ethernet.setIpAddress("1.1.1.1");
        ethernet.setGateway("1.1.1.3");
        ethernet.setNetmask("1.1.1.4");

        final GenericSettings genericSettings = new GenericSettings();
        agentSettings.setGenericSettings(genericSettings);
        genericSettings.setUpdateInterval(12);

        final File agentSettingsFile = folder.newFile("agentSettings.properties");
        persister.save(agentSettingsFile, agentSettings, "standard", "eth0", folder.getRoot().getAbsolutePath(), "wlan0", networkSettings);

        final AgentSettings loaded = persister.load(agentSettingsFile, "standard","eth0","wlan0");

        Assert.assertEquals(agentSettings.getNetworkSettings().getWifi().getSsid(), loaded.getNetworkSettings().getWifi().getSsid());

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
        persister.save(agentSettingsFile, agentSettings, null, null, null, null, null);

        final AgentSettings loaded = persister.load(agentSettingsFile, "standard", "eth0", "wlan0");
        Assert.assertNotNull(loaded.getGenericSettings().getUpdateInterval());
        Assert.assertEquals(agentSettings.getGenericSettings().getUpdateInterval(), loaded.getGenericSettings().getUpdateInterval());

        loaded.getGenericSettings().setUpdateInterval(null);
        persister.save(agentSettingsFile, loaded, null, null, null, null, null);

        final AgentSettings reloaded = persister.load(agentSettingsFile, "standard", "eth0", "wlan0");
        Assert.assertNull(reloaded.getGenericSettings().getUpdateInterval());
    }
}
