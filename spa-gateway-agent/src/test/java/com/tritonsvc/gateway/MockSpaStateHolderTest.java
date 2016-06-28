package com.tritonsvc.gateway;

import com.tritonsvc.spa.communication.proto.Bwg;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.Model.Components.ToggleComponent;
import org.junit.Assert;
import org.junit.Test;

/**
 * Created by holow on 3/27/2016.
 */
public class MockSpaStateHolderTest {

    @Test
    public void setState() throws Exception {
        final MockSpaStateHolder stateHolder = new MockSpaStateHolder();
        stateHolder.updateComponentState(Bwg.Uplink.Model.Constants.ComponentType.LIGHT, 0, "HIGH");

        final Bwg.Uplink.Model.SpaState spaState = stateHolder.buildSpaState();
        Assert.assertEquals(Bwg.Uplink.Model.Components.LightComponent.State.HIGH, spaState.getComponents().getLight1().getCurrentState());
    }

    @Test
    public void setTemperature() throws Exception {
        final MockSpaStateHolder stateHolder = new MockSpaStateHolder();
        stateHolder.updateHeater(78);

        final Bwg.Uplink.Model.SpaState spaState = stateHolder.buildSpaState();
        Assert.assertEquals(78, spaState.getController().getCurrentWaterTemp());
    }

    @Test
    public void setFilterCycle() throws Exception {
        final MockSpaStateHolder stateHolder = new MockSpaStateHolder();
        stateHolder.updateFilterCycle(0, 1);

        final Bwg.Uplink.Model.SpaState spaState = stateHolder.buildSpaState();

        Assert.assertEquals(true, spaState.getComponents().getFilterCycle1().getCurrentState().equals(ToggleComponent.State.ON));
        Assert.assertEquals(false, spaState.getComponents().hasFilterCycle2());
    }

    @Test
    public void nonCompleteSpa() throws Exception {
        final MockSpaStateHolder stateHolder = new MockSpaStateHolder(0, 1, 1, 1, 1, 1, 1, 1, 1);

        final Bwg.Uplink.Model.SpaState spaState = stateHolder.buildSpaState();
        Assert.assertEquals(true, spaState.getComponents().hasLight1());
        Assert.assertEquals(false, spaState.getComponents().hasLight2());
        Assert.assertEquals(false, spaState.getComponents().hasOzone());
        Assert.assertEquals(true, spaState.getComponents().hasFilterCycle1());
        Assert.assertEquals(false, spaState.getComponents().hasFilterCycle2());
    }

    @Test
    public void twoFilterCycles() throws Exception {
        final MockSpaStateHolder stateHolder = new MockSpaStateHolder(0, 1, 1, 1, 1, 1, 1, 1, 2);

        final Bwg.Uplink.Model.SpaState spaState = stateHolder.buildSpaState();
        Assert.assertEquals(true, spaState.getComponents().hasFilterCycle1());
        Assert.assertEquals(true, spaState.getComponents().hasFilterCycle2());
    }
}
