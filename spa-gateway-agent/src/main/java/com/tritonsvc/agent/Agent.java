package com.tritonsvc.agent;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Throwables;
import com.google.common.primitives.Ints;
import com.google.protobuf.AbstractMessageLite;
import com.tritonsvc.spa.communication.proto.Bwg.CommandType;
import com.tritonsvc.spa.communication.proto.Bwg.Header;
import com.tritonsvc.spa.communication.proto.Bwg.Header.Builder;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.UplinkCommandType;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.UplinkHeader;
import org.fusesource.mqtt.client.Callback;
import org.fusesource.mqtt.client.Future;
import org.fusesource.mqtt.client.FutureConnection;
import org.fusesource.mqtt.client.MQTT;
import org.fusesource.mqtt.client.Message;
import org.fusesource.mqtt.client.QoS;
import org.fusesource.mqtt.client.Topic;
import org.fusesource.mqtt.client.Tracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.naming.ldap.LdapName;
import javax.naming.ldap.Rdn;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.lang.reflect.Constructor;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

import static java.util.stream.Collectors.toList;

/**
 * Agent that handles message processing.
 * 
 * @author Derek
 */
public class Agent {

	/** Static logger instance */
	private static final Logger LOGGER = LoggerFactory.getLogger(Agent.class);
    private static final Logger MQTT_TRACE_LOGGER = LoggerFactory.getLogger("mqtt_trace");
    public static final long MAX_SUBSCRIPTION_INACTIVITY_TIME = 270000;

    /** Default filename for configuration properties */
    private static final String DEFAULT_CONFIG_FILENAME = "config.properties";
    static final String CA_ROOT_PUBKEY_FILE = "ca_root_cert.pem";
    static final String GATEWAY_PUBKEY_FILE = "gateway_cert.pem";
    static final String GATEWAY_PRIVKEY_FILE = "gateway_key.pkcs8";

	/** Defaults*/
	private static final int DEFAULT_MQTT_PORT = 1883;
    private static final int DEFAULT_MQTTS_PORT = 8883;
    private static final short DEFAULT_MQTT_KEEPALIVE = 30;
    private static final String DEFAULT_MQTT_HOSTNAME = "localhost";

	/** Command processor Java classname */
	private String commandProcessorClassname;

    /** The uplink queue on mqtt broker **/
    private static final String DEFAULT_MQTT_BASE_PATH = "BWG/spa";

	/** Gateway serial number */
	private String gwSerialNumber;

	/** MQTT server hostname */
	private String mqttHostname;

	/** MQTT server port */
	private int mqttPort;

    /** MQTT server user */
    private String mqttUsername;

    /** MQTT server password */
    private String mqttPassword;

    /** MQTT keep alive **/
    private short mqttKeepaliveSeconds;

	/** Outbound MQTT topic */
	private String outboundTopic;

	/** Inbound MQTT topic */
	private String inboundTopic;

	/** MQTT client */
	private MQTT mqttSub;

    /** MQTT client */
    private MQTT mqttPub;

	/** Outbound message processing */
	private MQTTOutbound outbound;

	/** Inbound message processing */
	private MQTTInbound inbound;

	/** Used to execute MQTT inbound in separate thread */
	private ExecutorService executor;

    /** BWGProcessor instance **/
    private AgentMessageProcessor processor;

    /** pki **/
    private SSLContext sslContext;
    private X509Certificate caRoot;
    private X509Certificate gatewayPublic;
    private PrivateKey gatewayPrivate;

    /** uplink retry queue **/
    private ConcurrentLinkedQueue<QueuedUplink> retryUplinks = new ConcurrentLinkedQueue();
    private static final int MAX_FAILED_SIZE = 500;
    private AtomicLong lastConnectAttempt = new AtomicLong(0);
    private AtomicLong lastSubReceived = new AtomicLong(0);


