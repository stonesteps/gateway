package com.tritonsvc.httpd;

/**
 * Created by holow on 4/8/2016.
 */
public class BaseRegistrationInfoHolder implements RegistrationInfoHolder {

    private String regKey;
    private String regUserId;
    private String spaId;
    private String serialNumber;

    @Override
    public String getRegKey() {
        return regKey;
    }

    @Override
    public String getRegUserId() {
        return regUserId;
    }

    @Override
    public String getSpaId() {
        return spaId;
    }

    @Override
    public String getSerialNumber() {
        return serialNumber;
    }

    public void setRegKey(String regKey) {
        this.regKey = regKey;
    }

    public void setRegUserId(String regUserId) {
        this.regUserId = regUserId;
    }

    public void setSpaId(String spaId) {
        this.spaId = spaId;
    }

    public void setSerialNumber(String serialNumber) {
        this.serialNumber = serialNumber;
    }
}
