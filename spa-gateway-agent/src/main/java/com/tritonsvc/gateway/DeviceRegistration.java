package com.tritonsvc.gateway;

import org.mapdb.Serializer;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.io.Serializable;
import java.util.Date;
import java.util.Map;

import static com.google.common.collect.Maps.newHashMap;

/**
 * The state of device registration attempt
 */
public class DeviceRegistration implements Serializable {
    private String hardwareId;
    private long lastTime;
    private Map<String, String> meta = newHashMap();

    /**
     * default constructor
     */
    public DeviceRegistration() {
        lastTime = new Date().getTime();
    }

    public long getLastTime() {
        return lastTime;
    }
    public void setLastTime(long lastTime) {
        this.lastTime = lastTime;
    }
    public String getHardwareId() {
        return hardwareId;
    }
    public void setHardwareId(String hardwareId) {
        this.hardwareId = hardwareId;
    }
    public Map<String, String> getMeta() {
        return meta;
    }

    public static class DeviceRegistrationSerializer extends Serializer<DeviceRegistration> implements Serializable {
        @Override
        public void serialize(DataOutput out, DeviceRegistration value) throws IOException {
            if (value == null) {
                out.writeUTF("null");
                return;
            }
            out.writeUTF("present");

            if (value.getHardwareId() != null) {
                out.writeUTF("present");
                out.writeUTF(value.getHardwareId());
            } else {
                out.writeUTF("null");
            }
            out.writeLong(value.getLastTime());
            out.writeInt(value.getMeta().size());
            for (Map.Entry<String, String> entry : value.getMeta().entrySet()) {
                out.writeUTF(entry.getKey());
                out.writeUTF(entry.getValue());
            }
        }

        @Override
        public DeviceRegistration deserialize(DataInput in, int available) throws IOException {
            String state = in.readUTF();
            if (state.equals("null")) {
                return null;
            }
            DeviceRegistration reg = new DeviceRegistration();

            state = in.readUTF();
            if (state.equals("present")) {
                reg.setHardwareId(in.readUTF());
            }
            reg.setLastTime(in.readLong());

            int metaCount = in.readInt();
            for (int x = 0; x < metaCount; x++) {
                String key = in.readUTF();
                String value = in.readUTF();
                reg.getMeta().put(key, value);
            }
            return reg;
        }
    }
}
