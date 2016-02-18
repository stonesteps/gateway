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

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.net.URISyntaxException;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

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

	/** Defaults*/
	private static final int DEFAULT_MQTT_PORT = 1883;
    private static final short DEFAULT_MQTT_KEEPALIVE = 30;
    private static final String DEFAULT_MQTT_HOSTNAME = "localhost";

	/** Command processor Java classname */
	private String commandProcessorClassname;

    /** The uplink queue on mqtt broker **/
    private static final String DEFAULT_MQTT_BASE_PATH = "BWG/spa";

	/** Hardware id */
	private String hardwareId;

	/** MQTT server hostname */
	private String mqttHostname;

	/** MQTT server port */
	private int mqttPort;

    /** MQTT keep alive **/
    private short mqttKeepaliveSeconds;

	/** Outbound MQTT topic */
	private String outboundTopic;

	/** Inbound MQTT topic */
	private String inboundTopic;

	/** MQTT client */
	private MQTT mqtt;

	/** MQTT connection */
	private BlockingConnection connection;

	/** Outbound message processing */
	private MQTTOutbound outbound;

	/** Inbound message processing */
	private MQTTInbound inbound;

	/** Used to execute MQTT inbound in separate thread */
	private ExecutorService executor = Executors.newSingleThreadExecutor();

    /** BWGProcessor instance **/
    private AgentMessageProcessor processor;

	/**
	 * Start the agent.
     *
     * @param homePath
	 */
	public void start(String homePath) {
        Properties props = loadProperties(homePath);
		LOGGER.info("BWG agent starting...");

		this.mqtt = createMQTT();
		try {
			mqtt.setHost(mqttHostname, mqttPort);
            mqtt.setCleanSession(false);
            mqtt.setClientId(hardwareId);
            mqtt.setKeepAlive(mqttKeepaliveSeconds);

            // set up the mqtt broker connection health logger
            if (MQTT_TRACE_LOGGER.isDebugEnabled()) {
                mqtt.setTracer(new Tracer() {
                    @Override
                    public void debug(String message, Object...args) {
                        MQTT_TRACE_LOGGER.debug(message, args);
                    }
                });
            }
		} catch (URISyntaxException e) {
			throw Throwables.propagate(e);
		}
		LOGGER.info("Connecting to MQTT broker at '" + mqttHostname + ":" + mqttPort + "'...");

        connection = mqtt.blockingConnection();
        try {
            connection.connect();
        } catch (Exception e) {
            throw Throwables.propagate(e);
        }

		// Create outbound message processor.
		outbound = new MQTTOutbound(connection, outboundTopic);

		// Create an instance of the command processor.
        processor = createProcessor();
		processor.setHardwareId(hardwareId);
        processor.setConfigProps(props);
        processor.setHomePath(homePath);
        processor.setEventDispatcher(outbound);

		// Create inbound message processing thread.
		inbound = new MQTTInbound(connection, inboundTopic, processor, outbound);

		// Handle shutdown gracefully.
		Runtime.getRuntime().addShutdownHook(new ShutdownHandler());

		// Starts inbound processing loop in a separate thread.
		executor.execute(inbound);

		// Executes any custom startup logic.
		processor.executeStartup();

		LOGGER.info("BWG agent started.");
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
                Builder builder = Header.newBuilder()
                        .setCommand(CommandType.UPLINK)
                        .setSentTimestamp(System.currentTimeMillis());

                if (originator != null) {
                    builder.setOriginator(originator);
                }
                Header header = builder.build();

                UplinkHeader uplinkHeader = UplinkHeader.newBuilder()
                        .setHardwareId(hardwareId)
                        .setCommand(uplinkCommandType)
                        .build();

                header.writeDelimitedTo(out);
                uplinkHeader.writeDelimitedTo(out);
                if (msg != null) {
                    msg.writeDelimitedTo(out);
                }
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

		/** Event dispatcher */
		private GatewayEventDispatcher dispatcher;

        /** run flag **/
        boolean running;

		public MQTTInbound(BlockingConnection connection, String topic,
                           AgentMessageProcessor processor, GatewayEventDispatcher dispatcher) {
			this.connection = connection;
			this.topic = topic;
			this.processor = processor;
			this.dispatcher = dispatcher;
		}

		@Override
		public void run() {
			// Subscribe to chosen topic.
			Topic[] topics = {new Topic(topic, QoS.AT_LEAST_ONCE)};
            running = true;

            while (running) {
                try {
                    connection.subscribe(topics);
                    LOGGER.info("Started MQTT inbound processing thread.");
                } catch (Exception e) {
                    LOGGER.error("Exception while attempting to subscribe to inbound topics.", e);
                    try {Thread.sleep(5000);} catch(InterruptedException ie) {return;}
                    continue;
                }

                while (running) {
                    try {
                        Message message = connection.receive(10, TimeUnit.SECONDS);
                        if (message == null) {
                            LOGGER.debug("no downlink messages received in last 10 seconds");
                            continue;
                        }
                        message.ack();
                        processor.processDownlinkCommand(message.getPayload(), dispatcher);
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
			if (connection != null) {
				try {
                    inbound.stop();
					connection.disconnect();
					LOGGER.info("Disconnected from MQTT broker.");
				} catch (Exception e) {
					LOGGER.warn("Shutdown initiated, exception disconnecting from MQTT broker.", e);
				}
			}
		}
	}

	/**
	 * Validates the agent configuration.
	 * 
	 * @return
	 */
	private boolean load(Properties properties) {
		LOGGER.info("Validating configuration...");

     	// Load command processor class name.
        commandProcessorClassname = properties.getProperty(AgentConfiguration.COMMAND_PROCESSOR_CLASSNAME);
		if (commandProcessorClassname == null) {
			LOGGER.error("Command processor class name not specified.");
			return false;
		}
        LOGGER.info("Using configured processor: " + commandProcessorClassname);

		// Validate hardware id.
		hardwareId = properties.getProperty(AgentConfiguration.DEVICE_HARDWARE_ID);
		if (hardwareId == null) {
			return false;
		}
		LOGGER.info("Using configured device hardware id: " + hardwareId);

		// Validate MQTT hostname.
		mqttHostname = properties.getProperty(AgentConfiguration.MQTT_HOSTNAME);
		if (mqttHostname == null) {
			mqttHostname = DEFAULT_MQTT_HOSTNAME;
		}
        LOGGER.info("Using MQTT hostname: " + DEFAULT_MQTT_HOSTNAME);

		// Validate MQTT port.
        mqttPort = Ints.tryParse(properties.getProperty(AgentConfiguration.MQTT_PORT,"")) != null ?
                Ints.tryParse(properties.getProperty(AgentConfiguration.MQTT_PORT)) : DEFAULT_MQTT_PORT;

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

		return true;
	}

	private String calculateInboundTopic(String inboundPrefix) {
		return inboundPrefix + "/" + hardwareId;
	}
}