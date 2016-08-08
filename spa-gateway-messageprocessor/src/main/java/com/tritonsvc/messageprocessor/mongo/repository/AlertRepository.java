package com.tritonsvc.messageprocessor.mongo.repository;

import com.bwg.iot.model.Alert;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.repository.query.Param;

/**
 * Repository for Alerts.
 */
public interface AlertRepository extends MongoRepository<Alert, String> {

    Long countBySpaIdAndSeverityLevelAndClearedDateIsNull(@Param("spaId") String spaId, @Param("severityLevel") String severityLevel);

    Long countBySpaIdAndSeverityLevelAndComponentAndClearedDateIsNull(@Param("spaId") String spaId, @Param("severityLevel") String severityLevel, @Param("component") String component);
}
