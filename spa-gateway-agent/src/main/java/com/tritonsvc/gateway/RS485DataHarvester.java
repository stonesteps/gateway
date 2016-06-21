package com.tritonsvc.gateway;

import com.google.common.base.Throwables;
import com.google.common.primitives.Ints;
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

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static com.google.common.collect.Lists.newArrayList;
import static javax.xml.bind.DatatypeConverter.printHexBinary;

/**
 * Abstract serial comms data adapter, implementors must provide the correct parsing routines
 */
public abstract class RS485DataHarvester implements Runnable {
    private static Logger LOGGER = LoggerFactory.getLogger(RS485DataHarvester.class);
    private BWGProcessor processor;
    private FaultLogManager faultLogManager;

    static byte delimiter = (byte)0x7e; // this is the HDLC delimeter flag
    static int HDLC_FRAME_LENGTH_MASK = 0x7F;
    static int HDLC_ALL_STATIONS_ADDRESS = 0xFF;
    static int HDLC_LINKING_ADDRESS = 0xFE;

    private RS485MessagePublisher rs485MessagePublisher;
    private State state = State.searchForBeginning;
    private int hdlcFrameLength =0;
    private byte rs485RegisrationAddress = 0;

    private AtomicReference<SpaClock> spaClock = new AtomicReference<>();
    private AtomicReference<byte[]> lastPanelUpdate = new AtomicReference<>(new byte[]{});
    private final ReentrantReadWriteLock spaStateLock = new ReentrantReadWriteLock();
    private boolean cancelled;

    private SpaState spaState = SpaState.newBuilder().setLastUpdateTimestamp(new Date().getTime()).build();

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
        spaState = SpaState.newBuilder(spaState).setRs485Address(rs485RegisrationAddress).setRs485AddressActive(false).build();
    }

    @Override
    public void run() {
        ByteBuffer workingMessage = ByteBuffer.allocate(256);
        ByteBuffer readBytes = ByteBuffer.allocate(1);
        LOGGER.info("RS 485 configured to use fixed address of {}", getRegisteredAddress());

        while(!cancelled && processor.stillRunning()) {
            try {
                if (processor.getRS485UART() == null) {
                    processor.setUpRS485();
                }
                processor.getRS485UART().setReceiveTimeout(5000);

                while (!cancelled && processor.stillRunning()) {
                    readBytes.clear();
                    int read = processor.getRS485UART().read(readBytes);
                    parseHDLCMessages(workingMessage, readBytes);
                }
            }
            catch (Throwable ex) {
                LOGGER.info("harvest rs485 data listener got exception ",ex);
                workingMessage.clear();
                state = State.searchForBeginning;
                hdlcFrameLength = 0;
                try {Thread.sleep(10000);} catch (InterruptedException ex2){}
            }
        }
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

    private void startProcessMessage(byte[] message) {
        int packetType = message[3];
        if (!HdlcCrc.isValidFCS(message)) {
            LOGGER.debug("Invalid rs485 data message, failed FCS check {}", printHexBinary(message));
            return;
        }

        if (packetType == 0x16 && processor.getRS485ControllerType() == null ) {
            switchToJacuzzi();
            return;
        }

        processMessage(message);
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

        if (bytesRead.remaining() + workingMessage.position() > 128) {
            hdlcFrameLength = 0;
            state = State.searchForBeginning;
            LOGGER.info("rs 485 frame out of sync, message parsing went over 128 bytes, resetting state.");
        }

        while (bytesRead.remaining() > 0) {
            byte data = bytesRead.get();
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
                        workingMessage.clear();
                        continue;
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
                        if ( !shouldNotProcessMessage(workingMessage) ) {
                            byte[] message = new byte[hdlcFrameLength];
                            workingMessage.position(0);
                            workingMessage.get(message);
                            try {
                                startProcessMessage(message);
                            } catch (Throwable th) {
                                LOGGER.error("had problem processing rs 485 message ", th);
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

    private boolean shouldNotProcessMessage(ByteBuffer workingMessage) {
        int incomingAddress = (0xFF & workingMessage.get(1));

        if (incomingAddress == HDLC_LINKING_ADDRESS || workingMessage.get(3) == 0 || workingMessage.get(3) == 2) {
            // skip address assignment, we used a fixed address
            return true;
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
            LOGGER.info("received wifi module command {}", command);
            if (command == 1) {
                rs485MessagePublisher.sendWifiMacAddress(rs485RegisrationAddress);
            }
        } catch (RS485Exception ex) {
            LOGGER.error("unable to process wifi module command", ex);
        }
    }

    protected void processDevicePresentQuery() {
        try {
            rs485MessagePublisher.sendDeviceQueryResponse(rs485RegisrationAddress);
            LOGGER.info("received device present query");
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
  }
