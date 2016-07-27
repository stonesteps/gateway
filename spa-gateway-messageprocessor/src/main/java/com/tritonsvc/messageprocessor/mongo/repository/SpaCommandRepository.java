package com.tritonsvc.messageprocessor.mongo.repository;

import com.bwg.iot.model.SpaCommand;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

/**
 * Spa command repo
 */
public interface SpaCommandRepository extends MongoRepository<SpaCommand, String> {

    List<SpaCommand> findFirst25ByProcessedTimestampIsNullOrderBySentTimestampAsc();
    List<SpaCommand> findByOriginatorIdOrderBySentTimestampDesc(String originatorId);

    SpaCommand findByOriginatorIdAndSpaId(String originatorId, String spaId);
}
