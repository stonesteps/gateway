package com.tritonsvc.messageprocessor.state;

import com.tritonsvc.messageprocessor.notifications.PushNotificationService;
import com.tritonsvc.spa.communication.proto.Bwg;

import java.util.Objects;

/**
 * Created by holow on 25.10.2016.
 */
public abstract class ExpectedState {
    private final int requestType;

    public ExpectedState(final int requestType) {
        this.requestType = requestType;
    }

    public int getRequestType() {
        return requestType;
    }

    public abstract boolean desiredStateReached(final Bwg.Uplink.Model.SpaState spaState);

    public abstract void pushNotification(final PushNotificationService pushNotificationService, final String deviceToken);
}
