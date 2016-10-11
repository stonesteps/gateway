package com.tritonsvc.gateway;

import com.tritonsvc.spa.communication.proto.Bwg.AckResponseCode;
import jdk.dio.uart.UART;
import org.junit.Before;
import org.junit.Test;

import java.nio.ByteBuffer;

import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.*;

public class NGSCMessagePublisherTest {

    private BWGProcessor processor;
    private NGSCMessagePublisher publisher;
    private UART uart;

    @Before
    public void setUp() {
        processor = mock(BWGProcessor.class);
        uart = mock(UART.class);
        when(processor.getRS485UART()).thenReturn(uart);
        publisher = new NGSCMessagePublisher(processor);
    }

    @Test
    public void itSubmitsFilterCycle() throws Exception {
        SpaClock clock = new SpaClock(1, 2);
        publisher.sendFilterCycleRequest(0, 10, (byte) 5, "originator", "hardware", clock);
        publisher.sendPendingDownlinkIfAvailable((byte) 5);

        verify(uart).write(any());
        verify(processor).sendAck(any(), any(), eq(AckResponseCode.OK), any());
    }

    @Test
    public void testUpdateSpaTime() throws Exception {
        publisher.updateSpaTime("1", "1", false, (byte) 0x5, null, null, null, 12, 13);
        publisher.sendPendingDownlinkIfAvailable((byte) 0x5);

        ByteBuffer bb = ByteBuffer.wrap(new byte[]{0x7E, 0x07, 0x05, (byte) 0xBF, 0x21, 0x0C, 0x0D, (byte) 0xDF, 0x7E});
        verify(uart).write(bb);
    }
}
