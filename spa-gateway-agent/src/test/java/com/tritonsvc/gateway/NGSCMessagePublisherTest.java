package com.tritonsvc.gateway;

import com.tritonsvc.spa.communication.proto.Bwg.AckResponseCode;
import jdk.dio.uart.UART;
import org.junit.Before;
import org.junit.Test;

import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
        SpaClock clock = new SpaClock(1,2);
        publisher.sendFilterCycleRequest(0, 10, (byte)5, "originator", "hardware", clock);
        publisher.sendPendingDownlinkIfAvailable((byte)5);

        verify(uart).write(any());
        verify(processor).sendAck(any(), any() , eq(AckResponseCode.OK), any());
    }
}
