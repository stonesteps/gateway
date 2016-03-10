package com.tritonsvc.gateway;

import com.google.common.base.Throwables;
import com.google.common.primitives.Bytes;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.Model.Components;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.Model.Constants.BlowerState;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.Model.Constants.ComponentType;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.Model.Constants.HeaterMode;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.Model.Constants.PanelDisplayCode;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.Model.Constants.PanelMode;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.Model.Constants.PumpState;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.Model.Constants.SwimSpaMode;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.Model.Constants.TempRange;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.Model.Controller;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.Model.SetupParams;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.Model.SpaState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.util.Date;
import java.util.Random;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static javax.xml.bind.DatatypeConverter.printHexBinary;

/**
 * WSN data collection thread, keeps the thread running/fresh at all times unless interrupted
 */
public class RS485DataHarvester implements Runnable {
    private static Logger LOGGER = LoggerFactory.getLogger(RS485DataHarvester.class);
    private BWGProcessor processor;
    private static byte delimiter = (byte)0x7e; // this is the HDLC delimeter flag
    private static int HDLC_FRAME_LENGTH_MASK = 0x7F;
    private static int HDLC_ALL_STATIONS_ADDRESS = 0xFF;
    private static int HDLC_LINKING_ADDRESS = 0xFE;
    private ExecutorService executorService;
    private AtomicInteger rejectCount = new AtomicInteger();
    private RS485MessagePublisher rs485MessagePublisher;
    private ByteBuffer buffer = ByteBuffer.allocate(150);
    private AtomicInteger regisrationAddress = new AtomicInteger(-1);
    private AtomicInteger registrationrequestId = new AtomicInteger();
    private AtomicLong registrationLastAttempt = new AtomicLong();
    private AtomicReference<Boolean> isCelsius = new AtomicReference<>();
    private AtomicInteger HighLow = new AtomicInteger(0);
    private AtomicInteger HighHigh = new AtomicInteger(0);
    private AtomicInteger LowHigh = new AtomicInteger(0);
    private AtomicInteger LowLow = new AtomicInteger(0);

    final ReentrantReadWriteLock spaStateLock = new ReentrantReadWriteLock();
    private SpaState spaState = SpaState.newBuilder().build();

