package com.tritonsvc.agent;

import java.util.Properties;

/**
 * Interface for agent message processor
 *
 */
public interface AgentMessageProcessor {

	/**
	 * Executes logic that happens before the standard processing loop.
	 *
	 */
	public void executeStartup();

	/**
	 * Process downlink command.
	 * 
	 * @param message
	 */
	public void processDownlinkCommand(byte[] message);


	/**
	 * Set based on hardware id configured in agent.
	 * 
	 * @param hardwareId
	 */
	public void setGwSerialNumber(String hardwareId);

    /**
     * Set the runtime props
     *
     * @param props
     */
    public void setConfigProps(Properties props);

	/**
	 * Set the runtime working directory for agent process
	 *
	 * @param path
     */
	public void setHomePath(String path);

    /**
     * Set the event dispatcher that allows data to be sent on uplink to cloud.
     *
     * @param dispatcher
     */
    public void setEventDispatcher(GatewayEventDispatcher dispatcher);
}