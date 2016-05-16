package com.tritonsvc.messageprocessor.mqtt;

import com.google.common.annotations.VisibleForTesting;
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
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Mqtt service class. Responsible for sending and receiving messages from mqtt.
 */
@Service
public class MqttSubscribeService {

    private static final Logger log = LoggerFactory.getLogger(MqttSubscribeService.class);

    private static final int BASE_TIME_MILLISECONDS = 120000;
    private static final int WATCHDOG_SLEEP_MILLISECONDS = BASE_TIME_MILLISECONDS + 30000;
    private static final int WATCHDOG_THRESHOLD_MILLISECONDS = BASE_TIME_MILLISECONDS + 15000;

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
    private Future<Void> watchdog;

    private String currentTopic;
    private MessageListener currentListener;
    private AtomicLong lastCheckin;

    @PreDestroy
    public void cleanup() {
        log.info("cleaning up mqtt service");

        es.shutdown();

        if (currentSubscription != null) {
            currentSubscription.cancel(true);
        }

        if (watchdog != null) {
            watchdog.cancel(true);
        }

        disconnect();
    }

    public void subscribe(final String topic, final MessageListener listener) throws Exception {
        log.info("subscribing listener to topic {}", topic);

        currentTopic = topic;
        currentListener = listener;
        lastCheckin = new AtomicLong(System.currentTimeMillis());

        final Subscription subscription = new Subscription(currentTopic, currentListener);
        currentSubscription = es.submit(subscription);
        watchdog = es.submit(new Watchdog());
    }

    @VisibleForTesting
    MQTT acquireMQTT() {
        return new MQTT();
    }

    private void disconnect() {
        if (connection != null && connection.isConnected()) {
            try {
                connection.disconnect();
            } catch (Exception e) {
                log.error("error when closing connection with mqtt broker {}:{}", mqttHostname, mqttPort);
            }
        }
        connection = null;
    }

    private void connect() throws Exception {
        try {
            mqtt = acquireMQTT();

            PkiInfo pki = messageProcessorConfiguration.obtainPKIArtifacts();
            if (pki.getSslContext() != null) {
                mqttPort = pki.getPort();
                log.info("server ssl being used, mqtt port will be {}", mqttPort);
            }

            log.info("connecting to mqtt {}:{}...", mqttHostname, mqttPort);
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
                    disconnect();
                    connect();
                    connection.subscribe(new Topic[]{new Topic(topic, QoS.AT_LEAST_ONCE)});
                } catch (Exception e) {
                    log.error("error with connection to mqtt broker {}:{} while subscribing to quete {}", mqttHostname, mqttPort, topic);
                    try {Thread.sleep(10000);} catch (InterruptedException ie){};
                    continue;
                }

                try {
                    while (!Thread.currentThread().isInterrupted()) {
                        lastCheckin.set(System.currentTimeMillis());
                        log.info("waiting for message...");
                        final Message message = connection.receive().await(BASE_TIME_MILLISECONDS, TimeUnit.MILLISECONDS);
                        if (message != null) {
                            message.ack();
                            log.info("got message, processing");
                            listener.processMessage(message.getPayload());
                        } else {
                            log.info("received a null message, skipping");
                        }
                    }
                } catch (final InterruptedException e) {
                    log.warn("subscription interrupted, quitting");
                    return null;
                } catch (TimeoutException te) {
                    log.info("no uplink messages received in 2 minutes, will recreate subscription ...");
                } catch (final Throwable e) {
                    log.error("exception processing message, will recreate subscription ...", e);
                    try {Thread.sleep(10000);} catch (InterruptedException ie){}
                }
            }
            return null;
        }
    }

    private final class Watchdog implements Callable<Void> {

        @Override
        public Void call() throws Exception {
            while (!Thread.currentThread().isInterrupted()) {
                // check periodically if current subscription thread is not hung
                Thread.sleep(WATCHDOG_SLEEP_MILLISECONDS);
                if (System.currentTimeMillis() - lastCheckin.get() > WATCHDOG_THRESHOLD_MILLISECONDS) {
                    log.error("No subscriber activity for over {} milliseconds, recreating subscriber thread", WATCHDOG_THRESHOLD_MILLISECONDS);
                    // terminate subscription
                    currentSubscription.cancel(true);
                    currentSubscription = es.submit(new Subscription(currentTopic, currentListener));
                } else {
                    log.info("Subscriber thread is active, taking no action");
                }
            }
            return null;
        }
    }
}
