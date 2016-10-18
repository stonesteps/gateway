package com.tritonsvc.gateway;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Throwables;
import com.google.common.primitives.Ints;
import com.tritonsvc.HostUtils;
import com.tritonsvc.agent.AgentConfiguration;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.Model.Components;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.Model.Components.BlowerComponent;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.Model.Components.LightComponent;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.Model.Components.PumpComponent;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.Model.Components.ToggleComponent;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.Model.Constants.ComponentType;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.Model.Controller;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.Model.SpaState;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.Model.SystemInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static com.google.common.collect.Lists.newArrayList;
import static javax.xml.bind.DatatypeConverter.printHexBinary;

/**
 * Abstract serial comms data adapter, implementors must provide the correct parsing routines
 */
public abstract class RS485DataHarvester implements Runnable {
    public static final int MAX_485_REG_WAIT = 10000;

    private static Logger LOGGER = LoggerFactory.getLogger(RS485DataHarvester.class);
    private static long ADDRESS_STATE_ROLL_INTERIM = 120000; // 2 minutes
    private static long REQUEST_MESSAGE_POLL_INTERVAL = 2000;
    private BWGProcessor processor;
    private FaultLogManager faultLogManager;

    static byte delimiter = (byte)0x7e; // this is the HDLC delimeter flag
    static int HDLC_FRAME_LENGTH_MASK = 0x7F;
    static int HDLC_ALL_STATIONS_ADDRESS = 0xFF;
    static int HDLC_LINKING_ADDRESS = 0xFE;

    private RS485MessagePublisher rs485MessagePublisher;
    private State state = State.searchForBeginning;
    private int hdlcFrameLength =0;
    private volatile byte rs485RegisrationAddress = 0;
    private byte rs485PreferredStaticAddress = 0;

    private AtomicReference<SpaClock> spaClock = new AtomicReference<>();
    private AtomicReference<byte[]> lastPanelUpdate = new AtomicReference<>(new byte[]{});
    private final ReentrantReadWriteLock spaStateLock = new ReentrantReadWriteLock();
    private volatile boolean cancelled;
    private AtomicInteger registrationrequestId = new AtomicInteger();
    private AtomicLong registrationLastAttempt = new AtomicLong();
    private SpaState spaState = SpaState.newBuilder().setLastUpdateTimestamp(new Date().getTime()).build();
    private AtomicReference<ADDRESS_STATE> addressState = new AtomicReference<>();
    private AtomicLong lastAddressStateRoll = new AtomicLong(0);
    private long lastPollSent = 0;
    private long lastWifiPollSent = 0;
    private LinkedBlockingQueue<byte[]> pendingMessages;
    private enum ADDRESS_STATE {
        STATIC_ADDRESS,
        ACQUIRING_DYNAMIC_ADDRESS,
        DYNAMIC_ADDRESS
    }
    private volatile boolean paused;
    private volatile long pausedStartTime;

    /**
     * process just the message, delimiters have been removed, the FCS should be the last byte
     *
     * @param message
     */
    public abstract void processMessage(byte[] message);

    /**
     * move data from panel status update messages into component state
     *
     * @return
     */
    public abstract void populateComponentStateFromPanelUpdate(Components.Builder compsBuilder, byte[] message);

    /**
     * move data from panel status update messages into controller state
     *
     * @param message
     * @return
     */
    public abstract Controller populateControllerStateFromPanelUpdate(byte[] message);

    /**
     * move data from spa message into system info state
     *
     * @param message
     * @param builder
     * @return
     */
    public abstract SystemInfo populateSystemInfoFromMessage(byte[] message, SystemInfo.Builder builder);

    /**
     * detect what components exist from spa message and populate components
     *
     * @param message
     * @param compsBuilder
     * @return
     */
    public abstract Components populateDeviceConfigsFromMessage(byte[] message, Components.Builder compsBuilder);

    /**
     * check if spa controller uses celisus
     *
     * @return
     */
    public abstract Boolean usesCelsius();

    /**
     * reports whether the harvester has received all necessary config state, if not
     * this will invoke publisher.sendPanelRequest()
     * @return
     */
    public abstract boolean hasAllConfigState();

    /**
     * check that state is ready to process requests
     *
     * @param checkTemp
     * @return
     */
    public abstract void verifyPanelCommandsAreReadyToExecute(boolean checkTemp) throws RS485Exception;