	/**
	 * Start the agent.
     *
     * @param homePath
	 */
	public void start(String homePath) {
		LOGGER.info("BWG agent starting...");
        Properties props;

		this.mqttSub = createMQTT();
        this.mqttPub = createMQTT();
		try {
            obtainPKIArtifacts(homePath);
            props = loadProperties(homePath);

            if (gwSerialNumber == null) {
                throw new IllegalStateException("no gateway serial number was found in properties or from a certificate, cannot continue");
            }
            //tcp://host:port or tls://host:port
            mqttSub.setHost( getProtocolPrefix() + mqttHostname + ":" + mqttPort);
            mqttSub.setCleanSession(true);
            mqttSub.setKeepAlive(mqttKeepaliveSeconds);
            mqttSub.setSslContext(sslContext);

            // set up the mqtt broker connection health logger
            if (MQTT_TRACE_LOGGER.isDebugEnabled()) {
                mqttSub.setTracer(new Tracer() {
                    @Override
                    public void debug(String message, Object...args) {
                        MQTT_TRACE_LOGGER.debug(message, args);
                    }
                });
            }

            mqttPub.setHost(getProtocolPrefix() + mqttHostname + ":" + mqttPort);
            mqttPub.setCleanSession(true);
            mqttPub.setKeepAlive(mqttKeepaliveSeconds);
            mqttPub.setSslContext(sslContext);
            mqttPub.setConnectAttemptsMax(1);
            mqttPub.setReconnectAttemptsMax(0);

            // set up the mqtt broker connection health logger
            if (MQTT_TRACE_LOGGER.isDebugEnabled()) {
                mqttPub.setTracer(new Tracer() {
                    @Override
                    public void debug(String message, Object...args) {
                        MQTT_TRACE_LOGGER.debug(message, args);
                    }
                });
            }

            if (mqttUsername != null) {
                mqttSub.setUserName(mqttUsername);
                mqttPub.setUserName(mqttUsername);
            }
            if (mqttPassword != null) {
                mqttSub.setPassword(mqttPassword);
                mqttPub.setPassword(mqttPassword);
            }

		} catch (Exception e) {
			throw Throwables.propagate(e);
		}
		LOGGER.info("Connecting to MQTT broker at '" + mqttHostname + ":" + mqttPort + "'...");

		// Create outbound message processor.
		outbound = new MQTTOutbound(mqttPub, outboundTopic);

        // data path
        final String dataPath = props.getProperty("dataPath", homePath);

        // Create an instance of the command processor.
        processor = createProcessor();
		processor.setGwSerialNumber(gwSerialNumber);
        processor.setConfigProps(props);
        processor.setHomePath(homePath);
        processor.setDataPath(dataPath);
        processor.setEventDispatcher(outbound);
        processor.setPKI(gatewayPublic, gatewayPrivate);

		// Create inbound message processing thread.
		inbound = new MQTTInbound(mqttSub, inboundTopic, processor);

		// Handle shutdown gracefully.
		Runtime.getRuntime().addShutdownHook(new ShutdownHandler());

		// Starts inbound processing loop in a separate thread.
        getInboundExecutor().execute(inbound);

		// Executes any custom startup logic.
		processor.executeStartup();

		LOGGER.info("BWG agent started.");
	}

    private void obtainPKIArtifacts(String homePath) {
        File caRootCert = new File(homePath + File.separator + CA_ROOT_PUBKEY_FILE);
        File gatewayPubCert = new File(homePath + File.separator + GATEWAY_PUBKEY_FILE);
        File gatewayPrivKey = new File(homePath + File.separator + GATEWAY_PRIVKEY_FILE);
        KeyStore ks;
        TrustManagerFactory tmf;
        CertificateFactory fact;
        KeyManagerFactory kmf = null;

        if (!caRootCert.exists()) {
            return;
        }

        try (FileInputStream isRoot = new FileInputStream(caRootCert)) {
            ks = KeyStore.getInstance(KeyStore.getDefaultType());
            ks.load(null);
            tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            fact = CertificateFactory.getInstance("X.509");

            caRoot = (X509Certificate) fact.generateCertificate(isRoot);
            ks.setCertificateEntry("caRoot", caRoot);
            tmf.init(ks);
            LOGGER.info("found ca_root_cert.pem, will use ssl to broker");
        } catch (Exception ex) {
            LOGGER.error("problem loading server crypto, will skip any crypto setup", ex);
            caRoot = null;
            return;
        }

        if (gatewayPubCert.exists() && gatewayPrivKey.exists()) {
            try (FileInputStream isGateway = new FileInputStream(gatewayPubCert);
                 DataInputStream is = new DataInputStream(new FileInputStream(gatewayPrivKey))) {

                kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                gatewayPublic = (X509Certificate) fact.generateCertificate(isGateway);
                byte[] keyBytes = new byte[(int) gatewayPrivKey.length()];
                is.readFully(keyBytes);

                PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(keyBytes);
                KeyFactory kf = KeyFactory.getInstance("RSA");
                gatewayPrivate = kf.generatePrivate(spec);
                ks.setKeyEntry("gateway", gatewayPrivate, "bwgkey".toCharArray(), new Certificate[]{gatewayPublic});
                kmf.init(ks, "bwgkey".toCharArray());
                List<Rdn> cns = new LdapName(gatewayPublic.getSubjectX500Principal().getName())
                        .getRdns()
                        .stream()
                        .filter( rdn -> rdn.getType().equalsIgnoreCase("cn"))
                        .collect(toList());
                if (cns.size() > 0) {
                    gwSerialNumber = cns.get(0).getValue().toString();
                }
                LOGGER.info("found gateway_cert.pem and gateway_key.pkcs8, cn={}, will submit as client in ssl handshake", gwSerialNumber);
            } catch (Exception ex) {
                gatewayPublic = null;
                gatewayPrivate = null;
                kmf = null;
                LOGGER.error("problem loading client crypto, will skip", ex);
            }
        }

        try {
            sslContext = SSLContext.getInstance("TLS");
            sslContext.init(kmf != null ? kmf.getKeyManagers() : null, tmf.getTrustManagers(), new java.security.SecureRandom());
        } catch (Exception ex) {
            LOGGER.error("unable to create SSL Context", ex);
            sslContext = null;
            caRoot = null;
            gatewayPublic = null;
            gatewayPrivate = null;
        }
    }

