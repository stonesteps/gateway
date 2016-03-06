package com.tritonsvc.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static javax.xml.bind.DatatypeConverter.printHexBinary;

/**
 * RS485 message issuer
 */
public class RS485MessagePublisher {
    private static Logger LOGGER = LoggerFactory.getLogger(RS485MessagePublisher.class);
    private BWGProcessor processor;
    private byte POLL_FINAL_CONTROL_BYTE = (byte)0xBF;
    private byte DELIMITER_BYTE = (byte)0x7E;
    private byte LINKING_ADDRESS_BYTE = (byte)0xFE;
    private LinkedBlockingQueue<byte[]> pendingDownlinks = new LinkedBlockingQueue<>(100);
    private AtomicLong lastLoggedDownlinkPoll = new AtomicLong(0);

    //private PanelUpdateMessage lastKnownPanelUpdateMessage;
    //private SystemInformation lastKnownSystemInformation;

    /**
     * Constructor
     *
     * @param processor
     */
    public RS485MessagePublisher(BWGProcessor processor) {
        this.processor = processor;
    }

    /**
     * assemble the target temperature message and put it on downlink queue
     *
     * @param newTemp
     * @param address
     * @throws RS485Exception
     */
    public synchronized void setTemperature(int newTemp, byte address) throws RS485Exception {
        try {
            ByteBuffer bb = ByteBuffer.allocate(8);
            bb.put(DELIMITER_BYTE); // start flag
            bb.put((byte) 0x06); // length between flags
            bb.put(address); // device address
            bb.put(POLL_FINAL_CONTROL_BYTE); // control byte
            bb.put((byte) 0x20); // the set target temp packet type
            bb.put((byte) (0xFF & newTemp));
            bb.put(HdlcCrc.generateFCS(bb.array()));
            bb.put(DELIMITER_BYTE); // stop flag

            if (pendingDownlinks.offer(bb.array(), 5000, TimeUnit.MILLISECONDS)) {
                LOGGER.info("put settemp in downlink queue {}", printHexBinary(bb.array()));
            } else {
                throw new Exception("downlink queue is full");
            }
        }
        catch (Throwable ex) {
            LOGGER.info("rs485 set temp got exception " + ex.getMessage());
            throw new RS485Exception(new Exception(ex));
        }
    }

    /**
     * assemble the button code request message and put it on queue
     *
     * @param code
     * @param address
     * @throws RS485Exception
     */
    public synchronized void sendButtonCode(ButtonCode code, byte address) throws RS485Exception {
        try {
            ByteBuffer bb = ByteBuffer.allocate(9);
            bb.put(DELIMITER_BYTE); // start flag
            bb.put((byte) 0x07); // length between flags
            bb.put(address); // device address
            bb.put(POLL_FINAL_CONTROL_BYTE); // control byte
            bb.put((byte) 0x11); // the send button code packet type
            bb.put((byte) (0xFF & code.getCode()));
            bb.put((byte) 0xFF); // modifier is not specified
            bb.put(HdlcCrc.generateFCS(bb.array()));
            bb.put(DELIMITER_BYTE); // stop flag

            if (pendingDownlinks.offer(bb.array(), 5000, TimeUnit.MILLISECONDS)) {
                LOGGER.info("put send button code in downlink queue {}", printHexBinary(bb.array()));
            } else {
                throw new Exception("downlink queue is full");
            }
        }
        catch (Throwable ex) {
            LOGGER.info("rs485 send button code got exception " + ex.getMessage());
            throw new RS485Exception(new Exception(ex));
        }
    }

    /**
     * send the unassigned device response
     *
     * @param requestId
     * @throws RS485Exception
     */
    public synchronized void sendUnassignedDeviceResponse(int requestId) throws RS485Exception {
        try {
            ByteBuffer bb = ByteBuffer.allocate(10);
            bb.put(DELIMITER_BYTE); // start flag
            bb.put((byte)0x08); // length between flags
            bb.put(LINKING_ADDRESS_BYTE); // device address
            bb.put(POLL_FINAL_CONTROL_BYTE); // control byte
            bb.put((byte)0x01); // the unassigned device reponse packet type
            bb.put((byte)0x00); // device type
            bb.put((byte)(0xFF & (requestId >> 8))); // unique id 1
            bb.put((byte)(requestId & 0xFF)); // unique id 2
            bb.put(HdlcCrc.generateFCS(bb.array()));
            bb.put(DELIMITER_BYTE); // stop flag
            bb.position(0);

            processor.getRS485UART().write(bb);
            LOGGER.info("sent unassigned device response {}", printHexBinary(bb.array()));
        }
        catch (Throwable ex) {
            LOGGER.info("rs485 sending unnassigned device response got exception " + ex.getMessage());
            throw new RS485Exception(new Exception(ex));
        }
    }