    /**
     * Constructor
     *
     * @param processor
     */
    public RS485DataHarvester(BWGProcessor processor, RS485MessagePublisher rs485MessagePublisher, FaultLogManager faultLogManager) {
        this.processor = processor;
        this.rs485MessagePublisher = rs485MessagePublisher;
        this.faultLogManager = faultLogManager;
        rs485RegisrationAddress = Ints.tryParse(processor.getConfigProps().getProperty(AgentConfiguration.RS485_GATEWAY_ADDRESS,"")) != null ? Ints.tryParse(processor.getConfigProps().getProperty(AgentConfiguration.RS485_GATEWAY_ADDRESS,"")).byteValue() : 10;
        rs485PreferredStaticAddress = rs485RegisrationAddress;
        setAddressState(ADDRESS_STATE.STATIC_ADDRESS);
        spaState = SpaState.newBuilder(spaState).setRs485Address(rs485RegisrationAddress).setRs485AddressActive(false).build();
        pendingMessages = new LinkedBlockingQueue<>(30);
    }

    @Override
    public void run() {
        ByteBuffer workingMessage = ByteBuffer.allocate(256);
        ByteBuffer readBytes = ByteBuffer.allocate(100);
        LOGGER.info("RS 485 configured to use address of {}", getRegisteredAddress());
        Thread messageProcessor = new Thread(new MessageProcessor());
        messageProcessor.start();

        while(!cancelled && processor.stillRunning()) {
            try {
                if (processor.getRS485UART() == null) {
                    processor.setUpRS485();
                }
                processor.getRS485UART().setReceiveTimeout(2);

                while (!cancelled && processor.stillRunning()) {
                    if (paused) {
                        workingMessage.clear();
                        state = State.searchForBeginning;
                        hdlcFrameLength = 0;
                        try {Thread.sleep(5000);} catch (InterruptedException ex2){}
                        if ((System.currentTimeMillis() - pausedStartTime > 300000) && paused) {
                            LOGGER.info("paused more then 5 mins, intentionally un-pausing rs485 processing");
                            pause(false);
                        }
                        continue;
                    }
                    readBytes.clear();
                    processor.getRS485UART().read(readBytes);
                    parseHDLCMessages(workingMessage, readBytes);
                }
            }
            catch (Throwable ex) {
                LOGGER.warn("harvest rs485 data listener got exception ",ex);
                workingMessage.clear();
                state = State.searchForBeginning;
                hdlcFrameLength = 0;
                try {Thread.sleep(5000);} catch (InterruptedException ex2){}
            }
        }
        messageProcessor.interrupt();
    }

    /**
     * get the latest rs485 address that is registered or null if none
     *
     * @return
     */
    public byte getRegisteredAddress() {
        return rs485RegisrationAddress;
    }

    /**
     * confirms that current address state is working correctly, bus is responding to address
     */
    public void confirmAddressState(long timestamp) {
        lastAddressStateRoll.set(timestamp);
    }

    /**
     * turn on ability to recognize dynamic address
     */
    public void rollAddressState() {
        if (System.currentTimeMillis() - lastAddressStateRoll.get() < ADDRESS_STATE_ROLL_INTERIM) {
            return;
        }

        if (addressState.get().equals(ADDRESS_STATE.STATIC_ADDRESS)) {
            if (processor.getPersistedRS485Address() == null) {
                setAddressState(ADDRESS_STATE.ACQUIRING_DYNAMIC_ADDRESS);
                LOGGER.info("changing address state from static to acquiring dynamic");
            } else {
                setAddressState(ADDRESS_STATE.DYNAMIC_ADDRESS);
                rs485RegisrationAddress = processor.getPersistedRS485Address();
                LOGGER.info("changing address state from static to dynamic for address of {}", rs485RegisrationAddress);
            }
        } else {
            if (addressState.get().equals(ADDRESS_STATE.DYNAMIC_ADDRESS)) {
                LOGGER.info("removing dynamic address after attempt, {}", rs485RegisrationAddress);
                processor.clearDynamicRS485AddressFromAgentSettings();
            }
            LOGGER.info("changing address state from {} to static for address of {}", addressState.get().name(),  rs485PreferredStaticAddress);
            setAddressState(ADDRESS_STATE.STATIC_ADDRESS);
            rs485RegisrationAddress = rs485PreferredStaticAddress;
        }

        if ( Objects.equals(getHostUtils().getOsType(), HostUtils.TS_IMX6)) {
            try {
                LOGGER.info("performing TS-7970 rs485 RX reset fix");
                executeUnixCommand("echo test > /dev/" + processor.getSerialPort()).waitFor(10, TimeUnit.SECONDS);
            } catch (Exception ex) {
                LOGGER.error("unable to perform 7970 rs485 reset", ex);
            }
        }
    }

