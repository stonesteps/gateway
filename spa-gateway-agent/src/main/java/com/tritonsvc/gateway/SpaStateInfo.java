package com.tritonsvc.gateway;

import java.util.Date;
import java.util.List;

import static com.google.common.collect.Lists.newArrayList;

/**
 * state cache for the spa
 */
public class SpaStateInfo {
    private List<ComponentStateInfo> componentStates = newArrayList();
    private int desiredTemperature;
    private int runMode;
    private int errorCode;
    private int hour;
    private int minute;
    private int panelDisplayCode;
    private int panelDisplaySubCode;
    private Date lastUpdated;

    public List<ComponentStateInfo> getComponentStates() {
        return componentStates;
    }

    public void setComponentStates(List<ComponentStateInfo> componentStates) {
        this.componentStates.clear();
        this.componentStates.addAll(componentStates);
    }

    public int getDesiredTemperature() {
        return desiredTemperature;
    }

    public void setDesiredTemperature(int temp) {
        this.desiredTemperature = temp;
    }

    public int getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(int errorCode) {
        this.errorCode = errorCode;
    }

    public int getRunMode() {
        return runMode;
    }

    public void setRunMode(int runMode) {
        this.runMode = runMode;
    }

    public int getHour() {
        return hour;
    }

    public void setHour(int hour) {
        this.hour = hour;
    }

    public int getMinute() {
        return minute;
    }

    public void setMinute(int minute) {
        this.minute = minute;
    }

    public int getPanelDisplayCode() {
        return panelDisplayCode;
    }

    public void setPanelDisplayCode(int panelDisplayCode) {
        this.panelDisplayCode = panelDisplayCode;
    }

    public int getPanelDisplaySubCode() {
        return panelDisplaySubCode;
    }

    public void setPanelDisplaySubCode(int panelDisplaySubCode) {
        this.panelDisplaySubCode = panelDisplaySubCode;
    }

    public Date getLastUpdated() {
        return lastUpdated;
    }

    public void setLastUpdated(Date updated) {
        this.lastUpdated = updated;
    }
}
