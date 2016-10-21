package com.tritonsvc.messageprocessor.mongo.repository;

import com.bwg.iot.model.User;
import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * Repository for Users.
 */
public interface UserRepository extends MongoRepository<User, String> {
}
