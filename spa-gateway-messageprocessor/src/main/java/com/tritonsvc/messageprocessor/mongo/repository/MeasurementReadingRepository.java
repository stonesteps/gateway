package com.tritonsvc.messageprocessor.mongo.repository;

import com.bwg.iot.model.MeasurementReading;
import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * Created by holow on 5/8/2016.
 */
public interface MeasurementReadingRepository extends MongoRepository<MeasurementReading, String> {

}
