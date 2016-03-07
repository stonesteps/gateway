package com.tritonsvc.gateway;

/**
 * state of individual component that is attached to spa system
 */
public class ComponentStateInfo {

    private String componentType;
    private int port;
    private String value;

    public ComponentStateInfo(String componentType, int port, String value) {
        this.componentType = componentType;
        this.port = port;
        this.value = value;
    }

    public String getComponentType() {
        return componentType;
    }

    public void setComponentType(String componentType) {
        this.componentType = componentType;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }
}
