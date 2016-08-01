package com.tritonsvc.messageprocessor.messagehandler;

import com.bwg.iot.model.*;
import com.tritonsvc.messageprocessor.mongo.repository.AlertRepository;
import com.tritonsvc.messageprocessor.mongo.repository.FaultLogDescriptionRepository;
import com.tritonsvc.messageprocessor.mongo.repository.FaultLogRepository;
import com.tritonsvc.messageprocessor.mongo.repository.SpaRepository;
import com.tritonsvc.spa.communication.proto.Bwg;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * process fault logs from spa systems
 */
@Component
public class FaultLogsMessageHandler extends AbstractMessageHandler<Bwg.Uplink.Model.FaultLogs> {

    private static final Logger log = LoggerFactory.getLogger(FaultLogsMessageHandler.class);
    private static final String ALERT_NAME_FAULT_LOG = "Fault Log";
    private static final String ALERT_COMPONENT_CONTROLLNER = "Controller";

    private final Map<String, FaultLogDescription> cache = new HashMap<>();

    @Autowired
    private SpaRepository spaRepository;

    @Autowired
    private FaultLogRepository faultLogRepository;

    @Autowired
    private FaultLogDescriptionRepository faultLogDescriptionRepository;

    @Autowired
    private AlertRepository alertRepository;

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
                Date occurDate = new Date(faultLog.getOccurenceDate());

                // check if db contains already entry like this
                FaultLog faultLogEntity = faultLogRepository.findFirstBySpaIdAndCodeAndTimestamp(spaId, code, occurDate);
                if (faultLogEntity == null) {
                    faultLogEntity = createFaultLogEntity(spaId, controllerType, code, spa, faultLog);
                    faultLogRepository.save(faultLogEntity);
                    log.info("Saved new fault log with code {} for spa {} and occur date {}", code, spaId, occurDate);

                    mapFaultLogToAlert(spa, faultLogEntity);
                } else {
                    log.info("Skipped fault log with code {} for spa {} and occur date {}, was a duplicate", code, spaId, occurDate);
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
        faultLogEntity.setFaultLogDescription(description);

        return faultLogEntity;
    }

    private void mapFaultLogToAlert(final Spa spa, final FaultLog faultLog) {
        final FaultLogDescription faultLogDescription = faultLog.getFaultLogDescription();
        final String severityLevel = getSeverityLevel(faultLog.getSeverity());
        if (severityLevel != null) {
            final Alert alert = new Alert();
            alert.setCreationDate(faultLog.getTimestamp());
            alert.setSpaId(faultLog.getSpaId());
            alert.setDealerId(faultLog.getDealerId());
            alert.setOemId(faultLog.getOemId());
            alert.setName(ALERT_NAME_FAULT_LOG);
            alert.setComponent(ALERT_COMPONENT_CONTROLLNER);
            alert.setSeverityLevel(severityLevel);
            alert.setLongDescription(faultLogDescription != null ? faultLogDescription.getDescription() : null);
            alert.setShortDescription(faultLogDescription != null ? faultLogDescription.getDescription() : null);
            alertRepository.save(alert);

            if (spa != null) {
                if (spa.getAlerts() == null) {
                    spa.setAlerts(new ArrayList<>());
                }
                spa.getAlerts().add(alert);
                spaRepository.save(spa);
            }
        }
    }

    private String getSeverityLevel(final FaultLogSeverity severity) {
        switch (severity) {
            case ERROR:
            case FATAL:
                return Alert.SeverityLevelEnum.red.name();
            case WARNING:
                return Alert.SeverityLevelEnum.yellow.name();
            default:
                return null;
        }
    }
}
