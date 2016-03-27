package com.tritonsvc.gateway;

import com.tritonsvc.spa.communication.proto.Bwg;
import org.junit.Assert;
import org.junit.Test;

/**
 * Created by holow on 3/27/2016.
 */
public class SpaStateHolderTest {

    @Test
    public void setState() throws Exception {
        final SpaStateHolder stateHolder = new SpaStateHolder();
        stateHolder.updateComponentState(Bwg.Uplink.Model.Constants.ComponentType.LIGHT, 0, "HIGH");

        final Bwg.Uplink.Model.SpaState spaState = stateHolder.buildSpaState();
        Assert.assertEquals(Bwg.Uplink.Model.Components.LightComponent.State.HIGH, spaState.getComponents().getLight1().getCurrentState());
    }
}
