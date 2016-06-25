package com.tritonsvc.messageprocessor.messagehandler;

import com.bwg.iot.model.MeasurementReading;
import com.bwg.iot.model.Spa;
import com.tritonsvc.messageprocessor.mongo.repository.ComponentRepository;
import com.tritonsvc.messageprocessor.mongo.repository.MeasurementReadingRepository;
import com.tritonsvc.messageprocessor.mongo.repository.SpaRepository;
import com.tritonsvc.spa.communication.proto.Bwg;
import com.tritonsvc.spa.communication.proto.BwgHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

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

        final com.bwg.iot.model.Component mote = componentRepository.findOne(uplinkHeader.getHardwareId());
        if (mote == null) {
            log.error("Received measurement for mote id {} which is not registered yet", uplinkHeader.getHardwareId());
            return;
        }

        final Spa spa = spaRepository.findOne(mote.getSpaId());
        if (spa == null) {
            log.error("Received measurement from component {} with unknown spaId: {}", mote.get_id(), mote.getSpaId());
            return;
        }

        if (measurements.getMeasurementsCount() > 0) {
            final List<MeasurementReading> readings = new ArrayList<>(measurements.getMeasurementsCount());
            for (final Bwg.Uplink.Model.Measurement measurement : measurements.getMeasurementsList()) {
                readings.add(processMeasurement(mote, spa, measurement));
            }
            measurementReadingRepository.save(readings);
        }
    }

    private MeasurementReading processMeasurement(final com.bwg.iot.model.Component mote, final Spa spa, final Bwg.Uplink.Model.Measurement measurement) {
        final MeasurementReading reading = new MeasurementReading();

        reading.setSpaId(spa.get_id());
        reading.setOwnerId(spa.getOwner() != null ? spa.getOwner().get_id() : null);
        reading.setDealerId(spa.getDealerId());
        reading.setOemId(spa.getOemId());
        reading.setMoteId(mote.get_id());

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

        return reading;
    }
}
