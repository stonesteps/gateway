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
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.mapreduce.MapReduceResults;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * process fault logs from spa systems
 */
@Component
public class FaultLogsMessageHandler extends AbstractMessageHandler<Bwg.Uplink.Model.FaultLogs> {

    private static final Logger log = LoggerFactory.getLogger(FaultLogsMessageHandler.class);
    private static final String ALERT_NAME_FAULT_LOG = "Fault Log";

    private final static String ALERT_AGGREGATE_MAP_FUNCTION = "function () { emit(this.severityLevel, 1); }";
    private final static String ALERT_AGGREGATE_REDUCE_FUNCTION = "function (key, values) { var sum = 0; for (var i = 0; i < values.length; i++) sum += values[i]; return sum; }";

    private final Map<String, FaultLogDescription> cache = new HashMap<>();

    @Autowired
    private MongoTemplate mongoTemplate;

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

                    //TODO - enable alerts once alert clearing feature is added to system
                    //mapFaultLogToAlert(spa, faultLogEntity);
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
        if (olderThanThreeDays(faultLog.getTimestamp())) {
            log.info("Skipping alert creation - FaultLog entry is older than three days.");
            return;
        }
        final FaultLogDescription faultLogDescription = faultLog.getFaultLogDescription();
        final String severityLevel = getSeverityLevel(faultLog.getSeverity());
        if (severityLevel != null) {
            final Alert alert = new Alert();
            alert.setCreationDate(faultLog.getTimestamp());
            alert.setSpaId(faultLog.getSpaId());
            alert.setDealerId(faultLog.getDealerId());
            alert.setOemId(faultLog.getOemId());
            alert.setName(ALERT_NAME_FAULT_LOG);
            alert.setComponent(com.bwg.iot.model.Component.ComponentType.CONTROLLER.name());
            alert.setSeverityLevel(severityLevel);
            alert.setLongDescription(faultLogDescription != null ? faultLogDescription.getDescription() : null);
            alert.setShortDescription(faultLogDescription != null ? faultLogDescription.getDescription() : null);
            alertRepository.save(alert);

            if (spa != null) {
                if (spa.getAlerts() == null) {
                    spa.setAlerts(new ArrayList<>());
                }
                spa.getAlerts().add(alert);
                updateSpaAlertState(spa, alert);
                spaRepository.save(spa);
            }
        }
    }

    /**
     * Returns true if given date is older than 3 days.
     * @param date
     * @return
     */
    private boolean olderThanThreeDays(final Date date) {
        final Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DAY_OF_YEAR, -3);
        final Date threeDaysAgo = cal.getTime();

        return date.before(threeDaysAgo);
    }

    private String getSeverityLevel(final FaultLogSeverity severity) {
        switch (severity) {
            case FATAL:
                return Alert.SeverityLevelEnum.SEVERE.name();
            case ERROR:
                return Alert.SeverityLevelEnum.ERROR.name();
            case WARNING:
                return Alert.SeverityLevelEnum.WARNING.name();
            case INFO:
                return Alert.SeverityLevelEnum.INFO.name();
            default:
                return null;
        }
    }

    private void updateSpaAlertState(final Spa spa, final Alert alert) {
        final String highestActiveAlertSeverity = findHighestActiveAlertSeverityForSpa(alert.getSpaId());
        if (spa != null && spa.getCurrentState() != null) {
            if (!Objects.equals(spa.getCurrentState().getAlertState(), highestActiveAlertSeverity)) {
                spa.getCurrentState().setAlertState(highestActiveAlertSeverity);
            }
            if (spa.getCurrentState().getComponents() != null && spa.getCurrentState().getComponents().size() > 0) {
                final String highestActiveAlertSeverityForComponent = findHighestActiveAlertSeverityForSpaAndComponentAndPortNo(alert.getSpaId(), alert.getComponent(),alert.getPortNo());
                for (final ComponentState componentState: spa.getCurrentState().getComponents()) {
                    if (Objects.equals(componentState.getComponentType(), alert.getComponent())) {
                        componentState.setAlertState(highestActiveAlertSeverityForComponent);
                    }
                }
            }
        }
    }

    private String findHighestActiveAlertSeverityForSpa(final String spaId) {
        final Query query = Query.query(Criteria.where("spaId").is(spaId)).addCriteria(Criteria.where("clearedDate").is(null));
        final MapReduceResults<ValueObject> results = mongoTemplate.mapReduce(query, "alert", ALERT_AGGREGATE_MAP_FUNCTION, ALERT_AGGREGATE_REDUCE_FUNCTION, ValueObject.class);

        return getHighestAlertSeverity(results);
    }

    private String findHighestActiveAlertSeverityForSpaAndComponentAndPortNo(final String spaId, final String component, final Integer portNo) {
        final Query query = Query.query(Criteria.where("spaId").is(spaId)).addCriteria(Criteria.where("component").is(component)).addCriteria(Criteria.where("clearedDate").is(null));
        if (portNo != null) query.addCriteria(Criteria.where("portNo").is(portNo));

        final MapReduceResults<ValueObject> results = mongoTemplate.mapReduce(query, "alert", ALERT_AGGREGATE_MAP_FUNCTION, ALERT_AGGREGATE_REDUCE_FUNCTION, ValueObject.class);

        return getHighestAlertSeverity(results);
    }

    private String getHighestAlertSeverity(final MapReduceResults<ValueObject> results) {
        final Map<String, Integer> resultMap = new HashMap<>();
        for (final ValueObject valueObject: results) {
            resultMap.put(valueObject.getId(), valueObject.getValue());
        }

        Integer alertCount = resultMap.get(Alert.SeverityLevelEnum.SEVERE.name());
        if (alertCount != null && alertCount.intValue() > 0) return Alert.SeverityLevelEnum.SEVERE.name();

        alertCount = resultMap.get(Alert.SeverityLevelEnum.ERROR.name());
        if (alertCount != null && alertCount.intValue() > 0) return Alert.SeverityLevelEnum.ERROR.name();

        alertCount = resultMap.get(Alert.SeverityLevelEnum.WARNING.name());
        if (alertCount != null && alertCount.intValue() > 0) return Alert.SeverityLevelEnum.WARNING.name();

        alertCount = resultMap.get(Alert.SeverityLevelEnum.INFO.name());
        if (alertCount != null && alertCount.intValue() > 0) return Alert.SeverityLevelEnum.INFO.name();

        return Alert.SeverityLevelEnum.NONE.name();
    }

    /**
     * Class used just for aggregate map reduce results.
     */
    private static final class ValueObject {
        private String id;
        private int value;

        public int getValue() {
            return value;
        }

        public void setValue(int value) {
            this.value = value;
        }

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }
    }
}
