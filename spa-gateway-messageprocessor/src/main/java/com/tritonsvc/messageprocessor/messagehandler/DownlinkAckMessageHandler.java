package com.tritonsvc.messageprocessor.messagehandler;

import com.bwg.iot.model.SpaCommand;
import com.tritonsvc.messageprocessor.mongo.repository.SpaCommandRepository;
import com.tritonsvc.spa.communication.proto.Bwg;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.Model.DownlinkAcknowledge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Date;

/**
 * process downlink acks from spa systems
 */
@Component
public class DownlinkAckMessageHandler extends AbstractMessageHandler<DownlinkAcknowledge> {

    private static final Logger log = LoggerFactory.getLogger(DownlinkAckMessageHandler.class);

    @Autowired
    private SpaCommandRepository spaCommandRepository;

    @Override
    public Class<DownlinkAcknowledge> handles() {
        return DownlinkAcknowledge.class;
    }

    @Override
    public void processMessage(final Bwg.Header header, final Bwg.Uplink.UplinkHeader uplinkHeader, final DownlinkAcknowledge ackMessage) {
        log.info("Processing downlink ack message for originator {}, and hw id {}, response code {} {} ", header.getOriginator(), uplinkHeader.getHardwareId(), ackMessage.getCode().name(), ackMessage.getDescription() == null ? "" : ackMessage.getDescription());

        SpaCommand request = spaCommandRepository.findByOriginatorIdAndSpaId(header.getOriginator(), uplinkHeader.getHardwareId());

        if (request == null) {
            log.error("Received an ack for prior downlink, however no originator {} and hardwareid {} exists.", header.getOriginator(), uplinkHeader.getHardwareId());
            return;
        }

        request.setAckResponseCode(ackMessage.getCode().name());
        request.setAckTimestamp(new Date());
        spaCommandRepository.save(request);
    }
}