    private String getProtocolPrefix() {
        return (sslContext != null ? "tls://" : "tcp://");
    }

    private Properties loadProperties(String homePath) {
        String propsFile;
        if (homePath != null) {
            propsFile = homePath + File.separator + DEFAULT_CONFIG_FILENAME;
            LOGGER.info("Loading configuration from properties file: " + propsFile);
        } else {
            LOGGER.info("Loading configuration from default properties file: " + DEFAULT_CONFIG_FILENAME);
            propsFile = DEFAULT_CONFIG_FILENAME;
        }

        Properties props = new Properties();
        try (FileInputStream in = new FileInputStream(propsFile)) {
            props.load(in);
            load(props);
            return props;
        } catch (Exception e) {
            throw Throwables.propagate(e);
        }
    }

    @VisibleForTesting
    AgentMessageProcessor createProcessor() {
		try {
			Class<?> clazz = Class.forName(commandProcessorClassname);
            Constructor construct = clazz.getDeclaredConstructor(AgentSettingsPersister.class);
			return (AgentMessageProcessor) construct.newInstance(new AgentSettingsPersister());
		} catch (Exception e) {
			throw Throwables.propagate(e);
		}
	}

    @VisibleForTesting
    ExecutorService getInboundExecutor() {
        if (executor == null) {
            executor = Executors.newSingleThreadExecutor();
        }
        return executor;
    }

    @VisibleForTesting
    MQTT createMQTT() {
        return new MQTT();
    }

    private void addUplinkRetry(QueuedUplink uplink) {
        if (uplink.getAttempts() > 5) {
            removeUplinkRetry(uplink);
            LOGGER.info("expired tries and removed cached uplink for {}", uplink.getUplinkCommandType().name());
            return;
        }

        uplink.incrementAttempts();
        if (uplink.isCached() == false && retryUplinks.size() < MAX_FAILED_SIZE) {
            synchronized (retryUplinks) {
                if (uplink.isCached() == false && retryUplinks.size() < MAX_FAILED_SIZE) {
                    uplink.setCached();
                    retryUplinks.add(uplink);
                }
            }
        }
    }

    private void removeUplinkRetry(QueuedUplink uplink) {
        synchronized (retryUplinks) {
            retryUplinks.remove(uplink);
        }
    }


    private void drainRetry() {
        synchronized(retryUplinks) {
            if (retryUplinks.size() > 0) {
                retryUplinks.stream().forEach(uplink -> outbound.sendMessage(uplink, true));
            }
        }
    }

	private class MQTTOutbound implements GatewayEventDispatcher {

		/** MQTT outbound topic */
		private String topic;

		/** MQTT connection */
		private FutureConnection connection;
        private MQTT mqttPub;

		public MQTTOutbound(MQTT mqttPub, String topic) {
            this.mqttPub = mqttPub;
			this.topic = topic;
            this.connection = mqttPub.futureConnection();
            this.connection.connect();
		}

