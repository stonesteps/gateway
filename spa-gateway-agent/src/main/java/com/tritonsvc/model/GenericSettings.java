package com.tritonsvc.model;

/**
 * Created by holow on 4/26/2016.
 */
public class GenericSettings {

    private Integer updateInterval;
    private String rs485ControllerType;

    public String getRs485ControllerType() {
        return rs485ControllerType;
    }

    public void setRs485ControllerType(String rs485ControllerType) {
        this.rs485ControllerType = rs485ControllerType;
    }

    public Integer getUpdateInterval() {
        return updateInterval;
    }

    public void setUpdateInterval(Integer updateInterval) {
        this.updateInterval = updateInterval;
    }
}
