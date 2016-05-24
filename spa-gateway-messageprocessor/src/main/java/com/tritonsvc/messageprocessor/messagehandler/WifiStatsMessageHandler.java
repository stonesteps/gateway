package com.tritonsvc.messageprocessor.messagehandler;

import com.bwg.iot.model.*;
import com.tritonsvc.messageprocessor.mongo.repository.FaultLogDescriptionRepository;
import com.tritonsvc.messageprocessor.mongo.repository.SpaRepository;
import com.tritonsvc.messageprocessor.mongo.repository.WifiStatRepository;
import com.tritonsvc.spa.communication.proto.Bwg;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * process wifi stats from spa systems
 */
@Component
public class WifiStatsMessageHandler extends AbstractMessageHandler<Bwg.Uplink.Model.WifiStats> {

    private static final Logger log = LoggerFactory.getLogger(WifiStatsMessageHandler.class);

    private final Map<String, FaultLogDescription> cache = new HashMap<>();

    @Autowired
    private SpaRepository spaRepository;

    @Autowired
    private WifiStatRepository wifiStatRepository;

    @Autowired
    private FaultLogDescriptionRepository faultLogDescriptionRepository;

    @Override
    public Class<Bwg.Uplink.Model.WifiStats> handles() {
        return Bwg.Uplink.Model.WifiStats.class;
    }

    @Override
    public void processMessage(final Bwg.Header header, final Bwg.Uplink.UplinkHeader uplinkHeader, final Bwg.Uplink.Model.WifiStats wifiStats) {
        log.info("Processing wifi stats from originator {}, with hw id {}", header.getOriginator(), uplinkHeader.getHardwareId());

        final String spaId = uplinkHeader.getHardwareId();
        final Spa spa = spaRepository.findOne(spaId);
        if (spa == null) {
            log.error("Received fault logs for unknown spa: {}", spaId);
            return;
        }

        if (wifiStats.getWifiStatsCount() > 0) {
            final List<WifiStat> statsEntities = new ArrayList<>(wifiStats.getWifiStatsCount());
            for (final Bwg.Uplink.Model.WifiStat wifiStat : wifiStats.getWifiStatsList()) {
                statsEntities.add(processWifiStat(spa, wifiStat));
            }
            wifiStatRepository.save(statsEntities);
        }
    }

    private WifiStat processWifiStat(final Spa spa, final Bwg.Uplink.Model.WifiStat wifiStat) {
        final WifiStat statEntity = new WifiStat();

        statEntity.setSpaId(spa.get_id());
        statEntity.setOwnerId(spa.getOwner() != null ? spa.getOwner().get_id() : null);
        statEntity.setDealerId(spa.getDealerId());
        statEntity.setOemId(spa.getOemId());

        if (wifiStat.hasRecordedDate())
            statEntity.setRecordedDate(new Date(wifiStat.getRecordedDate()));
        if (wifiStat.hasElapsedDeltaMilliseconds())
            statEntity.setElapsedDeltaMilliseconds(wifiStat.getElapsedDeltaMilliseconds());
        statEntity.setWifiConnectionHealth(wifiStat.getWifiConnectionHealth() != null ? WifiConnectionHealth.toEnum(wifiStat.getWifiConnectionHealth().getNumber()) : null);
        if (wifiStat.hasTxPowerDbm())
            statEntity.setTxPowerDbm(wifiStat.getTxPowerDbm());
        if (wifiStat.hasApMacAddress())
            statEntity.setApMacAddress(wifiStat.getApMacAddress());
        if (wifiStat.hasMode())
            statEntity.setMode(wifiStat.getMode());
        if (wifiStat.hasRetryLimitPhraseConfig())
            statEntity.setRetryLimitPhraseConfig(wifiStat.getRetryLimitPhraseConfig());
        if (wifiStat.hasRetryLimitValueConfig())
            statEntity.setRetryLimitValueConfig(wifiStat.getRetryLimitValueConfig());
        if (wifiStat.hasRtsConfig())
            statEntity.setRtsConfig(wifiStat.getRtsConfig());
        if (wifiStat.hasFragConfig())
            statEntity.setFragConfig(wifiStat.getFragConfig());
        if (wifiStat.hasPowerMgmtConfig())
            statEntity.setPowerMgmtConfig(wifiStat.getPowerMgmtConfig());
        if (wifiStat.hasSSID())
            statEntity.setSSID(wifiStat.getSSID());
        if (wifiStat.hasConnectedDiag())
            statEntity.setConnectedDiag(getConnectedDiag(wifiStat.getConnectedDiag()));

        return statEntity;
    }