        @Override
        public void sendMessage(QueuedUplink uplink,
                                boolean retryOnFailure)  {
            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                Builder builder = Header.newBuilder()
                        .setCommand(CommandType.UPLINK)
                        .setSentTimestamp(System.currentTimeMillis());

                if (uplink.getOriginator() != null) {
                    builder.setOriginator(uplink.getOriginator());
                }
                Header header = builder.build();

                UplinkHeader.Builder ulBuilder = UplinkHeader.newBuilder();
                if (uplink.getHardwareId() != null) {
                    ulBuilder.setHardwareId(uplink.getHardwareId());
                }

                UplinkHeader uplinkHeader = ulBuilder
                        .setCommand(uplink.getUplinkCommandType())
                        .build();

                header.writeDelimitedTo(out);
                uplinkHeader.writeDelimitedTo(out);
                if (uplink.getMsg() != null) {
                    uplink.getMsg().writeDelimitedTo(out);
                }

                Future<Void> attempt = connection.publish(topic, out.toByteArray(), QoS.EXACTLY_ONCE, false);
                try {
                    attempt.await(10, TimeUnit.SECONDS);
                    if (!uplink.isCached()) {
                        if (retryUplinks.size() > 0) {
                            drainRetry();
                        }
                    } else {
                        removeUplinkRetry(uplink);
                        LOGGER.info("resent and removed cached uplink for {}", uplink.getUplinkCommandType().name());
                    }
                } catch (Exception te) {
                    LOGGER.info("Unable to publish message {}, retry={}, cannot connect to broker", uplink.getUplinkCommandType().name(), uplink.getAttempts());
                    if (retryOnFailure) {
                        addUplinkRetry(uplink);
                    }
                    if (System.currentTimeMillis() - lastConnectAttempt.get() > 60000) {
                        try {
                            cleanUp(60);
                            connection = mqttPub.futureConnection();
                            connection.connect();
                        } finally {
                            lastConnectAttempt.set(System.currentTimeMillis());
                        }
                    }
                }
            } catch (Exception ex) {
                LOGGER.error("had error processing uplink message payload while trying to publish message", ex);
            }
        }

        @Override
        public void sendUplink(String hardwareId,
                               String originator,
                               UplinkCommandType uplinkCommandType,
                               AbstractMessageLite msg,
                               boolean retry)  {
            sendMessage(new QueuedUplink(hardwareId, originator, uplinkCommandType, msg), retry);
        }

