package com.tritonsvc.agent;

import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Properties;

/**
 * Interface for agent message processor
 */
public interface AgentMessageProcessor {

    /**
     * Executes logic that happens before the standard processing loop.
     */
    void executeStartup();

    /**
     * Process downlink command.
     *
     * @param message
     */
    void processDownlinkCommand(byte[] message);


    /**
     * Set based on hardware id configured in agent.
     *
     * @param hardwareId
     */
    void setGwSerialNumber(String hardwareId);

    /**
     * Set the runtime props
     *
     * @param props
     */
    void setConfigProps(Properties props);

    /**
     * Set the runtime working directory for agent process
     *
     * @param path
     */
    void setHomePath(String path);

    /**
     * Set theruntime data directory for agent process
     *
     * @param path
     */
    void setDataPath(String path);

    /**
     * Set the event dispatcher that allows data to be sent on uplink to cloud.
     *
     * @param dispatcher
     */
    void setEventDispatcher(GatewayEventDispatcher dispatcher);

    /**
     * Set the pki files for gateway
     *
     * @param publicCert
     * @param privateKey
     */
    void setPKI(X509Certificate publicCert, PrivateKey privateKey);

}