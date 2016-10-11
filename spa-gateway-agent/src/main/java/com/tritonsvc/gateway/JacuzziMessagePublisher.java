package com.tritonsvc.gateway;

import com.tritonsvc.spa.communication.proto.Bwg.Uplink.Model.Components.LightComponent;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.Model.Components.LightComponent.State;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.Model.Constants.HeaterMode;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.Model.Constants.TempRange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.Calendar;

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
    public void sendFilterCycleRequest(int port, int durationMinutes, byte address, String originatorId, String hardwareId, SpaClock spaClock) throws RS485Exception {
        int durationHours = durationMinutes / 60;
        if (durationHours < 2 || durationHours > 24) {
            LOGGER.info("received invalid filter cycle duration request of {}, defaulting to 2 hours instead", durationHours);
            durationHours = 2;
        }

        try {
            ByteBuffer bb = ByteBuffer.allocate(10);
            bb.put(DELIMITER_BYTE); // start flag
            bb.put((byte) 0x07); // length between flags
            bb.put(address); // device address
            bb.put(POLL_FINAL_CONTROL_BYTE); // control byte
            bb.put((byte) 0x1B); // the panel request packet type
            bb.put((byte) (0xFF & spaClock.getHour()));
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

    @Override
    public void sendCode(int code, byte address, String originatorId, String hardwareId) throws RS485Exception {

        if (code >= JacuzziCommandCode.kLight1MetaButton.getCode() &&
                code <= JacuzziCommandCode.kLight4MetaButton.getCode()) {
            sendLightCommand(address, originatorId, hardwareId, code);
            return;
        }

        try {
            ByteBuffer bb = ByteBuffer.allocate(9);
            bb.put(DELIMITER_BYTE); // start flag
            bb.put((byte) 0x06); // length between flags
            bb.put(address); // device address
            bb.put(POLL_FINAL_CONTROL_BYTE); // control byte
            bb.put((byte) 0x17); // the send button code packet type
            bb.put((byte) (0xFF & code));
            bb.put(HdlcCrc.generateFCS(bb.array()));
            bb.put(DELIMITER_BYTE); // stop flag
            addToPending(new PendingRequest(bb.array(), originatorId, hardwareId));
        } catch (Throwable ex) {
            LOGGER.info("rs485 send button code got exception " + ex.getMessage());
            throw new RS485Exception(new Exception(ex));
        }
    }

    public void sendPanelRequest(byte address, boolean faultLogs, Short faultLogEntryNumber) throws RS485Exception {
        // Jacuzzi information request in ICD
        try {
            int request = 0x30; // get lights, sys config
            if (faultLogs) {
                request = 0x40; // get just fault logs
            }

            ByteBuffer bb = ByteBuffer.allocate(10);
            bb.put(DELIMITER_BYTE); // start flag
            bb.put((byte) 0x07); // length between flags
            bb.put(address); // device address
            bb.put(POLL_FINAL_CONTROL_BYTE); // control byte
            bb.put((byte) 0x19); // the panel request packet type
            bb.put((byte) (0xFF & request)); // requested messages
            bb.put((byte) (faultLogEntryNumber != null ? (0xFF & faultLogEntryNumber) : 0xFF)); // fault log entry number
            bb.put(HdlcCrc.generateFCS(bb.array()));
            bb.put(DELIMITER_BYTE); // stop flag
            bb.position(0);

            addToPending(new PendingRequest(bb.array(), "self", null));
        } catch (Throwable ex) {
            LOGGER.info("rs485 sending panel request got exception " + ex.getMessage());
            throw new RS485Exception(new Exception(ex));
        }
    }

    @Override
    public Codeable getCode(final String value) {
        return JacuzziCommandCode.valueOf(value);
    }

    @Override
    public void setTemperature(int newTempFahr,
                               TempRange currentTempRange,
                               int currentWaterTempFahr,
                               HeaterMode currentHeaderMode,
                               byte address,
                               String originatorId,
                               String hardwareId,
                               int HighHigh, int HighLow, int LowHigh, int LowLow) throws RS485Exception {
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
        } catch (Throwable ex) {
            LOGGER.info("rs485 set temp got exception " + ex.getMessage());
            throw new RS485Exception(new Exception(ex));
        }
    }

    private void sendLightCommand(byte address, String originatorId, String hardwareId, int code) throws RS485Exception {
        try {
            LightComponent.State state = null;
            int intensity = 25;

            int command = 0x20;
            if (code == JacuzziCommandCode.kLight1MetaButton.getCode()) {
                command |= 1;
                state = processor.getLatestLightState(1);
            } else if (code == JacuzziCommandCode.kLight2MetaButton.getCode()) {
                command |= 2;
                state = processor.getLatestLightState(2);
            } else if (code == JacuzziCommandCode.kLight3MetaButton.getCode()) {
                command |= 4;
                state = processor.getLatestLightState(3);
            } else if (code == JacuzziCommandCode.kLight4MetaButton.getCode()) {
                command |= 8;
                state = processor.getLatestLightState(4);
            }

            if (state != null) {
                switch (state) {
                    case HIGH:
                        intensity = 0;
                        break;
                    case MED:
                        intensity = 100;
                        break;
                    case LOW:
                        intensity = 66;
                        break;
                    case OFF:
                        intensity = 33;
                }
            }

            ByteBuffer bb = ByteBuffer.allocate(15);
            bb.put(DELIMITER_BYTE); // start flag
            bb.put((byte) 0x0D); // length between flags
            bb.put(address); // device address
            bb.put(POLL_FINAL_CONTROL_BYTE); // control byte
            bb.put((byte) 0x21); // 0010 0000   -
            bb.put((byte) (0xFF & command)); // requested light zone
            bb.put((byte) 0); //subcommand
            bb.put((byte) 0); //Red
            bb.put((byte) 0); //Green
            bb.put((byte) 0); //Blue
            bb.put((byte) 0); //White
            bb.put((byte) (0xFF & intensity)); // intensity 0-100
            bb.put((byte) 0); //Speed
            bb.put(HdlcCrc.generateFCS(bb.array()));
            bb.put(DELIMITER_BYTE); // stop flag
            bb.position(0);

            addToPending(new PendingRequest(bb.array(), originatorId, hardwareId));
            LOGGER.info("sent light request {}", printHexBinary(bb.array()));
        } catch (Throwable ex) {
            LOGGER.info("rs485 sending light request got exception " + ex.getMessage());
            throw new RS485Exception(new Exception(ex));
        }
    }

    @Override
    public void updateSpaTime(String originatorId, String hardwareId, boolean currentTimeMilitaryDisplay, byte address, Integer year, Integer month, Integer day, Integer hour, Integer minute, Integer second) throws RS485Exception {
        try {
            int yearVal = year < 2000 ? 0 : year - 2000;

            ByteBuffer bb = ByteBuffer.allocate(12);
            bb.put(DELIMITER_BYTE); // start flag
            bb.put((byte) 0x0A);
            bb.put(address); // device address
            bb.put(POLL_FINAL_CONTROL_BYTE); // control byte
            //TODO, set 12/24 bits to 0 since we don't want to change the display format and only enable Set Time or Set Date bits dependent on data passed in
            bb.put((byte) 0x18); // set time packet type
            bb.put((byte) (0xF0 | (0xF & month.intValue()))); // flags + month
            bb.put((byte) day.intValue()); // day
            bb.put((byte) yearVal); // year
            bb.put((byte) hour.intValue()); // hour
            bb.put((byte) minute.intValue()); // minute
            bb.put(HdlcCrc.generateFCS(bb.array()));
            bb.put(DELIMITER_BYTE); // stop flag
            bb.position(0);

            addToPending(new PendingRequest(bb.array(), originatorId, hardwareId));
            LOGGER.info("sent time request {}", printHexBinary(bb.array()));
        } catch (Throwable ex) {
            LOGGER.info("rs485 set time and date got exception " + ex.getMessage());
            throw new RS485Exception(new Exception(ex));
        }
    }
}
