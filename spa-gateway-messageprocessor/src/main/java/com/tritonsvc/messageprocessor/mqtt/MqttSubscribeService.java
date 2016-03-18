package com.tritonsvc.messageprocessor.mqtt;

import com.google.common.base.Throwables;
import org.fusesource.mqtt.client.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PreDestroy;
import java.rmi.dgc.VMID;
import java.util.concurrent.*;
import java.util.concurrent.Future;

/**
 * Mqtt service class. Responsible for sending and receiving messages from mqtt.
 */
@Service
public final class MqttSubscribeService {

    private static final Logger log = LoggerFactory.getLogger(MqttSubscribeService.class);

    @Value("${mqttHostname:localhost}")
    private String mqttHostname;
    @Value("${mqttPort:1883}")
    private int mqttPort;
    @Value("${mqttKeepAliveSeconds:60}")
    private short mqttKeepAliveSeconds;

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

        if (connection != null && connection.isConnected()) {
            try {
                connection.disconnect();
            } catch (Exception e) {
                log.error("error when closing connection with mqtt broker {}:{}", mqttHostname, mqttPort);
            }
        }
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

    private void connect() throws Exception {
        log.info("connecting to mqtt {}:{}...", mqttHostname, mqttPort);
        try {
            mqtt = new MQTT();

            mqtt.setHost(mqttHostname, mqttPort);
            mqtt.setKeepAlive(mqttKeepAliveSeconds);
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
                    // FIXME wait for some time before reconnect?
                    continue;
                }

                try {
                    while (!Thread.currentThread().isInterrupted()) {
                        log.info("waiting for message...");
                        final Message message = connection.receive().await();
                        if (message != null) {
                            message.ack();
                            log.info("got message, processing");
                            listener.processMessage(message.getPayload());
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
