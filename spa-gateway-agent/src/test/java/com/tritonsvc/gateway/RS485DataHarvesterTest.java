package com.tritonsvc.gateway;


import jdk.dio.uart.UART;
import org.junit.Before;
import org.junit.Test;

import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class RS485DataHarvesterTest {

    private BWGProcessor processor;
    private RS485MessagePublisher publisher;
    private UART uart;
    private RS485DataHarvester harvester;
    private int read;
    private CountDownLatch cdl;

    @Before
    public void setUp() {
        processor = mock(BWGProcessor.class);
        uart = mock(UART.class);
        publisher = mock(RS485MessagePublisher.class);
        when(processor.getRS485UART()).thenReturn(uart);
        when(processor.stillRunning()).thenReturn(true);
        harvester = new RS485DataHarvester(processor, publisher);
        read = 0;
        cdl = new CountDownLatch(1);
    }

    @Test
    public void itAcquiresAddressOnBus() throws Exception {

        byte [] addressUnassigned = new byte[]{(byte) 0x7E, (byte) 0x05, (byte) 0xFE, (byte) 0xBF, (byte) 0x00};
        doAnswer(invocation -> {
            if (read < 7) {
                ByteBuffer bb = (ByteBuffer) invocation.getArguments()[0];

                if (read < 5) {
                    bb.put(addressUnassigned[read]);
                } else if (read == 5) {
                    bb.put(HdlcCrc.generateFCS(addressUnassigned));
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

        verify(publisher).sendUnassignedDeviceResponse(anyInt());
    }
}