    /**
     * send the address assignemnt acknowledgent message back to controller when
     *
     * @param address
     * @throws RS485Exception
     */
    public synchronized void sendAddressAssignmentAck(byte address) throws RS485Exception {
        try {
            ByteBuffer bb = ByteBuffer.allocate(7);
            bb.put(DELIMITER_BYTE); // start flag
            bb.put((byte)0x05); // length between flags
            bb.put(address); // device address
            bb.put(POLL_FINAL_CONTROL_BYTE); // control byte
            bb.put((byte)0x03); // the assigned device ack packet type
            bb.put(HdlcCrc.generateFCS(bb.array()));
            bb.put(DELIMITER_BYTE); // stop flag
            bb.position(0);

            processor.getRS485UART().write(bb);
            LOGGER.info("sent address assignment response for newly acquired address {} {}", address, printHexBinary(bb.array()));
        }
        catch (Throwable ex) {
            LOGGER.info("rs485 sending address assignment ack got exception " + ex.getMessage());
            throw new RS485Exception(new Exception(ex));
        }
    }

    /**
     * send the response message for a device query message
     *
     * @param address
     * @throws RS485Exception
     */
    public synchronized void sendDeviceQueryResponse(byte address) throws RS485Exception {
        try {
            ByteBuffer bb = ByteBuffer.allocate(10);
            bb.put(DELIMITER_BYTE); // start flag
            bb.put((byte)0x08); // length between flags
            bb.put(address); // device address
            bb.put(POLL_FINAL_CONTROL_BYTE); // control byte
            bb.put((byte)0x05); // the unassigned device reponse packet type
            bb.put((byte)0x01); // major
            bb.put((byte)0x00); // minor
            bb.put((byte)0x00); // build
            bb.put(HdlcCrc.generateFCS(bb.array()));
            bb.put(DELIMITER_BYTE); // stop flag
            bb.position(0);

            processor.getRS485UART().write(bb);
            LOGGER.info("sent device query response {}", printHexBinary(bb.array()));
        }
        catch (Throwable ex) {
            LOGGER.info("rs485 sending device query response got exception " + ex.getMessage());
            throw new RS485Exception(new Exception(ex));
        }
    }

    /**
     * sends either a pending downlink message if one is queued or a default rs485 ack to the device poll request
     * from controller
     *
     * @param address
     * @throws RS485Exception
     */
    public synchronized void sendPendingDownlinkIfAvailable(byte address) throws RS485Exception {
        try {
            byte[] requestMessage = pendingDownlinks.poll();
            if (requestMessage != null) {
                ByteBuffer bb = ByteBuffer.wrap(requestMessage);
                processor.getRS485UART().write(bb);
                LOGGER.info("sent queued downlink messages for poll response {}, there are {} remaining", printHexBinary(bb.array()), pendingDownlinks.size());
                lastLoggedDownlinkPoll.set(System.currentTimeMillis());
            } else {
                ByteBuffer bb = ByteBuffer.allocate(7);
                bb.put(DELIMITER_BYTE); // start flag
                bb.put((byte) 0x05); // length between flags
                bb.put(address); // device address
                bb.put(POLL_FINAL_CONTROL_BYTE); // control byte
                bb.put((byte) 0x07); // the unassigned device reponse packet type
                bb.put(HdlcCrc.generateFCS(bb.array()));
                bb.put(DELIMITER_BYTE); // stop flag
                bb.position(0);

                processor.getRS485UART().write(bb);
                if (System.currentTimeMillis() - lastLoggedDownlinkPoll.get() > 60000) {
                    synchronized (lastLoggedDownlinkPoll) {
                        if (System.currentTimeMillis() - lastLoggedDownlinkPoll.get() > 60000) {
                            lastLoggedDownlinkPoll.set(System.currentTimeMillis());
                            LOGGER.info("sent no downlink messages for poll response in last 60 seconds");
                        }
                    }
                }
            }
        }
        catch (Throwable ex) {
            LOGGER.info("rs485 sending device downlinks for poll check, got exception " + ex.getMessage());
            throw new RS485Exception(new Exception(ex));
        }
    }
}
