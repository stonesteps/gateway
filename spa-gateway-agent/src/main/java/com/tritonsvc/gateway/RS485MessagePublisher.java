package com.tritonsvc.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * RS485 message issuer
 */
public class RS485MessagePublisher {
    private static Logger LOGGER = LoggerFactory.getLogger(RS485MessagePublisher.class);
    private BWGProcessor processor;
    private RS485DataHarvester harvester;

    //private PanelUpdateMessage lastKnownPanelUpdateMessage;
    //private SystemInformation lastKnownSystemInformation;

    /**
     * Constructor
     *
     * @param processor
     */
    public RS485MessagePublisher(BWGProcessor processor, RS485DataHarvester harvester) {
        this.harvester = harvester;
        this.processor = processor;
    }

    public void setTemperature(double newTemp) {


            try {
                //
                // Java Device I/O UART
                // http://docs.oracle.com/javame/8.0/api/dio/api/jdk/dio/uart/UART.html
                // https://www.voxxed.com/blog/2014/12/device-io-api/
                //
                // UART uart = DeviceManager.open("/dev/ttys0", buadrate, etc);
                // build SetTargetTemperature rs485 command from protobufs classes based on 3.1.8 in ICD - http://iotdev02/download/attachments/1015837/NGSC%20Communications%20ICD.doc?version=1&modificationDate=1454715406000&api=v2
                //
                // byte[] setTempBytes = serialize SetTargetTemperature protobufs object to byte[]
                // uart.startWriting(setTempBytes);

            }
            catch (Throwable ex) {
                LOGGER.info("rs485 set temp got exception " + ex.getMessage());
                // retry?
                // try {Thread.sleep(10000);} catch (InterruptedException ex2){}
            }

    }
}
