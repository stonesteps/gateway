package com.tritonsvc.messageprocessor.mongo.repository;

import com.bwg.iot.model.FaultLogDescription;
import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * Created by holow on 5/8/2016.
 */
public interface FaultLogDescriptionRepository extends MongoRepository<FaultLogDescription, String> {

    FaultLogDescription findFirstByCodeAndControllerType(int code, String controllerType);

}
