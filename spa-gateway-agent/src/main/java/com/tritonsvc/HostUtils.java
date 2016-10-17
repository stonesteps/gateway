package com.tritonsvc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

/**
 * Helps with os command execution.
 */
public final class HostUtils {

    public static final String TS_IMX6 = "ts-imx6";
    public static final String BEAGLEBONE = "beaglebone";
    public static final String BWG = "linux iotgw";
    private static final Logger LOGGER = LoggerFactory.getLogger(HostUtils.class);
    private static HostUtils hostUtils;
    private final String uname;
    private final boolean isSystemD;
    private final boolean isRunit;
    private final String osType;
    private final boolean isFast;

    private HostUtils() {
        uname = unameCmd().toLowerCase();
        isSystemD = (uname.contains(TS_IMX6) || uname.contains(BEAGLEBONE));
        isRunit = (uname.contains(BWG));
        if (uname.contains(HostUtils.TS_IMX6)) {
            osType = HostUtils.TS_IMX6;
            isFast = true;
        } else if (uname.contains(HostUtils.BEAGLEBONE)) {
            osType = HostUtils.BEAGLEBONE;
            isFast = true;
        } else {
            osType = HostUtils.BWG;
            isFast = false;
        }
    }

    public boolean isSystemD() {
        return isSystemD;
    }

    public boolean isRunit() {
        return isRunit;
    }

    public boolean isFastProcessor() { return isFast; }

    public String getOsType() {
        return osType;
    }

    public static HostUtils instance() {
        if (hostUtils == null) {
            hostUtils = new HostUtils();
        }
        return hostUtils;
    }

    private static String unameCmd() {
        final StringBuilder sb = new StringBuilder();
        try {
            Process proc = executeUnixCommand("uname -a");
            proc.waitFor(2, TimeUnit.SECONDS);
            String line;
            try (final BufferedReader iwconfigInput = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                while ((line = iwconfigInput.readLine()) != null) {
                    sb.append(line);
                }
            }
        } catch (final Exception ex) {
            LOGGER.error("problem getting os type", ex);
        }
        return sb.toString();
    }

    private static Process executeUnixCommand(final String command) throws IOException {
        return Runtime.getRuntime().exec(command);
    }
}
