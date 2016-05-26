package com.tritonsvc.wifi;

import com.tritonsvc.spa.communication.proto.Bwg.Uplink.Model.Constants.WifiConnectionHealth;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.Model.WifiStat;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.Model.WifiStat.WifiConnectionDiagnostics;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

public class ParserIwconfigTest {

    private ParserIwconfig parser;
    private Process unixProcess;

    @Before
    public void setUp() throws IOException {
        parser = new ParserIwconfig();
        parser = spy(parser);
        unixProcess = mock(Process.class);
        doReturn(unixProcess).when(parser).executeUnixCommand(eq("wlan0"), eq("iwconfig"));
     }

    @Test
    public void itParsesConnectedWifi() throws Exception {
        when(unixProcess.getInputStream()).thenReturn(ParserIwconfigTest.class.getResourceAsStream("/connectedIwConfig.txt"));

        WifiConnectionDiagnostics diag = WifiConnectionDiagnostics.newBuilder()
                .setDataRate(1)
                .setLinkQualityPercentage(1)
                .setLostBeaconCount(1)
                .setRxInvalidCryptPacketCount(1)
                .setRxInvalidFragPacketCount(1)
                .setRxOtherAPPacketCount(1)
                .setSignalLevelUnits(-3)
                .setTxExcessiveRetries(1)
                .setNoiseLevel(-3)
                .build();

        WifiStat prev = WifiStat.newBuilder()
                .setConnectedDiag(diag)
                .setRecordedDate(2)
                .setWifiConnectionHealth(WifiConnectionHealth.AVG)
                .build();

        WifiStat newStat = parser.parseStat("wlan0", prev, "iwconfig");

        assertEquals(newStat.getApMacAddress(), "00:24:17:44:35:28");
        assertEquals(newStat.getWifiConnectionHealth(), WifiConnectionHealth.WEAK);
        assertEquals(newStat.getRetryLimitPhraseConfig(), "retry limit");
        assertEquals(newStat.getRetryLimitValueConfig(), "231");

        assertTrue(newStat.getConnectedDiag().getDataRate() == 2);
        assertTrue(newStat.getConnectedDiag().getLinkQualityPercentage() == 2);
        assertTrue(newStat.getConnectedDiag().getLostBeaconCount() == 0);
        assertTrue(newStat.getConnectedDiag().getRxInvalidCryptPacketCount() == 2);
        assertTrue(newStat.getConnectedDiag().getRxInvalidFragPacketCount() == 2);
        assertTrue(newStat.getConnectedDiag().getRxOtherAPPacketCount() == 2);
        assertTrue(newStat.getConnectedDiag().getSignalLevelUnits() == -2);
        assertTrue(newStat.getConnectedDiag().getTxExcessiveRetries() == 2);
        assertTrue(newStat.getConnectedDiag().getNoiseLevel() == -2);

        assertTrue(newStat.getConnectedDiag().getDeltaDataRate() == 1);
        assertTrue(newStat.getConnectedDiag().getDeltaLinkQualityPercentage() == 1);
        assertTrue(newStat.getConnectedDiag().getDeltaLostBeaconCount() == -1);
        assertTrue(newStat.getConnectedDiag().getDeltaRxInvalidCryptPacketCount() == 1);
        assertTrue(newStat.getConnectedDiag().getDeltaRxInvalidFragPacketCount() == 1);
        assertTrue(newStat.getConnectedDiag().getDeltaRxOtherAPPacketCount() == 1);
        assertTrue(newStat.getConnectedDiag().getDeltaSignalLevelUnits() == 1);
        assertTrue(newStat.getConnectedDiag().getDeltaTxExcessiveRetries() == 1);
        assertTrue(newStat.getConnectedDiag().getDeltaNoiseLevel() == 1);
    }

    @Test
    public void itParsesDisConnectedWifi() throws Exception {
        when(unixProcess.getInputStream()).thenReturn(ParserIwconfigTest.class.getResourceAsStream("/disconnectedIwConfig.txt"));

        WifiStat newStat = parser.parseStat("wlan0", null, "iwconfig");
        assertFalse(newStat.hasApMacAddress());
        assertEquals(newStat.getWifiConnectionHealth(), WifiConnectionHealth.DISCONNECTED);

        assertFalse(newStat.getConnectedDiag().hasDataRate());
        assertFalse(newStat.getConnectedDiag().hasLinkQualityPercentage());
        assertFalse(newStat.getConnectedDiag().hasLostBeaconCount());
        assertFalse(newStat.getConnectedDiag().hasRxInvalidCryptPacketCount());
        assertFalse(newStat.getConnectedDiag().hasRxInvalidFragPacketCount());
        assertFalse(newStat.getConnectedDiag().hasRxOtherAPPacketCount());
        assertFalse(newStat.getConnectedDiag().hasSignalLevelUnits());
        assertFalse(newStat.getConnectedDiag().hasTxExcessiveRetries());

        assertFalse(newStat.getConnectedDiag().hasDeltaDataRate());
        assertFalse(newStat.getConnectedDiag().hasDeltaLinkQualityPercentage());
        assertFalse(newStat.getConnectedDiag().hasDeltaLostBeaconCount());
        assertFalse(newStat.getConnectedDiag().hasDeltaRxInvalidCryptPacketCount());
        assertFalse(newStat.getConnectedDiag().hasDeltaRxInvalidFragPacketCount());
        assertFalse(newStat.getConnectedDiag().hasDeltaRxOtherAPPacketCount());
        assertFalse(newStat.getConnectedDiag().hasDeltaSignalLevelUnits());
        assertFalse(newStat.getConnectedDiag().hasDeltaTxExcessiveRetries());
    }
}
