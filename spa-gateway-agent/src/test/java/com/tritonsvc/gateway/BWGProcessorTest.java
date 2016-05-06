package com.tritonsvc.gateway;


import com.tritonsvc.spa.communication.proto.Bwg.AckResponseCode;
import com.tritonsvc.spa.communication.proto.Bwg.Downlink.Model.Request;
import com.tritonsvc.spa.communication.proto.Bwg.Downlink.Model.RequestMetadata;
import com.tritonsvc.spa.communication.proto.Bwg.Downlink.Model.RequestType;
import com.tritonsvc.spa.communication.proto.Bwg.Downlink.Model.SpaCommandAttribName;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.Model.Components.PumpComponent;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.Model.Constants.ComponentType;
import jdk.dio.uart.UART;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;

import static com.google.common.collect.Lists.newArrayList;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Matchers.isNull;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class BWGProcessorTest {

    private BWGProcessor processor;
    private RS485MessagePublisher rs485MessagePublisher;
    private RS485DataHarvester rs485DataHarvester;

    @Before
    public void setUp() throws IOException {
        rs485MessagePublisher = mock(RS485MessagePublisher.class);
        rs485DataHarvester = mock(RS485DataHarvester.class);
        processor = new BWGProcessor();
        processor.setRS485DataHarvester(rs485DataHarvester);
        processor.setRS485MessagePublisher(rs485MessagePublisher);
        processor.setRS485(mock(UART.class));
        processor = spy(processor);
        when(rs485DataHarvester.getRegisteredAddress()).thenReturn((byte)2);
        doNothing().when(processor).sendAck(any(), any(), eq(AckResponseCode.RECEIVED), isNull(String.class));
    }

    @Test
    public void itSubmitsCircPumpRequest() throws Exception {
        ComponentInfo info = new ComponentInfo("OFF", newArrayList(PumpComponent.State.OFF,PumpComponent.State.LOW,PumpComponent.State.HIGH));
        when(rs485DataHarvester.getComponentState(eq(ComponentType.CIRCULATION_PUMP), eq(0))).thenReturn(info);
        when(rs485MessagePublisher.getCode(eq("kPump0MetaButton"))).thenReturn(NGSCButtonCode.kPump0MetaButton);
        Request request = Request.newBuilder().setRequestType(RequestType.CIRCULATION_PUMP).addMetadata(RequestMetadata.newBuilder().setName(SpaCommandAttribName.DESIREDSTATE).setValue("HIGH")).build();

        processor.handleDownlinkCommand(request, "hardwareId", "originatorId");

        verify(rs485MessagePublisher, times(2)).sendCode(eq(NGSCButtonCode.kPump0MetaButton.getCode()), eq((byte)2), eq("originatorId"), eq("hardwareId"));
    }
}
