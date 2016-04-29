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

    public abstract void setTemperature(int newTempFahr, byte address, String originatorId, String hardwareId) throws RS485Exception;

    public abstract void sendCode(int code, byte address, String originatorId, String hardwareId) throws RS485Exception;

    public abstract void initiateFilterCycleRequest(int port, int durationMinutes, byte address, String originatorId, String hardwareId) throws RS485Exception;

    public abstract void sendUnassignedDeviceResponse(int requestId) throws RS485Exception;

    public abstract void sendPanelRequest(byte address, Short faultLogEntryNumber) throws RS485Exception;

    public abstract void sendFilterCycleRequestIfPending(byte[] currentFilterCycleInfo, SpaClock spaClock);

    public abstract void sendAddressAssignmentAck(byte address) throws RS485Exception;

    public abstract void sendDeviceQueryResponse(byte address) throws RS485Exception;

    public abstract Codeable getCode(final String value);

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
