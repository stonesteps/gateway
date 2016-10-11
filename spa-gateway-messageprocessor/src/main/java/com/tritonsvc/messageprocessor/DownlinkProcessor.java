package com.tritonsvc.messageprocessor;

import com.bwg.iot.model.ProcessedResult;
import com.bwg.iot.model.SpaCommand;
import com.tritonsvc.messageprocessor.mongo.repository.SpaCommandRepository;
import com.tritonsvc.messageprocessor.mqtt.DownlinkRequestor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.List;

/**
 * this class is entry point responsible for performing all Downlink processing
 * namely scanning the mongodb Requests collection for any unprocessed
 * messages and transforming that document into a gateway-idl message(protobufs) and
 * publishing the serialized protobufs byte array to MQTT broker
 */
@Component
public class DownlinkProcessor {

    private static final Logger log = LoggerFactory.getLogger(DownlinkProcessor.class);

    @Autowired
    private SpaCommandRepository spaCommandRepository;

    @Autowired
    private DownlinkRequestor downlinkRequestor;

    @Scheduled(fixedRate = 5000)
    public void processCommands() {
        final List<SpaCommand> commands = spaCommandRepository.findFirst25ByProcessedTimestampIsNullOrderBySentTimestampAsc();

        if (commands != null && commands.size() > 0) {
            for (final SpaCommand command : commands) {
                final boolean sent = sendCommand(command);
                command.setProcessedTimestamp(new Date());
                command.setProcessedResult(sent ? ProcessedResult.SENT : ProcessedResult.INVALID);
                spaCommandRepository.save(command);
                log.info("Spa command processed successfully");
            }
        } else {
            log.info("No commands, sleeping");
        }
    }

    private boolean sendCommand(final SpaCommand command) {
        log.info("Processing command {}", command);
        boolean sent = false;

        if (command == null) {
            log.error("Spa command is null, not processing");
        } else if (command.getRequestTypeId() != null) {
            try {
                if (SpaCommand.RequestType.HEATER.getCode() == command.getRequestTypeId().intValue()) {
                    sent = downlinkRequestor.sendHeaterUpdateCommand(command);
                } else if (SpaCommand.RequestType.FILTER.getCode() == command.getRequestTypeId().intValue()) {
                    sent = downlinkRequestor.sendFilterUpdateCommand(command);
                } else if (SpaCommand.RequestType.UPDATE_AGENT_SETTINGS.getCode() == command.getRequestTypeId().intValue()) {
                    sent = downlinkRequestor.sendUpdateAgentSettingsCommand(command);
                } else if (SpaCommand.RequestType.RESTART_AGENT.getCode() == command.getRequestTypeId().intValue()) {
                    sent = downlinkRequestor.sendPlainCommand(command);
                } else if (SpaCommand.RequestType.REBOOT_GATEWAY.getCode() == command.getRequestTypeId().intValue()) {
                    sent = downlinkRequestor.sendPlainCommand(command);
                } else if (SpaCommand.RequestType.SET_TIME.getCode() == command.getRequestTypeId().intValue()) {
                    sent = downlinkRequestor.sendPlainCommand(command);
                } else {
                    sent = downlinkRequestor.sendPeripheralStateUpdateCommand(command);
                }
            } catch (Throwable th) {
                log.error("unable to send downlink command ", th);
                // squash everything, need to return from here in all cases
                // so command can be marked processed
            }
        }

        return sent;
    }
}
