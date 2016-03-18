package com.tritonsvc.messageprocessor.mongo.repository;

import com.bwg.iot.model.Component;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.data.rest.core.annotation.RestResource;

/**
 * access to all components of a spa
 */
public interface ComponentRepository extends MongoRepository<Component, String> {
    Page<Component> findBySpaIdAndComponentType(@Param("spaId") String spaId, @Param("componentType") String type, Pageable p);
    Page<Component> findByComponentTypeAndSerialNumber(@Param("componentType") String type, @Param("serialNumber") String serialNumber, Pageable p);

    Component findOneBySpaIdAndComponentTypeAndPort(@Param("spaId") String spaId, @Param("componentType") String type, @Param("port") String port);
}
