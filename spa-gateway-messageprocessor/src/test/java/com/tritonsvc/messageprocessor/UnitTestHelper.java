package com.tritonsvc.messageprocessor;

import com.bwg.iot.model.Component;
import com.bwg.iot.model.Component.ComponentType;
import com.bwg.iot.model.Spa;
import com.bwg.iot.model.SpaCommand;
import com.github.fakemongo.Fongo;
import com.mongodb.Mongo;
import com.tritonsvc.messageprocessor.mongo.repository.ComponentRepository;
import com.tritonsvc.messageprocessor.mongo.repository.SpaCommandRepository;
import com.tritonsvc.messageprocessor.mongo.repository.SpaRepository;
import io.moquette.server.Server;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.data.mongodb.config.AbstractMongoConfiguration;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.nio.file.Paths;
import java.util.HashMap;

/**
 * Helper class for unit testting.
 */
@Configuration
public class UnitTestHelper extends AbstractMongoConfiguration {

    @Autowired
    private Environment env;

    @Autowired
    private SpaRepository spaRepository;

    @Autowired
    private ComponentRepository componentRepository;

    @Autowired
    private SpaCommandRepository spaCommandRepository;

    private final Server mqttServer = new Server();

    @Override
    protected String getDatabaseName() {
        return env.getRequiredProperty("mongo.db.name");
    }

    @Override
    public Mongo mongo() throws Exception {
        return new Fongo(getDatabaseName()).getMongo();
    }

    @PostConstruct
    public void startMqttBroker() throws Exception {
        mqttServer.startServer(Paths.get(UnitTestHelper.class.getResource("/moquette.conf").toURI()).toFile());
    }

    @PreDestroy
    public void stopMqttBroker() {
        mqttServer.stopServer();
    }

    public Spa createSpa() {
        final Spa spa = new Spa();
        spaRepository.save(spa);
        return spa;
    }

    public Component createGateway(Spa spa, String serialNumber) {
        Component gateway = new Component();
        gateway.setSerialNumber(serialNumber);
        gateway.setSpaId(spa.get_id());
        gateway.setComponentType(ComponentType.GATEWAY.name());
        componentRepository.save(gateway);
        return gateway;
    }

    public SpaCommand createSpaCommand(Spa spa, int requestType, HashMap<String, String> values) {
        final SpaCommand command = new SpaCommand();
        command.setSpaId(spa.get_id());
        command.setSentTimestamp(String.valueOf(System.currentTimeMillis()));
        command.setRequestTypeId(Integer.valueOf(requestType));
        command.setValues(values);
        spaCommandRepository.save(command);
        return command;
    }
}
