package com.tritonsvc.agent;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.core.joran.spi.JoranException;
import ch.qos.logback.core.util.StatusPrinter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;

/**
 * Bootstraps the Java agent.
 *
 */
public class AgentLoader {

	/** Static logger instance */
	private static Logger LOGGER = LoggerFactory.getLogger(AgentLoader.class);

	/** Agent controlled by this loader */
	private static Agent agent = new Agent();

	/**
	 * Start the agent loader.
	 * 
	 * @param args
	 */
	public static void main(String[] args) {
		LOGGER.info("BWG Java agent starting...");
        String homePath = args.length > 0 ? args[0] : System.getProperty("user.dir");
        String logsPath = homePath + File.separator + "logs";
        String logBackPath = homePath + File.separator + "logback.xml";

        if (new File(logBackPath).exists()) {
            LOGGER.info("BWG Java agent reconfiguring logging ...");
            new File(logsPath).mkdirs();
            LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();

            try {
                JoranConfigurator configurator = new JoranConfigurator();
                configurator.setContext(context);
                context.reset();
                context.putProperty("logDir", logsPath);
                configurator.doConfigure(logBackPath);

            } catch (JoranException je) {
                // StatusPrinter will handle this
            }
            StatusPrinter.printInCaseOfErrorsOrWarnings(context);
        }

		try {
			agent.start(homePath);
		} catch (Throwable e) {
			LOGGER.error("Unable to start agent.", e);
		}
	}
}