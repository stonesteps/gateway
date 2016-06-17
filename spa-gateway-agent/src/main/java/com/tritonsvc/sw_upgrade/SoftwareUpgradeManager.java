package com.tritonsvc.sw_upgrade;

import com.tritonsvc.CommandUtil;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.util.Properties;

/**
 * Responsible for fetching software upgrade packages and initiating upgrade procedure (calling external script).
 */
public final class SoftwareUpgradeManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(SoftwareUpgradeManager.class);

    private String softwareUpgradePackageFolder = "./";
    private String softwareUpgradePackageFilename = "upgradePackage.tar.gz";
    private String softwareUpgradeCommand = "service bwg-gateway-agent upgrade";
    private String softwareUpgradeCommandYocto = "sudo systemctl restart bwg-gateway-agent.service";

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
    }

    public boolean checkSoftwareUpgradeAvailable(final String swUpgradeUrl) {
        LOGGER.info("Checking url {} for software upgrades", swUpgradeUrl);
        boolean upgradePackageDownloaded = false;

        try {
            final URL url = new URL(swUpgradeUrl);
            final HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.connect();
            int code = connection.getResponseCode();

            if (code == 200) {
                LOGGER.info("New software package available - downloading...");
                final ReadableByteChannel rbc = Channels.newChannel(connection.getInputStream());
                final FileOutputStream fos = new FileOutputStream(getSoftwareUpgradeDestination());
                fos.getChannel().transferFrom(rbc, 0, Long.MAX_VALUE);

                upgradePackageDownloaded = true;
                LOGGER.info("Software package obtained successfully - ready for upgrade");
            } else {
                LOGGER.info("Software is up to date");
            }
        } catch (final Exception e) {
            LOGGER.error("Error while downloading software upgrade package", e);
        }

        return upgradePackageDownloaded;
    }

    private File getSoftwareUpgradeDestination() {
        final File swUpgradeDestinationFolder = new File(softwareUpgradePackageFolder);
        if (!swUpgradeDestinationFolder.exists()) {
            swUpgradeDestinationFolder.mkdirs();
        }
        return new File(swUpgradeDestinationFolder, softwareUpgradePackageFilename);
    }

    public void initiateSoftwareUpgradeProcedure() {
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
