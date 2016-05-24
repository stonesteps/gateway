package com.tritonsvc.messageprocessor;

import com.tritonsvc.messageprocessor.messagehandler.MessageHandler;
import com.tritonsvc.messageprocessor.mongo.repository.SpaCommandRepository;
import com.tritonsvc.messageprocessor.mongo.repository.SpaRepository;
import com.tritonsvc.messageprocessor.mqtt.MessageListener;
import com.tritonsvc.messageprocessor.mqtt.MqttSubscribeService;
import com.tritonsvc.spa.communication.proto.Bwg;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.Model.DownlinkAcknowledge;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.Model.FaultLogs;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.Model.RegisterDevice;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.Model.SpaState;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.Model.WifiStats;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.UplinkCommandType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * This class transforms MQTT message payloads into MongoDB Documents.
 */
@Component
public class UplinkProcessor implements MessageListener {

    private static final Logger log = LoggerFactory.getLogger(UplinkProcessor.class);

    @Autowired
    private SpaCommandRepository spaCommandRepository;

    @Autowired
    private SpaRepository spaRepository;

    @Autowired
    private MqttSubscribeService mqttSubscribeService;

    @Autowired
    private MessageProcessorConfiguration messageProcessorConfiguration;

    @PostConstruct
    public void start() throws Exception {
        mqttSubscribeService.subscribe(messageProcessorConfiguration.getUplinkTopicName(), this);
    }

    private Map<Class<?>, MessageHandler> handlersMap = new HashMap<>();

    @Override
    public void processMessage(byte[] payload) {
        log.info("start message processing");
        final InputStream stream = new ByteArrayInputStream(payload);
        try {
            final Bwg.Header header = Bwg.Header.parseDelimitedFrom(stream);
            if (header.getCommand() != Bwg.CommandType.UPLINK) {
                throw new IllegalArgumentException("not an uplink command");
            }
            final Bwg.Uplink.UplinkHeader uplinkHeader = Bwg.Uplink.UplinkHeader.parseDelimitedFrom(stream);
            if (uplinkHeader.getCommand() == Bwg.Uplink.UplinkCommandType.REGISTRATION) {
                final RegisterDevice registerDevice = RegisterDevice.parseDelimitedFrom(stream);
                handleMessage(RegisterDevice.class, header, uplinkHeader, registerDevice);
            } else if (uplinkHeader.getCommand() == UplinkCommandType.ACKNOWLEDGEMENT) {
                final DownlinkAcknowledge ack = DownlinkAcknowledge.parseDelimitedFrom(stream);
                handleMessage(DownlinkAcknowledge.class, header, uplinkHeader, ack);
            } else if (uplinkHeader.getCommand() == UplinkCommandType.SPA_STATE) {
                final SpaState state = SpaState.parseDelimitedFrom(stream);
                handleMessage(SpaState.class, header, uplinkHeader, state);
            } else if (uplinkHeader.getCommand() == UplinkCommandType.FAULT_LOGS) {
                final FaultLogs faultLogs = FaultLogs.parseDelimitedFrom(stream);
                handleMessage(FaultLogs.class, header, uplinkHeader, faultLogs);
            } else if (uplinkHeader.getCommand() == UplinkCommandType.WIFI_STATS) {
                final WifiStats wifiStats = WifiStats.parseDelimitedFrom(stream);
                handleMessage(WifiStats.class, header, uplinkHeader, wifiStats);
            }

        } catch (Exception e) {
            log.error("error processing message", e);
        }
        log.info("message processing complete");
    }

    private <T> void handleMessage(final Class<T> clazz, final Bwg.Header header, final Bwg.Uplink.UplinkHeader uplinkHeader, final T message) {
        final MessageHandler<T> handler = handlersMap.get(clazz);
        if (handler != null) {
            handler.processMessage(header, uplinkHeader, message);
        }
    }

    public <T> void registerHandler(final Class<T> clazz, final MessageHandler<T> messageHandler) {
        log.info("registering message handler for class {}", clazz);
        handlersMap.put(clazz, messageHandler);
    }
}
