package com.tritonsvc.messageprocessor.mongo.repository;

import com.bwg.iot.model.Spa;
import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * Spa repo
 */
public interface SpaRepository extends MongoRepository<Spa, String> {

}
