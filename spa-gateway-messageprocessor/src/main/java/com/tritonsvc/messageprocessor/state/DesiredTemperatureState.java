package com.tritonsvc.messageprocessor.state;

import com.bwg.iot.model.SpaCommand;
import com.tritonsvc.messageprocessor.notifications.PushNotificationService;
import com.tritonsvc.spa.communication.proto.Bwg;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Created by holow on 25.10.2016.
 */
public final class DesiredTemperatureState extends ExpectedState {
    private static final Logger log = LoggerFactory.getLogger(DesiredTemperatureState.class);

    private final int desiredTemperature;

    public DesiredTemperatureState(final int desiredTemperature) {
        super(SpaCommand.RequestType.HEATER.getCode());
        this.desiredTemperature = desiredTemperature;
    }

    @Override
    public boolean desiredStateReached(final Bwg.Uplink.Model.SpaState spaState) {
        final int currentTemp = spaState != null && spaState.getComponents() != null ? spaState.getController().getCurrentWaterTemp() : 0;
        log.debug("comparing {} to {}", Integer.valueOf(currentTemp), Integer.valueOf(desiredTemperature));
        return currentTemp >= desiredTemperature;
    }

    @Override
    public void pushNotification(final PushNotificationService pushNotificationService, final String deviceToken) {
        log.info("Sending temp achieved push notification to device: "+ deviceToken);
        pushNotificationService.pushApnsTemperatureReachedNotification(deviceToken, desiredTemperature);
    }
}
