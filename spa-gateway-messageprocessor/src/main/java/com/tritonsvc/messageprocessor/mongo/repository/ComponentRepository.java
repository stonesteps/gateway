package com.tritonsvc.messageprocessor.mongo.repository;

import com.bwg.iot.model.Component;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.data.rest.core.annotation.RestResource;

import java.util.List;

/**
 * access to all components of a spa
 */
public interface ComponentRepository extends MongoRepository<Component, String> {
    Page<Component> findBySpaIdAndComponentType(@Param("spaId") String spaId, @Param("componentType") String type, Pageable p);
    Component findOneBySpaIdAndComponentTypeAndSerialNumber(@Param("spaId") String spaId, @Param("componentType") String type, @Param("serialNumber") String serialNumber);
    Page<Component> findByComponentTypeAndSerialNumber(@Param("componentType") String type, @Param("serialNumber") String serialNumber, Pageable p);
    List<Component> findByParentComponentIdAndSerialNumberIn(@Param("parentComponentId") String parentComponentId, @Param("serialNumbers") List<String> serialNumbers);
    Component findOneBySpaIdAndComponentTypeAndPort(@Param("spaId") String spaId, @Param("componentType") String type, @Param("port") String port);
}
