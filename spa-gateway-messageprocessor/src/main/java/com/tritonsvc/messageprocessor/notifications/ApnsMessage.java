package com.tritonsvc.messageprocessor.notifications;

public class ApnsMessage {
    private final String device;
    private final String payload;

    public ApnsMessage(final String device, final String payload) {
        this.device = device;
        this.payload = payload;
    }

    public String getDevice() {
        return device;
    }

    public String getPayload() {
        return payload;
    }
}
