package com.tritonsvc.httpd;

/**
 * Created by holow on 4/8/2016.
 */
public interface RegistrationInfoHolder {

    /**
     * retrieve the registration key for this gateway
     * @return
     */
    String getRegKey();

    /**
     * retrieve the registration user id for this gateway
     * @return
     */
    String getRegUserId();

    /**
     * retrieve the registered spa id for this gateway
     * @return
     */
    String getSpaId();

    /**
     * retrieve the serial number this gateway is using
     * @return
     */
    String getSerialNumber();
}
