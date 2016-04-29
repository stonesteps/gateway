package com.tritonsvc.gateway;

import com.google.common.annotations.VisibleForTesting;
import com.tritonsvc.spa.communication.proto.Bwg.AckResponseCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static java.lang.System.arraycopy;
import static javax.xml.bind.DatatypeConverter.printHexBinary;

/**
 * RS485 message issuer
 */
public abstract class RS485MessagePublisher {
    private static Logger LOGGER = LoggerFactory.getLogger(RS485MessagePublisher.class);
    protected BWGProcessor processor;
    protected byte POLL_FINAL_CONTROL_BYTE = (byte)0xBF;
    protected byte DELIMITER_BYTE = (byte)0x7E;
    protected byte LINKING_ADDRESS_BYTE = (byte)0xFE;
    protected LinkedBlockingQueue<PendingRequest> pendingDownlinks = new LinkedBlockingQueue<>(100);
    protected AtomicLong lastLoggedDownlinkPoll = new AtomicLong(0);
    protected AtomicReference<FilterCycleRequest> filterCycleRequest = new AtomicReference<>();

    /**
     * Constructor
     *
     * @param processor
     */
    public RS485MessagePublisher(BWGProcessor processor) {
        this.processor = processor;
    }

    public abstract void initiateFilterCycleRequest(int port, int durationMinutes, byte address, String originatorId, String hardwareId) throws RS485Exception;

    public abstract void sendPanelRequest(byte address, Short faultLogEntryNumber) throws RS485Exception;

    public abstract void sendFilterCycleRequestIfPending(byte[] currentFilterCycleInfo, SpaClock spaClock);

    public abstract Codeable getCode(final String value);

