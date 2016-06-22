package com.tritonsvc.model;

/**
 * update interval settings and other various agent runtime settings that should be persistent
 *
 */
public class GenericSettings {

    private Integer updateInterval;
    private Integer wifiUpdateInterval;
    private Integer ambientUpdateInterval;
    private Integer pumpCurrentUpdateInterval;
    private String rs485ControllerType;
    private Integer persistedRS485Address;

    public Integer getPersistedRS485Address() {
        return persistedRS485Address;
    }

    public void setPersistedRS485Address(Integer persistedRS485Address) {
        this.persistedRS485Address = persistedRS485Address;
    }

    public Integer getAmbientUpdateInterval() {
        return ambientUpdateInterval;
    }

    public void setAmbientUpdateInterval(Integer ambientUpdateInterval) {
        this.ambientUpdateInterval = ambientUpdateInterval;
    }

    public Integer getPumpCurrentUpdateInterval() {
        return pumpCurrentUpdateInterval;
    }

    public void setPumpCurrentUpdateInterval(Integer pumpCurrentUpdateInterval) {
        this.pumpCurrentUpdateInterval = pumpCurrentUpdateInterval;
    }

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

    public Integer getWifiUpdateInterval() {
        return wifiUpdateInterval;
    }

    public void setWifiUpdateInterval(Integer wifiUpdateInterval) {
        this.wifiUpdateInterval = wifiUpdateInterval;
    }
}
