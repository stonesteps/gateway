package com.tritonsvc.sw_upgrade;

import com.google.common.annotations.VisibleForTesting;
import com.tritonsvc.HostUtils;
import com.tritonsvc.gateway.BWGProcessor;
import com.tritonsvc.httpd.util.SSLUtil;
import com.tritonsvc.spa.communication.proto.Bwg;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.google.common.collect.Lists.newArrayList;

/**
 * Responsible for fetching software upgrade packages and initiating upgrade procedure (calling external script).
 */
public final class SoftwareUpgradeManager {

    static {
        // turns off ssl certificate checking
        SSLUtil.turnOffSslVerification();
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(SoftwareUpgradeManager.class);

    private String softwareUpgradePackageFolder = "./upgrade";
    private String softwareUpgradePackageFilename = "upgradePackage.tar.gz";
    private String softwareUpgradeCommand = "sudo sv restart /service/bwg-gateway-agent";
    private String softwareUpgradeCommandSystemd = "sudo systemctl restart bwg-gateway-agent.service";
    private String softwareUpgradeTempFile = ".upgr_last_version";
    private String softwareUpgradeMarkerFile = ".upgr_marker";
    private ExecutorService es = Executors.newSingleThreadExecutor();
    private CheckSoftwareUpgrade checker = new CheckSoftwareUpgrade();

    public SoftwareUpgradeManager(final Properties properties) {
        init(properties);
    }

    public void shutdown() {
        es.shutdownNow();
    }

    private void init(final Properties properties) {
        final String softwareUpgradePackageFolder = properties.getProperty("softwareUpgrade.packageFolder");
        if (softwareUpgradePackageFolder != null) {
            this.softwareUpgradePackageFolder = softwareUpgradePackageFolder;
        }

        final String softwareUpgradePackageFilename = properties.getProperty("softwareUpgrade.packageFilename");
        if (softwareUpgradePackageFilename != null) {
            this.softwareUpgradePackageFilename = softwareUpgradePackageFilename;
        }

        final String softwareUpgradeCommand = properties.getProperty("softwareUpgrade.command");
        if (softwareUpgradeCommand != null) {
            this.softwareUpgradeCommand = softwareUpgradeCommand;
        }

        final String softwareUpgradeCommandYocto = properties.getProperty("softwareUpgrade.commandYocto");
        if (softwareUpgradeCommandYocto != null) {
            this.softwareUpgradeCommandSystemd = softwareUpgradeCommandYocto;
        }

        final String softwareUpgradeTempFile = properties.getProperty("softwareUpgrade.tempFile");
        if (softwareUpgradeTempFile != null) {
            this.softwareUpgradeTempFile = softwareUpgradeTempFile;
        }
    }

    /**
     * Spawns new thread to check, downalod new software and call upgrade procedure.
     *
     * @param swUpgradeUrl
     * @param currentVersion
     * @param hardwareId
     * @param bwgProcessor
     */
    public void checkAndPerformSoftwareUpgrade(final String swUpgradeUrl, final String currentVersion,
                                               final String hardwareId, final BWGProcessor bwgProcessor) {
        // upgrade in progress, do not spawn new thread
        if (checker.isRunning()) return;
        checker.setupParams(swUpgradeUrl, currentVersion, hardwareId, bwgProcessor);
        es.execute(checker);
    }

    private class CheckSoftwareUpgrade implements Runnable {
        private String swUpgradeUrl;
        private String currentVersion;
        private String hardwareId;
        private BWGProcessor bwgProcessor;
        private boolean running;

        public void setupParams(final String swUpgradeUrl, final String currentVersion,
                                final String hardwareId, final BWGProcessor bwgProcessor) {
            this.swUpgradeUrl = swUpgradeUrl;
            this.currentVersion = currentVersion;
            this.hardwareId = hardwareId;
            this.bwgProcessor = bwgProcessor;
        }

        public void run() {
            running = true;

            final String fullUpgradeUrl = swUpgradeUrl + "?currentBuildNumber=" + currentVersion;
            LOGGER.info("Checking url {} for software upgrades", fullUpgradeUrl);
            HttpURLConnection connection = null;
            try {
                try {
                    final URL url = new URL(fullUpgradeUrl);
                    connection = (HttpURLConnection) url.openConnection();
                    connection.connect();
                } catch (Exception ex) {
                    LOGGER.info("skip sw upgrade, url {} was not reachable", fullUpgradeUrl, ex);
                    connection = null;
                }

                if (connection != null) {
                    int code = connection.getResponseCode();
                    if ( code == 200) {
                        final String upgradePackageName = connection.getHeaderField("UPGRADE_PACKAGE_NAME");
                        long bytesSent = connection.getContentLengthLong();
                        LOGGER.info("New software package available {} - downloading...", upgradePackageName);
                        try (ReadableByteChannel rbc = Channels.newChannel(connection.getInputStream());
                             FileOutputStream fos = new FileOutputStream(getSoftwareUpgradeDestination())) {
                            long bytesCopied = fos.getChannel().transferFrom(rbc, 0, bytesSent);
                            if (bytesCopied != bytesSent) {
                                throw new Exception("invalid upgrade file download, bytes sent was " + bytesSent + ", but bytes copied was " + bytesCopied);
                            }
                        }
                        bwgProcessor.sendEvents(hardwareId, newArrayList(buildSoftwareUpgradeEvent(upgradePackageName)));
                        writeTempFile(currentVersion);
                        LOGGER.info("Software package obtained successfully {} - ready for upgrade", upgradePackageName);
                        initiateSoftwareUpgradeProcedure();
                    } else if (code == 204) {
                        LOGGER.info("Software is up to date, upgrade url returned code 204");
                    } else {
                        LOGGER.error("Error while invoking software upgrade url, the returned code is {}", code);
                    }
                }
            } catch (final Exception e) {
                LOGGER.error("Error while downloading software upgrade package", e);
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
            running = false;
        }

        private boolean isRunning() {
            return running;
        }
    }

    /**
     * Wites current version to temp file. After upgrade and restart the file is read and event sent to mqtt with current and old version.
     *
     * @param currentVersion
     */
    private void writeTempFile(final String currentVersion) {
        try {
            IOUtils.write(currentVersion, new FileOutputStream(new File(softwareUpgradePackageFolder, softwareUpgradeTempFile)));
        } catch (IOException e) {
            LOGGER.error("Error while writing temp file", e);
        }
    }

    private void writeUpgradeMarkerFile() {
        try {
            FileUtils.touch(new File(softwareUpgradePackageFolder, softwareUpgradeMarkerFile));
        } catch (IOException e) {
            LOGGER.error("Error while writing marker file", e);
        }
    }

    public String readOldVersionNumber() {
        String contents = null;
        final File tempFile = new File(softwareUpgradePackageFolder, softwareUpgradeTempFile);
        if (tempFile.exists()) {
            try {
                contents = IOUtils.toString(new FileInputStream(tempFile));
            } catch (final IOException e) {
                //ignore - the file usually will not be here
                LOGGER.error("Error while reading temp file");
            } finally {
                FileUtils.deleteQuietly(tempFile);
            }
        }
        return contents;
    }

    private Bwg.Uplink.Model.Event buildSoftwareUpgradeEvent(final String upgradePackageName) {
        final long timestamp = System.currentTimeMillis();
        final Bwg.Uplink.Model.Event event = Bwg.Uplink.Model.Event.newBuilder()
                .setEventOccuredTimestamp(timestamp)
                .setEventReceivedTimestamp(timestamp)
                .setEventType(Bwg.Uplink.Model.Constants.EventType.NOTIFICATION)
                .setDescription(StringUtils.isNotBlank(upgradePackageName)
                        ? "Downloaded software upgrade package " + upgradePackageName + ", initiating upgrade procedure"
                        : "Downloaded software upgrade package, initiating upgrade procedure")
                .build();

        return event;
    }

    private File getSoftwareUpgradeDestination() {
        final File swUpgradeDestinationFolder = new File(softwareUpgradePackageFolder);
        if (!swUpgradeDestinationFolder.exists()) {
            swUpgradeDestinationFolder.mkdirs();
        }
        return new File(swUpgradeDestinationFolder, softwareUpgradePackageFilename);
    }

    private void initiateSoftwareUpgradeProcedure() {
        final String upgradeCommand;
        writeUpgradeMarkerFile();
        if (getHostUtils().isSystemD()) {
            upgradeCommand = softwareUpgradeCommandSystemd;
        } else {
            upgradeCommand = softwareUpgradeCommand;
        }

        LOGGER.info("Initiating software upgrade procedure, executing script {}", upgradeCommand);
        try {
            Runtime.getRuntime().exec(upgradeCommand);
        } catch (final IOException e) {
            LOGGER.error("Error while initiating software upgrade procedure");
        }
    }

    @VisibleForTesting
    HostUtils getHostUtils() {
        return HostUtils.instance();
    }
}
