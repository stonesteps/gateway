package com.tritonsvc.httpd;

import com.tritonsvc.model.NetworkSettings;

/**
 * Created by holow on 4/13/2016.
 */
public interface NetworkSettingsHolder {

    /**
     * retrieve network settings
     * @return
     */
    NetworkSettings getNetworkSettings();

    /**
     * set network settings
     * @param networkSettings
     */
    void setNetworkSettings(final NetworkSettings networkSettings) throws Exception;

}
