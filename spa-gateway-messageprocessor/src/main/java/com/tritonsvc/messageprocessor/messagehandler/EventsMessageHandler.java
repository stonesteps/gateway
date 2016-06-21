package com.tritonsvc.messageprocessor.messagehandler;

import com.bwg.iot.model.Event;
import com.bwg.iot.model.Spa;
import com.tritonsvc.messageprocessor.mongo.repository.EventRepository;
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
 * process events from spa systems
 */
@Component
public class EventsMessageHandler extends AbstractMessageHandler<Bwg.Uplink.Model.Events> {

    private static final Logger log = LoggerFactory.getLogger(EventsMessageHandler.class);

    @Autowired
    private SpaRepository spaRepository;

    @Autowired
    private EventRepository eventRepository;

    @Override
    public Class<Bwg.Uplink.Model.Events> handles() {
        return Bwg.Uplink.Model.Events.class;
    }

    @Override
    public void processMessage(final Bwg.Header header, final Bwg.Uplink.UplinkHeader uplinkHeader, final Bwg.Uplink.Model.Events events) {
        log.info("Processing events from originator {}, with hw id {}", header.getOriginator(), uplinkHeader.getHardwareId());

        final String spaId = uplinkHeader.getHardwareId();
        final Spa spa = spaRepository.findOne(spaId);
        if (spa == null) {
            log.error("Received events for unknown spa: {}", spaId);
            return;
        }

        if (events.getEventsCount() > 0) {
            final List<Event> eventEntities = new ArrayList<>(events.getEventsCount());
            for (final Bwg.Uplink.Model.Event event : events.getEventsList()) {
                eventEntities.add(processEvent(spa, event));
            }
            eventRepository.save(eventEntities);
        }
    }

    private Event processEvent(final Spa spa, final Bwg.Uplink.Model.Event event) {
        final Event eventEntity = new Event();

        eventEntity.setSpaId(spa.get_id());
        eventEntity.setOwnerId(spa.getOwner() != null ? spa.getOwner().get_id() : null);
        eventEntity.setDealerId(spa.getDealerId());
        eventEntity.setOemId(spa.getOemId());
        eventEntity.setEventReceivedTimestamp(new Date());

        eventEntity.setEventType(event.hasEventType() ? event.getEventType().name() : null);
        if (event.hasDescription()) eventEntity.setDescription(event.getDescription());
        if (event.hasEventOccuredTimestamp())
            eventEntity.setEventOccuredTimestamp(new Date(event.getEventOccuredTimestamp()));
        if (event.getMetadataCount() > 0) eventEntity.setMetadata(BwgHelper.getMetadataAsMap(event.getMetadataList()));
        if (event.getOidDataCount() > 0) eventEntity.setOidData(BwgHelper.getMetadataAsMap(event.getOidDataList()));

        return eventEntity;
    }
}
