package com.tritonsvc.messageprocessor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
public class MessageProcessorConfiguration {

    @Value("${downlinkTopicName:BWG/spa/downlink}")
    private String downlinkTopicName;

    @Value("${uplinkTopicName:BWG/spa/uplink}")
    private String uplinkTopicName;

    public String getDownlinkTopicName() {
        return downlinkTopicName;
    }

    public String getUplinkTopicName() {
        return uplinkTopicName;
    }

    public String getDownlinkTopicName(final String serialNumber) {
        if (serialNumber == null) {
            return downlinkTopicName;
        } else {
            final StringBuilder sb = new StringBuilder(downlinkTopicName);
            sb.append("/").append(serialNumber);
            return sb.toString();
        }
    }
}
