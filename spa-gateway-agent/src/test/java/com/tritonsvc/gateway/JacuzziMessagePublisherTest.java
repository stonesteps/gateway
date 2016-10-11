package com.tritonsvc.gateway;

import jdk.dio.uart.UART;
import org.junit.Before;
import org.junit.Test;

import java.nio.ByteBuffer;

import static org.mockito.Mockito.*;

public class JacuzziMessagePublisherTest {

    private BWGProcessor processor;
    private JacuzziMessagePublisher publisher;
    private UART uart;

    @Before
    public void setUp() {
        processor = mock(BWGProcessor.class);
        uart = mock(UART.class);
        when(processor.getRS485UART()).thenReturn(uart);
        publisher = new JacuzziMessagePublisher(processor);
    }

    @Test
    public void testUpdateSpaTime() throws Exception {
        publisher.updateSpaTime("1", "1", false, (byte) 0x5, 2016, 9, 11, 12, 13);
        publisher.sendPendingDownlinkIfAvailable((byte) 0x5);

        ByteBuffer bb = ByteBuffer.wrap(new byte[]{0x7E, 0x0A, 0x05, (byte) 0xBF, 0x18, 0x39, 0x0B, 0x10, 0x0C, 0x0D, (byte) 0x96, 0x7E});
        verify(uart).write(bb);
    }
}
