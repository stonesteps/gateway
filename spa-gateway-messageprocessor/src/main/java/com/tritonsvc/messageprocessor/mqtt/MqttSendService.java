package com.tritonsvc.messageprocessor.mqtt;

import com.google.common.base.Throwables;
import com.tritonsvc.messageprocessor.MessageProcessorConfiguration;
import com.tritonsvc.messageprocessor.PkiInfo;
import org.fusesource.mqtt.client.BlockingConnection;
import org.fusesource.mqtt.client.FutureConnection;
import org.fusesource.mqtt.client.MQTT;
import org.fusesource.mqtt.client.QoS;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PreDestroy;

/**
 * Mqtt service class. Responsible for sending and receiving messages from mqtt.
 */
@Service
public final class MqttSendService {

    private static final Logger log = LoggerFactory.getLogger(MqttSendService.class);

    @Autowired
    private MessageProcessorConfiguration messageProcessorConfiguration;

    @Value("${mqttHostname:localhost}")
    private String mqttHostname;
    @Value("${mqttPort:1883}")
    private int mqttPort;
    @Value("${mqttKeepAliveSeconds:60}")
    private short mqttKeepAliveSeconds;
    @Value("${mqttUserName:#{null}}")
    private String mqttUserName;
    @Value("${mqttPassword:#{null}}")
    private String mqttPassword;

    private MQTT mqtt;
    private FutureConnection connection;

    @PreDestroy
    public void cleanup() {
        log.info("cleaning up mqtt service");

        if (connection != null && connection.isConnected()) {
            try {
                connection.disconnect();
            } catch (Exception e) {
                log.error("error when closing connection with mqtt broker {}:{}", mqttHostname, mqttPort);
            }
        }
    }

    public void sendMessage(final String topic, byte[] message) throws Exception {
        log.info("sending data to topic {}", topic);
        checkConnection();
        connection.publish(topic, message, QoS.EXACTLY_ONCE, false);
    }

    private void checkConnection() throws Exception {
        if (connection == null || !connection.isConnected()) {
            connect();
        }
    }

    private void connect() throws Exception {
        log.info("connecting to mqtt {}:{}...", mqttHostname, mqttPort);
        try {
            mqtt = new MQTT();

            PkiInfo pki = messageProcessorConfiguration.obtainPKIArtifacts();
            if (pki.getSslContext() != null) {
                mqttPort = pki.getPort();
                log.info("server ssl being used, mqtt port will be {}", mqttPort);
            }

            mqtt.setHost(pki.getProtocolPrefix() + mqttHostname + ":" + mqttPort);
            mqtt.setKeepAlive(mqttKeepAliveSeconds);
            mqtt.setSslContext(pki.getSslContext());
            if (pki.getClientPublic() == null && mqttPassword != null) {
                mqtt.setPassword(mqttPassword);
            }
            if (pki.getClientPublic() == null && mqttUserName != null) {
                mqtt.setUserName(mqttUserName);
            }
            mqtt.setCleanSession(true);

            connection = mqtt.futureConnection();
            connection.connect();
        } catch (final Throwable t) {
            throw Throwables.propagate(t);
        }
    }
}
