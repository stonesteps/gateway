package com.tritonsvc.messageprocessor.state;

import com.bwg.iot.model.Spa;
import com.bwg.iot.model.SpaCommand;
import com.bwg.iot.model.User;
import com.tritonsvc.messageprocessor.mongo.repository.SpaRepository;
import com.tritonsvc.messageprocessor.mongo.repository.UserRepository;
import com.tritonsvc.messageprocessor.notifications.PushNotificationService;
import com.tritonsvc.spa.communication.proto.Bwg;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Created by holow on 25.10.2016.
 */
@Component
public class SpaCommandExecutionWatcher {
    private static final Logger log = LoggerFactory.getLogger(SpaCommandExecutionWatcher.class);

    @Autowired
    private SpaRepository spaRepository;

    @Autowired
    private UserRepository userRepository;

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
        log.debug("checking Desired State Reached for spa: " + spaId);

        final List<ExpectedState> stateList = desiredStateMap.get(spaId);
        if (stateList != null) {
            final Iterator<ExpectedState> iter = stateList.iterator();
            while (iter.hasNext()) {
                final ExpectedState expectedState = iter.next();

                if (expectedState.desiredStateReached(state)) {
                    log.info("reached Desired State Reached for spa: " + spaId);
                    iter.remove();

                    final Spa spa = spaRepository.findOne(spaId);
                    if (spa != null && spa.getOwner() != null) {
                        User owner = userRepository.findOne(spa.getOwner().get_id());
                        if (owner == null) {
                            log.debug("aborting: can't find owner");
                            return;
                        }
                        String deviceToken = owner.getDeviceToken();
                        if (deviceToken == null) {
                            log.debug("no device token for user: {}", owner.getUsername());
                            return;
                        }
                        log.info("Sending Push Notification to owner {}", owner.getUsername());
                        expectedState.pushNotification(pushNotificationService, deviceToken);
                    } else {
                        log.debug("Not enough info to send push notification.");
                        if (spa == null) {
                            log.debug("spa is null");
                        }
                        if (spa.getOwner() == null) {
                            log.debug("no owner");
                        }
                    }
                }
            }

        } else {
            log.debug("stateList is empty");
        }



    }
}

