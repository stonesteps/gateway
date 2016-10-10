package com.tritonsvc.gateway;

import com.tritonsvc.spa.communication.proto.Bwg.AckResponseCode;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.Model.Constants.HeaterMode;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.Model.Constants.TempRange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;

import static java.lang.System.arraycopy;
import static javax.xml.bind.DatatypeConverter.printHexBinary;

/**
 * RS485 message issuer
 */
public class NGSCMessagePublisher extends RS485MessagePublisher {
    private static Logger LOGGER = LoggerFactory.getLogger(NGSCMessagePublisher.class);

    /**
     * Constructor
     *
     * @param processor
     */
    public NGSCMessagePublisher(BWGProcessor processor) {
        super(processor);
    }

    /**
     * put request for filter cycle info on downlink queue
     *
     * @param port
     * @param durationMinutes
     * @throws RS485Exception
     */
    @Override
    public void sendFilterCycleRequest(int port, int durationMinutes, byte address, String originatorId, String hardwareId, SpaClock spaClock) throws RS485Exception {

        filterCycleRequest.set(new FilterCycleRequest(port, durationMinutes, address, originatorId, hardwareId));
        try {
            ByteBuffer bb = ByteBuffer.allocate(10);
            bb.put(DELIMITER_BYTE); // start flag
            bb.put((byte) 0x08); // length between flags
            bb.put(address); // device address
            bb.put(POLL_FINAL_CONTROL_BYTE); // control byte
            bb.put((byte) 0x22); // the panel request packet type
            bb.put((byte) 0x01); // request filter cycle info
            bb.put((byte) 0x00); // no fault log entry number
            bb.put((byte) 0x00); // do not get device config
            bb.put(HdlcCrc.generateFCS(bb.array()));
            bb.put(DELIMITER_BYTE); // stop flag
            bb.position(0);

            addToPending(new PendingRequest(bb.array(), originatorId, hardwareId));
            LOGGER.info("sent filter cycle panel request {}", printHexBinary(bb.array()));
        } catch (Throwable ex) {
            LOGGER.info("rs485 send filter cycle got exception " + ex.getMessage());
            throw new RS485Exception(new Exception(ex));
        }
    }

