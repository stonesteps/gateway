package com.tritonsvc.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tritonsvc.gateway.wsn.WsnData;
import com.tritonsvc.gateway.wsn.WsnValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.zeromq.ZMQ;
import org.zeromq.ZMQ.PollItem;
import org.zeromq.ZMQ.Poller;
import org.zeromq.ZMQ.Socket;

import java.io.IOException;
import java.util.Map;

import static com.google.common.collect.Maps.newHashMap;
import static org.zeromq.ZMQ.poll;

/**
 * WSN data collection thread, keeps the thread running/fresh at all times unless interrupted
 */
public class WSNDataHarvester implements Runnable {
    private static Logger LOGGER = LoggerFactory.getLogger(WSNDataHarvester.class);
    private BWGProcessor processor;
    private ZMQ.Context context;
    private static final String DATA_HARVEST_SUBSCRIPTION_ADDRESS = "wsn.data.harvest.subscription.address";


    /**
     * Constructor
     *
     * @param processor
     */
    public WSNDataHarvester (BWGProcessor processor) {
        this.processor = processor;
    }

    @Override
    public void run() {
        Socket subscriber = null;
        context = ZMQ.context(1);
        while(processor.stillRunning()) {
            try {
                if (subscriber == null) {
                    subscriber = createWSNSubscriberSocket();
                }

                while(true) {
                    String data = waitForWSNData(subscriber, 10000);
                    if (data != null) {
                        sendWSNDataToCloud(data);
                        continue;
                    }
                    break;
                }
            }
            catch (Throwable ex) {
                LOGGER.info("harvest data listener got exception " + ex.getMessage());
                if (subscriber != null) {
                    subscriber.close();
                    subscriber = null;
                }
                try {Thread.sleep(10000);} catch (InterruptedException ex2){}
            }
        }
        if (subscriber != null) {
            subscriber.close();
        }
        if (context != null) {
            context.close();
        }
    }

    private Socket createWSNSubscriberSocket() {
        Socket subscriber = context.socket(ZMQ.SUB);
        subscriber.setRcvHWM(0);
        subscriber.connect("tcp://" + processor.getConfigProps().getProperty(DATA_HARVEST_SUBSCRIPTION_ADDRESS));
        subscriber.subscribe("".getBytes());
        return subscriber;
    }

    private void sendWSNDataToCloud(String json) throws IOException {
        DeviceRegistration registeredSpa = processor.obtainSpaRegistration();
        if (registeredSpa .getHardwareId() == null) {
            LOGGER.info("skipping data harvest, spa gateway has not been registered");
            return;
        }

        ObjectMapper mapper = new ObjectMapper();
        WsnData wsnData = mapper.readValue(json, WsnData.class);

        DeviceRegistration registeredMote = processor.obtainMoteRegistration(registeredSpa.getHardwareId(), wsnData.getMac());
        if (registeredMote.getHardwareId() == null) {
            LOGGER.info("skipping wsn data harvest, mote {} has not been registered", wsnData.getMac());
            return;
        }

        // TODO send a 'pump registered' data in here once the NFC tag for the pump appears

        Map<String, String> meta = newHashMap();
        meta.put("receivedDate", Long.toString(wsnData.getReceivedUnixTimestamp() * 1000));
        if (wsnData.getRssi() != null) {
            meta.put("rssi_quality", Double.toString(wsnData.getRssi().getQuality()));
            meta.put("rssi_ul", Double.toString(wsnData.getRssi().getUplink()));
            meta.put("rssi_dl", Double.toString(wsnData.getRssi().getDownlink()));
        }

        String safeMacKey = wsnData.getMac().replaceAll(":","").toLowerCase();
        for (WsnValue wsnValue : wsnData.getValues()) {
            String oid = processor.getConfigProps().getProperty(BWGProcessor.DYNAMIC_DEVICE_OID_PROPERTY
                    .replaceAll("MAC",safeMacKey)
                    .replaceAll("DEVICE_NAME", wsnValue.getDeviceName()));

            if (oid == null) {
                LOGGER.info("Unable to send sensor data to cloud, no oid and specId properties for it's mac address " +
                        safeMacKey + " and device name " + wsnValue.getDeviceName() + " were found in config.properties");
                continue;
            }

            Map<String, Double> measurement = newHashMap();
            measurement.put(oid, wsnValue.getValue());
            long timestamp = wsnData.getRecordedUnixTimestamp() != null ? wsnData.getRecordedUnixTimestamp() * 1000 : wsnData.getReceivedUnixTimestamp() * 1000;
            processor.sendMeasurements(registeredMote.getHardwareId(), null, measurement, timestamp, meta);
            LOGGER.info(" sent measurement for mote {}, registered id {} {} {}", wsnValue.getDeviceName(), registeredMote.getHardwareId(), oid, Double.toString(wsnValue.getValue()));
        }
    }

    private String waitForWSNData(Socket client, int timeout) {
        PollItem items[] = {new PollItem(client, Poller.POLLIN)};
        int rc = poll(items, timeout);
        if (rc == -1) {
            return null; //  Interrupted
        }

        if (items[0].isReadable()) {
            //  We got a msg from the ZeroMQ socket, it's a new WSN data message in json string format
            return client.recvStr();
        } else {
            LOGGER.info("timed out waiting for wsn data");
            return null;
        }
    }
}
