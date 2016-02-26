package com.tritonsvc.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.zeromq.ZMQ.Socket;

import java.util.Date;
import java.util.Map;
import java.util.Random;

import static com.google.common.collect.Maps.newHashMap;

/**
 * WSN data collection thread, keeps the thread running/fresh at all times unless interrupted
 */
public class RS485DataHarvester implements Runnable {
    private static Logger LOGGER = LoggerFactory.getLogger(RS485DataHarvester.class);
    private BWGProcessor processor;

    //private PanelUpdateMessage lastKnownPanelUpdateMessage;
    //private SystemInformation lastKnownSystemInformation;

    /**
     * Constructor
     *
     * @param processor
     */
    public RS485DataHarvester(BWGProcessor processor) {
        this.processor = processor;
    }

    @Override
    public void run() {

        while(processor.stillRunning()) {
            try {
                //
                // Java Device I/O UART
                // http://docs.oracle.com/javame/8.0/api/dio/api/jdk/dio/uart/UART.html
                // https://www.voxxed.com/blog/2014/12/device-io-api/
                //
                // Use UART.setEventListener() to attach a handler to the message streams
                // that are coming in on rs 485
                // the handler should then do processMessage(msgBuffer, len);
            }
            catch (Throwable ex) {
                LOGGER.info("harvest rs485 data listener got exception " + ex.getMessage());
                // close rs485SerialInputStream
                // try {Thread.sleep(10000);} catch (InterruptedException ex2){}
            }
        }
        // shutdown/close any resources/etc if needed, the jvm is trying to shut down.
    }

    private void processMessage(byte[] msgBuffer, int len) {
        // run the byte array through HDLC and application message parsing routine using the
        // protobufs generated classes in com.tritonsvc.spa.controller.ngsc.proto

        // HDLCHeader hdlcHeader = parseHDLCHeader(msgBuffer, len);
        //
        // for PanelUpdateMessage, SystemInformation messages, store the latest received as
        // class variables
        //
        // basically have a router here that dispatches the remaining unparsed bytes of message
        // to detailed parser/handler class/method/routine that associated the application message type
        // header
        //
        //if (hdlcHeader.getMessageType() == PanelUpdateMessage_TYPE) {
        //    processPanelUpdateMessage(msgBuffer, hdlcHeader.byteLength);
        //}
        //if (hdlcHeader.getMessageType() == SystemInformation_TYPE) {
        //    processSystemInformationMessage(msgBuffer, hdlcHeader.byteLength);
        //}
    }

    private void processPanelUpdateMessage(byte[] message, int offset) {
        // ... do conditional parsing using protobufs and header logic,etc
        // for any sub/nested structs within the message
        // PanelUpdateMessage lastKnownPanelUpdateMessage = new PanelUpdateMessage();
        // lastKnownPanelUpdateMessage.setXYZ(...)
        // setPanelUpdateMessage(lastKnownPanelUpdateMessage);
    }

    public void sendPeriodicControllerMeasurements(String hardwareId) {
        //Map<String, String> meta = newHashMap();
        //Map<String, Double> measurement = newHashMap();
        //buildControllerTemps(lastKnownPanelUpdateMessage, meta, measurement);
        //meta.put("comment", "controller data harvest");
        //
        //processor.sendMeasurements(hardwareId, null, measurement, new Date().getTime(), meta);
    }

    /*
    // this is example of very rudimentary thread safety, needed on access to
    // these class variables, main processor thread is reading, and this thread is writing
    private synchronized void setLastKnownPanelUpdateMessage(PanelUpdateMessage panelUpdateMessage) {
        this.lastKnownPanelUpdateMessage = panelUpdateMessage;
    }
    private synchronized PanelUpdateMessage getLastKnownPanelUpdateMessage() {
        return lastKnownPanelUpdateMessage;
    }

    private void buildControllerTemps(lastKnownPanelUpdateMessage controllerInfo, Map<String, String> meta, Map<String, Double> measurement) {
        double pre = new controllerInfo.getPreHeaterTemp();
        double post = new controllerInfo.getPostHeaterTemp();

        if (processor.getOidProperties().getPreHeaterTemp() != null) {
            measurement.put(processor.getOidProperties().getPreHeaterTemp(), pre);
        }
        if (processor.getOidProperties().getPostHeaterTemp() != null) {
            measurement.put(processor.getOidProperties().getPostHeaterTemp(), post);
        }

        // ... other measurements ...

        meta.put("heater_temp_delta", Double.toString(Math.abs(pre - post)));
    }
    */
}
