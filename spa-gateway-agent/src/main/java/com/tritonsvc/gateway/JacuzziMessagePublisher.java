package com.tritonsvc.gateway;

import com.tritonsvc.spa.communication.proto.Bwg.AckResponseCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.Calendar;

import static java.lang.System.arraycopy;
import static javax.xml.bind.DatatypeConverter.printHexBinary;

/**
 * RS485 message issuer
 */
public class JacuzziMessagePublisher extends RS485MessagePublisher {
    private static Logger LOGGER = LoggerFactory.getLogger(JacuzziMessagePublisher.class);

    /**
     * Constructor
     *
     * @param processor
     */
    public JacuzziMessagePublisher(BWGProcessor processor) {
        super(processor);
        this.processor = processor;
    }

    @Override
    public void initiateFilterCycleRequest(int port, int durationMinutes, byte address, String originatorId, String hardwareId) throws RS485Exception {
        int durationHours = durationMinutes / 60;
        if (durationHours < 2 || durationHours > 24) return; // min duration 2h, max - 24h

        try {
            ByteBuffer bb = ByteBuffer.allocate(10);
            bb.put(DELIMITER_BYTE); // start flag
            bb.put((byte) 0x07); // length between flags
            bb.put(address); // device address
            bb.put(POLL_FINAL_CONTROL_BYTE); // control byte
            bb.put((byte) 0x1B); // the panel request packet type
            // current hour - start right now?
            int currentHour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
            bb.put((byte) (0xFF & currentHour));
            bb.put((byte) (0xFF & durationHours));
            bb.put(HdlcCrc.generateFCS(bb.array()));
            bb.put(DELIMITER_BYTE); // stop flag
            bb.position(0);

            addToPending(new PendingRequest(bb.array(), originatorId, hardwareId));
        } catch (Throwable ex) {
            LOGGER.info("rs485 send filter cycle got exception " + ex.getMessage());
            throw new RS485Exception(new Exception(ex));
        }
    }

    public void sendPanelRequest(byte address, Short faultLogEntryNumber) throws RS485Exception {
        // information request
        try {
            int request = 0x14;
            if (faultLogEntryNumber != null) {
                request |= 0x40;
            }

            ByteBuffer bb = ByteBuffer.allocate(10);
            bb.put(DELIMITER_BYTE); // start flag
            bb.put((byte) 0x07); // length between flags
            bb.put(address); // device address
            bb.put(POLL_FINAL_CONTROL_BYTE); // control byte
            bb.put((byte) 0x19); // the panel request packet type
            bb.put((byte) (0xFF & request)); // requested messages
            bb.put((byte) (faultLogEntryNumber != null ? (0xFF & faultLogEntryNumber) : 0x00)); // fault log entry number
            bb.put(HdlcCrc.generateFCS(bb.array()));
            bb.put(DELIMITER_BYTE); // stop flag
            bb.position(0);

            addToPending(new PendingRequest(bb.array(), "self", null));
            LOGGER.info("sent panel request {}", printHexBinary(bb.array()));
        } catch (Throwable ex) {
            LOGGER.info("rs485 sending panel request got exception " + ex.getMessage());
            throw new RS485Exception(new Exception(ex));
        }
    }

    @Override
    public Codeable getCode(final String value) {
        return JacuzziCommandCode.valueOf(value);
    }

    public void sendFilterCycleRequestIfPending(byte[] currentFilterCycleInfo, SpaClock spaClock) {
        // nothing here
    }
}
