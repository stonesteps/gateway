package com.tritonsvc.agent;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.core.joran.spi.JoranException;
import ch.qos.logback.core.util.StatusPrinter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Bootstraps the Java agent.
 *
 */
public class AgentLoader {

	/** Static logger instance */
	private static Logger LOGGER = LoggerFactory.getLogger(AgentLoader.class);

	/** Agent controlled by this loader */
	private static Agent agent;

	/**
	 * Start the agent loader.
	 * 
	 * @param args
	 */
	public static void main(String[] args) {
		LOGGER.info("Java agent starting...");
        String homePath = args.length > 0 ? args[0] : System.getProperty("user.dir");
        String logsPath = homePath + File.separator + "logs";
        String logBackPath = homePath + File.separator + "logback.xml";

        // determine number of agent threads.
        // default: 1, may be set via command line arg, or system property
        int threadCount = 1;
        if (args.length > 1) {
            threadCount = Integer.parseInt(args[1]);
        } else if (System.getProperty("thread.count") != null) {
            threadCount = Integer.parseInt(System.getProperty("thread.count"));
        }

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

        if (threadCount <= 1) {
            // normal production execution path, run a single Agent
            try {
                agent = new Agent();
                agent.start(homePath);
            } catch (Throwable e) {
                LOGGER.error("Unable to start agent.", e);
            }
        } else {
            // Test execution: run several agents, each in a separate thread
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            try {
                for (int i = 0; i < threadCount; i++) {
                    Runnable worker = new AgentRunner((i+1),homePath);
                    executor.execute(worker);
                }
                executor.shutdown();
                executor.awaitTermination(Long.MAX_VALUE, TimeUnit.SECONDS);
                // Wait until all threads are finish
                System.out.println("\nFinished all threads");
            } catch (Throwable e) {
                LOGGER.error("Error starting agent threads", e);
            } finally {
                if (!executor.isTerminated()) {
                    LOGGER.error("cancel non-finished thread");
                }
                executor.shutdownNow();
                LOGGER.error("shutdown now finished");
            }
        }
	}
}