package com.tritonsvc.messageprocessor.notifications;

import java.io.IOException;

/**
 * Created by holow on 18.10.2016.
 */
public class NotnoopApnsSenderBuilder implements ApnsSenderBuilder {

    private String certificateResourceLocation;
    private String certificatePassword;
    private boolean useProductionApnsServer;

    @Override
    public ApnsSender build() throws IOException {
        return new NotnoopApnsSender(certificateResourceLocation, certificatePassword, useProductionApnsServer);
    }

    public NotnoopApnsSenderBuilder setCertificateResourceLocation(String certificateResourceLocation) {
        this.certificateResourceLocation = certificateResourceLocation;
        return this;
    }

    public NotnoopApnsSenderBuilder setCertificatePassword(String certificatePassword) {
        this.certificatePassword = certificatePassword;
        return this;
    }

    public NotnoopApnsSenderBuilder setUseProductionApnsServer(boolean useProductionApnsServer) {
        this.useProductionApnsServer = useProductionApnsServer;
        return this;
    }
}
