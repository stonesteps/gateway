package com.tritonsvc.gateway;

import org.mapdb.Serializer;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.io.Serializable;
import java.util.Date;

/**
 * The state of device registration attempt
 */
public class DeviceRegistration implements Serializable {
    private String hardwareId;
    private long lastTime;

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
            return reg;
        }
    }
}
