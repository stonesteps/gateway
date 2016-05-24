package com.tritonsvc.messageprocessor.mongo.repository;

import com.bwg.iot.model.WifiStat;
import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * Created by holow on 5/8/2016.
 */
public interface WifiStatRepository extends MongoRepository<WifiStat, String> {

}
