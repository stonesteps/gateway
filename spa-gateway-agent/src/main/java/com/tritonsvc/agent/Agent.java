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
import org.fusesource.mqtt.client.BlockingConnection;
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
import java.net.URL;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.jar.Manifest;

import static com.google.common.collect.Maps.newHashMap;
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

	/** MQTT connection */
	private BlockingConnection subConnection;

    /** MQTT connection */
    private BlockingConnection pubConnection;

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

        subConnection = mqttSub.blockingConnection();
        pubConnection = mqttPub.blockingConnection();

		// Create outbound message processor.
		outbound = new MQTTOutbound(pubConnection, outboundTopic);

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
		inbound = new MQTTInbound(subConnection, inboundTopic, processor);

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
			AgentMessageProcessor processor = (AgentMessageProcessor) clazz.newInstance();
			return processor;
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

	private static class MQTTOutbound implements GatewayEventDispatcher {

		/** MQTT outbound topic */
		private String topic;

		/** MQTT connection */
		private BlockingConnection connection;

		public MQTTOutbound(BlockingConnection connection, String topic) {
			this.connection = connection;
			this.topic = topic;
		}

        @Override
		public void sendUplink(String hardwareId,
                               String originator,
                               UplinkCommandType uplinkCommandType,
                               AbstractMessageLite msg)  {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			try {
                if (!connection.isConnected()) {
                    connection.connect();
                    //TODO needs to be callback connection with onsuccess/failure handlers, cache uplink if possible
                }

                Builder builder = Header.newBuilder()
                        .setCommand(CommandType.UPLINK)
                        .setSentTimestamp(System.currentTimeMillis());

                if (originator != null) {
                    builder.setOriginator(originator);
                }
                Header header = builder.build();

                UplinkHeader.Builder ulBuilder = UplinkHeader.newBuilder();
                if (hardwareId != null) {
                    ulBuilder.setHardwareId(hardwareId);
                }

                UplinkHeader uplinkHeader = ulBuilder
                        .setCommand(uplinkCommandType)
                        .build();

                header.writeDelimitedTo(out);
                uplinkHeader.writeDelimitedTo(out);
                if (msg != null) {
                    msg.writeDelimitedTo(out);
                }

                //TODO needs to be call back connection, with onsuccess/failure handlers
				connection.publish(topic, out.toByteArray(), QoS.EXACTLY_ONCE, false);
            } catch (Exception e) {
				throw Throwables.propagate(e);
			}
		}
    }

	/**
	 * Handles inbound commands.
	 */
	private static class MQTTInbound implements Runnable {

		/** MQTT connection */
		private BlockingConnection connection;

		/** inbound MQTT topic */
		private String topic;

		/** Command processor */
		private AgentMessageProcessor processor;

        /** run flag **/
        boolean running;

		public MQTTInbound(BlockingConnection connection, String topic,
                           AgentMessageProcessor processor) {
			this.connection = connection;
			this.topic = topic;
			this.processor = processor;
		}

		@Override
		public void run() {
			// Subscribe to chosen topic.
			Topic[] topics = {new Topic(topic, QoS.AT_LEAST_ONCE)};
            running = true;

            while (running) {
                try {
                    if (!connection.isConnected()) {
                        connection.connect();
                    }
                    connection.subscribe(topics);
                    LOGGER.info("Started MQTT inbound processing thread.");
                } catch (Exception e) {
                    LOGGER.error("Exception while attempting to subscribe to inbound topics.", e);
                    try {Thread.sleep(5000);} catch(InterruptedException ie) {return;}
                    continue;
                }

                while (running) {
                    try {
                        Message message = connection.receive();
                        if (message == null) {
                            LOGGER.debug("no downlink messages received in last 10 seconds");
                            continue;
                        }
                        message.ack();
                        processor.processDownlinkCommand(message.getPayload());
                    } catch (InterruptedException e) {
                        LOGGER.warn("Device event processor interrupted.");
                        return;
                    } catch (Throwable e) {
                        LOGGER.error("Exception processing inbound message", e);
                        try {Thread.sleep(5000);} catch(InterruptedException ie) {return;}
                    }
                }
            }
		}

        public void stop() {
            running = false;
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
                    if (subConnection != null) {
                        subConnection.disconnect();
                    }
                    if (pubConnection != null) {
                        pubConnection.disconnect();
                    }
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