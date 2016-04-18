package com.tritonsvc.httpd;

import com.tritonsvc.httpd.model.NetworkSettings;

/**
 * Created by holow on 4/13/2016.
 */
public class BaseNetworkSettingsHolder implements NetworkSettingsHolder {

    private NetworkSettings networkSettings = new NetworkSettings();

    @Override
    public NetworkSettings loadAndGetNetworkSettings() {
        return networkSettings;
    }

    @Override
    public void setAndSaveNetworkSettings(NetworkSettings networkSettings) {
        this.networkSettings = networkSettings;
    }
}
