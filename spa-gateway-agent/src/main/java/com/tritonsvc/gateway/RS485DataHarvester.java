package com.tritonsvc.gateway;

import com.google.common.primitives.Bytes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Date;
import java.util.Random;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

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
    // TODO - remove lastTest when no longer needing downlink test generator in parseHDLCMessages()
    long lastTest = 0;

    //TODO - Marek, these are stateful pojo objects holding the attribs from the panel info/update
    //       messages received over 485. these objects get updated each time the 485 message arrives by processPanelUpdateMessage().
    //
    //       there is a dedicated thread that pushes spa info uplink message to cloud that gets its data from these objects,
    //       the method that performs this task is sendPeriodicSpaInfo(). Feel free to start filling out
    //       these classes to enable sendPeriodicSpaInfo() to build the SpaInfo uplink message and define it in bwg.proto as needed.
    //       I will connect the rs485 data flow in processPanelUpdateMessage, unless you want to try that out, feel free,
    //       would need to refer to the ICD for message layout of PanelUpdate message.
    //
    //private SystemInformation lastKnownSystemInformation;
    //
    private SpaStateInfo lastKnownSpaState = new SpaStateInfo();

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

    public Byte getRegisteredAddress() {
        int value = regisrationAddress.get();
        if (value < 0) {
            return null;
        }
        return (byte)(0xFF & value);
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


        //TODO - eventually remove, servers as a handy downlink test harness
        //if (regisrationAddress.get() > -1 && (System.currentTimeMillis() - lastTest) > 40000) {
        //    try {rs485MessagePublisher.setTemperature(80, (byte)regisrationAddress.get());} catch (Exception ex) {}
        //    try {rs485MessagePublisher.sendButtonCode(ButtonCode.kJets2MetaButton, (byte)regisrationAddress.get());} catch (Exception ex) {}
        //    lastTest = System.currentTimeMillis();
        // }

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
        }

        //if (packetType == SystemInformation_TYPE) {
        //    processSystemInformationMessage(msgBuffer, hdlcHeader.byteLength);
        //}
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
        //TODO - Marek, parse this panel update message using PanelUpdate message, sect 3.1.4 from ICD,
        // http://iotdev02/download/attachments/1015837/NGSC%20Communications%20ICD.doc?version=1&modificationDate=1454715406000&api=v2
        //
        // and update lastKnownSpaState
        //

        lastKnownSpaState.setLastUpdated(new Date());
        // ...
    }

    public SpaStateInfo getLatestSpaInfo() {
        return lastKnownSpaState;
    }
}
