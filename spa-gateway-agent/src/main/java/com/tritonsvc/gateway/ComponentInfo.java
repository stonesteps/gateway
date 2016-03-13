package com.tritonsvc.gateway;

import java.util.List;

/**
 * pojo to carry component meta info
 */
public class ComponentInfo {
    String currentState;
    List<?> supportedStates;

    /**
     * constructor
     * @param currentState
     * @param supportedStates
     */
    public ComponentInfo(String currentState, List<?> supportedStates) {
        this.currentState = currentState;
        this.supportedStates = supportedStates;
    }

    public String getCurrentState() {
        return currentState;
    }

    public List<?> getNumberOfSupportedStates() {
        return supportedStates;
    }
}
