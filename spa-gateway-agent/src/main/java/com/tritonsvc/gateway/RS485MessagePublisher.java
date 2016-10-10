package com.tritonsvc.gateway;

import com.google.common.annotations.VisibleForTesting;
import com.tritonsvc.HostUtils;
import com.tritonsvc.spa.communication.proto.Bwg.AckResponseCode;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.Model.Constants.HeaterMode;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.Model.Constants.TempRange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.stream.Collectors.toList;
import static javax.xml.bind.DatatypeConverter.printHexBinary;

/**
 * RS485 message issuer
 */
public abstract class RS485MessagePublisher {
    private static Logger LOGGER = LoggerFactory.getLogger(RS485MessagePublisher.class);
    protected BWGProcessor processor;
    protected byte POLL_FINAL_CONTROL_BYTE = (byte) 0xBF;
    protected byte DELIMITER_BYTE = (byte) 0x7E;
    protected byte LINKING_ADDRESS_BYTE = (byte) 0xFE;
    protected LinkedBlockingQueue<PendingRequest> pendingDownlinks = new LinkedBlockingQueue<>(8);
    protected AtomicReference<FilterCycleRequest> filterCycleRequest = new AtomicReference<>();
    protected long lastEmptyPollSent = 0;

    /**
     * Constructor
     *
     * @param processor
     */
    public RS485MessagePublisher(BWGProcessor processor) {
        this.processor = processor;
    }

    /**
     * send filter cycle request
     *
     * @param port
     * @param durationMinutes
     * @param address
     * @param originatorId
     * @param hardwareId
     * @param spaClock
     * @throws RS485Exception
     */
    public abstract void sendFilterCycleRequest(int port, int durationMinutes, byte address, String originatorId, String hardwareId, SpaClock spaClock) throws RS485Exception;

    /**
     * send panel request
     *
     * @param address
     * @param faultLogs
     * @param faultLogEntryNumber
     * @throws RS485Exception
     */
    public abstract void sendPanelRequest(byte address, boolean faultLogs, Short faultLogEntryNumber) throws RS485Exception;

    /**
     * get the message codes
     *
     * @param value
     * @return
     */
    public abstract Codeable getCode(final String value);

    /**
     * send a message code to spa
     *
     * @param code
     * @param address
     * @param originatorId
     * @param hardwareId
     * @throws RS485Exception
     */
    public abstract void sendCode(int code, byte address, String originatorId, String hardwareId) throws RS485Exception;

    /**
     * assemble and send the temperature change request
     *
     * @param newTempFahr
     * @param currentTempRange
     * @param currentWaterTempFahr
     * @param currentHeaterMode
     * @param address
     * @param originatorId
     * @param hardwareId
     * @param HighHigh
     * @param HighLow
     * @param LowHigh
     * @param LowLow
     * @throws RS485Exception
     */
    public abstract void setTemperature(int newTempFahr,
                                        TempRange currentTempRange,
                                        int currentWaterTempFahr,
                                        HeaterMode currentHeaterMode,
                                        byte address,
                                        String originatorId,
                                        String hardwareId,
                                        int HighHigh,
                                        int HighLow,
                                        int LowHigh,
                                        int LowLow) throws RS485Exception;

    /**
     * Sets time and date on spa. Some spas allow only time to be set, date is ignored in such case.
     *
     * @param originatorId
     * @param hardwareId
     * @param address
     * @param year         in format xxxx
     * @param month        values 0-11
     * @param day          values 1-31
     * @param hour         values 0-23
     * @param minute       values 0-59
     * @param second       values 0-59
     */
    public abstract void updateSpaTime(String originatorId, String hardwareId, byte address, Integer year, Integer month, Integer day, Integer hour, Integer minute, Integer second) throws RS485Exception;

