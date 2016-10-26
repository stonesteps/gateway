package com.tritonsvc.messageprocessor.state;

import com.bwg.iot.model.Spa;
import com.bwg.iot.model.SpaCommand;
import com.tritonsvc.messageprocessor.mongo.repository.SpaRepository;
import com.tritonsvc.messageprocessor.notifications.PushNotificationService;
import com.tritonsvc.spa.communication.proto.Bwg;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Created by holow on 25.10.2016.
 */
@Component
public class SpaCommandExecutionWatcher {

    @Autowired
    private SpaRepository spaRepository;

    @Autowired
    private PushNotificationService pushNotificationService;

    private final Map<String, List<ExpectedState>> desiredStateMap = new HashMap<>();

    public void watchCommand(final SpaCommand spaCommand) {
        final ExpectedState expectedState = ExpectedStateBuilder.buildExpectedStateFromCommand(spaCommand);
        if (expectedState != null) {
            List stateList = desiredStateMap.get(spaCommand.getSpaId());
            if (stateList == null) {
                stateList = new ArrayList<>();
                desiredStateMap.put(spaCommand.getSpaId(), stateList);
            }
            stateList.add(expectedState);
        }
    }

    public void checkDesiredStateReached(final String spaId, final Bwg.Uplink.Model.SpaState state) {
        final List<ExpectedState> stateList = desiredStateMap.get(spaId);
        if (stateList != null) {
            final Iterator<ExpectedState> iter = stateList.iterator();
            while (iter.hasNext()) {
                final ExpectedState expectedState = iter.next();

                if (expectedState.desiredStateReached(state)) {
                    iter.remove();

                    final Spa spa = spaRepository.findOne(spaId);
                    if (spa != null && spa.getOwner() != null && spa.getOwner().getDeviceToken() != null) {
                        final String deviceToken = spa.getOwner().getDeviceToken();
                        expectedState.pushNotification(pushNotificationService, deviceToken);
                    }
                }
            }

        }



    }
}

