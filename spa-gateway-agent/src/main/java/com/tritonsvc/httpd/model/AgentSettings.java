package com.tritonsvc.httpd.model;

/**
 * Created by holow on 4/8/2016.
 */
public class AgentSettings {

    private NetworkSettings networkSettings;
    private GenericSettings genericSettings;

    public NetworkSettings getNetworkSettings() {
        return networkSettings;
    }

    public void setNetworkSettings(NetworkSettings networkSettings) {
        this.networkSettings = networkSettings;
    }

    public GenericSettings getGenericSettings() {
        return genericSettings;
    }

    public void setGenericSettings(GenericSettings genericSettings) {
        this.genericSettings = genericSettings;
    }
}
