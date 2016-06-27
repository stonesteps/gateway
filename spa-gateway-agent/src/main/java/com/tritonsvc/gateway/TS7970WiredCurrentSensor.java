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
 * Senva C-2345 sensor for AC Current and the Adafruit HTU21DF sensor for Temperature and Humidity
 *
 */
public class TS7970WiredCurrentSensor {
    private static Logger LOGGER = LoggerFactory.getLogger(WSNDataHarvester.class);
    private final int READ_ADC_REGISTER_BYTES = 2;
    private final int READ_TEMP_HUMIDITY_REGISTER_BYTES = 3;
    private I2CDeviceConfig acCurrentConfig;
    private I2CDevice acCurrentDevice = null;
    private I2CDeviceConfig tempHumidityConfig;
    private I2CDevice tempHumidityDevice = null;
    private boolean printedError;
    private boolean printedErrorTempHumidity;
    private double ampsMeasuredScale = 30.0;

    private int HTU21DF_I2CADDR = 0x40;
    private int HTU21DF_READTEMP = 0xE3;
    private int HTU21DF_READHUM = 0xE5;
    private int HTU21DF_READREG = 0xE7;
    private int HTU21DF_RESET = 0xFE;
    private int HTU21DF_RESET_STATE = 0x2;

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

        acCurrentConfig = new I2CDeviceConfig.Builder()
                .setControllerNumber(0)
                .setAddress(0x10,I2CDeviceConfig.ADDR_SIZE_7)
                .setClockFrequency(I2CDeviceConfig.UNASSIGNED)
                .build();

        tempHumidityConfig = new I2CDeviceConfig.Builder()
                .setControllerNumber(1)
                .setAddress(HTU21DF_I2CADDR,I2CDeviceConfig.ADDR_SIZE_7)
                .setClockFrequency(I2CDeviceConfig.UNASSIGNED)
                .build();

