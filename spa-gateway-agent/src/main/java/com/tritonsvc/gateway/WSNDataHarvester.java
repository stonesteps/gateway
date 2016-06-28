package com.tritonsvc.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ArrayListMultimap;
import com.tritonsvc.gateway.wsn.WsnData;
import com.tritonsvc.spa.communication.proto.Bwg.Metadata;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.Model.Measurement;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.Model.Measurement.DataType;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.Model.Measurement.QualityType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.zeromq.ZMQ;
import org.zeromq.ZMQ.PollItem;
import org.zeromq.ZMQ.Poller;
import org.zeromq.ZMQ.Socket;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import static com.google.common.collect.Lists.newArrayList;
import static java.util.stream.Collectors.toList;
import static org.zeromq.ZMQ.poll;

/**
 * WSN data collection thread, keeps the thread running/fresh at all times unless interrupted
 */
public class WSNDataHarvester implements Runnable {
    private static Logger LOGGER = LoggerFactory.getLogger(WSNDataHarvester.class);
    private BWGProcessor processor;
    private ZMQ.Context context;
    private static final String DATA_HARVEST_SUBSCRIPTION_ADDRESS = "wsn.data.harvest.subscription.address";
    private Map<String, WsnData> measurements = new ConcurrentHashMap<>();
    private TS7970WiredCurrentSensor wiredCurrentSensor;
    private static long WSN_POLL_TIME = 30000;

    /**
     * Constructor
     *
     * @param processor
     */
    public WSNDataHarvester (BWGProcessor processor) {
        this.processor = processor;
        wiredCurrentSensor = new TS7970WiredCurrentSensor(processor.getConfigProps());
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

                long startTime = System.currentTimeMillis();
                long timeLeft = WSN_POLL_TIME;
                while(timeLeft > 0) {
                    String data = waitForWSNData(subscriber, timeLeft);
                    if (data != null) {
                        updateMeasurements(data);
                    }
                    timeLeft = WSN_POLL_TIME - (System.currentTimeMillis() - startTime);
                }

                // temprorary hard wired sensors
                List<WsnData> wsnDatas = wiredCurrentSensor.processWiredSensors();
                for (WsnData wsnData : wsnDatas) {
                    measurements.put(wsnData.getSensorMac(), wsnData);
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
        wiredCurrentSensor.shutdown();
    }

    /**
     * see if any new sensor data in memory and send to cloud if so
     *
     * @param dataTypes
     * @param spaHardwareId
     * @throws IOException
     */
    public void sendLatestWSNDataToCloud(List<DataType> dataTypes, String spaHardwareId) throws IOException {
        ArrayListMultimap<String, WsnData> moteMac2Data = ArrayListMultimap.create();
        for (DataType type : dataTypes) {
            List<WsnData> wsnDatas = measurements.values()
                    .stream()
                    .filter(wsnData -> Objects.equals(wsnData.getDataType(), type))
                    .collect(toList());

            for (WsnData wsnData : wsnDatas) {
                moteMac2Data.put(wsnData.getMoteMac(), wsnData);
            }
        }
        sendLatestWSNDataToCloud(moteMac2Data, spaHardwareId);
    }

    private void updateMeasurements(String json) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        WsnData wsnData = mapper.readValue(json, WsnData.class);
        measurements.put(wsnData.getSensorMac(), wsnData);
    }

    private Socket createWSNSubscriberSocket() {
        Socket subscriber = context.socket(ZMQ.SUB);
        subscriber.setRcvHWM(0);
        subscriber.connect("tcp://" + processor.getConfigProps().getProperty(DATA_HARVEST_SUBSCRIPTION_ADDRESS));
        subscriber.subscribe("".getBytes());
        return subscriber;
    }

    private void sendLatestWSNDataToCloud(ArrayListMultimap<String, WsnData> moteMac2Data, String spaHardwareId) {
        for (String mac : moteMac2Data.keySet()) {
            List<WsnData> entries = moteMac2Data.get(mac);
            if (entries == null || entries.isEmpty()) {
                continue;
            }
            DeviceRegistration registeredMote = processor.obtainMoteRegistration(spaHardwareId, entries.get(0).getMoteMac(), entries.get(0).getDeviceName());
            if (registeredMote.getHardwareId() == null) {
                LOGGER.info("skipping wsn data harvest for mote mac {}, has not been registered yet with cloud", entries.get(0).getMoteMac());
                continue;
            }
            List<Measurement> measurements = newArrayList();
            for (WsnData wsnData : entries) {
                // TODO send a 'pump registered' data in here once the NFC tag for the pump appears
                if (wsnData.getValue() != null) {
                    /*
                    //
                    // If ever going to OID's ...
                    //
                    String safeMacKey = wsnData.getMoteMac().replaceAll(":", "").toLowerCase();
                    String oid = processor.getConfigProps().getProperty(BWGProcessor.DYNAMIC_DEVICE_OID_PROPERTY
                            .replaceAll("MAC", safeMacKey)
                            .replaceAll("DEVICE_NAME", wsnData.getDeviceName()));

                    if (oid == null) {
                        LOGGER.info("Unable to send sensor data to cloud, no oid and specId properties for it's mac address " +
                                safeMacKey + " and device name " + wsnData.getDeviceName() + " were found in config.properties");
                        return;
                    }

                    eb.addOidData(Metadata.newBuilder().setName(oid).setValue(wsnData.getValue().toString()).build());
                    */
                    Measurement.Builder eb = Measurement.newBuilder();
                    if (wsnData.getRssi() != null) {
                        eb.addMetadata(Metadata.newBuilder().setName("rssi_quality").setValue(Double.toString(wsnData.getRssi().getQuality())).build());
                        eb.addMetadata(Metadata.newBuilder().setName("rssi_ul").setValue(Double.toString(wsnData.getRssi().getUplink())).build());
                        eb.addMetadata(Metadata.newBuilder().setName("rssi_dl").setValue(Double.toString(wsnData.getRssi().getDownlink())).build());
                    }
                    long timestamp = wsnData.getRecordedUnixTimestamp() != null ? wsnData.getRecordedUnixTimestamp() * 1000 : wsnData.getReceivedUnixTimestamp() * 1000;

                    eb.setTimestamp(timestamp);
                    eb.setType(wsnData.getDataType());
                    eb.setValue(wsnData.getValue());
                    eb.setUom(wsnData.getUom());
                    eb.setQuality(QualityType.VALID);
                    if (wsnData.getSensorIdentifier() != null) {
                        eb.setSensorIdentifier(wsnData.getSensorIdentifier());
                    }
                    measurements.add(eb.build());
                    LOGGER.info(" sent {} measurement for mote {}, registered id {} {}", wsnData.getDataType().name(), wsnData.getDeviceName(), registeredMote.getHardwareId(), Double.toString(wsnData.getValue()));
                }
            }
            if (!measurements.isEmpty()) {
                processor.sendMeasurements(registeredMote.getHardwareId(), measurements);
            }
        }
    }

    private String waitForWSNData(Socket client, long timeout) {
        PollItem items[] = {new PollItem(client, Poller.POLLIN)};
        int rc = poll(items, timeout);
        if (rc == -1) {
            return null; //  Interrupted
        }

        if (items[0].isReadable()) {
            //  We got a msg from the ZeroMQ socket, it's a new WSN data message in json string format
            return client.recvStr();
        } else {
            return null;
        }
    }
}
