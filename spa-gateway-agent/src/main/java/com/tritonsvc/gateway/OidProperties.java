package com.tritonsvc.gateway;

/**
 * a collector of all oid key values
 */
public class OidProperties {
    private String preHeaterTemp;
    private String postHeaterTemp;

    public String getPostHeaterTemp() {
        return postHeaterTemp;
    }

    public void setPostHeaterTemp(String postHeaterTemp) {
        this.postHeaterTemp = postHeaterTemp;
    }

    public String getPreHeaterTemp() {
        return preHeaterTemp;
    }

    public void setPreHeaterTemp(String preHeaterTemp) {
        this.preHeaterTemp = preHeaterTemp;
    }
}