    /**
     * Constructor
     *
     * @param processor
     */
    public RS485DataHarvester(BWGProcessor processor, RS485MessagePublisher rs485MessagePublisher) {
        this.processor = processor;
        this.rs485MessagePublisher = rs485MessagePublisher;
        BlockingQueue q = new ArrayBlockingQueue(1000);
        executorService = new ThreadPoolExecutor(2, 5, 20, TimeUnit.SECONDS, q, (r, executor) -> {
            if (rejectCount.addAndGet(1) > 500) {
                LOGGER.warn("filled up the rs 485 listener message processing queue, dropping messages");
                rejectCount.set(0);
            }
        });

        spaState = (SpaState.newBuilder(spaState)
                .setComponents(Components.newBuilder(spaState.getComponents()).setBlower2(BlowerState.BLOWER_HIGH)
                        .build())
                .build());


        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run() {
                executorService.shutdownNow();
                try {executorService.awaitTermination(10, TimeUnit.SECONDS);} catch (InterruptedException ex){}
            }
        });
    }

    @Override
    public void run() {
        try {
            processor.getRS485UART().setReceiveTimeout(5000);
        } catch (IOException ex) {
            LOGGER.error("unable to set read timeout on rs485 uart", ex);
        }
        ByteBuffer bb = ByteBuffer.allocate(1024);
        byte[] remainderBytes = new byte[]{};

        while(processor.stillRunning()) {
            try {
                bb.clear();
                if (remainderBytes.length > 0) {
                    bb.put(remainderBytes);
                }

                int read = processor.getRS485UART().read(bb);
                byte[] data = bb.array();
                remainderBytes = parseHDLCMessages(data, remainderBytes);

                if (LOGGER.isDebugEnabled()) {
                    if (read > 0) {
                        LOGGER.debug("Received raw rs485 data {}", printHexBinary(data));
                    } else {
                        LOGGER.debug("Received no rs485 data during read operation");
                    }
                }
            }
            catch (Throwable ex) {
                LOGGER.info("harvest rs485 data listener got exception " + ex.getMessage());
                remainderBytes = new byte[] {};
                try {Thread.sleep(1000);} catch (InterruptedException ex2){}
            }
        }
    }

    /**
     * get the latest rs485 address that is registered or null if none
     *
     * @return
     */
    public Byte getRegisteredAddress() {
        int value = regisrationAddress.get();
        if (value < 0) {
            return null;
        }
        return (byte)(0xFF & value);
    }

    /**
     * fetch the latest state of spa
     *
     * @return
     */
    public SpaState getLatestSpaInfo() {
        return spaState;
    }

    /**
     * fetch the read/write lock that should be wrapped around any access to latest spa info
     *
     * @return
     */
    public ReentrantReadWriteLock getLatestSpaInfoLock() {
        return spaStateLock;
    }

    /**
     * check if spa controller uses celisus
     * @return
     */
    public Boolean requiresCelsius() {
        return isCelsius.get();
    }

    /**
     * get state of component from controller, all ordinals are 1-based !
     *
     * @param type
     * @param port
     * @return
     * @throws Exception
     */
    public String getComponentState(ComponentType type, int port) throws Exception {
        boolean locked = false;
        try {
            getLatestSpaInfoLock().readLock().lockInterruptibly();
            locked = true;
            switch (type) {
                case PUMP:
                    switch (port) {
                        case 1:
                            return spaState.getComponents().hasPump1() ? spaState.getComponents().getPump1().name(): null;
                        case 2:
                            return spaState.getComponents().hasPump2() ? spaState.getComponents().getPump2().name(): null;
                        case 3:
                            return spaState.getComponents().hasPump3() ? spaState.getComponents().getPump3().name(): null;
                        case 4:
                            return spaState.getComponents().hasPump4() ? spaState.getComponents().getPump4().name(): null;
                        case 5:
                            return spaState.getComponents().hasPump5() ? spaState.getComponents().getPump5().name(): null;
                        case 6:
                            return spaState.getComponents().hasPump6() ? spaState.getComponents().getPump6().name(): null;
                        case 7:
                            return spaState.getComponents().hasPump7() ? spaState.getComponents().getPump7().name(): null;
                        case 8:
                            return spaState.getComponents().hasPump8() ? spaState.getComponents().getPump8().name(): null;
                    }
            }
        } finally {
            if (locked) {
                getLatestSpaInfoLock().readLock().unlock();
            }
        }
        return null;
    }

    /**
     * changing temps is a bit laborious, it requires possible changing other states on the
     * controller to support the new temp, this does that.
     *
     * @param tempFahr
     * @throws Exception
     */
    public void prepareForTemperatureChangeRequest(int tempFahr) throws Exception {
        boolean locked = false;
        boolean sendTempRangeChange = false;
        boolean sendModeChange = false;
        try {
            getLatestSpaInfoLock().readLock().lockInterruptibly();
            locked = true;
            // if the target temp is outside current range, toggle the range, or send as is if it's out of either
            if (spaState.getController().getTempRange().equals(TempRange.HIGH)) {
                if (!withinHighRange(tempFahr) && withinLowRange(tempFahr)) {
                    sendTempRangeChange = true;
                }
            }
            if (spaState.getController().getTempRange().equals(TempRange.LOW)) {
                if (!withinLowRange(tempFahr) && withinHighRange(tempFahr)) {
                    sendTempRangeChange = true;
                }
            }

            // if the desired temp is above current and the spa was in rest mode, time to light it up
            if (tempFahr > spaState.getController().getCurrentWaterTemp() &&
                    spaState.getController().getHeaterMode() == (HeaterMode.REST.getNumber())) {
                sendModeChange = true;
            }
        } finally {
            if (locked) {
                getLatestSpaInfoLock().readLock().unlock();
            }
        }

        if (sendTempRangeChange) {
            rs485MessagePublisher.sendButtonCode(ButtonCode.kTempRangeMetaButton, getRegisteredAddress(), "self", null);
        }
        if (sendModeChange) {
            rs485MessagePublisher.sendButtonCode(ButtonCode.kHeatModeMetaButton, getRegisteredAddress(), "self", null);
        }
    }

    /**
     * verifies that the state of controller is populated, allows request commands to be
     * processed correctly, i.e. some command builders refer to state bits
     *
     * @param checkTemp
     * @throws Exception
     */
    public void arePanelCommandsSafe(boolean checkTemp) throws Exception {

        boolean locked = false;
        try {
            getLatestSpaInfoLock().readLock().lockInterruptibly();
            locked = true;
            SpaState spaState = getLatestSpaInfo();
            if (!spaState.hasController() ||
                    !spaState.hasComponents() ||
                    !spaState.hasSetupParams()) {
                throw new RS485Exception("Spa state has not been populated, cannot process requests yet");
            }
            if (spaState.getController().getUiCode() == PanelDisplayCode.DEMO.getNumber() ||
                    spaState.getController().getUiCode() == PanelDisplayCode.STANDBY.getNumber() ||
                    spaState.getController().getPrimingMode() ||
                    spaState.getController().getPanelLock() ||
                    (checkTemp && spaState.getController().getTempLock())){
                throw new RS485Exception("Spa is locked out, no requests are allowed.");
            }
        } finally {
            if (locked) {
                getLatestSpaInfoLock().readLock().unlock();
            }
        }
    }

    private enum State {
        searchForBeginning,
        searchForEnd,
        processFrameFormat,
        getPackets
    }

    private byte[] parseHDLCMessages(byte[] input, byte[] previousRemainder) {
        if (previousRemainder.length > 0) {
            input = Bytes.concat(previousRemainder, input);
        }

        int hdlcFrameLength =0;
        int index =0;
        buffer.clear();

        State state = State.searchForBeginning;

        while (index < input.length) {
            byte data = input[index++];
            switch (state) {
                case searchForBeginning:
                    if (data == delimiter) {
                        state = State.processFrameFormat;
                    }
                    break;
                case processFrameFormat:
                    if (data == delimiter) {
                        continue;
                    }
                    hdlcFrameLength = (data & HDLC_FRAME_LENGTH_MASK);
                    if (hdlcFrameLength < 4 || hdlcFrameLength == 126) {
                        hdlcFrameLength = 0;
                        state = State.searchForBeginning;
                        buffer.clear();
                        continue;
                    }
                    buffer.put(data);
                    state = State.getPackets;
                    break;
                case getPackets:
                    buffer.put(data);
                    if (hdlcFrameLength == buffer.position()) {
                        state = State.searchForEnd;
                    }
                    break;
                case searchForEnd:
                    if (data == delimiter) {
                        if ( !shouldNotProcessMessage() ) {
                            byte[] message = new byte[hdlcFrameLength];
                            buffer.position(0);
                            buffer.get(message);
                            executorService.execute(new ParseRS485Runner(message));
                        }
                    }
                    buffer.clear();
                    state = State.processFrameFormat;
                    break;
            }
        }

        if (buffer.position() > 0) {
            byte[] partial = new byte[buffer.position()];
            buffer.position(0);
            buffer.get(partial);
            return Bytes.concat(new byte[]{delimiter}, partial);
        } else {
            return new byte[]{};
        }
    }

    private boolean shouldNotProcessMessage() {
        int myAddress = regisrationAddress.get();
        int incomingAddress = (0xFF & buffer.get(1));
        if (myAddress > -1 &&
                (incomingAddress == HDLC_LINKING_ADDRESS ||
                        (myAddress != incomingAddress &&
                        incomingAddress != HDLC_ALL_STATIONS_ADDRESS))) {
            return true;
        }

        if (myAddress == -1 && incomingAddress != HDLC_LINKING_ADDRESS) {
            return true;
        }

        return false;
    }

    private class ParseRS485Runner implements Runnable {
        private byte[] message;
        public ParseRS485Runner(byte[] message) {
            this.message = message;
        }
        @Override
        public void run() {
            try {
                processMessage(message);
            } catch (Throwable th) {
                LOGGER.error("had problem processing rs 485 message ", th);
            }
        }
    }

    /**
     * process just the message, delimiters have been removed, the FCS should be the last byte
     *
     * @param message
     */
    private void processMessage(byte[] message) {
        int packetType = message[3];
        if (!HdlcCrc.isValidFCS(message)) {
            LOGGER.debug("Invalid rs485 data message, failed FCS check {}", printHexBinary(message));
            return;
        }

        if (packetType == 0x0) {
            processUnassignedDevicePoll();
        } else if (packetType == 0x2) {
            processAddressAssignment(message);
        } else if (packetType == 0x4) {
            processDevicePresentQuery();
        } else if (packetType == 0x6) {
            processDevicePollForDownlink();
        } else if (packetType == 0x13) {
            processPanelUpdateMessage(message);
        } else if (packetType == 0x24) {
            processSystemInfoMessage(message);
        } else if (packetType == 0x25) {
            processSetupParamsMessage(message);
        } else if (packetType == 0x2E) {
            processDeviceConfigsMessage(message);
        }
    }

    private void processUnassignedDevicePoll() {
        if (System.currentTimeMillis() - registrationLastAttempt.get() > 60000) {
            synchronized(registrationLastAttempt) {
                if (System.currentTimeMillis() - registrationLastAttempt.get() > 60000) {
                    byte[] requestKey = new byte[2];
                    new Random().nextBytes(requestKey);
                    int requestId = (0xFF00 & (requestKey[0] << 8)) | (0xFF & requestKey[1]);
                    registrationrequestId.set(requestId);
                    registrationLastAttempt.set(System.currentTimeMillis());
                    try {
                        rs485MessagePublisher.sendUnassignedDeviceResponse(requestId);
                    } catch (RS485Exception ex) {
                        LOGGER.error("unable to send unassigned device response", ex);
                        registrationrequestId.set(0);
                    }
                }
            }
        }
    }

    private void processAddressAssignment(byte[] message) {
        if (registrationrequestId.get() > 0) {
            int incomingRequestId = ((0xFF & message[5]) << 8) | (0xFF & message[6]);
            if (incomingRequestId == registrationrequestId.get()) {
                regisrationAddress.set(message[4]);
                try {
                    rs485MessagePublisher.sendAddressAssignmentAck((byte)regisrationAddress.get());
                } catch (RS485Exception ex) {
                    LOGGER.error("unable to send assigned device ack", ex);
                }
            }
        }
    }

    private void processDevicePresentQuery() {
        try {
            rs485MessagePublisher.sendDeviceQueryResponse((byte)regisrationAddress.get());
        } catch (RS485Exception ex) {
            LOGGER.error("unable to send device present response", ex);
        }
    }

    private void processDevicePollForDownlink() {
        try {
            rs485MessagePublisher.sendPendingDownlinkIfAvailable((byte)regisrationAddress.get());
        } catch (RS485Exception ex) {
            LOGGER.error("unable to send device poll for downlinks", ex);
        }
    }

    private void processPanelUpdateMessage(byte[] message) {
        isCelsius.set((0x01 & message[13]) > 0);
        Controller controller = buildControllerMessageFromPanelUpdate(message);
        Components components = null;
        try {
            components = buildComponentsMessageFromPanelUpdate(message);
        } catch (IllegalStateException ex) {
            LOGGER.warn("device config is not present, will not include component state from panel update yet");
        }

        boolean wLocked = false;
        try {
            getLatestSpaInfoLock().writeLock().lockInterruptibly();
            wLocked = true;
            SpaState.Builder builder = SpaState.newBuilder(spaState);
            builder.setController(controller);
            if (components != null) {
                builder.setComponents(components);
            }
            spaState = builder.build();
        } catch (Exception ex) {
            throw Throwables.propagate(ex);
        }
        finally {
            if (wLocked) {
                getLatestSpaInfoLock().writeLock().unlock();
            }
        }
    }

    private Components buildComponentsMessageFromPanelUpdate(byte[] message) {
        boolean rLocked = false;
        Components.Builder compsBuilder = Components.newBuilder();
        try {
            getLatestSpaInfoLock().readLock().lockInterruptibly();
            rLocked = true;
            if (!spaState.hasComponents()) {
                throw new IllegalStateException("DeviceConfig has not been received yet, cannot process panel updates until that is received.");
            }
            compsBuilder.mergeFrom(spaState.getComponents());
        } catch (Exception ex) {
            throw Throwables.propagate(ex);
        } finally {
            if (rLocked) {
                getLatestSpaInfoLock().readLock().unlock();
            }
        }

        compsBuilder.setLastUpdateTimestamp(new Date().getTime());
        if (compsBuilder.hasAux1()) { compsBuilder.setAux1((0x08 & message[19]) > 0);}
        if (compsBuilder.hasAux2()) { compsBuilder.setAux1((0x10 & message[19]) > 0);}

        return compsBuilder.build();
    }

    private Controller buildControllerMessageFromPanelUpdate(byte[] message) {
        return Controller.newBuilder()
                .setErrorCode(0xFF & message[10])
                .setHour(0xFF & message[7])
                .setABDisplay((0x01 & message[25]) > 0)
                .setAllSegsOn((0x40 & message[13]) > 0)
                .setBluetoothStatus((0xF0 & message[27]) >> 4)
                .setCelsius(isCelsius.get())
                .setCircPump(PumpState.valueOf(0x03 & message[17]))
                .setCleanupCycle((0x08 & message[23]) > 0)
                .setCurrentWaterTemp(bwgTempToFahrenheit(isCelsius.get(), (0xFF & message[6])))
                .setDemoMode((0x10 & message[23]) > 0)
                .setEcoMode((0x04 & message[26]) > 0)
                .setElapsedTimeDisplay((0x80 & message[25]) > 0)
                .setFilter1((0x04 & message[13]) > 0)
                .setFilter2((0x08 & message[13]) > 0)
                .setHeaterCooling((0x40 & message[23]) > 0)
                .setHeatExternallyDisabled((0x01 & message[27]) > 0)
                .setInvert((0x80 & message[13]) > 0)
                .setLastUpdateTimestamp(new Date().getTime())
                .setLatchingMessage((0x20 & message[23]) > 0)
                .setLightCycleOn((0x01 & message[23]) > 0)
                .setMessageSeverity((0x0F & message[22]))
                .setMilitary((0x02 & message[13]) > 0)
                .setMinute(0xFF & message[8])
                .setOverrangeEnabled((0x02 & message[27]) > 0)
                .setUiCode(0xFF & message[4])
                .setUiSubCode(0xFF & message[5])
                .setPanelLock((0x20 & message[13]) > 0)
                .setTempLock((0x10 & message[13]) > 0)
                .setPanelMode(PanelMode.valueOf((0xC0 & message[13])))
                .setPrimingMode((0x02 & message[14]) > 0)
                .setRepeat((0x80 & message[19]) > 0)
                .setHeaterMode(0xFF & message[9])
                .setSettingsLock((0x08 & message[25]) > 0)
                .setSoakMode((0x01 & message[26]) > 0)
                .setSoundAlarm((0x01 & message[14]) > 0)
                .setSpaOverheatDisabled((0x04 & message[25]) > 0)
                .setSpecialTimeouts((0x02 & message[25]) > 0)
                .setStirring((0x08 & message[26]) > 0)
                .setSwimSpaMode(SwimSpaMode.valueOf((0x30 & message[22]) >> 4))
                .setSwimSpaModeChanging((0x08 & message[23]) > 0)
                .setTargetWaterTemperature(bwgTempToFahrenheit(isCelsius.get(), (0xFF & message[24])))
                .setTempRange(TempRange.valueOf((0x04 & message[14]) >> 2))
                .setTestMode((0x04 & message[23]) > 0)
                .setTimeNotSet((0x02 & message[23]) > 0)
                .setTvLiftState((0x70 & message[25]) >> 4)
                .build();
    }

    private int bwgTempToFahrenheit(boolean isCelsius, int bwgTemp) {
        if (!isCelsius) {
            return bwgTemp;
        }
        double celsius = new BigDecimal(bwgTemp / 2.0 ).setScale(1, BigDecimal.ROUND_HALF_UP).doubleValue();
        return new BigDecimal((9.0/5.0)*celsius + 32).setScale(0, BigDecimal.ROUND_HALF_UP).intValue();
    }

    private void processSystemInfoMessage(byte[] message) {

    }

    private void processSetupParamsMessage(byte[] message) {
        HighLow.set(0xFF & message[8]);
        HighHigh.set(0xFF & message[9]);
        LowLow.set(0xFF & message[6]);
        LowHigh.set(0xFF & message[7]);

        SetupParams setupParams = SetupParams.newBuilder()
                .setLowRangeLow(LowLow.get())
                .setLowRangeHigh(LowHigh.get())
                .setHighRangeHigh(HighHigh.get())
                .setHighRangeLow(HighLow.get())
                .setGfciEnabled((0x08 & message[10]) > 0)
                .setDrainModeEnabled((0x04 & message[10]) > 0)
                .setLastUpdateTimestamp(new Date().getTime())
                .build();

        boolean wLocked = false;
        try {
            getLatestSpaInfoLock().writeLock().lockInterruptibly();
            wLocked = true;
            SpaState.Builder builder = SpaState.newBuilder(spaState);
            builder.setSetupParams(setupParams);
            spaState = builder.build();
        } catch (Exception ex) {
            throw Throwables.propagate(ex);
        }
        finally {
            if (wLocked) {
                getLatestSpaInfoLock().writeLock().unlock();
            }
        }
    }

    private void processDeviceConfigsMessage(byte[] message) {

    }

    private boolean withinHighRange(int tempFahr) {
        return tempFahr >= HighLow.get() && tempFahr <= HighHigh.get();
    }

    private boolean withinLowRange(int tempFahr) {
        return tempFahr >= LowLow.get() && tempFahr <= LowHigh.get();
    }
}
