package com.tritonsvc.messageprocessor.mongo.repository;

import com.bwg.iot.model.Event;
import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * Created by holow on 5/8/2016.
 */
public interface EventRepository extends MongoRepository<Event, String> {

}