        // enable the i2c linux f/s to allow the bwg user to read/write
        try {
            executeUnixCommand("sudo chmod 666 /dev/i2c-0; sudo chmod 666 /dev/i2c-1").waitFor(5, TimeUnit.SECONDS);
        } catch (Exception ex) {
            LOGGER.error("unable to set file permissions in /dev/i2c-*, may be an issue or not.");
        }
    }

    /**
     * stop the i2c connection
     *
     */
    public void shutdown() {
        if (acCurrentDevice != null) {
            try {
                acCurrentDevice.close();
            } catch (IOException ex) {
                LOGGER.info("had some chatter when closing the i2c connection", ex);
            }
        }
        if (tempHumidityDevice != null) {
            try {
                tempHumidityDevice.close();
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
    public List<WsnData> processWiredSensors() {
        acquireCurrentSensorBusDevice();
        acquireTempHumidityBusDevice();
        List<WsnData> datas = newArrayList();
        if (acCurrentDevice == null) {
            LOGGER.info("skipping wired ac current sensor check, no i2c bus available");
        } else {
            try {
                WsnData data = getACCurrentSensorValueForAdc(0);
                if (data != null) {
                    datas.add(data);
                }
                data = getACCurrentSensorValueForAdc(1);
                if (data != null) {
                    datas.add(data);
                }
                data = getACCurrentSensorValueForAdc(2);
                if (data != null) {
                    datas.add(data);
                }
            } catch (Exception ex) {
                LOGGER.info("error when attempting to process ac current reading from i2c bus", ex);
            }
        }

        if (tempHumidityDevice == null) {
            LOGGER.info("skipping wired temp humidity sensor check, no i2c bus available");
        } else {
            try {
                datas.addAll(getTempHumiditySensorValue());
            } catch (Exception ex) {
                LOGGER.info("error when attempting to process ac current reading from i2c bus", ex);
            }
        }

        return datas;
    }

    private WsnData getACCurrentSensorValueForAdc(int adcNum) throws Exception {
        long unixTimestamp = System.currentTimeMillis() / 1000;
        ByteBuffer tempBuf = ByteBuffer.allocateDirect(READ_ADC_REGISTER_BYTES);
        acCurrentDevice.read(8 + (adcNum * READ_ADC_REGISTER_BYTES), tempBuf); // 8 is offset - http://wiki.embeddedarm.com/wiki/TS-7970#Silabs_Microcontroller
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
        wsnData.setReceivedUnixTimestamp(unixTimestamp);
        wsnData.setRecordedUnixTimestamp(unixTimestamp);
        wsnData.setSensorIdentifier("1");
        wsnData.setUom("amps");
        wsnData.setMoteMac("wired_ac_current_adc_" + adcNum);
        wsnData.setDeviceName("ac current sensor " + adcNum);
        LOGGER.info("Retrieved pump current sensor adc {} raw value is {} and amps value is {}", adcNum, adcValue, ampsValue );
        return wsnData;
    }

    private List<WsnData> getTempHumiditySensorValue() throws Exception {
        List<WsnData> datas = newArrayList();
        long unixTimestamp = System.currentTimeMillis() / 1000;
        ByteBuffer tempBuf = ByteBuffer.allocateDirect(READ_TEMP_HUMIDITY_REGISTER_BYTES);
        // byte 1 is MSB reading
        // byte 2 is LSB reading
        // byte 3 is CRC
        resetTempHumiditySensor();
        if (tempHumidityDevice.read() != HTU21DF_RESET_STATE) {
            LOGGER.info("temp humidity sensor not responding to reset command, cannot acquire readings at this time.");
            return datas;
        }

        datas.add(readTemperature(tempBuf, unixTimestamp));
        datas.add(readHumidity(tempBuf, unixTimestamp));

        LOGGER.info("Retrieved ambient temperature of {} and relative humidity of {}", datas.get(0).getValue(), datas.get(1).getValue());
        return datas;
    }

    private void resetTempHumiditySensor() throws Exception {
        tempHumidityDevice.write(HTU21DF_RESET);
        Thread.sleep(15);
        tempHumidityDevice.write(HTU21DF_READREG);
    }

    private WsnData readTemperature(ByteBuffer tempBuf, long unixTimestamp) throws Exception {
        tempHumidityDevice.write(HTU21DF_READTEMP);
        Thread.sleep(50);
        tempBuf.clear();
        tempHumidityDevice.read(tempBuf);
        tempBuf.flip();
        int reading = tempBuf.getShort();
        double tempFahr = reading * 175.72;
        tempFahr /= 65536;
        tempFahr -= 46.85;
        tempFahr *= 1.8;
        tempFahr += 32;

        WsnData wsnData = new WsnData();
        wsnData.setValue(tempFahr);
        wsnData.setQuality(QualityType.VALID);
        wsnData.setDataType(DataType.AMBIENT_TEMP);
        wsnData.setReceivedUnixTimestamp(unixTimestamp);
        wsnData.setRecordedUnixTimestamp(unixTimestamp);
        wsnData.setSensorIdentifier("1");
        wsnData.setUom("fahrenheit");
        wsnData.setMoteMac("wired_temp_humidity_sensor");
        wsnData.setDeviceName("ambient temp and humidity sensor");
        return wsnData;
    }

    private WsnData readHumidity(ByteBuffer tempBuf, long unixTimestamp) throws Exception {
        tempHumidityDevice.write(HTU21DF_READHUM);
        Thread.sleep(50);
        tempBuf.clear();
        tempHumidityDevice.read(tempBuf);
        tempBuf.flip();
        int reading = tempBuf.getShort();
        double relativeHumidity =  reading * 125;
        relativeHumidity /= 65536;
        relativeHumidity -= 6;

        WsnData wsnData = new WsnData();
        wsnData.setValue(relativeHumidity);
        wsnData.setQuality(QualityType.VALID);
        wsnData.setDataType(DataType.AMBIENT_HUMIDITY);
        wsnData.setReceivedUnixTimestamp(unixTimestamp);
        wsnData.setRecordedUnixTimestamp(unixTimestamp);
        wsnData.setSensorIdentifier("2");
        wsnData.setUom("percentage");
        wsnData.setMoteMac("wired_temp_humidity_sensor");
        wsnData.setDeviceName("ambient temp and humidity sensor");
        return wsnData;
    }

    private Double calculateAmpsFrom420LoopADCValue(int adcValue) {
        // This is how the ts7970 silabs ADC 10 bit conversion scales
        double sensorSignalAmps = (adcValue / 42.0) - 4.05; // adc measures 0-20 mA, expresses as 0-1024 value,
                                                            // ignore the first 4.04 mA becuase of 4-20mA output, the Senva C3245puts out 4.05 at no current
        if (sensorSignalAmps < 0.0) {
            return 0d;
        }

        double sensorSignalPercentage = sensorSignalAmps / 16.0; // in a 4-20, there are 16 mA of total range
        return sensorSignalPercentage * ampsMeasuredScale; // convert the percentage of total range into measured amps
    }

    private void acquireCurrentSensorBusDevice() {
        if (acCurrentDevice == null) {
            try {
                acCurrentDevice = DeviceManager.open(acCurrentConfig);
            } catch (Exception ex) {
                if (!printedError) {
                    LOGGER.error("unable to open i2c bus acCurrentDevice ", ex);
                    printedError = true;
                }
            }
        }
    }

    private void acquireTempHumidityBusDevice() {
        if (tempHumidityDevice == null) {
            try {
                tempHumidityDevice = DeviceManager.open(tempHumidityConfig);
            } catch (Exception ex) {
                if (!printedErrorTempHumidity) {
                    LOGGER.error("unable to open i2c bus for tempHumidity device ", ex);
                    printedErrorTempHumidity = true;
                }
            }
        }
    }

    @VisibleForTesting
    Process executeUnixCommand(String command) throws IOException {
        return Runtime.getRuntime().exec(command);
    }
}
