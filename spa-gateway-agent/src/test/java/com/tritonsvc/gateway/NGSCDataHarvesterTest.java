package com.tritonsvc.gateway;


import jdk.dio.uart.UART;
import org.junit.Before;
import org.junit.Test;

import java.nio.ByteBuffer;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.*;

public class NGSCDataHarvesterTest {

    private BWGProcessor processor;
    private NGSCMessagePublisher publisher;
    private FaultLogManager manager;
    private UART uart;
    private NGSCDataHarvester harvester;
    private int read;
    private CountDownLatch cdl;

    @Before
    public void setUp() {
        processor = mock(BWGProcessor.class);
        when(processor.getConfigProps()).thenReturn(new Properties());
        uart = mock(UART.class);
        publisher = mock(NGSCMessagePublisher.class);
        manager = mock(FaultLogManager.class);
        when(processor.getRS485UART()).thenReturn(uart);
        when(processor.stillRunning()).thenReturn(true);
        harvester = new NGSCDataHarvester(processor, publisher, manager);
        read = 0;
        cdl = new CountDownLatch(1);
    }

    @Test
    public void itSetsFixedAddressOnBus() throws Exception {

        byte[] devicePoll = new byte[]{(byte) 0x7E, (byte) 0x05, (byte) 0xA, (byte) 0xBF, (byte) 0x04};
        doAnswer(invocation -> {
            if (read < 7) {
                ByteBuffer bb = (ByteBuffer) invocation.getArguments()[0];

                if (read < 5) {
                    bb.put(devicePoll[read]);
                } else if (read == 5) {
                    bb.put(HdlcCrc.generateFCS(devicePoll));
                } else {
                    bb.put((byte) 0x7E); // flag
                }
                read++;
            } else {
                cdl.countDown();
            }
            return 1;
        }).when(uart).read(any(ByteBuffer.class));

        Thread thread = new Thread(harvester);
        thread.start();

        // wait at most 5 seconds for the message to be read, should really be under one second.
        cdl.await(5, TimeUnit.SECONDS);
        // there's a separate thread that processes 485 messages off the bus, this helps the test wait
        // for that thread to process message
        Thread.sleep(1000);

        verify(publisher).sendDeviceQueryResponse(eq((byte)10));
    }

    @Test
    public void itSwitchesToJacuzzi() throws Exception {
        byte[] devicePoll = new byte[]{(byte) 0x7E, (byte) 0x05, (byte) 0xA, (byte) 0xBF, (byte) 0x04};
        byte[] jacuzziPanelUpdate = new byte[]{(byte) 0x7E, (byte) 0x05, (byte) 0xA, (byte) 0xBF, (byte) 0x16};

        doAnswer(invocation -> {
            if (read < 14) {
                ByteBuffer bb = (ByteBuffer) invocation.getArguments()[0];

                if (read < 5) {
                    bb.put(devicePoll[read]);
                } else if (read == 5) {
                    bb.put(HdlcCrc.generateFCS(devicePoll));
                } else if (read == 6){
                    bb.put((byte) 0x7E); // flag}
                } else if (read == 7){
                    bb.put(jacuzziPanelUpdate[0]);
                } else if (read == 8){
                    bb.put(jacuzziPanelUpdate[1]);
                } else if (read == 9){
                    bb.put(jacuzziPanelUpdate[2]);
                } else if (read == 10){
                    bb.put(jacuzziPanelUpdate[3]);
                } else if (read == 11){
                    bb.put(jacuzziPanelUpdate[4]);
                } else if (read == 12) {
                    bb.put(HdlcCrc.generateFCS(jacuzziPanelUpdate));
                } else if (read == 13){
                    bb.put((byte) 0x7E);
                }
                read++;
            } else {
                cdl.countDown();
            }
            return 1;
        }).when(uart).read(any(ByteBuffer.class));

        Thread thread = new Thread(harvester);
        thread.start();

        // wait at most 5 seconds for the message to be read, should really be under one second.
        cdl.await(5, TimeUnit.SECONDS);
        // there's a separate thread that processes 485 messages off the bus, this helps the test wait
        // for that thread to process message
        Thread.sleep(1000);

        verify(processor).setRS485ControllerType(eq("JACUZZI"));
        verify(processor).setUpRS485Processors();
    }
}
