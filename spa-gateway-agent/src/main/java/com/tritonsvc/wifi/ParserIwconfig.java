package com.tritonsvc.wifi;

import com.google.common.primitives.Doubles;
import com.google.common.primitives.Ints;
import com.google.common.primitives.Longs;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.Model.Constants.WifiConnectionHealth;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.Model.WifiStat;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.Model.WifiStat.WifiConnectionDiagnostics;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Date;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ParserIwconfig {

    /**
     these are obtained from linux 'iwconfig wlan0' command
     the output of command defined in detail at http://linux.die.net/man/8/iwconfig

     ** when connected **
     Mode:Managed  Frequency:2.412 GHz  Access Point: 00:24:17:44:35:28
     Bit Rate=48 Mb/s   Tx-Power=19 dBm
     Retry limit:231   RTS thr:off   Fragment thr:off
     Power Management:off
     Link Quality=46/70  Signal level=-64 dBm
     Rx invalid nwid:0  Rx invalid crypt:0  Rx invalid frag:0
     Tx excessive retries:170  Invalid misc:134   Missed beacon:0

     ** when disconnected **
     wlan0     IEEE 802.11abgn  ESSID:off/any
     Mode:Managed  Access Point: Not-Associated   Tx-Power=20 dBm
     Retry  long limit:7   RTS thr:off   Fragment thr:off
     Power Management:on
     */

    public WifiStat parseStat(String interfaceName, WifiStat previousWifiStat) throws Exception {

        String line;
        WifiStat.Builder wifiStatBuilder = WifiStat.newBuilder();
        WifiConnectionDiagnostics.Builder dataBuilder = WifiConnectionDiagnostics.newBuilder();
        Process proc = Runtime.getRuntime().exec("sudo iwconfig " + interfaceName);
        wifiStatBuilder.setWifiConnectionHealth(WifiConnectionHealth.UNKONWN);
        wifiStatBuilder.setConnectedDiag(dataBuilder);
        wifiStatBuilder.setRecordedDate(new Date().getTime());
        Pattern numericGrab = Pattern.compile("(-?[0-9]+)");
        Pattern paramGrab = Pattern.compile("[:,=](.*)");

        try (BufferedReader iwconfigInput = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
            while ((line = iwconfigInput.readLine()) != null) {

                line = line.toLowerCase();

                ///// BASE STATION MAC /////
                int macIndex = line.indexOf("access point");
                String bsMAC = processMatch(paramGrab, line.substring(macIndex));
                if (bsMAC != null) {
                    if (!bsMAC.equalsIgnoreCase("not-associated")) {
                        wifiStatBuilder.setApMacAddress(bsMAC);
                        wifiStatBuilder.setWifiConnectionHealth(WifiConnectionHealth.AVG);
                    } else {
                        wifiStatBuilder.setWifiConnectionHealth(WifiConnectionHealth.DISCONNECTED);
                    }
                }

                ///// BASE STATION ESSID /////
                int essidIndex = line.indexOf("essid");
                String essid = processMatch(paramGrab, line.substring(essidIndex));
                if (essid != null) {
                    wifiStatBuilder.setSSID(essid);
                }

                ///// ACCESS POINT MODE /////
                int modeIndex = line.indexOf("mode");
                int frequencyIndex = line.indexOf("frequency");
                if (modeIndex != -1 && frequencyIndex != -1) {
                    String mode = processMatch(paramGrab, line.substring(modeIndex, frequencyIndex - 1));
                    if (mode != null) {
                        wifiStatBuilder.setMode(mode);
                    }
                }

                ///// FREQ /////
                if (frequencyIndex != -1 && macIndex != -1) {
                    String freq = processMatch(paramGrab, line.substring(frequencyIndex, macIndex - 1));
                    if (freq != null) {
                        dataBuilder.setFrequency(freq);
                    }
                }

                ///// Signal Level /////
                int signalIndex = line.indexOf("signal level");
                if (signalIndex != -1) {
                    String signalLevel = processMatch(numericGrab, line.substring(signalIndex));
                    if (signalLevel != null) {
                        Long level = Longs.tryParse(signalLevel);
                        if (level != null) {
                            dataBuilder.setSignalLevelUnits(level);
                        }
                    }
                }

                ///// bit rate /////
                int bitRateIndex = line.indexOf("bit rate");
                int txPowerIndex = line.indexOf("tx-power");
                if (bitRateIndex != -1 && txPowerIndex != -1) {
                    String bitRate = processMatch(paramGrab, line.substring(bitRateIndex, txPowerIndex - 1));
                    if (bitRate != null) {
                        dataBuilder.setDataRate(bitRate);
                    }
                }

                ///// TX POWER /////
                if (txPowerIndex != -1) {
                    String txPower = processMatch(numericGrab, line.substring(txPowerIndex));
                    if (txPower != null) {
                        Double power = Doubles.tryParse(txPower);
                        if (power != null) {
                            wifiStatBuilder.setTxPowerDbm(power);
                        }
                    }
                }

                ///// retry limit /////
                int retryLimitIndex = line.indexOf("retry limit");
                int rtsIndex = line.indexOf("rts thr");
                if (retryLimitIndex != -1 && rtsIndex != -1) {
                    String retryLimit = processMatch(paramGrab, line.substring(retryLimitIndex, rtsIndex - 1));
                    if (retryLimit != null) {
                        wifiStatBuilder.setRetryConfig(retryLimit);
                    }
                }

                ///// rts /////
                int fragmentIndex = line.indexOf("fragment thr");
                if (rtsIndex != -1 && fragmentIndex != -1) {
                    String rts = processMatch(paramGrab, line.substring(rtsIndex, fragmentIndex - 1));
                    if (rts != null) {
                        wifiStatBuilder.setRtsConfig(rts);
                    }
                }

                ///// fragment /////
                if (fragmentIndex != -1) {
                    String fragment = processMatch(paramGrab, line.substring(fragmentIndex));
                    if (fragment != null) {
                        wifiStatBuilder.setFragConfig(fragment);
                    }
                }

                ///// Power management /////
                int powerMgmtIndex = line.indexOf("power management");
                if (powerMgmtIndex != -1) {
                    String powerMgmt = processMatch(paramGrab, line.substring(powerMgmtIndex));
                    if (powerMgmt != null) {
                        wifiStatBuilder.setPowerMgmtConfig(powerMgmt);
                    }
                }

                ///// link quality /////
                int linkQualityIndex = line.indexOf("link quality");
                int signalLevelIndex = line.indexOf("signal level");
                if (linkQualityIndex != -1 && signalLevelIndex != -1) {
                    String linkQuality = processMatch(paramGrab, line.substring(linkQualityIndex, signalLevelIndex - 1));
                    if (linkQuality != null) {
                        dataBuilder.setLinkQualityRaw(linkQuality);
                        String[] parts = linkQuality.split("[\\\\,\\/]");
                        if (parts.length == 2) {
                            Double numerator = Doubles.tryParse(parts[0]);
                            Double denominator = Doubles.tryParse(parts[1]);
                            long linkQualityPercent = Math.round(numerator / denominator);
                            if (numerator != null && denominator != null) {
                                dataBuilder.setLinkQualityPercentage(linkQualityPercent);
                            }

                            if (linkQualityPercent < 34) {
                                wifiStatBuilder.setWifiConnectionHealth(WifiConnectionHealth.WEAK);
                            } else if (linkQualityPercent > 67) {
                                wifiStatBuilder.setWifiConnectionHealth(WifiConnectionHealth.AVG);
                            } else {
                                wifiStatBuilder.setWifiConnectionHealth(WifiConnectionHealth.STRONG);
                            }
                        }
                    }
                }

                ///// signal level /////
                if (signalLevelIndex != -1) {
                    String signalLevel = processMatch(numericGrab, line.substring(signalLevelIndex));
                    if (signalLevel != null) {
                        Long signal = Longs.tryParse(signalLevel);
                        if (signal != null) {
                            dataBuilder.setSignalLevelUnits(signal);
                        }
                    }
                }

                ///// invalid nwid /////
                int invalidNetworkIndex = line.indexOf("rx invalid nwid");
                int invalidCryptIndex = line.indexOf("rx invalid crypt");
                if (invalidNetworkIndex != -1 && invalidCryptIndex != -1) {
                    String invalidNwid = processMatch(numericGrab, line.substring(invalidNetworkIndex, invalidCryptIndex - 1));
                    if (invalidNwid != null && Ints.tryParse(invalidNwid) != null) {
                        dataBuilder.setRxOtherAPPacketCount(Ints.tryParse(invalidNwid));
                    }
                }

                ///// invalid crypt /////
                int invalidFragIndex = line.indexOf("rx invalid frag");
                if (invalidCryptIndex != -1 && invalidFragIndex != -1) {
                    String invalidCrypt = processMatch(numericGrab, line.substring(invalidCryptIndex, invalidFragIndex - 1));
                    if (invalidCrypt != null && Ints.tryParse(invalidCrypt) != null) {
                        dataBuilder.setRxInvalidCryptPacketCount(Ints.tryParse(invalidCrypt));
                    }
                }

                ///// invalid frag /////
                if (invalidFragIndex != -1) {
                    String invalidFrag = processMatch(numericGrab, line.substring(invalidFragIndex));
                    if (invalidFrag != null && Ints.tryParse(invalidFrag) != null) {
                        dataBuilder.setRxInvalidFragPacketCount(Ints.tryParse(invalidFrag));
                    }
                }

                ///// tx excessive retrty /////
                int txExcessiveIndex = line.indexOf("Tx excessive retries");
                int invalidMiscIndex = line.indexOf("invalid misc");
                if (txExcessiveIndex != -1 && invalidMiscIndex != -1) {
                    String invalidExcessive = processMatch(numericGrab, line.substring(txExcessiveIndex, invalidMiscIndex - 1));
                    if (invalidExcessive != null && Ints.tryParse(invalidExcessive) != null) {
                        dataBuilder.setTxExcessiveRetries(Ints.tryParse(invalidExcessive));
                    }
                }

                ///// missed beacon /////
                int missedBeaconIndex = line.indexOf("missed beacon");
                if (missedBeaconIndex != -1) {
                    String missedBeacon = processMatch(numericGrab, line.substring(missedBeaconIndex));
                    if (missedBeacon != null && Ints.tryParse(missedBeacon) != null) {
                        dataBuilder.setLostBeaconCount(Ints.tryParse(missedBeacon));
                    }
                }
            }
        } finally {
            proc.destroyForcibly();
        }
        return wifiStatBuilder.build();
    }

    private String processMatch(Pattern pattern, String input) {
        Matcher matcher = pattern.matcher(input);
        if (matcher.find()) {
            return matcher.group().trim();
        }
        return null;
    }
}