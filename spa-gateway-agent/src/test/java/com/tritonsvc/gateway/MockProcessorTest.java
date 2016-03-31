package com.tritonsvc.gateway;

import com.tritonsvc.agent.GatewayEventDispatcher;
import com.tritonsvc.spa.communication.proto.Bwg;
import com.tritonsvc.spa.communication.proto.BwgHelper;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentMatcher;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import static org.mockito.Matchers.*;
import static org.mockito.Mockito.*;

/**
 * Created by holow on 3/31/2016.
 */
public class MockProcessorTest {

    private MockProcessor mockProcessor;
    private GatewayEventDispatcher mockGatewayEventDispatcher;
    
    @Before
    public void init() {
        final Properties props = new Properties();
        props.setProperty("mock.spaId", "1");
        props.setProperty("mock.controllerId", "2");
        props.setProperty("mock.moteId", "3");

        mockGatewayEventDispatcher = mock(GatewayEventDispatcher.class);
        
        mockProcessor = new MockProcessor();
        mockProcessor.init(props);
        mockProcessor.setEventDispatcher(mockGatewayEventDispatcher);
    }
    
    @Test
    public void testSetTemp() {
        Map<String,String> values = new HashMap<>();
        values.put("DESIREDTEMP", "78");
        final Bwg.Downlink.Model.Request request = BwgHelper.buildRequest(Bwg.Downlink.Model.RequestType.HEATER, values);
        mockProcessor.handleDownlinkCommand(request, "1", "1");

        mockProcessor.processDataHarvestIteration();
        verify(mockGatewayEventDispatcher, times(2)).sendUplink(eq("1"), eq("1"), eq(Bwg.Uplink.UplinkCommandType.ACKNOWLEDGEMENT), any(Bwg.Uplink.Model.DownlinkAcknowledge.class));
        verify(mockGatewayEventDispatcher, times(1)).sendUplink(eq("3"), any(), eq(Bwg.Uplink.UplinkCommandType.MEASUREMENT), any(Bwg.Uplink.Model.DeviceMeasurements.class));
        verify(mockGatewayEventDispatcher, times(1)).sendUplink(eq("1"), any(), eq(Bwg.Uplink.UplinkCommandType.SPA_STATE), argThat(new HasTempSetArgMatcher(78)));
    }

    private class HasTempSetArgMatcher extends ArgumentMatcher<Bwg.Uplink.Model.SpaState> {

        private final int desiredTemp;

        public HasTempSetArgMatcher(final int desiredTemp) {
            this.desiredTemp = desiredTemp;
        }

        @Override
        public boolean matches(final Object argument) {
            if (argument instanceof Bwg.Uplink.Model.SpaState) {
                final Bwg.Uplink.Model.SpaState arg = (Bwg.Uplink.Model.SpaState) argument;
                return desiredTemp == arg.getController().getCurrentWaterTemp();
            }

            return false;
        }
    }
}
