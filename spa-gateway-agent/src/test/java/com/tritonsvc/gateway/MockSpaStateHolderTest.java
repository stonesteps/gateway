package com.tritonsvc.gateway;

import com.tritonsvc.spa.communication.proto.Bwg;
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
    public void nonCompleteSpa() throws Exception {
        final MockSpaStateHolder stateHolder = new MockSpaStateHolder(1, 1, 1, 1, 1, 1, 1, 1);

        final Bwg.Uplink.Model.SpaState spaState = stateHolder.buildSpaState();
        Assert.assertEquals(true, spaState.getComponents().hasLight1());
        Assert.assertEquals(false, spaState.getComponents().hasLight2());
    }
}