    /**
     * send the response message for a device query message
     *
     * @param address
     * @throws RS485Exception
     */
    public void sendDeviceQueryResponse(byte address) throws RS485Exception {
        try {
            ByteBuffer bb = ByteBuffer.allocate(10);
            bb.put(DELIMITER_BYTE); // start flag
            bb.put((byte) 0x08); // length between flags
            bb.put(address); // device address
            bb.put(POLL_FINAL_CONTROL_BYTE); // control byte
            bb.put((byte) 0x05); // the device query reponse packet type
            bb.put((byte) 0x01); // major
            bb.put((byte) 0x00); // minor
            bb.put((byte) 0x00); // build
            bb.put(HdlcCrc.generateFCS(bb.array()));
            bb.put(DELIMITER_BYTE); // stop flag
            bb.position(0);

            pauseForBus();
            processor.getRS485UART().write(bb);
            if (LOGGER.isDebugEnabled()) LOGGER.debug("sent device query response {}", printHexBinary(bb.array()));
        } catch (Throwable ex) {
            LOGGER.warn("rs485 sending device query response got exception ", ex);
            throw new RS485Exception(new Exception(ex));
        }
    }

    /**
     * send the unassigned device response
     *
     * @param requestId
     * @throws RS485Exception
     */
    public void sendUnassignedDeviceResponse(int requestId) throws RS485Exception {
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
            if (LOGGER.isDebugEnabled()) LOGGER.debug("sent unassigned device response {}", printHexBinary(bb.array()));
        } catch (Throwable ex) {
            LOGGER.warn("rs485 sending unnassigned device response got exception ", ex);
            throw new RS485Exception(new Exception(ex));
        }
    }

    /**
     * send the address assignemnt acknowledgent message back to controller when
     *
     * @param address
     * @throws RS485Exception
     */
    public void sendAddressAssignmentAck(byte address) throws RS485Exception {
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
            if (LOGGER.isDebugEnabled())
                LOGGER.debug("sent address assignment response for newly acquired address {} {}", address, printHexBinary(bb.array()));
        } catch (Throwable ex) {
            LOGGER.warn("rs485 sending address assignment ack got exception ", ex);
            throw new RS485Exception(new Exception(ex));
        }
    }

    /**
     * send the wifi module mac address
     *
     * @param address
     * @throws RS485Exception
     */
    public void sendWifiMacAddress(byte address) throws RS485Exception {
        try {
            ByteBuffer bb = ByteBuffer.allocate(16);
            bb.put(DELIMITER_BYTE); // start flag
            bb.put((byte) 0xE); // length between flags
            bb.put(address); // device address
            bb.put(POLL_FINAL_CONTROL_BYTE); // control byte
            bb.put((byte) 0x91); // the device query reponse packet type
            bb.put((byte) 0x00); // mac msb, this is static made-up mac up of 0:10:20:30:40:50, it's not important
            bb.put((byte) 0x10); //
            bb.put((byte) 0x20); //
            bb.put((byte) 0x30); //
            bb.put((byte) 0x40); //
            bb.put((byte) 0x50); // mac lsb
            bb.put((byte) 0x01); // our fw major
            bb.put((byte) 0x00); // our fw minor
            bb.put((byte) 0x00); // world enabled
            bb.put(HdlcCrc.generateFCS(bb.array()));
            bb.put(DELIMITER_BYTE); // stop flag
            bb.position(0);

            pauseForBus();
            processor.getRS485UART().write(bb);
            if (LOGGER.isDebugEnabled()) LOGGER.debug("sent wifi mac response {}", printHexBinary(bb.array()));
        } catch (Throwable ex) {
            LOGGER.warn("rs485 sending wifi mac response got exception ", ex);
            throw new RS485Exception(new Exception(ex));
        }
    }

    /**
     * send the wifi module mac address
     *
     * @param address
     * @throws RS485Exception
     */
    public void sendWifiPollResponse(byte address) throws RS485Exception {
        try {
            ByteBuffer bb = ByteBuffer.allocate(12);
            bb.put(DELIMITER_BYTE); // start flag
            bb.put((byte) 0xA); // length between flags
            bb.put(address); // device address
            bb.put(POLL_FINAL_CONTROL_BYTE); // control byte
            bb.put((byte) 0x96); // packet type
            bb.put((byte) 0x62); // wifi state, connected
            bb.put((byte) 0x10); // MSB ip address, pass something hardcoded, this is not meaningful
            bb.put((byte) 0x20); //
            bb.put((byte) 0x30); //
            bb.put((byte) 0x40); // LSB ip address
            bb.put(HdlcCrc.generateFCS(bb.array()));
            bb.put(DELIMITER_BYTE); // stop flag
            bb.position(0);

            pauseForBus();
            processor.getRS485UART().write(bb);
            if (LOGGER.isDebugEnabled()) LOGGER.debug("sent wifi poll response {}", printHexBinary(bb.array()));
        } catch (Throwable ex) {
            LOGGER.warn("rs485 sending wifi poll default response got exception ", ex);
            throw new RS485Exception(new Exception(ex));
        }
    }

    /**
     * sends a pending downlink message if one is queued
     *
     * @param address
     * @throws RS485Exception
     */
    public void sendPendingDownlinkIfAvailable(byte address) throws RS485Exception {
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
                    if (LOGGER.isDebugEnabled())
                        LOGGER.debug("sent queued downlink message, originator {}, as 485 poll response, payload {}, there are {} remaining", requestMessage.getOriginatorId(), printHexBinary(bb.array()), pendingDownlinks.size());
                } catch (Exception ex) {
                    if (requestMessage.getHardwareId() != null) {
                        // if hardwareid is not present, this was a message initiated by the agent not the cloud, don't send an ack up to cloud in this case
                        processor.sendAck(requestMessage.getHardwareId(), requestMessage.getOriginatorId(), AckResponseCode.ERROR, "485 communication problem");
                    }
                    LOGGER.warn("failed sending downlink message, originator {}, as 485 poll response, payload {}", requestMessage.getOriginatorId(), printHexBinary(bb.array()));
                    throw ex;
                }
            } else if (address == 10 && System.currentTimeMillis() - lastEmptyPollSent > 500) {
                sendWifiPollResponse(address);
                lastEmptyPollSent = System.currentTimeMillis();
            }
        } catch (Throwable ex) {
            LOGGER.error("rs485 sending device downlinks for poll check, got exception", ex);
            throw new RS485Exception(new Exception(ex));
        }
    }

    public void drainPendingQueues() {
        List<PendingRequest> cloudRequests = pendingDownlinks
                .stream()
                .filter(request -> request.getHardwareId() != null)
                .collect(toList());
        pendingDownlinks.clear();
        for (PendingRequest requestMessage : cloudRequests) {
            processor.sendAck(requestMessage.getHardwareId(), requestMessage.getOriginatorId(), AckResponseCode.ERROR, "485 communication request queue was full");
        }
    }

    @VisibleForTesting
    void addToPending(PendingRequest request) throws Exception {
        if (pendingDownlinks.offer(request, 1, TimeUnit.MILLISECONDS)) {
            if (LOGGER.isDebugEnabled())
                LOGGER.debug("put rs485 request, originator id {} in downlink queue, payload {}, queue size {}", request.getOriginatorId(), printHexBinary(request.getPayload()), pendingDownlinks.size());
        } else {
            LOGGER.error("rs485 spa request command queue was full, clearing to remove old commands.");
            drainPendingQueues();
            if (!pendingDownlinks.offer(request, 1, TimeUnit.MILLISECONDS)) {
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

        if (getHostUtils().isFastProcessor()) {
            Thread.sleep(0, 250000);
        }
    }

    @VisibleForTesting
    HostUtils getHostUtils() {
        return HostUtils.instance();
    }
}
