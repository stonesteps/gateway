package com.tritonsvc.sw_upgrade;

import com.tritonsvc.CommandUtil;
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
import java.util.Arrays;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
    private String softwareUpgradeCommand = "service bwg-gateway-agent upgrade";
    private String softwareUpgradeCommandYocto = "sudo systemctl restart bwg-gateway-agent.service";
    private String softwareUpgradeTempFile = ".upgr_last_version";
    private ExecutorService es = Executors.newSingleThreadExecutor();
    private CheckSoftwareUpgrade checker = new CheckSoftwareUpgrade();

    public SoftwareUpgradeManager(final Properties properties) {
        init(properties);
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
            this.softwareUpgradeCommandYocto = softwareUpgradeCommandYocto;
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
            try {
                final URL url = new URL(fullUpgradeUrl);
                final HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.connect();
                final int code = connection.getResponseCode();
                final String upgradePackageName = connection.getHeaderField("UPGRADE_PACKAGE_NAME");

                if (code == 200) {
                    LOGGER.info("New software package available - downloading...");
                    final ReadableByteChannel rbc = Channels.newChannel(connection.getInputStream());
                    final FileOutputStream fos = new FileOutputStream(getSoftwareUpgradeDestination());
                    fos.getChannel().transferFrom(rbc, 0, Long.MAX_VALUE);
                    bwgProcessor.sendEvents(hardwareId, Arrays.asList(buildSoftwareUpgradeEvent(upgradePackageName)));
                    writeTempFile(currentVersion);
                    LOGGER.info("Software package obtained successfully - ready for upgrade");
                    initiateSoftwareUpgradeProcedure();
                } else if (code == 204) {
                    LOGGER.info("Software is up to date, upgrade url returned code 204");
                } else {
                    LOGGER.error("Error while invoking software upgrade url, the returned code is {}", code);
                }
            } catch (final Exception e) {
                LOGGER.error("Error while downloading software upgrade package", e);
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
            IOUtils.write(currentVersion, new FileOutputStream(softwareUpgradeTempFile));
        } catch (IOException e) {
            LOGGER.error("Error while writing temp file", e);
        }
    }

    public String readOldVersionNumber() {
        String contents = null;
        final File tempFile = new File(softwareUpgradeTempFile);
        if (tempFile.exists()) {
            try {
                contents = IOUtils.toString(new FileInputStream(tempFile));
            } catch (final IOException e) {
                //ignore - the file usually will not be here
                LOGGER.error("Error while reading temp file");
            } finally {
                FileUtils.deleteQuietly(new File(softwareUpgradeTempFile));
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
        final String uname = CommandUtil.uname();
        final String upgradeCommand;
        if (StringUtils.isNotBlank(uname) && uname.toLowerCase().contains("yocto")) {
            upgradeCommand = softwareUpgradeCommandYocto;
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
}
