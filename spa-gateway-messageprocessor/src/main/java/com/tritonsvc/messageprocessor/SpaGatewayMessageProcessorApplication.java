package com.tritonsvc.messageprocessor;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.web.EmbeddedServletContainerAutoConfiguration;
import org.springframework.boot.autoconfigure.web.WebMvcAutoConfiguration;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(exclude = {EmbeddedServletContainerAutoConfiguration.class,
        WebMvcAutoConfiguration.class})
@EnableScheduling
public class SpaGatewayMessageProcessorApplication {

    public static void main(String[] args) {
        SpringApplication.run(SpaGatewayMessageProcessorApplication.class, args);
    }

}
