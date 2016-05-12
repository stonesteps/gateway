package com.tritonsvc.agent;

import com.google.common.base.Throwables;
import com.tritonsvc.httpd.NetworkSettingsHolder;
import com.tritonsvc.model.AgentSettings;
import com.tritonsvc.model.NetworkSettings;
import com.tritonsvc.spa.communication.proto.Bwg;
import com.tritonsvc.spa.communication.proto.Bwg.AckResponseCode;
import com.tritonsvc.spa.communication.proto.Bwg.Downlink.Model.RegistrationResponse;
import com.tritonsvc.spa.communication.proto.Bwg.Downlink.Model.Request;
import com.tritonsvc.spa.communication.proto.Bwg.Downlink.Model.SpaRegistrationResponse;
import com.tritonsvc.spa.communication.proto.Bwg.Downlink.Model.UplinkAcknowledge;
import com.tritonsvc.spa.communication.proto.Bwg.Metadata;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.Model.*;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.UplinkCommandType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.naming.ldap.LdapName;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Enumeration;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.jar.Manifest;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.collect.Maps.newHashMap;

/**
 * Concrete class for handling downlink and sending uplink on MQTT
 */
public abstract class MQTTCommandProcessor implements AgentMessageProcessor, NetworkSettingsHolder {

    private static final Logger LOGGER = LoggerFactory.getLogger(MQTTCommandProcessor.class);
    private static final String AGENT_SETTINGS_PROPERTIES_FILENAME = "agentSettings.properties";

    private String gwSerialNumber;
    private Properties configProps;
    private String homePath;
    private String dataPath;
    private GatewayEventDispatcher eventDispatcher;
    private int controllerUpdateInterval = 3;
    private final ScheduledExecutorService scheduledExecutorService = new ScheduledThreadPoolExecutor(4);
    private X509Certificate publicCert;
    private PrivateKey privateKey;
    private AgentSettings agentSettings;
    private Map<String, String> buildParams = newHashMap();

    protected abstract void handleRegistrationAck(RegistrationResponse response, String originatorId, String hardwareId);

    protected abstract void handleSpaRegistrationAck(SpaRegistrationResponse response, String originatorId, String hardwareId);

    protected abstract void handleDownlinkCommand(Request request, String hardwareId, String originatorId);

    protected abstract void handleUplinkAck(UplinkAcknowledge ack, String originatorId);

    protected abstract void handleStartup(String gwSerialNumber, Properties configProps, String homePath, ScheduledExecutorService executorService);

    protected abstract void handleShutdown();

    protected abstract void processDataHarvestIteration();

