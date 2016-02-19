package com.tritonsvc.messageprocessor;

import com.bwg.iot.model.SpaCommand;
import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * Spa repo
 */
public interface SpaCommandRepository extends MongoRepository<SpaCommand, String> {

}
