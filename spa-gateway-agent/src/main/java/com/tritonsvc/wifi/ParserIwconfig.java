package com.tritonsvc.wifi;

import com.tritonsvc.spa.communication.proto.Bwg.Uplink.Model.WifiStat;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.Model.WifiStat.WifiConnectionDiagnostics;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Date;

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

    public WifiStat parseStat(String interfaceName) throws Exception {

        String line;
        WifiStat.Builder wifiStatBuilder = WifiStat.newBuilder();
        WifiConnectionDiagnostics.Builder dataBuilder = WifiConnectionDiagnostics.newBuilder();
        Process proc = Runtime.getRuntime().exec("sudo iwconfig " + interfaceName);
        wifiStatBuilder.setApConnected(false);
        wifiStatBuilder.setConnectedDiag(dataBuilder);
        wifiStatBuilder.setRecordedDate(new Date().getTime());

        try (BufferedReader iwconfigInput = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
            while ((line = iwconfigInput.readLine()) != null) {

                ///// BASE STATION MAC /////
                int macIndex = line.indexOf("Access Point:");
                if (macIndex != -1) {
                    String bsMAC = line.substring(macIndex + "Access Point:".length());
                    bsMAC = bsMAC.trim();
                    if (!bsMAC.equalsIgnoreCase("Not-Associated")) {
                        wifiStatBuilder.setApConnected(true);
                        wifiStatBuilder.setApMacAddress(bsMAC);
                    }
                }

            }
        } finally {
            proc.destroyForcibly();
        }
        return wifiStatBuilder.build();
    }
}