    /**
     * assemble the target temperature message and put it on downlink queue
     *
     * @param newTempFahr
     * @param address
     * @throws RS485Exception
     */
    public void setTemperature(int newTempFahr, byte address, String originatorId, String hardwareId) throws RS485Exception {
        try {
            ByteBuffer bb = ByteBuffer.allocate(8);
            bb.put(DELIMITER_BYTE); // start flag
            bb.put((byte) 0x06); // length between flags
            bb.put(address); // device address
            bb.put(POLL_FINAL_CONTROL_BYTE); // control byte
            bb.put((byte) 0x20); // the set target temp packet type
            bb.put((byte) (0xFF & newTempFahr));
            bb.put(HdlcCrc.generateFCS(bb.array()));
            bb.put(DELIMITER_BYTE); // stop flag
            addToPending(new PendingRequest(bb.array(), originatorId, hardwareId));
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
    public void sendCode(int code, byte address, String originatorId, String hardwareId) throws RS485Exception {
        try {
            ByteBuffer bb = ByteBuffer.allocate(9);
            bb.put(DELIMITER_BYTE); // start flag
            bb.put((byte) 0x07); // length between flags
            bb.put(address); // device address
            bb.put(POLL_FINAL_CONTROL_BYTE); // control byte
            bb.put((byte) 0x11); // the send button code packet type
            bb.put((byte) (0xFF & code));
            bb.put((byte) 0xFF); // modifier is not specified
            bb.put(HdlcCrc.generateFCS(bb.array()));
            bb.put(DELIMITER_BYTE); // stop flag
            addToPending(new PendingRequest(bb.array(), originatorId, hardwareId));
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
            bb.put((byte) 0x08); // length between flags
            bb.put(LINKING_ADDRESS_BYTE); // device address
            bb.put(POLL_FINAL_CONTROL_BYTE); // control byte
            bb.put((byte) 0x01); // the unassigned device reponse packet type
            bb.put((byte) 0x00); // device type
            bb.put((byte) (0xFF & (requestId >> 8))); // unique id 1
            bb.put((byte) (requestId & 0xFF)); // unique id 2
            bb.put(HdlcCrc.generateFCS(bb.array()));
            bb.put(DELIMITER_BYTE); // stop flag
            bb.position(0);

            pauseForBus();
            processor.getRS485UART().write(bb);
            LOGGER.info("sent unassigned device response {}", printHexBinary(bb.array()));
        } catch (Throwable ex) {
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
            bb.put((byte) 0x05); // length between flags
            bb.put(address); // device address
            bb.put(POLL_FINAL_CONTROL_BYTE); // control byte
            bb.put((byte) 0x03); // the assigned device ack packet type
            bb.put(HdlcCrc.generateFCS(bb.array()));
            bb.put(DELIMITER_BYTE); // stop flag
            bb.position(0);

            pauseForBus();
            processor.getRS485UART().write(bb);
            LOGGER.info("sent address assignment response for newly acquired address {} {}", address, printHexBinary(bb.array()));
        } catch (Throwable ex) {
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
            bb.put((byte) 0x08); // length between flags
            bb.put(address); // device address
            bb.put(POLL_FINAL_CONTROL_BYTE); // control byte
            bb.put((byte) 0x05); // the unassigned device reponse packet type
            bb.put((byte) 0x01); // major
            bb.put((byte) 0x00); // minor
            bb.put((byte) 0x00); // build
            bb.put(HdlcCrc.generateFCS(bb.array()));
            bb.put(DELIMITER_BYTE); // stop flag
            bb.position(0);

            pauseForBus();
            processor.getRS485UART().write(bb);
            LOGGER.info("sent device query response {}", printHexBinary(bb.array()));
        } catch (Throwable ex) {
            LOGGER.info("rs485 sending device query response got exception " + ex.getMessage());
            throw new RS485Exception(new Exception(ex));
        }
    }

    /**
     * sends a pending downlink message if one is queued
     *
     * @param address
     * @throws RS485Exception
     */
    public synchronized void sendPendingDownlinkIfAvailable(byte address) throws RS485Exception {
        try {
            PendingRequest requestMessage = pendingDownlinks.poll();
            if (requestMessage != null) {
                ByteBuffer bb = ByteBuffer.wrap(requestMessage.getPayload());
                try {
                    pauseForBus();
                    processor.getRS485UART().write(bb);
                    if (requestMessage.getHardwareId() != null) {
                        // if hardwareid is not present, this was a message initiated by the agent not the cloud, don't send an ack up to cloud in this case
                        processor.sendAck(requestMessage.getHardwareId(), requestMessage.getOriginatorId(), AckResponseCode.OK, null);
                    }
                    LOGGER.info("sent queued downlink message, originator {}, as 485 poll response, payload {}, there are {} remaining", requestMessage.getOriginatorId(), printHexBinary(bb.array()), pendingDownlinks.size());
                } catch (Exception ex) {
                    if (requestMessage.getHardwareId() != null) {
                        // if hardwareid is not present, this was a message initiated by the agent not the cloud, don't send an ack up to cloud in this case
                        processor.sendAck(requestMessage.getHardwareId(), requestMessage.getOriginatorId(), AckResponseCode.ERROR, "485 communication problem");
                    }
                    LOGGER.info("failed sending downlink message, originator {}, as 485 poll response, payload {}", requestMessage.getOriginatorId(), printHexBinary(bb.array()));
                    throw ex;
                } finally {
                    lastLoggedDownlinkPoll.set(System.currentTimeMillis());
                }
            }
        }
        catch (Throwable ex) {
            LOGGER.info("rs485 sending device downlinks for poll check, got exception " + ex.getMessage());
            throw new RS485Exception(new Exception(ex));
        }
    }

    @VisibleForTesting
    void addToPending(PendingRequest request) throws Exception{
        if (pendingDownlinks.offer(request, 5000, TimeUnit.MILLISECONDS)) {
            LOGGER.info("put rs485 request, originator id {} in downlink queue, payload {}", request.getOriginatorId(), printHexBinary(request.getPayload()));
        } else {
            LOGGER.error("downlink queue was full, clearing it to try and recover");
            pendingDownlinks.clear();
            if (!pendingDownlinks.offer(request, 5000, TimeUnit.MILLISECONDS)) {
                throw new Exception("downlink queue is full");
            }
        }
    }

    protected static class PendingRequest {
        private byte[] payload;
        private String originatorId;
        private String hardwareId;

        public PendingRequest(byte[] payload, String originatorId, String hardwareId) {
            this.payload = payload;
            this.originatorId = originatorId;
            this.hardwareId = hardwareId;
        }

        public byte[] getPayload() {
            return payload;
        }

        public String getOriginatorId() {
            return originatorId;
        }

        public String getHardwareId() {
            return hardwareId;
        }
    }

    protected void pauseForBus() throws InterruptedException {
        // rs485 spec from BWG specified that at a minimum, clients shouldn't submit to the bus after
        // recieving a prompt to do so for at least 250 msecs, to give the spa controller time to release
        // from bus
        Thread.sleep(0,250000);
    }
}
