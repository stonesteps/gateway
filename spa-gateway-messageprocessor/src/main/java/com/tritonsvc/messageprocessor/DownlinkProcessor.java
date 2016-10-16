package com.tritonsvc.messageprocessor;

import com.bwg.iot.model.ProcessedResult;
import com.bwg.iot.model.SpaCommand;
import com.tritonsvc.messageprocessor.mongo.repository.SpaCommandRepository;
import com.tritonsvc.messageprocessor.mqtt.DownlinkRequestor;
import com.tritonsvc.messageprocessor.util.Watchdog;
import com.tritonsvc.messageprocessor.util.WatchedThreadCreator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.Date;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

/**
 * this class is entry point responsible for performing all Downlink processing
 * namely scanning the mongodb Requests collection for any unprocessed
 * messages and transforming that document into a gateway-idl message(protobufs) and
 * publishing the serialized protobufs byte array to MQTT broker
 */
@Component
public class DownlinkProcessor implements WatchedThreadCreator {

    private static final long DOWNLINK_PROCESSOR_THREAD_SLEEP_MILLISECONDS = 5000;
    private static final long WATCHDOG_SLEEP_MILLISECONDS = 30000;
    private static final long WATCHDOG_THRESHOLD_MILLISECONDS = DOWNLINK_PROCESSOR_THREAD_SLEEP_MILLISECONDS + 15000;

    private static final Logger log = LoggerFactory.getLogger(DownlinkProcessor.class);

    @Autowired
    private SpaCommandRepository spaCommandRepository;

    @Autowired
    private DownlinkRequestor downlinkRequestor;

    private final ExecutorService es = Executors.newCachedThreadPool();
    private Future<Void> currentDownlinkProcessor;
    private Future<Void> watchdog;
    private AtomicLong lastCheckin;

    @PostConstruct
    public void init() {
        lastCheckin = new AtomicLong(System.currentTimeMillis());

        final DownlinkProcessorThread downlinkProcessorThread = new DownlinkProcessorThread();
        currentDownlinkProcessor = es.submit(downlinkProcessorThread);
        watchdog = es.submit(new Watchdog(WATCHDOG_SLEEP_MILLISECONDS, WATCHDOG_THRESHOLD_MILLISECONDS, lastCheckin, this));
    }

    @PreDestroy
    public void cleanup() {
        log.info("Cleaning up downlinkk processor");
        es.shutdown();
        if (currentDownlinkProcessor != null) {
            currentDownlinkProcessor.cancel(true);
        }
        if (watchdog != null) {
            watchdog.cancel(true);
        }
    }

    @Override
    public void recreateThread() {
        if (currentDownlinkProcessor != null) {
            currentDownlinkProcessor.cancel(true);
        }
        currentDownlinkProcessor = es.submit(new DownlinkProcessorThread());
    }

    private void processCommands() throws Exception {
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
                log.error("Unable to send downlink command ", th);
                // squash everything, need to return from here in all cases
                // so command can be marked processed
            }
        }

        return sent;
    }

    private final class DownlinkProcessorThread implements Callable<Void> {

        @Override
        public Void call() throws Exception {
            while (!Thread.currentThread().isInterrupted()) {
                Thread.sleep(DOWNLINK_PROCESSOR_THREAD_SLEEP_MILLISECONDS);
                try {
                    processCommands();
                    lastCheckin.set(System.currentTimeMillis());
                } catch (final InterruptedException e) {
                    log.info("Downlink processor thread stopped");
                    // exit thread normally
                    break;
                } catch (final Exception e) {
                    log.error("Error while processing commands", e);
                }
            }
            return null;
        }
    }
}