    private WifiConnectionDiagnostics getConnectedDiag(final Bwg.Uplink.Model.WifiStat.WifiConnectionDiagnostics connectedDiag) {
        final WifiConnectionDiagnostics connectionDiag = new WifiConnectionDiagnostics();

        if (connectedDiag.hasFrequency())
            connectionDiag.setFrequency(connectedDiag.getFrequency());
        if (connectedDiag.hasRawDataRate())
            connectionDiag.setRawDataRate(connectedDiag.getRawDataRate());
        if (connectedDiag.hasDataRate())
            connectionDiag.setDataRate(connectedDiag.getDataRate());
        if (connectedDiag.hasDeltaDataRate())
            connectionDiag.setDeltaDataRate(connectedDiag.getDeltaDataRate());
        if (connectedDiag.hasLinkQualityPercentage())
            connectionDiag.setLinkQualityPercentage(connectedDiag.getLinkQualityPercentage());
        if (connectedDiag.hasDeltaLinkQualityPercentage())
            connectionDiag.setDeltaLinkQualityPercentage(connectedDiag.getDeltaLinkQualityPercentage());
        if (connectedDiag.hasLinkQualityRaw())
            connectionDiag.setLinkQualityRaw(connectedDiag.getLinkQualityRaw());
        if (connectedDiag.hasSignalLevelUnits())
            connectionDiag.setSignalLevelUnits(connectedDiag.getSignalLevelUnits());
        if (connectedDiag.hasDeltaSignalLevelUnits())
            connectionDiag.setDeltaSignalLevelUnits(connectedDiag.getDeltaSignalLevelUnits());
        if (connectedDiag.hasRxOtherAPPacketCount())
            connectionDiag.setRxOtherAPPacketCount(connectedDiag.getRxOtherAPPacketCount());
        if (connectedDiag.hasDeltaRxOtherAPPacketCount())
            connectionDiag.setDeltaRxOtherAPPacketCount(connectedDiag.getDeltaRxOtherAPPacketCount());
        if (connectedDiag.hasRxInvalidCryptPacketCount())
            connectionDiag.setRxInvalidCryptPacketCount(connectedDiag.getRxInvalidCryptPacketCount());
        if (connectedDiag.hasDeltaRxInvalidCryptPacketCount())
            connectionDiag.setDeltaRxInvalidCryptPacketCount(connectedDiag.getDeltaRxInvalidCryptPacketCount());
        if (connectedDiag.hasRxInvalidFragPacketCount())
            connectionDiag.setRxInvalidFragPacketCount(connectedDiag.getRxInvalidFragPacketCount());
        if (connectedDiag.hasDeltaRxInvalidFragPacketCount())
            connectionDiag.setDeltaRxInvalidFragPacketCount(connectedDiag.getDeltaRxInvalidFragPacketCount());
        if (connectedDiag.hasTxExcessiveRetries())
            connectionDiag.setTxExcessiveRetries(connectedDiag.getTxExcessiveRetries());
        if (connectedDiag.hasDeltaTxExcessiveRetries())
            connectionDiag.setDeltaTxExcessiveRetries(connectedDiag.getDeltaTxExcessiveRetries());
        if (connectedDiag.hasLostBeaconCount())
            connectionDiag.setLostBeaconCount(connectedDiag.getLostBeaconCount());
        if (connectedDiag.hasDeltaLostBeaconCount())
            connectionDiag.setDeltaLostBeaconCount(connectedDiag.getDeltaLostBeaconCount());
        if (connectedDiag.hasNoiseLevel())
            connectionDiag.setNoiseLevel(connectedDiag.getNoiseLevel());
        if (connectedDiag.hasDeltaNoiseLevel())
            connectionDiag.setDeltaNoiseLevel(connectedDiag.getDeltaNoiseLevel());

        return connectionDiag;
    }
}
