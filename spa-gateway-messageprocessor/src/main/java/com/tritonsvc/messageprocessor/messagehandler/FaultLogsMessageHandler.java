package com.tritonsvc.messageprocessor.messagehandler;

import com.bwg.iot.model.FaultLog;
import com.bwg.iot.model.FaultLogDescription;
import com.bwg.iot.model.Spa;
import com.tritonsvc.messageprocessor.mongo.repository.FaultLogDescriptionRepository;
import com.tritonsvc.messageprocessor.mongo.repository.FaultLogRepository;
import com.tritonsvc.messageprocessor.mongo.repository.SpaRepository;
import com.tritonsvc.spa.communication.proto.Bwg;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * process fault logs from spa systems
 */
@Component
public class FaultLogsMessageHandler extends AbstractMessageHandler<Bwg.Uplink.Model.FaultLogs> {

    private static final Logger log = LoggerFactory.getLogger(FaultLogsMessageHandler.class);

    private final Map<String, FaultLogDescription> cache = new HashMap<>();

    @Autowired
    private SpaRepository spaRepository;

    @Autowired
    private FaultLogRepository faultLogRepository;

    @Autowired
    private FaultLogDescriptionRepository faultLogDescriptionRepository;

    @Override
    public Class<Bwg.Uplink.Model.FaultLogs> handles() {
        return Bwg.Uplink.Model.FaultLogs.class;
    }

    @Override
    public void processMessage(final Bwg.Header header, final Bwg.Uplink.UplinkHeader uplinkHeader, final Bwg.Uplink.Model.FaultLogs faultLogs) {
        log.info("Processing fault logs from originator {}, with hw id {}", header.getOriginator(), uplinkHeader.getHardwareId());

        final String spaId = uplinkHeader.getHardwareId();
        final Spa spa = spaRepository.findOne(spaId);
        if (spa == null) {
            log.error("Received fault logs for unknown spa: {}", spaId);
            return;
        }

        if (faultLogs.getFaultLogsCount() > 0) {
            final String controllerType = spa.getCurrentState() != null && spa.getCurrentState().getControllerType() != null ?
                    spa.getCurrentState().getControllerType() : "NGSC";

            for (final Bwg.Uplink.Model.FaultLog faultLog : faultLogs.getFaultLogsList()) {

                final int code = faultLog.getFaultCode();
                log.debug("Processing fault log with code {} for spa {}", code, spaId);

                // check if db contains already entry like this
                FaultLog faultLogEntity = faultLogRepository.findFirstBySpaIdAndCodeAndTimestamp(spaId, code, new Date(faultLog.getOccurenceDate()));
                if (faultLogEntity == null) {
                    faultLogEntity = createFaultLogEntity(spaId, controllerType, code, spa, faultLog);
                    faultLogRepository.save(faultLogEntity);
                }
            }
        }
    }

    private FaultLog createFaultLogEntity(final String spaId, final String controllerType, final int code,
                                          final Spa spa, final Bwg.Uplink.Model.FaultLog faultLog) {

        final String cacheKey = controllerType + code;
        FaultLogDescription description = cache.get(cacheKey);
        if (description == null && !cache.containsKey(cacheKey)) {
            description = faultLogDescriptionRepository.findFirstByCodeAndControllerType(code, controllerType);
            cache.put(cacheKey, description);
        }

        final FaultLog faultLogEntity = new FaultLog();
        faultLogEntity.setSpaId(spaId);
        faultLogEntity.setControllerType(controllerType);
        faultLogEntity.setOwnerId(spa.getOwner() != null ? spa.getOwner().get_id() : null);
        faultLogEntity.setDealerId(spa.getDealerId());
        faultLogEntity.setOemId(spa.getDealerId());
        faultLogEntity.setCode(code);
        faultLogEntity.setTimestamp(new Date(faultLog.getOccurenceDate()));
        faultLogEntity.setSeverity(description != null ? description.getSeverity() : null);
        faultLogEntity.setTargetTemp(faultLog.getTargetTemp());
        faultLogEntity.setSensorATemp(faultLog.getSensorATemp());
        faultLogEntity.setSensorBTemp(faultLog.getSensorBTemp());
        faultLogEntity.setCelcius(faultLog.getCelcius());

        return faultLogEntity;
    }
}
