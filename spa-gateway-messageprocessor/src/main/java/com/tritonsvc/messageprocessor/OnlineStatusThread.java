package com.tritonsvc.messageprocessor;

import com.bwg.iot.model.Spa;
import com.mongodb.WriteResult;
import com.tritonsvc.messageprocessor.mongo.repository.SpaRepository;
import org.springframework.context.annotation.Scope;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Calendar;
import java.util.Date;

import static org.springframework.data.mongodb.core.query.Criteria.where;
import static org.springframework.data.mongodb.core.query.Query.query;

@Component
@Scope("prototype")
public class OnlineStatusThread extends Thread {
    private static final Logger log = LoggerFactory.getLogger(UplinkProcessor.class);

    private static long sleepInterval = 60000;  // once per minute

    @Autowired
    private SpaRepository spaRepository;

    @Autowired
    MongoOperations mongoOps;

    @Override
    public void run() {
        setName("OnlineStatusThread");
        while (true) {
            try {
                log.debug(getName() + " is running online status check");
                Date now = new Date();

                WriteResult wr = mongoOps.updateMulti(
                        query(where("currentState.staleTimestamp").lt(now).andOperator(Criteria.where("currentState.online").is(Boolean.TRUE))),
                        new Update().set("currentState.online", Boolean.FALSE),
                        Spa.class);
                if (wr.getN() > 0) {
                    log.info(wr.getN() + " spas set offline");
                }

                log.debug(getName() + " has completed online status check");
                Thread.sleep(sleepInterval);
            } catch (InterruptedException e) {
                e.printStackTrace();
                break;
            }
        }
        log.warn(getName() + " is exiting.");
    }
}
