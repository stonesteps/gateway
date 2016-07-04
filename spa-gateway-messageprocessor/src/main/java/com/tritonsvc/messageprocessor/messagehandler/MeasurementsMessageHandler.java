package com.tritonsvc.messageprocessor.messagehandler;

import com.bwg.iot.model.Component.ComponentType;
import com.bwg.iot.model.MeasurementReading;
import com.bwg.iot.model.Spa;
import com.tritonsvc.messageprocessor.mongo.repository.ComponentRepository;
import com.tritonsvc.messageprocessor.mongo.repository.MeasurementReadingRepository;
import com.tritonsvc.messageprocessor.mongo.repository.SpaRepository;
import com.tritonsvc.spa.communication.proto.Bwg;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.Model.Measurement;
import com.tritonsvc.spa.communication.proto.BwgHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

import static com.google.common.collect.Lists.newArrayList;
import static java.util.stream.Collectors.toMap;

/**
 * process wifi stats from spa systems
 */
@Component
public class MeasurementsMessageHandler extends AbstractMessageHandler<Bwg.Uplink.Model.Measurements> {

    private static final Logger log = LoggerFactory.getLogger(MeasurementsMessageHandler.class);

    @Autowired
    private SpaRepository spaRepository;

    @Autowired
    private ComponentRepository componentRepository;

    @Autowired
    private MeasurementReadingRepository measurementReadingRepository;

    @Override
    public Class<Bwg.Uplink.Model.Measurements> handles() {
        return Bwg.Uplink.Model.Measurements.class;
    }

    @Override
    public void processMessage(final Bwg.Header header, final Bwg.Uplink.UplinkHeader uplinkHeader, final Bwg.Uplink.Model.Measurements measurements) {
        log.info("Processing measurements from originator {}, with hw id {}", header.getOriginator(), uplinkHeader.getHardwareId());

        final com.bwg.iot.model.Component sensorParent = componentRepository.findOne(uplinkHeader.getHardwareId());
        if (sensorParent == null) {
            log.error("Received measurement for sensor parent component id of {} which is not registered yet", uplinkHeader.getHardwareId());
            return;
        }

        final Spa spa = spaRepository.findOne(sensorParent.getSpaId());
        if (spa == null) {
            log.error("Received measurement from component {} with unknown spaId: {}", sensorParent.get_id(), sensorParent.getSpaId());
            return;
        }

        Map<String,String> sensorIdentifiers = measurements.getMeasurementsList().stream()
                .filter(Measurement::hasSensorIdentifier)
                .collect(toMap(Measurement::getSensorIdentifier, measurement -> measurement.getType().name()));

        Map<String, com.bwg.iot.model.Component> lookup =
                componentRepository.findByParentComponentIdAndSerialNumberIn(sensorParent.get_id(), newArrayList(sensorIdentifiers.keySet())).stream()
                        .collect(toMap(com.bwg.iot.model.Component::getSerialNumber, Function.identity()));

        sensorIdentifiers.keySet().removeAll(lookup.keySet());

        for (Map.Entry<String, String> sensorIdentifier : sensorIdentifiers.entrySet()) {
            com.bwg.iot.model.Component comp = new com.bwg.iot.model.Component();
            comp.setSpaId(spa.get_id());
            comp.setComponentType(ComponentType.SENSOR.name());
            comp.setSerialNumber(sensorIdentifier.getKey());
            comp.setDealerId(spa.getDealerId());
            comp.setOemId(spa.getOemId());
            comp.setOwnerId(spa.getOwner() != null ? spa.getOwner().get_id() : null);
            comp.setRegistrationDate(new Date());
            comp.setParentComponentId(sensorParent.get_id());
            comp.setName("sensor " + sensorIdentifier.getValue());
            componentRepository.save(comp);
            lookup.put(comp.getSerialNumber(), comp);
        }

        if (measurements.getMeasurementsCount() > 0) {
            final List<MeasurementReading> readings = new ArrayList<>(measurements.getMeasurementsCount());
            for (final Bwg.Uplink.Model.Measurement measurement : measurements.getMeasurementsList()) {
                com.bwg.iot.model.Component sensor = lookup.get(measurement.getSensorIdentifier());
                readings.add(processMeasurement(sensor, spa, measurement, sensorParent));
            }
            measurementReadingRepository.save(readings);
        }
    }

    private MeasurementReading processMeasurement(final com.bwg.iot.model.Component sensor, final Spa spa, final Bwg.Uplink.Model.Measurement measurement, final com.bwg.iot.model.Component parent) {
        final MeasurementReading reading = new MeasurementReading();

        reading.setSpaId(spa.get_id());
        reading.setOwnerId(spa.getOwner() != null ? spa.getOwner().get_id() : null);
        reading.setDealerId(spa.getDealerId());
        reading.setOemId(spa.getOemId());
        reading.setMoteId(Objects.equals(parent.getComponentType(), com.bwg.iot.model.Component.ComponentType.MOTE.name()) ? parent.get_id() : null);
        reading.setSensorId(sensor.get_id());

        if (measurement.hasTimestamp())
            reading.setTimestamp(new Date(measurement.getTimestamp()));
        if (measurement.hasType())
            reading.setType(measurement.getType().name());
        if (measurement.hasUom())
            reading.setUnitOfMeasure(measurement.getUom());
        if (measurement.hasValue())
            reading.setValue(measurement.getValue());
        if (measurement.getMetadataCount() > 0)
            reading.setMetadata(BwgHelper.getMetadataAsMap(measurement.getMetadataList()));
        if (measurement.hasQuality()) {
            reading.setQuality(measurement.getQuality().name());
        }
        if (measurement.hasSensorIdentifier()) {
            reading.setSensorIdentifier(measurement.getSensorIdentifier());
        }

        return reading;
    }
}
