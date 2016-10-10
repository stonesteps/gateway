package com.tritonsvc.gateway;

import com.tritonsvc.agent.AgentSettingsPersister;
import com.tritonsvc.agent.GatewayEventDispatcher;
import com.tritonsvc.spa.communication.proto.Bwg;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.UplinkCommandType;
import com.tritonsvc.spa.communication.proto.BwgHelper;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentMatcher;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import static org.mockito.Matchers.any;
import static org.mockito.Matchers.argThat;
import static org.mockito.Matchers.eq;
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
        props.setProperty("mock.tempMoteId", "4");
        props.setProperty("mock.currentMoteId", "5");

        mockGatewayEventDispatcher = mock(GatewayEventDispatcher.class);

        mockProcessor = new MockProcessor(mock(AgentSettingsPersister.class));
        mockProcessor.handleStartup("1234", props, "./", null);
        mockProcessor.setEventDispatcher(mockGatewayEventDispatcher);
    }
    @After
    public void cleanup() {
        if (mockProcessor != null) {
            mockProcessor.handleShutdown();
        }
    }

    @Test
    public void testSetTemp() {
        Map<String, String> values = new HashMap<>();
        values.put("DESIREDTEMP", "78");
        final Bwg.Downlink.Model.Request request = BwgHelper.buildRequest(Bwg.Downlink.Model.RequestType.HEATER, values);
        mockProcessor.handleDownlinkCommand(request, "1", "1");

        mockProcessor.processDataHarvestIteration();
        verify(mockGatewayEventDispatcher, times(2)).executeRunnable(any());
        verify(mockGatewayEventDispatcher, times(1)).sendUplink(eq("4"), any(), eq(UplinkCommandType.MEASUREMENT), any(Bwg.Uplink.Model.Events.class), anyBoolean());
        verify(mockGatewayEventDispatcher, times(1)).sendUplink(eq("5"), any(), eq(UplinkCommandType.MEASUREMENT), any(Bwg.Uplink.Model.Events.class), anyBoolean());
        verify(mockGatewayEventDispatcher, times(1)).sendUplink(eq("1"), any(), eq(Bwg.Uplink.UplinkCommandType.SPA_STATE), argThat(new HasTempSetArgMatcher(78)), anyBoolean());
    }

    @Test
    public void updateCircPumpState() {
        Map<String, String> values = new HashMap<>();
        values.put("DESIREDSTATE", "LOW");
        final Bwg.Downlink.Model.Request request = BwgHelper.buildRequest(Bwg.Downlink.Model.RequestType.CIRCULATION_PUMP, values);
        mockProcessor.handleDownlinkCommand(request, "1", "1");

        mockProcessor.processDataHarvestIteration();
        verify(mockGatewayEventDispatcher, times(2)).executeRunnable(any());
        verify(mockGatewayEventDispatcher, times(1)).sendUplink(eq("4"), any(), eq(UplinkCommandType.MEASUREMENT), any(Bwg.Uplink.Model.Events.class), anyBoolean());
        verify(mockGatewayEventDispatcher, times(1)).sendUplink(eq("5"), any(), eq(UplinkCommandType.MEASUREMENT), any(Bwg.Uplink.Model.Events.class), anyBoolean());
        verify(mockGatewayEventDispatcher, times(1)).sendUplink(eq("1"), any(), eq(Bwg.Uplink.UplinkCommandType.SPA_STATE), argThat(new HasCircPumpStateSetArgMatcher("LOW")), anyBoolean());
    }

    @Test
    public void testSetTime() {
        Map<String, String> values = new HashMap<>();
        values.put(Bwg.Downlink.Model.SpaCommandAttribName.DATE_YEAR.name(), "2016");
        values.put(Bwg.Downlink.Model.SpaCommandAttribName.DATE_MONTH.name(), "9");
        values.put(Bwg.Downlink.Model.SpaCommandAttribName.DATE_DAY.name(), "10");
        values.put(Bwg.Downlink.Model.SpaCommandAttribName.TIME_HOUR.name(), "14");
        values.put(Bwg.Downlink.Model.SpaCommandAttribName.TIME_MINUTE.name(), "15");
        values.put(Bwg.Downlink.Model.SpaCommandAttribName.TIME_SECOND.name(), "0");

        final Bwg.Downlink.Model.Request request = BwgHelper.buildRequest(Bwg.Downlink.Model.RequestType.SET_TIME, values);
        mockProcessor.handleDownlinkCommand(request, "1", "1");

        mockProcessor.processDataHarvestIteration();
        verify(mockGatewayEventDispatcher, times(1)).executeRunnable(any());
        verify(mockGatewayEventDispatcher, times(1)).sendUplink(eq("4"), any(), eq(UplinkCommandType.MEASUREMENT), any(Bwg.Uplink.Model.Events.class), anyBoolean());
        verify(mockGatewayEventDispatcher, times(1)).sendUplink(eq("5"), any(), eq(UplinkCommandType.MEASUREMENT), any(Bwg.Uplink.Model.Events.class), anyBoolean());
        verify(mockGatewayEventDispatcher, times(1)).sendUplink(eq("1"), any(), eq(Bwg.Uplink.UplinkCommandType.SPA_STATE), argThat(new HasTimeSetArgMatcher(2016, 10, 10, 14, 15)), anyBoolean());
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

    private class HasCircPumpStateSetArgMatcher extends ArgumentMatcher<Bwg.Uplink.Model.SpaState> {

        private final String desiredState;

        public HasCircPumpStateSetArgMatcher(final String desiredState) {
            this.desiredState = desiredState;
        }

        @Override
        public boolean matches(final Object argument) {
            if (argument instanceof Bwg.Uplink.Model.SpaState) {
                final Bwg.Uplink.Model.SpaState arg = (Bwg.Uplink.Model.SpaState) argument;
                return desiredState.equals(arg.getComponents().getCirculationPump().getCurrentState().name());
            }

            return false;
        }
    }

    private class HasTimeSetArgMatcher extends ArgumentMatcher<Bwg.Uplink.Model.SpaState> {

        private final int year;
        private final int month;
        private final int day;
        private final int hour;
        private final int minute;

        public HasTimeSetArgMatcher(int year, int month, int day, int hour, int minute) {
            this.year = year;
            this.month = month;
            this.day = day;
            this.hour = hour;
            this.minute = minute;
        }

        @Override
        public boolean matches(final Object argument) {
            if (argument instanceof Bwg.Uplink.Model.SpaState) {
                final Bwg.Uplink.Model.SpaState arg = (Bwg.Uplink.Model.SpaState) argument;
                return year == arg.getController().getYear() && month == arg.getController().getMonth() &&
                        day == arg.getController().getDay() && hour == arg.getController().getHour() &&
                        minute == arg.getController().getMinute();
            }

            return false;
        }
    }
}
