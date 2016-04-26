package com.tritonsvc.messageprocessor.mqtt;

import com.google.common.base.Throwables;
import com.tritonsvc.messageprocessor.MessageProcessorConfiguration;
import com.tritonsvc.messageprocessor.PkiInfo;
import org.fusesource.mqtt.client.FutureConnection;
import org.fusesource.mqtt.client.MQTT;
import org.fusesource.mqtt.client.Message;
import org.fusesource.mqtt.client.QoS;
import org.fusesource.mqtt.client.Topic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PreDestroy;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Mqtt service class. Responsible for sending and receiving messages from mqtt.
 */
@Service
public final class MqttSubscribeService {

    private static final Logger log = LoggerFactory.getLogger(MqttSubscribeService.class);

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


    private ExecutorService es = Executors.newCachedThreadPool();
    private MQTT mqtt;
    private FutureConnection connection;

    private Future<Void> currentSubscription;

    @PreDestroy
    public void cleanup() {
        log.info("cleaning up mqtt service");

        es.shutdown();

        if (currentSubscription != null) {
            currentSubscription.cancel(true);
        }
        disconnect();
    }

    public void subscribe(final String topic, final MessageListener listener) throws Exception {
        log.info("subscribing listener to topic {}", topic);
        final Subscription subscription = new Subscription(topic, listener);
        currentSubscription = es.submit(subscription);
    }

    private void checkConnection() throws Exception {
        if (connection == null || !connection.isConnected()) {
            connect();
        }
    }

    private void disconnect() {
        if (connection != null && connection.isConnected()) {
            try {
                connection.disconnect();
                connection = null;
            } catch (Exception e) {
                log.error("error when closing connection with mqtt broker {}:{}", mqttHostname, mqttPort);
            }
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

    private final class Subscription implements Callable<Void> {
        private final String topic;
        private final MessageListener listener;

        public Subscription(final String topic, final MessageListener listener) {
            this.topic = topic;
            this.listener = listener;
        }

        @Override
        public Void call() throws Exception {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    checkConnection();
                    final Topic[] topics = new Topic[]{new Topic(topic, QoS.AT_LEAST_ONCE)};
                    connection.subscribe(topics);
                } catch (Exception e) {
                    log.error("error with connection to mqtt broker {}:{} while subscribing to quete {}", mqttHostname, mqttPort, topic);
                    disconnect();
                    Thread.sleep(10000);
                    continue;
                }

                try {
                    while (!Thread.currentThread().isInterrupted()) {
                        log.info("waiting for message...");
                        final Message message = connection.receive().await(2, TimeUnit.MINUTES);
                        if (message != null) {
                            message.ack();
                            log.info("got message, processing");
                            listener.processMessage(message.getPayload());
                        } else {
                            log.info("no uplink messages received in 2 minutes, will recreate subscription ...");
                            disconnect();
                            break;
                        }
                    }
                } catch (final InterruptedException e) {
                    log.warn("subscription interrupted, quitting");
                    return null;
                } catch (final Throwable e) {
                    log.error("exception processing message", e);
                }
            }
            return null;
        }
    }
}
