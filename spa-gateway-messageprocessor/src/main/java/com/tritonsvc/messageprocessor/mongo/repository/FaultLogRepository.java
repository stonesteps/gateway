package com.tritonsvc.messageprocessor.mongo.repository;

import com.bwg.iot.model.FaultLog;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Date;

/**
 * Created by holow on 5/8/2016.
 */
public interface FaultLogRepository extends MongoRepository<FaultLog, String> {

    FaultLog findFirstBySpaIdAndCodeAndTimestamp(String spaId, int code, Date timestamp);

}
