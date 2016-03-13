package com.tritonsvc.messageprocessor.mongo.repository;

import com.bwg.iot.model.SpaState;
import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * Created by holow on 3/11/2016.
 */
public interface SpaStateRepository extends MongoRepository<SpaState, String> {
    SpaState findOneBySpaId(final String spaId);
}