        public void cleanUp(int timeout) throws Exception {
            connection.kill().await(timeout, TimeUnit.SECONDS);
        }
    }

	/**
	 * Handles inbound commands.
	 */
	private class MQTTInbound implements Runnable {

		private FutureConnection connection;
        private MQTT mqtt;
		private String topic;
		private AgentMessageProcessor processor;
        boolean running;
        private int killAttempts;
        private Thread currentThread;

		public MQTTInbound(MQTT mqttSub, String topic,
                           AgentMessageProcessor processor) {
			this.mqtt = mqttSub;
			this.topic = topic;
			this.processor = processor;
		}

		@Override
		public void run() {
			// Subscribe to chosen topic.
			Topic[] topics = {new Topic(topic, QoS.AT_LEAST_ONCE)};
            running = true;
            currentThread = Thread.currentThread();

            while (!Thread.currentThread().isInterrupted() && running) {
                if (connection != null) {
                    try {
                        connection.kill().await(60, TimeUnit.SECONDS);
                    } catch (Exception ex) {
                        LOGGER.info("unable to terminate stale old connection");
                        try {Thread.sleep(10000);} catch(InterruptedException ie) {break;}
                        if (killAttempts++ < 5) {
                            continue;
                        }
                    }
                }
                killAttempts = 0;
                connection = mqtt.futureConnection();
                try {
                    connection.connect().await(45, TimeUnit.SECONDS);
                    connection.subscribe(topics).await(45, TimeUnit.SECONDS);
                } catch (Exception ex) {
                    LOGGER.info("unable to get connection and subscription set in 45 seconds, will try again");
                    continue;
                }
                lastSubReceived.set(System.currentTimeMillis());

                Future<Message> receive = connection.receive();
                while (!Thread.currentThread().isInterrupted() && running) {
                    try {
                        Message message = receive.await(10, TimeUnit.SECONDS);
                        receive = connection.receive();
                        if (message == null) {
                            continue;
                        }
                        lastSubReceived.set(System.currentTimeMillis());
                        message.ack();
                        processor.processDownlinkCommand(message.getPayload());
                    } catch (InterruptedException e) {
                        LOGGER.warn("Device event processor interrupted.");
                        running = false;
                        break;
                    } catch (Throwable e) {
                        if (System.currentTimeMillis() - lastSubReceived.get() > MAX_SUBSCRIPTION_INACTIVITY_TIME) {
                            LOGGER.error("Have not received a downlink in {} seconds, recreating mqtt session", MAX_SUBSCRIPTION_INACTIVITY_TIME / 1000);
                            break;
                        }
                    }
                }
            }

            if (connection != null) {
                try {
                    connection.kill().await(20, TimeUnit.SECONDS);
                } catch (Exception ex) {}
            }
		}

        public void stop() {
            running = false;
            currentThread.interrupt();
        }
    }

	/**
	 * Handles graceful shutdown of agent.
	 *
	 */
	private class ShutdownHandler extends Thread {
		@Override
		public void run() {

				try {
                    inbound.stop();
                    outbound.cleanUp(20);
					LOGGER.info("Disconnected from MQTT broker.");
				} catch (Exception e) {
					LOGGER.warn("Shutdown initiated, exception disconnecting from MQTT broker.", e);
				}

		}
	}

	/**
	 * Validates the agent configuration.
	 * 
	 * @return
	 */
	private void load(Properties properties) {
		LOGGER.info("Validating configuration...");

     	// Load command processor class name.
        commandProcessorClassname = properties.getProperty(AgentConfiguration.COMMAND_PROCESSOR_CLASSNAME);
		if (commandProcessorClassname == null) {
			LOGGER.error("Command processor class name not specified.");
			throw new IllegalStateException(AgentConfiguration.COMMAND_PROCESSOR_CLASSNAME + " is required property");
		}
        LOGGER.info("Using configured processor: " + commandProcessorClassname);

		// Validate hardware id.
        if (gwSerialNumber == null) {
            gwSerialNumber = properties.getProperty(AgentConfiguration.GATEWAY_SERIALNUMBER);
        }
		LOGGER.info("Using gateway serial number property: " + gwSerialNumber);

		// Validate MQTT hostname.
		mqttHostname = properties.getProperty(AgentConfiguration.MQTT_HOSTNAME);
		if (mqttHostname == null) {
			mqttHostname = DEFAULT_MQTT_HOSTNAME;
		}

        if (sslContext != null) {
            mqttPort = DEFAULT_MQTTS_PORT;
        } else {
            mqttPort = DEFAULT_MQTT_PORT;
        }
        LOGGER.info("Using MQTT host: {}, {}", mqttHostname, mqttPort);

        // Validate MQTT username.
        mqttUsername = properties.getProperty(AgentConfiguration.MQTT_USERNAME);
        if (gatewayPublic != null || (mqttUsername != null && mqttUsername.trim().length() < 1)) {
            mqttUsername = null;
        }
        mqttPassword = properties.getProperty(AgentConfiguration.MQTT_PASSWORD);
        if (gatewayPublic != null || (mqttPassword != null && mqttPassword.trim().length() < 1)) {
            mqttPassword = null;
        }
        LOGGER.info("Using MQTT username: " + mqttUsername);

        mqttKeepaliveSeconds = Ints.tryParse(properties.getProperty(AgentConfiguration.MQTT_KEEPALIVE,"")) != null ?
                Ints.tryParse(properties.getProperty(AgentConfiguration.MQTT_KEEPALIVE)).shortValue() : DEFAULT_MQTT_KEEPALIVE;

		// override for outbound topic.
		outboundTopic = properties.getProperty(AgentConfiguration.MQTT_OUTBOUND_TOPIC);
		if (outboundTopic == null) {
            outboundTopic = DEFAULT_MQTT_BASE_PATH + "/uplink";
		}
        LOGGER.info("Using uplink BWG MQTT topic: " + outboundTopic);

		// override for inbound topic.
		inboundTopic = properties.getProperty(AgentConfiguration.MQTT_INBOUND_TOPIC);
		if (inboundTopic == null) {
            inboundTopic = calculateInboundTopic(DEFAULT_MQTT_BASE_PATH + "/downlink");
        }
        LOGGER.info("Using downlink MQTT topic: " + inboundTopic);
	}

	private String calculateInboundTopic(String inboundPrefix) {
		return inboundPrefix + "/" + gwSerialNumber;
	}
}