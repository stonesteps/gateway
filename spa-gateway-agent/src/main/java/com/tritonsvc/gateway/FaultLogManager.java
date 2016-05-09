package com.tritonsvc.gateway;

import com.google.common.primitives.Longs;
import com.tritonsvc.spa.communication.proto.Bwg;

import java.util.*;

/**
 * Created by holow on 5/9/2016.
 */
public class FaultLogManager {

    private static final long DEFAULT_INTERVAL = 600000; // 10 minutes
    private final long fetchInterval;
    private final Map<String, FaultLogEntry> cache = new HashMap<>();

    private int fetchNext = -1;

    public FaultLogManager(final Properties configProps) {
        // init default interval

        final String faultLogsFetchIntervalStr = configProps.getProperty("faultLogs.fetchInterval");
        Long fetchIntervalLong = Longs.tryParse(faultLogsFetchIntervalStr);
        if (fetchIntervalLong != null) {
            fetchInterval = fetchIntervalLong.longValue();
        } else {
            fetchInterval = DEFAULT_INTERVAL;
        }

    }

    public long getFetchInterval() {
        return fetchInterval;
    }

    public int getFetchNext() {
        return fetchNext;
    }

    public synchronized boolean addFaultLogEntry(final FaultLogEntry entry) {
        final String key = buildEntryKey(entry);
        boolean added = false;
        if (!cache.containsKey(key)) {
            cache.put(key, entry);
            added = true;
        }
        fetchNext = entry.getNumber() - 1;
        findFetchNext();
        return added;
    }

    private void findFetchNext() {
        if (fetchNext == -1) return;
        while (true) {
            boolean exit = true;
            for (final FaultLogEntry entry : cache.values()) {
                if (entry.getNumber() == fetchNext) {
                    fetchNext--;
                    exit = false;
                    break;
                }
            }
            if (exit) break;
        }
    }

    private String buildEntryKey(final FaultLogEntry entry) {
        return new StringBuilder(entry.getNumber()).append('x').append(entry.getCode()).append('x').append(entry.getTimestamp()).toString();
    }

    public synchronized Bwg.Uplink.Model.FaultLogs getUnsentFaultLogEntryList() {
        final List<FaultLogEntry> entries = new ArrayList<>(cache.size());
        for (final FaultLogEntry entry : cache.values()) {
            if (!entry.isSentToUplik()) {
                entries.add(entry);
                entry.setSentToUplik(true);
            }
        }

        if (entries.size() > 0) {
            final Bwg.Uplink.Model.FaultLogs.Builder builder =  Bwg.Uplink.Model.FaultLogs.newBuilder();

            for (final FaultLogEntry entry: entries) {
                final Bwg.Uplink.Model.FaultLog.Builder flBuilder = Bwg.Uplink.Model.FaultLog.newBuilder();
                flBuilder.setOccurenceDate(entry.getTimestamp());
                flBuilder.setFaultCode(entry.getCode());
                flBuilder.setTargetTemp(entry.getTargetTemp());
                flBuilder.setSensorATemp(entry.getSensorATemp());
                flBuilder.setSensorBTemp(entry.getSensorBTemp());
                flBuilder.setCelcius(entry.isCelcius());
                builder.addFaultLogs(flBuilder.build());
            }
            return builder.build();
        }

        return null;
    }
}
