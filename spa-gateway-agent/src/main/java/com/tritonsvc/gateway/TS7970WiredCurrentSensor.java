package com.tritonsvc.gateway;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.primitives.Doubles;
import com.tritonsvc.gateway.wsn.WsnData;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.Model.Measurement.DataType;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.Model.Measurement.QualityType;
import jdk.dio.DeviceManager;
import jdk.dio.i2cbus.I2CDevice;
import jdk.dio.i2cbus.I2CDeviceConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import static com.google.common.collect.Lists.newArrayList;

/**
 * Temporary usage of getting current sensor from TS7970 beta board over a wire with the
 * Senva C-2345 sensor
 *
 */
public class TS7970WiredCurrentSensor {
    private static Logger LOGGER = LoggerFactory.getLogger(WSNDataHarvester.class);
    private final int READ_ADC_REGISTER_BYTES = 2;
    private I2CDeviceConfig config;
    private I2CDevice device = null;
    private boolean printedError;
    private double ampsMeasuredScale = 30.0;

    /**
     * Constructor
     *
     * @param props
     */
    public TS7970WiredCurrentSensor(Properties props) {
        Double scaledAmps = Doubles.tryParse(props.getProperty("sensor.accurrent.amps.range","30"));
        if (scaledAmps != null) {
            ampsMeasuredScale = scaledAmps;
        }

        config = new I2CDeviceConfig.Builder()
                .setControllerNumber(0)
                .setAddress(0x10,I2CDeviceConfig.ADDR_SIZE_7)
                .setClockFrequency(I2CDeviceConfig.UNASSIGNED)
                .build();

        // enable the i2c linux f/s to allow the bwg user to read/write
        try {
            executeUnixCommand("sudo chmod 666 /dev/i2c-0").waitFor(5, TimeUnit.SECONDS);
        } catch (Exception ex) {
            LOGGER.error("unable to set file permissions in /dev/i2c-*, may be an issue or not.");
        }
    }

    /**
     * stop the i2c connection
     *
     */
    public void shutdown() {
        if (device != null) {
            try {
                device.close();
            } catch (IOException ex) {
                LOGGER.info("had some chatter when closing the i2c connection", ex);
            }
        }
    }

    /**
     * retrieve data from i2c
     *
     * @return
     */
    public List<WsnData> processWiredCurrentSensor() {
        acquireBusDevice();
        List<WsnData> datas = newArrayList();
        if (device == null) {
            LOGGER.info("skipping wired current sensor check, no i2c bus available");
            return datas;
        }
        try  {
            WsnData data = getSensorValueForAdc(0);
            if (data != null) {
                datas.add(data);
            }
            data = getSensorValueForAdc(1);
            if (data != null) {
                datas.add(data);
            }
            data = getSensorValueForAdc(2);
            if (data != null) {
                datas.add(data);
            }
        } catch (Exception ex) {
            LOGGER.info("error when attempting to process ac current reading from i2c bus", ex);
        }
        return datas;
    }

    private WsnData getSensorValueForAdc(int adcNum) throws Exception {
        long timestamp = System.currentTimeMillis() / 1000;
        ByteBuffer tempBuf = ByteBuffer.allocateDirect(READ_ADC_REGISTER_BYTES);
        device.read(8 + (adcNum * READ_ADC_REGISTER_BYTES), tempBuf); // 8 is offset - http://wiki.embeddedarm.com/wiki/TS-7970#Silabs_Microcontroller
        tempBuf.flip();
        int adcValue = tempBuf.getShort();
        if (adcValue == 0) {
            return null;
        }
        Double ampsValue = calculateAmpsFrom420LoopADCValue(adcValue);
        WsnData wsnData = new WsnData();
        wsnData.setValue(ampsValue);
        wsnData.setQuality(QualityType.VALID);
        wsnData.setDataType(DataType.PUMP_AC_CURRENT);
        wsnData.setReceivedUnixTimestamp(timestamp);
        wsnData.setRecordedUnixTimestamp(timestamp);
        wsnData.setUom("amps");
        wsnData.setMac("wired_ac_current_adc_" + adcNum);
        wsnData.setDeviceName("ac current sensor " + adcNum);
        LOGGER.info("Retrieved pump current sensor adc {} raw value is {} and amps value is {}", adcNum, adcValue, ampsValue );
        return wsnData;
    }

    private Double calculateAmpsFrom420LoopADCValue(int adcValue) {
        // zero amps readings come in at less than 4mA from the Senva C-2345 with 30Amp range
        // it comes in around 175, whereas 204 should be the value at 4mA,
        // this is a quick calibration hack to avoid non-sensical values, this is for demo purposes only
        // real current sensor solution is TBD
        if (adcValue < 176) {
            return 0d;
        } else {
            adcValue = adcValue - 176;
        }

        double amps = adcValue / (1024.0 * .8); // adc measures 0-20 mA, expresses as 0-1024 value, ignore the first 4 mA becuase of 4-20mA output
        return amps * ampsMeasuredScale; // convert the adc value into amps measured based on amps range
    }

    private void acquireBusDevice() {
        if (device == null) {
            try {
                device = DeviceManager.open(config);
            } catch (Exception ex) {
                if (!printedError) {
                    LOGGER.error("unable to open i2c bus device ", ex);
                    printedError = true;
                }
            }
        }
    }

    @VisibleForTesting
    Process executeUnixCommand(String command) throws IOException {
        return Runtime.getRuntime().exec(command);
    }
}
