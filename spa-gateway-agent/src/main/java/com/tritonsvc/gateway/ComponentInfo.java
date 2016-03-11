package com.tritonsvc.gateway;

import com.tritonsvc.spa.communication.proto.Bwg.Uplink.Model.Constants.AvailableStates;

/**
 * pojo to carry component meta info
 */
public class ComponentInfo {
    String currentState;
    AvailableStates numberOfSupportedStates;

    /**
     * constructor
     * @param currentState
     * @param numberOfSupportedStates
     */
    public ComponentInfo(String currentState, AvailableStates numberOfSupportedStates) {
        this.currentState = currentState;
        this.numberOfSupportedStates = numberOfSupportedStates;
    }

    public String getCurrentState() {
        return currentState;
    }

    public AvailableStates getNumberOfSupportedStates() {
        return numberOfSupportedStates;
    }
}
