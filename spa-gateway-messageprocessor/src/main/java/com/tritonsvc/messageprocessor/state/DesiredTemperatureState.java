package com.tritonsvc.messageprocessor.state;

import com.bwg.iot.model.SpaCommand;
import com.tritonsvc.messageprocessor.notifications.PushNotificationService;
import com.tritonsvc.spa.communication.proto.Bwg;

/**
 * Created by holow on 25.10.2016.
 */
public final class DesiredTemperatureState extends ExpectedState {

    private final int desiredTemperature;

    public DesiredTemperatureState(final int desiredTemperature) {
        super(SpaCommand.RequestType.HEATER.getCode());
        this.desiredTemperature = desiredTemperature;
    }

    @Override
    public boolean desiredStateReached(final Bwg.Uplink.Model.SpaState spaState) {
        final int currentTemp = spaState != null && spaState.getComponents() != null ? spaState.getController().getCurrentWaterTemp() : 0;
        return currentTemp >= desiredTemperature;
    }

    @Override
    public void pushNotification(final PushNotificationService pushNotificationService, final String deviceToken) {
        pushNotificationService.pushApnsTemperatureReachedNotification(deviceToken, desiredTemperature);
    }
}
