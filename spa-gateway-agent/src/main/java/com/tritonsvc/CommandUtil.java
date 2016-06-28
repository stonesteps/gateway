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
public final class CommandUtil {

    private static final Logger LOGGER = LoggerFactory.getLogger(CommandUtil.class);
    private static final String uname = unameCmd();

    private CommandUtil() {
        // utility class
    }

    public static String uname() {
        return uname;
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
