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
	public static final String RS485_GATEWAY_ADDRESS = "rs485.gateway.address";

	/** Optional Property for serial port that rs485 is located **/
	public static final String RS485_LINUX_SERIAL_PORT_BAUD = "rs485.port.baudrate";

	/** Optioal Property for wifi device name on linux **/
	public static final String WIFI_DEVICE_NAME = "wifi.device";

	/** Optioal Property for iwconfig path on linux **/
	public static final String WIFI_IWCONFIG_PATH = "wifi.iwconfig.path";

	/** Optioal Property for iwconfig path on linux **/
	public static final String WIFI_IFCONFIG_PATH = "wifi.ifconfig.path";

	/** Optioal Property for ethernet device  on linux **/
	public static final String ETHERNET_DEVICE_NAME = "ethernet.device";

	/** Optioal Property web service port **/
	public static final String AP_MODE_WEB_SERVER_PORT = "webserver.port";

	/** Optioal Property web service ssl enabled, true or false **/
	public static final String AP_MODE_WEB_SERVER_SSLENABLED = "webserver.ssl";

	/** Optioal Property web service idle timeout **/
	public static final String AP_MODE_WEB_SERVER_TIMEOUT_SECONDS = "webserver.timeout.seconds";

	/** Optioal Property skip sw upgrade **/
	public static final String SKIP_UPGARDE = "software.upgrade.skip";

	/** Optioal Property gen fake sensor data **/
	public static final String GENERATE_FAKE_SENSOR = "software.generate.fake_sensor_data";
}