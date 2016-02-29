package com.tritonsvc.messageprocessor;

import com.bwg.iot.model.Spa;
import com.bwg.iot.model.SpaCommand;
import com.tritonsvc.messageprocessor.mongo.repository.SpaCommandRepository;
import com.tritonsvc.messageprocessor.mongo.repository.SpaRepository;
import com.tritonsvc.messageprocessor.mqtt.MqttSendService;
import com.tritonsvc.messageprocessor.util.NumberHelper;
import com.tritonsvc.messageprocessor.util.SpaDataHelper;
import com.tritonsvc.spa.communication.proto.Bwg;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * this class is entry point responsible for performing all Downlink processing
 * namely scanning the mongodb Requests collection for any unprocessed
 * messages and transforming that document into a gateway-idl message(protobufs) and
 * publishing the serialized protobufs byte array to MQTT broker
 */
@Component
public class DownlinkProcessor {

    private static final Logger log = LoggerFactory.getLogger(UplinkProcessor.class);
    private static final String DESIRED_TEMP_PARAM = "desiredTemp";

    @Autowired
    private SpaCommandRepository spaCommandRepository;

    @Autowired
    private SpaRepository spaRepository;

    @Autowired
    private MqttSendService mqttSendService;

    @Value("${downlinkTopicName:BWG/spa/downlink}")
    private String downlinkTopicName;

    @Scheduled(fixedRate = 5000)
    public void processCommands() {
        final List<SpaCommand> commands = spaCommandRepository.findFirst25ByProcessedTimestampIsNullOrderBySentTimestampAsc();

        if (commands != null && commands.size() > 0) {
            for (final SpaCommand command : commands) {
                if (processCommand(command)) {
                    command.setProcessedTimestamp(String.valueOf(System.currentTimeMillis()));
                    spaCommandRepository.save(command);
                    log.info("Spa command processed successfully");
                }
            }
        } else {
            log.info("No commands, sleeping");
        }
    }

    private boolean processCommand(final SpaCommand command) {
        log.info("Processing command {}", command);
        boolean processed = false;

        if (command == null) {
            log.error("Spa command is null, not processing");
        } else if (command.getRequestTypeId() != null && Bwg.Downlink.Model.RequestType.HEATER_VALUE == command.getRequestTypeId().intValue()) {
            processed = sendHeaterUpdateCommand(command);
        }

        return processed;
    }

    private boolean sendHeaterUpdateCommand(final SpaCommand command) {
        boolean sent = false;
        final Spa spa = spaRepository.findOne(command.getSpaId());
        if (spa == null) {
            log.error("Could not find related spa with id {}", command.getSpaId());
        } else {
            log.info("Building heater update downlink message");

            final String desiredTemp = command.getValues().get(DESIRED_TEMP_PARAM);
            if (!NumberHelper.isDouble(desiredTemp)) {
                log.error("Desired temp passed with command is invalid {}", desiredTemp);
            } else {
                final Bwg.Downlink.Model.Request request = SpaDataHelper.buildRequest(Bwg.Downlink.Model.RequestType.HEATER, command.getValues());
                final byte[] messageData = SpaDataHelper.buildDownlinkMessage(command.getOriginatorId(), command.getSpaId(), Bwg.Downlink.DownlinkCommandType.REQUEST, request);
                if (messageData != null && messageData.length > 0) {
                    final String serialNumber = spa.getSerialNumber();
                    final String downlinkTopic = downlinkTopicName + (serialNumber != null ? "/" + serialNumber : "");
                    log.info("Seding downlink message to topic {}", downlinkTopic);
                    try {
                        mqttSendService.sendMessage(downlinkTopic, messageData);
                        sent = true;
                    } catch (Exception e) {
                        log.error("Error while sending downlink message", e);
                    }
                } else {
                    log.error("Message data is empty - not sending anything");
                }
            }
        }
        return sent;
    }
}
