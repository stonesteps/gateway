package com.tritonsvc.messageprocessor.messagehandler;

import com.bwg.iot.model.Alert;
import com.bwg.iot.model.ComponentState;
import com.bwg.iot.model.Spa;
import com.tritonsvc.messageprocessor.UplinkProcessor;
import com.tritonsvc.spa.communication.proto.Bwg;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.mapreduce.MapReduceResults;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

import javax.annotation.PostConstruct;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Created by holow on 2/22/2016.
 */
public abstract class AbstractMessageHandler<T> implements MessageHandler<T> {
    protected static final String DATE_FORMAT = "yyyy-MM-dd'T'HH:mm:ss.SSSZ";
    private final static String ALERT_AGGREGATE_MAP_FUNCTION = "function () { emit(this.severityLevel, 1); }";
    private final static String ALERT_AGGREGATE_REDUCE_FUNCTION = "function (key, values) { var sum = 0; for (var i = 0; i < values.length; i++) sum += values[i]; return sum; }";


    @Autowired
    private UplinkProcessor uplinkProcessor;

    @Autowired
    private MongoTemplate mongoTemplate;

    @PostConstruct
    public void registerInUplinkProcessor() {
        uplinkProcessor.registerHandler(this.handles(), this);
    }

    protected void updateSpaAlertState(final Spa spa, final Alert alert) {
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

    protected String findHighestActiveAlertSeverityForSpa(final String spaId) {
        final Query query = Query.query(Criteria.where("spaId").is(spaId)).addCriteria(Criteria.where("clearedDate").is(null));
        final MapReduceResults<ValueObject> results = mongoTemplate.mapReduce(query, "alert", ALERT_AGGREGATE_MAP_FUNCTION, ALERT_AGGREGATE_REDUCE_FUNCTION, ValueObject.class);

        return getHighestAlertSeverity(results);
    }

    protected String findHighestActiveAlertSeverityForSpaAndComponentAndPortNo(final String spaId, final String component, final Integer portNo) {
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
