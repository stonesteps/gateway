package com.tritonsvc.agent;

/**
 * Constants for agent configuration properties.
 *
 */
public interface AgentConfiguration {

	/** Property for command processor classname */
	public static final String COMMAND_PROCESSOR_CLASSNAME = "command.processor.classname";

	/** Property for device unique hardware id */
	public static final String DEVICE_HARDWARE_ID = "device.hardware.id";

	/** Property for MQTT hostname */
	public static final String MQTT_HOSTNAME = "mqtt.hostname";

	/** Property for MQTT port */
	public static final String MQTT_PORT = "mqtt.port";

	/** Optional property for outbound MQTT topic, default is BWG/spa/uplink */
	public static final String MQTT_OUTBOUND_TOPIC = "mqtt.outbound.topic";

	/** Optional property for inbound MQTT topic, default is BWG/spa/downlink/[hardwareId] */
	public static final String MQTT_INBOUND_TOPIC = "mqtt.inbound.topic";

	/** Optional property for keepalive on MQTT, defaults to 30 seconds */
	public static final String MQTT_KEEPALIVE = "mqtt.keepalive.seconds";

    /** Optional property for how often the controller status info should be sent */
    public static final String CONTROLLER_UPDATE_INTERVAL = "bwg.controller.update_interval_seconds";

}