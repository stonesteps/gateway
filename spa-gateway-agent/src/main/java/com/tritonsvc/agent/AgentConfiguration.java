package com.tritonsvc.agent;

/**
 * Constants for agent configuration properties.
 *
 */
public interface AgentConfiguration {

	/** Property for command processor classname */
	public static final String COMMAND_PROCESSOR_CLASSNAME = "command.processor.classname";

	/** Property for device unique hardware id */
	public static final String GATEWAY_SERIALNUMBER = "spa.gateway.serialnumber";

	/** Property for MQTT hostname */
	public static final String MQTT_HOSTNAME = "mqtt.hostname";

	/** Property for MQTT username */
	public static final String MQTT_USERNAME = "mqtt.username";

    /** Property for MQTT password */
    public static final String MQTT_PASSWORD = "mqtt.password";

	/** Optional property for outbound MQTT topic, default is BWG/spa/uplink */
	public static final String MQTT_OUTBOUND_TOPIC = "mqtt.outbound.topic";

	/** Optional property for inbound MQTT topic, default is BWG/spa/downlink/[hardwareId] */
	public static final String MQTT_INBOUND_TOPIC = "mqtt.inbound.topic";

	/** Optional property for keepalive on MQTT, defaults to 30 seconds */
	public static final String MQTT_KEEPALIVE = "mqtt.keepalive.seconds";

  	/** Optional Property for serial port that rs485 is located **/
	public static final String RS485_LINUX_SERIAL_PORT = "rs485.port";

	/** Optional Property for serial port that rs485 is located **/
	public static final String RS485_LINUX_SERIAL_PORT_BAUD = "rs485.port.baudrate";
}