    /**
     * get the latest state of spa
     *
     * @return
     */
    public SpaState getLatestSpaInfo() {
        return spaState;
    }

    /**
     * set the latest spa state
     * @param spaState
     */
    public void setLatestSpaInfo(SpaState spaState) {
        this.spaState = spaState;
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
     * get the last know spa clock settings
     * @return
     */
    public SpaClock getSpaClock() {
        return spaClock.get();
    }

    /**
     * get state of component from controller, all ordinals are 1-based !
     *
     * @param type
     * @param port 0 means no port passed
     * @return
     * @throws Exception
     */
    public ComponentInfo getComponentState(ComponentType type, int port) throws Exception {
        boolean locked = false;
        try {
            getLatestSpaInfoLock().readLock().lockInterruptibly();
            locked = true;
            if (!spaState.hasComponents()) {
                return null;
            }
            switch (type) {
                case LIGHT:
                    switch (port) {
                        case 1:
                            return spaState.getComponents().hasLight1() ? new ComponentInfo(spaState.getComponents().getLight1().getCurrentState().name(), spaState.getComponents().getLight1().getAvailableStatesList()): null;
                        case 2:
                            return spaState.getComponents().hasLight2() ? new ComponentInfo(spaState.getComponents().getLight2().getCurrentState().name(), spaState.getComponents().getLight2().getAvailableStatesList()): null;
                        case 3:
                            return spaState.getComponents().hasLight3() ? new ComponentInfo(spaState.getComponents().getLight3().getCurrentState().name(), spaState.getComponents().getLight3().getAvailableStatesList()): null;
                        case 4:
                            return spaState.getComponents().hasLight4() ? new ComponentInfo(spaState.getComponents().getLight4().getCurrentState().name(), spaState.getComponents().getLight4().getAvailableStatesList()): null;
                    }
                case AUX:
                    switch (port) {
                        case 1:
                            return spaState.getComponents().hasAux1() ? new ComponentInfo(spaState.getComponents().getAux1().getCurrentState().name(), spaState.getComponents().getAux1().getAvailableStatesList()): null;
                        case 2:
                            return spaState.getComponents().hasAux2() ? new ComponentInfo(spaState.getComponents().getAux2().getCurrentState().name(), spaState.getComponents().getAux2().getAvailableStatesList()): null;
                        case 3:
                            return spaState.getComponents().hasAux3() ? new ComponentInfo(spaState.getComponents().getAux3().getCurrentState().name(), spaState.getComponents().getAux3().getAvailableStatesList()): null;
                        case 4:
                            return spaState.getComponents().hasAux4() ? new ComponentInfo(spaState.getComponents().getAux4().getCurrentState().name(), spaState.getComponents().getAux4().getAvailableStatesList()): null;

                    }
                case MISTER:
                    switch (port) {
                        case 1:
                            return spaState.getComponents().hasMister1() ? new ComponentInfo(spaState.getComponents().getMister1().getCurrentState().name(), spaState.getComponents().getMister1().getAvailableStatesList()): null;
                        case 2:
                            return spaState.getComponents().hasMister2() ? new ComponentInfo(spaState.getComponents().getMister2().getCurrentState().name(), spaState.getComponents().getMister2().getAvailableStatesList()): null;
                        case 3:
                            return spaState.getComponents().hasMister3() ? new ComponentInfo(spaState.getComponents().getMister3().getCurrentState().name(), spaState.getComponents().getMister3().getAvailableStatesList()): null;

                    }
                case BLOWER:
                    switch (port) {
                        case 1:
                            return spaState.getComponents().hasBlower1() ? new ComponentInfo(spaState.getComponents().getBlower1().getCurrentState().name(), spaState.getComponents().getBlower1().getAvailableStatesList()): null;
                        case 2:
                            return spaState.getComponents().hasBlower2() ? new ComponentInfo(spaState.getComponents().getBlower2().getCurrentState().name(), spaState.getComponents().getBlower2().getAvailableStatesList()): null;
                    }
                case PUMP:
                    switch (port) {
                        case 1:
                            return spaState.getComponents().hasPump1() ? new ComponentInfo(spaState.getComponents().getPump1().getCurrentState().name(), spaState.getComponents().getPump1().getAvailableStatesList()): null;
                        case 2:
                            return spaState.getComponents().hasPump2() ? new ComponentInfo(spaState.getComponents().getPump2().getCurrentState().name(), spaState.getComponents().getPump2().getAvailableStatesList()) : null;
                        case 3:
                            return spaState.getComponents().hasPump3() ? new ComponentInfo(spaState.getComponents().getPump3().getCurrentState().name(), spaState.getComponents().getPump3().getAvailableStatesList()) : null;
                        case 4:
                            return spaState.getComponents().hasPump4() ? new ComponentInfo(spaState.getComponents().getPump4().getCurrentState().name(), spaState.getComponents().getPump4().getAvailableStatesList()) : null;
                        case 5:
                            return spaState.getComponents().hasPump5() ? new ComponentInfo(spaState.getComponents().getPump5().getCurrentState().name(), spaState.getComponents().getPump5().getAvailableStatesList()) : null;
                        case 6:
                            return spaState.getComponents().hasPump6() ? new ComponentInfo(spaState.getComponents().getPump6().getCurrentState().name(), spaState.getComponents().getPump6().getAvailableStatesList()) : null;
                        case 7:
                            return spaState.getComponents().hasPump7() ? new ComponentInfo(spaState.getComponents().getPump7().getCurrentState().name(), spaState.getComponents().getPump7().getAvailableStatesList()) : null;
                        case 8:
                            return spaState.getComponents().hasPump8() ? new ComponentInfo(spaState.getComponents().getPump8().getCurrentState().name(), spaState.getComponents().getPump8().getAvailableStatesList()) : null;
                    }
                case OZONE:
                    if (spaState.getComponents().hasOzone()) {
                        return new ComponentInfo(spaState.getComponents().getOzone().getCurrentState().name(), spaState.getComponents().getOzone().getAvailableStatesList());
                    }
                case MICROSILK:
                    if (spaState.getComponents().hasMicroSilk()) {
                        return new ComponentInfo(spaState.getComponents().getMicroSilk().getCurrentState().name(), spaState.getComponents().getMicroSilk().getAvailableStatesList());
                    }
                case CIRCULATION_PUMP:
                    if (spaState.getComponents().hasCirculationPump()) {
                        return new ComponentInfo(spaState.getComponents().getCirculationPump().getCurrentState().name(), spaState.getComponents().getCirculationPump().getAvailableStatesList());
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
            verifyPanelCommandsAreReadyToExecute(checkTemp);
        } finally {
            if (locked) {
                getLatestSpaInfoLock().readLock().unlock();
            }
        }
    }

    /**
     * stop running the harvester
     */
    public void cancel() {
        cancelled = true;
    }

    /**
     * temporarily stop the thread from reading on rs485
     * @param toggle
     */
    public void pause(boolean toggle) {
        if (paused == toggle) {
            return;
        }
        if (toggle) {
            pausedStartTime = System.currentTimeMillis();
            LOGGER.info("intentionally pausing rs 485 data harvester");
        } else {
            pausedStartTime = 0;
            LOGGER.info("intentionally un-pausing rs 485 data harvester");
        }
        paused = toggle;
    }

    private void setAddressState(ADDRESS_STATE addressState) {
        lastAddressStateRoll.set(System.currentTimeMillis());
        rs485MessagePublisher.drainPendingQueues();
        this.addressState.set(addressState);
    }

    /**
     * crunch message parsing on a separate thread from the 485 reader
     */
    private class MessageProcessor implements Runnable {
        public void run() {
            while (!cancelled && processor.stillRunning()) {
                try {
                    byte[] message = pendingMessages.take();
                    if (message != null) {
                        int packetType = message[3];
                        if (!HdlcCrc.isValidFCS(message)) {
                            if (LOGGER.isDebugEnabled()) LOGGER.debug("Invalid rs485 data message, failed FCS check {}", printHexBinary(message));
                            continue;
                        }
                        if (packetType == 0x16 && processor.getRS485ControllerType() == null) {
                            switchToJacuzzi();
                            continue;
                        }
                        processMessage(message);
                    }
                } catch (Throwable ex) {
                    LOGGER.warn("harvest rs485 message cruncher got exception ",ex);
                }
            }
        }
    }

    private void switchToJacuzzi() {
        cancel();
        processor.setRS485ControllerType("JACUZZI");
        processor.setUpRS485Processors();
    }

    private enum State {
        searchForBeginning,
        searchForEnd,
        processFrameFormat,
        getPackets
    }

    private void parseHDLCMessages(ByteBuffer workingMessage, ByteBuffer bytesRead) {
        bytesRead.flip();
        while (bytesRead.remaining() > 0) {
            byte data = bytesRead.get();
            if (workingMessage.position() > 127) {
                hdlcFrameLength = 0;
                state = State.searchForBeginning;
                workingMessage.clear();
                LOGGER.warn("rs 485 frame out of sync, message parsing went over 128 bytes, resetting state.");
            }
            switch (state) {
                case searchForBeginning:
                    if (data == delimiter) {
                        state = State.processFrameFormat;
                    }
                    break;
                case processFrameFormat:
                    if (data == delimiter) {
                        break;
                    }
                    hdlcFrameLength = (data & HDLC_FRAME_LENGTH_MASK);
                    if (hdlcFrameLength < 4 || hdlcFrameLength == 126) {
                        hdlcFrameLength = 0;
                        state = State.searchForBeginning;
                        workingMessage.clear();
                        break;
                    }
                    workingMessage.put(data);
                    state = State.getPackets;
                    break;
                case getPackets:
                    workingMessage.put(data);
                    if (hdlcFrameLength == workingMessage.position()) {
                        state = State.searchForEnd;
                    }
                    break;
                case searchForEnd:
                    if (data == delimiter) {
                        workingMessage.flip();
                        if ( !shouldNotProcessMessage(workingMessage) ) {
                            int packetType = (0xFF & workingMessage.get(3));
                            long now = System.currentTimeMillis();
                            switch (packetType) {
                                case 6:
                                    if ((now - lastPollSent) > REQUEST_MESSAGE_POLL_INTERVAL) {
                                        lastPollSent = now;
                                        processDevicePollForDownlink();
                                    }
                                    break;
                                case 4:
                                    processDevicePresentQuery(workingMessage.get(1));
                                    break;
                                case 2:
                                    processAddressAssignment(convertToMessageBody(workingMessage));
                                    break;
                                case 0:
                                    processUnassignedDevicePoll();
                                    break;
                                case 0x90:
                                    if ((now - lastWifiPollSent) > REQUEST_MESSAGE_POLL_INTERVAL) {
                                        lastWifiPollSent = now;
                                        processWifiModuleCommand(convertToMessageBody(workingMessage));
                                    }
                                    break;
                                default:
                                    pendingMessages.offer(convertToMessageBody(workingMessage));
                                    break;
                            }
                        }
                    }
                    workingMessage.clear();
                    state = State.processFrameFormat;
                    hdlcFrameLength = 0;
                    break;
            }
        }
    }

    private byte[] convertToMessageBody(ByteBuffer workingMessage) {
        byte[] message = new byte[hdlcFrameLength];
        workingMessage.get(message);
        return message;
    }

    private boolean shouldNotProcessMessage(ByteBuffer workingMessage) {
        int incomingAddress = (0xFF & workingMessage.get(1));
        int packetType = (0xFF & workingMessage.get(3));

        if (packetType == 4) {
            LOGGER.info("received device present query for address {}", incomingAddress);
        }

        if (incomingAddress == HDLC_LINKING_ADDRESS || packetType == 0 || packetType == 2) {
            // skip address assignment, we used a fixed address
            return !addressState.get().equals(ADDRESS_STATE.ACQUIRING_DYNAMIC_ADDRESS);
        }

        if (packetType == 4 && (incomingAddress == rs485PreferredStaticAddress || (processor.getPersistedRS485Address() != null && incomingAddress == (0xFF & processor.getPersistedRS485Address())))) {
            // if getting a device present query(packettype=4) on prefered static address of 10 or acquired dynamic address, use it,
            // let agent switch onto that address
            return false;
        }

        if (rs485RegisrationAddress != incomingAddress && incomingAddress != HDLC_ALL_STATIONS_ADDRESS) {
            return true;
        }

        return false;
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
        populateComponentStateFromPanelUpdate(compsBuilder, message);
        return compsBuilder.build();
    }

    protected ReentrantReadWriteLock getSpaStateLock() {
        return spaStateLock;
    }

    protected void processWifiModuleCommand(byte[] message) {
        try {
            int command = (0xFF & message[4]);
            if (LOGGER.isDebugEnabled()) LOGGER.debug("received wifi module command {}", command);
            if (command == 1) {
                rs485MessagePublisher.sendWifiMacAddress(rs485RegisrationAddress);
            }
        } catch (RS485Exception ex) {
            LOGGER.error("unable to process wifi module command", ex);
        }
    }

    protected void processUnassignedDevicePoll() {
        if (System.currentTimeMillis() - registrationLastAttempt.get() > MAX_485_REG_WAIT) {
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

    protected void processAddressAssignment(byte[] message) {
        if (registrationrequestId.get() > 0) {
            int incomingRequestId = ((0xFF & message[5]) << 8) | (0xFF & message[6]);
            if (incomingRequestId == registrationrequestId.get()) {
                rs485RegisrationAddress = message[4];
                processor.saveDynamicRS485AddressInAgentSettings(rs485RegisrationAddress);
                setAddressState(ADDRESS_STATE.DYNAMIC_ADDRESS);
                try {
                    rs485MessagePublisher.sendAddressAssignmentAck(rs485RegisrationAddress);
                } catch (RS485Exception ex) {
                    LOGGER.error("unable to send assigned device ack", ex);
                }
            }
        }
    }

    protected void processDevicePresentQuery(byte address) {
        try {
            if (address == rs485PreferredStaticAddress) {
                setAddressState(ADDRESS_STATE.STATIC_ADDRESS);
            } else {
                setAddressState(ADDRESS_STATE.DYNAMIC_ADDRESS);
            }
            rs485RegisrationAddress = address;
            LOGGER.info("received device present query for address {}, set state to {}", address, addressState.get().name());
            rs485MessagePublisher.sendDeviceQueryResponse(rs485RegisrationAddress);
        } catch (RS485Exception ex) {
            LOGGER.error("unable to send device present response", ex);
        }
    }

    protected void processDevicePollForDownlink() {
        try {
            rs485MessagePublisher.sendPendingDownlinkIfAvailable(rs485RegisrationAddress);
        } catch (RS485Exception ex) {
            LOGGER.error("unable to send device poll for downlinks", ex);
        }
    }

    protected void processDeviceConfigsMessage(byte[] message) {
        boolean rLocked = false;
        Components.Builder compsBuilder = Components.newBuilder();
        try {
            getLatestSpaInfoLock().readLock().lockInterruptibly();
            rLocked = true;
            if (getLatestSpaInfo().hasComponents()) {
                compsBuilder.mergeFrom(getLatestSpaInfo().getComponents());
            }
        } catch (Exception ex) {
            throw Throwables.propagate(ex);
        } finally {
            if (rLocked) {
                getLatestSpaInfoLock().readLock().unlock();
            }
        }

        Components components = populateDeviceConfigsFromMessage(message, compsBuilder);

        boolean wLocked = false;
        try {
            getLatestSpaInfoLock().writeLock().lockInterruptibly();
            wLocked = true;
            SpaState.Builder builder = SpaState.newBuilder(getLatestSpaInfo());
            builder.setComponents(components);
            builder.setLastUpdateTimestamp(components.getLastUpdateTimestamp());
            setLatestSpaInfo(builder.build());
        } catch (Exception ex) {
            throw Throwables.propagate(ex);
        } finally {
            if (wLocked) {
                getLatestSpaInfoLock().writeLock().unlock();
            }
        }
        LOGGER.info("processed device config message");
    }

    protected void processSystemInfoMessage(byte[] message) {
        boolean rLocked = false;
        SystemInfo.Builder infoBuilder = SystemInfo.newBuilder();
        try {
            getLatestSpaInfoLock().readLock().lockInterruptibly();
            rLocked = true;
            if (getLatestSpaInfo().hasSystemInfo()) {
                infoBuilder.mergeFrom(getLatestSpaInfo().getSystemInfo());
            }
        } catch (Exception ex) {
            throw Throwables.propagate(ex);
        } finally {
            if (rLocked) {
                getLatestSpaInfoLock().readLock().unlock();
            }
        }

        SystemInfo systemInfo = populateSystemInfoFromMessage(message, infoBuilder);
        boolean wLocked = false;
        try {
            getLatestSpaInfoLock().writeLock().lockInterruptibly();
            wLocked = true;
            SpaState.Builder builder = SpaState.newBuilder(getLatestSpaInfo());
            builder.setSystemInfo(systemInfo);
            builder.setLastUpdateTimestamp(systemInfo.getLastUpdateTimestamp());
            setLatestSpaInfo(builder.build());
        } catch (Exception ex) {
            throw Throwables.propagate(ex);
        } finally {
            if (wLocked) {
                getLatestSpaInfoLock().writeLock().unlock();
            }
        }
        LOGGER.info("processed system info message");
    }

    protected void processPanelUpdateMessage(byte[] message) {
        if (Arrays.equals(lastPanelUpdate.get(), message)) {
            // dedupe panel updates, they come often, and are usually the exact same, skip if so
            return;
        }

        lastPanelUpdate.set(message);
        Controller controller = populateControllerStateFromPanelUpdate(message);
        spaClock.set(new SpaClock(controller.getHour(), controller.getMinute()));
        Components components = null;
        try {
            components = buildComponentsMessageFromPanelUpdate(message);
        } catch (IllegalStateException ex) {
            // the device config message hasn't arrived yet, so the presence of components cannot be recorded yet
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
            builder.setLastUpdateTimestamp(new Date().getTime());
            builder.setUpdateInterval(processor.getUpdateIntervalSeconds());
            builder.setWifiUpdateInterval(processor.getWifiUpdateIntervalSeconds());
            spaState = builder.build();
        } catch (Exception ex) {
            throw Throwables.propagate(ex);
        }
        finally {
            if (wLocked) {
                getLatestSpaInfoLock().writeLock().unlock();
            }
        }
        LOGGER.info("processed panel update message");
    }

    protected final int bwgTempToFahrenheit(int bwgTemp) {
        return toFahrenheit(usesCelsius(), bwgTemp);
    }

    protected final boolean validTempReading(int bwgTemp) {
        if (bwgTemp > 249) {
            // internal error code for anything above 250
            return false;
        }
        return true;
    }

    protected final int toFahrenheit(boolean celsius, int rawTemp) {
        if (! celsius) {
            return rawTemp;
        }
        double tempCelsius = new BigDecimal(rawTemp / 2.0 ).setScale(1, BigDecimal.ROUND_HALF_UP).doubleValue();
        return new BigDecimal((9.0/5.0)*tempCelsius + 32).setScale(0, BigDecimal.ROUND_HALF_UP).intValue();
    }

    protected final List<PumpComponent.State> getAvailablePumpStates(int available) {
        // 1 = 2 speed
        // 2 and up = 3 speed
        if (available > 1) {
            return newArrayList(PumpComponent.State.values());
        }
        return newArrayList(PumpComponent.State.OFF, PumpComponent.State.HIGH);
    }

    protected final List<LightComponent.State> getAvailableLightStates(int available) {
        // 1 = 2 speed
        // 2 = 3 speed
        // 3 = 4 speed
        if (available > 2) {
            return newArrayList(LightComponent.State.values());
        } else if (available > 1) {
            return newArrayList(LightComponent.State.OFF, LightComponent.State.LOW, LightComponent.State.HIGH);
        }
        return newArrayList(LightComponent.State.OFF, LightComponent.State.HIGH);
    }

    protected final List<BlowerComponent.State> getAvailableBlowerStates(int available) {
        // 1 = 2 speed
        // 2 = 3 speed
        // 3 = 4 speed
        if (available > 2) {
            return newArrayList(BlowerComponent.State.values());
        } else if (available > 1) {
            return newArrayList(BlowerComponent.State.OFF, BlowerComponent.State.LOW, BlowerComponent.State.HIGH);
        }
        return newArrayList(BlowerComponent.State.OFF, BlowerComponent.State.HIGH);
    }

    protected List<ToggleComponent.State> getAvailableToggleStates() {
        return newArrayList(ToggleComponent.State.OFF, ToggleComponent.State.ON);
    }

    protected FaultLogManager getFaultLogManager() {
        return this.faultLogManager;
    }

    @VisibleForTesting
    Process executeUnixCommand(String command) throws IOException {
        return Runtime.getRuntime().exec(command);
    }

    @VisibleForTesting
    HostUtils getHostUtils() {
        return HostUtils.instance();
    }

  }