    /**
     * Constructor
     */
    public MQTTCommandProcessor() {
        loadBuildProps();
        LOGGER.info("agent build version {}", getBuildParams().get("BWG-Agent-Version"));
        LOGGER.info("agent build number {}", getBuildParams().get("BWG-Agent-Build-Number"));
        LOGGER.info("agent scm revision {}", getBuildParams().get("BWG-Agent-SCM-Revision"));

        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run() {
                handleShutdown();
                scheduledExecutorService.shutdownNow();
                try {
                    scheduledExecutorService.awaitTermination(10, TimeUnit.SECONDS);
                } catch (InterruptedException ex) {
                }
            }
        });
    }

    @Override
    public void executeStartup() {
        loadAgentSettings();
        handleStartup(gwSerialNumber, configProps, homePath, scheduledExecutorService);
        kickOffDataHarvest();
    }

    @Override
    public void processDownlinkCommand(byte[] message) {
        ByteArrayInputStream stream = new ByteArrayInputStream(message);
        try {
            Bwg.Header header = Bwg.Header.parseDelimitedFrom(stream);
            if (!header.getCommand().equals(Bwg.CommandType.DOWNLINK)) {
                throw new IllegalArgumentException("Invalid downlink command received");
            }

            Bwg.Downlink.DownlinkHeader downlinkHeader = Bwg.Downlink.DownlinkHeader.parseDelimitedFrom(stream);

            LOGGER.info("received downlink command " + downlinkHeader.getCommandType().name() + ", dated " + header.getSentTimestamp());
            switch (downlinkHeader.getCommandType()) {
                case REGISTRATION_RESPONSE: {
                    RegistrationResponse response = RegistrationResponse.parseDelimitedFrom(stream);
                    handleRegistrationAck(response, header.getOriginator(), downlinkHeader.getHardwareId());
                    break;
                }
                case SPA_REGISTRATION_RESPONSE: {
                    SpaRegistrationResponse response = SpaRegistrationResponse.parseDelimitedFrom(stream);
                    handleSpaRegistrationAck(response, header.getOriginator(), downlinkHeader.getHardwareId());
                    break;
                }
                case REQUEST: {
                    Request request = Request.parseDelimitedFrom(stream);
                    handleDownlinkCommand(request, downlinkHeader.getHardwareId(), header.getOriginator());
                    break;
                }
                case ACK: {
                    UplinkAcknowledge ack = UplinkAcknowledge.parseDelimitedFrom(stream);
                    handleUplinkAck(ack, header.getOriginator());
                    break;
                }
            }
        } catch (IOException e) {
            throw Throwables.propagate(e);
        }
    }

    @Override
    public void setGwSerialNumber(String gwSerialNumber) {
        this.gwSerialNumber = gwSerialNumber;
    }

    @Override
    public void setConfigProps(Properties props) {
        this.configProps = props;
    }

    @Override
    public void setHomePath(String path) {
        this.homePath = path;
    }

    public void setDataPath(String path) {
        this.dataPath = path;
    }

    public String getDataPath() {
        return this.dataPath;
    }

    public Map<String, String> getBuildParams() {
        return buildParams;
    }

    @Override
    public void setEventDispatcher(GatewayEventDispatcher eventDispatcher) {
        this.eventDispatcher = eventDispatcher;
    }

    @Override
    public void setPKI(X509Certificate publicCert, PrivateKey privateKey) {
        this.publicCert = publicCert;
        this.privateKey = privateKey;
        if (publicCert == null) {
            return;
        }

        // override the gw serial number with cert's CN
        String dn = null;
        try {
            dn = publicCert.getSubjectX500Principal().getName();
            LdapName ldapDN = new LdapName(dn);
            ldapDN.getRdns()
                    .stream()
                    .filter(rdn -> rdn.getType().equalsIgnoreCase("CN"))
                    .map(rdn -> this.gwSerialNumber = rdn.getValue().toString());
        } catch (Exception ex) {
            LOGGER.error("unable to extract CN from gateway certificate DN {}", dn);
        }
    }

    /**
     * Convenience method to tell if the processor is enabled to be executing
     *
     * @return true if running
     */
    public boolean stillRunning() {
        return !Thread.currentThread().isInterrupted() && !scheduledExecutorService.isShutdown();
    }

    /**
     * Convenience method for sending device registration
     *
     * @param parentHardwareId
     * @param gwSerialNumber
     * @param deviceTypeName
     * @param meta
     */
    public void sendRegistration(String parentHardwareId, String gwSerialNumber, String deviceTypeName, Map<String, String> meta, String originatorId) {
        RegisterDevice.Builder builder = RegisterDevice.newBuilder();
        for (Map.Entry<String, String> entry : meta.entrySet()) {
            builder.addMetadata(Metadata.newBuilder().setName(entry.getKey()).setValue(entry.getValue()).build());
        }
        builder.setDeviceTypeName(deviceTypeName);
        if (parentHardwareId != null) {
            builder.setParentDeviceHardwareId(parentHardwareId);
        }
        builder.setGatewaySerialNumber(gwSerialNumber);
        eventDispatcher.sendUplink(null, originatorId, UplinkCommandType.REGISTRATION, builder.build());
        LOGGER.info("sent device registration for {}", deviceTypeName);
    }

    /**
     * Convenience method for sending an acknowledgement event for prior downlink
     *
     * @param hardwareId
     * @param originator
     */
    public void sendAck(String hardwareId, String originator, AckResponseCode code, String description) {
        DownlinkAcknowledge.Builder builder = DownlinkAcknowledge.newBuilder()
                .setCode(code);
        if (description != null) {
            builder.setDescription(description);
        }
        eventDispatcher.sendUplink(hardwareId, originator, UplinkCommandType.ACKNOWLEDGEMENT, builder.build());
    }

    /**
     * Convenience method for sending a measurement event to cloud.
     *
     * @param hardwareId
     * @param originator
     * @param measurements
     * @param measurementTimestampMillis
     * @param meta
     */
    public void sendMeasurements(String hardwareId,
                                 String originator,
                                 Map<String, Double> measurements,
                                 long measurementTimestampMillis,
                                 Map<String, String> meta) {
        checkNotNull(hardwareId, "hardwareId must not be null for measurements");
        DeviceMeasurements.Builder builder = DeviceMeasurements.newBuilder();
        builder.setEventDate(measurementTimestampMillis);
        for (Map.Entry<String, Double> entry : measurements.entrySet()) {
            builder.addMeasurement(Measurement.newBuilder().setMeasurementId(entry.getKey()).setMeasurementValue(entry.getValue()).build());
        }

        for (Map.Entry<String, String> entry : meta.entrySet()) {
            builder.addMetadata(Metadata.newBuilder().setName(entry.getKey()).setValue(entry.getValue()).build());
        }
        eventDispatcher.sendUplink(hardwareId, originator, UplinkCommandType.MEASUREMENT, builder.build());
    }

    /**
     * convenience method to send events to cloud
     *
     * @param hardwareId
     * @param eventType
     * @param eventTimestampMillis
     * @param meta
     */
    public void sendEvent(String hardwareId, Constants.EventType eventType, long eventTimestampMillis, Map<String, String> meta) {
        Event.Builder eb = Event.newBuilder();
        eb.setEventTimestamp(eventTimestampMillis);
        eb.setEventType(eventType);
        for (Map.Entry<String, String> entry : meta.entrySet()) {
            eb.addMetadata(Metadata.newBuilder().setName(entry.getKey()).setValue(entry.getValue()).build());
        }
        eventDispatcher.sendUplink(hardwareId, null, UplinkCommandType.EVENT, eb.build());
    }

    public void sendSpaState(String hardwareId, Bwg.Uplink.Model.SpaState spaState) {
        getCloudDispatcher().sendUplink(hardwareId, null, UplinkCommandType.SPA_STATE, spaState);
    }

    /**
     * obtain a repeatable unique key for each device registration
     *
     * @param parentHwId
     * @param deviceTypeName
     * @param identityAttributes
     * @return
     */
    public String generateRegistrationKey(String parentHwId, String deviceTypeName, Map<String, String> identityAttributes) {
        return Long.toString(0xFFFFFFFFL & Objects.hash(parentHwId == null ? "" : parentHwId, deviceTypeName, identityAttributes));
    }

    /**
     * retrieve the instance of mqtt message dispatcher to send messages up to cloud
     *
     * @return
     */
    public GatewayEventDispatcher getCloudDispatcher() {
        return eventDispatcher;
    }

    // after 30 seconds from start, and once every X minutes send up to cloud whatever system states
    // the X reporting interval should be settable via other reuest messages like setSystemStateReportInterval(), etc
    private void kickOffDataHarvest() {
        scheduledExecutorService.scheduleWithFixedDelay((Runnable) () -> {
            try {
                processDataHarvestIteration();
            } catch (Throwable ex) {
                LOGGER.error("unable to process data harvest iteration", ex);
            }
        }, controllerUpdateInterval, controllerUpdateInterval, TimeUnit.SECONDS);
    }

    @Override
    public NetworkSettings getNetworkSettings() {
        return this.agentSettings.getNetworkSettings();
    }

    @Override
    public void setNetworkSettings(final NetworkSettings networkSettings) {
        this.agentSettings.setNetworkSettings(networkSettings);
        saveAgentSettings();
    }

    protected AgentSettings getAgentSettings() {
        return agentSettings;
    }

    protected synchronized void loadAgentSettings() {
        final File networkSettingFile = new File(dataPath, AGENT_SETTINGS_PROPERTIES_FILENAME);
        this.agentSettings = AgentSettingsPersister.load(networkSettingFile);
    }

    protected synchronized void saveAgentSettings() {
        final File networkSettingFile = new File(dataPath, AGENT_SETTINGS_PROPERTIES_FILENAME);
        AgentSettingsPersister.save(networkSettingFile, this.agentSettings);
    }

    private void loadBuildProps() {
        try {
            Enumeration<URL> resources = getClass().getClassLoader().getResources("META-INF/MANIFEST.MF");
            while (resources.hasMoreElements()) {
                Manifest manifest = new Manifest(resources.nextElement().openStream());
                if (manifest.getMainAttributes().getValue("BWG-Version") != null ) {
                    buildParams.put("BWG-Agent-Version", manifest.getMainAttributes().getValue("BWG-Version"));
                    buildParams.put("BWG-Agent-Build-Number", manifest.getMainAttributes().getValue("BWG-Build-Number"));
                    buildParams.put("BWG-Agent-SCM-Revision", manifest.getMainAttributes().getValue("BWG-SCM-Revision"));
                    break;
                }
            }
        } catch (Exception ex) {
            LOGGER.info("unable to obtain build info from jar");
        }
    }
}