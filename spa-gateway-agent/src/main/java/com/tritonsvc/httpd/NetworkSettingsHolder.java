package com.tritonsvc.httpd;

import com.tritonsvc.httpd.model.NetworkSettings;

/**
 * Created by holow on 4/13/2016.
 */
public interface NetworkSettingsHolder {

    NetworkSettings getNetworkSettings();
    void setNetworkSettings(final NetworkSettings networkSettings);

}
