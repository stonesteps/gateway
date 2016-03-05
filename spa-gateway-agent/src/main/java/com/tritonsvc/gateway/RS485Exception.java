package com.tritonsvc.gateway;

/**
 * General Exception for RS485
 */
public class RS485Exception extends Exception {
    public RS485Exception(Exception ex) {
        super(ex);
    }
    public RS485Exception(String msg) {
        super(msg);
    }
}