    @Override
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
        } catch (Throwable ex) {
            LOGGER.info("rs485 send button code got exception " + ex.getMessage());
            throw new RS485Exception(new Exception(ex));
        }
    }

    @Override
    public void sendPanelRequest(byte address, boolean faultLogs, Short faultLogEntryNumber) throws RS485Exception {
        try {
            int request = 0x07;
            if (faultLogs) {
                request = 0x20; // just fault logs
            }

            ByteBuffer bb = ByteBuffer.allocate(10);
            bb.put(DELIMITER_BYTE); // start flag
            bb.put((byte) 0x08); // length between flags
            bb.put(address); // device address
            bb.put(POLL_FINAL_CONTROL_BYTE); // control byte
            bb.put((byte) 0x22); // the panel request packet type
            bb.put((byte) (0xFF & request)); // requested messages
            bb.put((byte) (faultLogEntryNumber != null ? (0xFF & faultLogEntryNumber) : 0xFF)); // fault log entry number
            bb.put((byte) (faultLogs ? 0x00 : 0x01)); // get device config
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
    public Codeable getCode(String value) {
        return NGSCButtonCode.valueOf(value);
    }

    public void sendFilterCycleRequestIfPending(byte[] currentFilterCycleInfo, SpaClock spaClock) {
        if (filterCycleRequest.get() == null) {
            return;
        }
        FilterCycleRequest cycleRequest = filterCycleRequest.getAndSet(null);
        if (cycleRequest == null) {
            // in case multi threads at same time, the second one would get this.
            return;
        }

        try {
            if (spaClock == null) {
                throw new IllegalArgumentException("spa has not reported time yet, cannot set filter cycle yet.");
            }

            byte[] setFilterCycleInfo = new byte[currentFilterCycleInfo.length + 2];
            arraycopy(currentFilterCycleInfo, 0, setFilterCycleInfo, 1, currentFilterCycleInfo.length);
            setFilterCycleInfo[0] = DELIMITER_BYTE;

            if (cycleRequest.getPort() == 0) {
                if (cycleRequest.getDurationMinutes() < 1) {
                    setFilterCycleInfo[5] = (byte) 0x00;
                    setFilterCycleInfo[6] = (byte) 0x00;
                    setFilterCycleInfo[7] = (byte) 0x00;
                    setFilterCycleInfo[8] = (byte) 0x00;
                } else {
                    setFilterCycleInfo[5] = (byte) (0xFF & spaClock.getHour());
                    setFilterCycleInfo[6] = (byte) (0xFF & spaClock.getMinute());
                    setFilterCycleInfo[7] = (byte) (0xFF & cycleRequest.getDurationMinutes() / 60);
                    setFilterCycleInfo[8] = (byte) (0xFF & cycleRequest.getDurationMinutes() % 60);
                }
            } else if (cycleRequest.getPort() == 1) {
                if (cycleRequest.getDurationMinutes() < 1) {
                    setFilterCycleInfo[9] = (byte) 0x00;
                    setFilterCycleInfo[10] = (byte) 0x00;
                    setFilterCycleInfo[11] = (byte) 0x00;
                    setFilterCycleInfo[12] = (byte) 0x00;
                } else {
                    int byte9 = spaClock.getHour();
                    byte9 |= 0x80; // bit7 is on for enabling the second filter cycle
                    setFilterCycleInfo[9] = (byte) (0xFF & byte9);
                    setFilterCycleInfo[10] = (byte) (0xFF & spaClock.getMinute());
                    setFilterCycleInfo[11] = (byte) (0xFF & cycleRequest.getDurationMinutes() / 60);
                    setFilterCycleInfo[12] = (byte) (0xFF & cycleRequest.getDurationMinutes() % 60);
                }
            } else {
                throw new IllegalArgumentException("invalid port number, only 0 or 1 supported for filter cycle");
            }
            setFilterCycleInfo[13] = HdlcCrc.generateFCS(setFilterCycleInfo);
            setFilterCycleInfo[14] = DELIMITER_BYTE;
            ByteBuffer bb = ByteBuffer.wrap(setFilterCycleInfo);

            addToPending(new PendingRequest(bb.array(), "self", null));
            LOGGER.info("sent filter cycle request {}", printHexBinary(bb.array()));
        } catch (Throwable ex) {
            LOGGER.info("rs485 sending filter cycle request got exception " + ex.getMessage());
            processor.sendAck(cycleRequest.getHardwareId(), cycleRequest.getOriginatorId(), AckResponseCode.ERROR, ex.getMessage());
        }
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
        prepareForTemperatureChangeRequest(newTempFahr,
                address,
                currentTempRange,
                currentWaterTempFahr,
                currentHeaderMode,
                HighHigh,
                HighLow,
                LowHigh,
                LowLow);
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

    private void prepareForTemperatureChangeRequest(int tempFahr,
                                                    byte address,
                                                    TempRange currentTempRange,
                                                    int currentWaterTempFahr,
                                                    HeaterMode currentHeaterMode,
                                                    int HighHigh, int HighLow, int LowHigh, int LowLow) throws RS485Exception {
        boolean sendTempRangeChange = false;
        boolean sendModeChange = false;

        // if the target temp is outside current range, toggle the range, or send as is if it's out of either
        if (currentTempRange != null && currentTempRange.equals(TempRange.HIGH)) {
            if (!withinRange(tempFahr, HighLow, HighHigh) && withinRange(tempFahr, LowLow, LowHigh)) {
                sendTempRangeChange = true;
            }
        }
        if (currentTempRange != null && currentTempRange.equals(TempRange.LOW)) {
            if (!withinRange(tempFahr, LowLow, LowHigh) && withinRange(tempFahr, HighLow, HighHigh)) {
                sendTempRangeChange = true;
            }
        }

        // if the desired temp is above current and the spa was in rest mode, time to light it up
        if (tempFahr > currentWaterTempFahr &&
                currentHeaterMode != null && currentHeaterMode == HeaterMode.REST) {
            sendModeChange = true;
        }

        if (sendTempRangeChange) {
            sendCode(NGSCButtonCode.kTempRangeMetaButton.getCode(), address, "self", null);
        }
        if (sendModeChange) {
            sendCode(NGSCButtonCode.kHeatModeMetaButton.getCode(), address, "self", null);
        }
    }

    private boolean withinRange(int tempFahr, int low, int high) {
        return tempFahr >= low && tempFahr <= high;
    }

    @Override
    public void updateSpaTime(String originatorId, String hardwareId, byte address, Integer year, Integer month, Integer day, Integer hour, Integer minute, Integer second) throws RS485Exception {
        try {
            ByteBuffer bb = ByteBuffer.allocate(8);
            bb.put(DELIMITER_BYTE); // start flag
            bb.put((byte) 0x07);
            bb.put(address); // device address
            bb.put(POLL_FINAL_CONTROL_BYTE); // control byte
            bb.put((byte) 0x21); // the set target time packet type
            bb.put((byte) hour.intValue());
            bb.put((byte) minute.intValue());
            bb.put(HdlcCrc.generateFCS(bb.array()));
            bb.put(DELIMITER_BYTE); // stop flag
            addToPending(new PendingRequest(bb.array(), originatorId, hardwareId));
        }
        catch (Throwable ex) {
            LOGGER.info("rs485 set time got exception " + ex.getMessage());
            throw new RS485Exception(new Exception(ex));
        }
    }
}
