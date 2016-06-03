package com.tritonsvc.gateway;

import com.google.common.primitives.Longs;
import com.tritonsvc.spa.communication.proto.Bwg;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Created by holow on 5/9/2016.
 */
public class FaultLogManager {

    private static final long DEFAULT_INTERVAL = 60000; // 1 minute
    private static final int CACHE_SIZE = 256;
    private final long fetchInterval;
    private final Map<String, FaultLogEntry> cache = new LinkedHashMap<>();
    private AtomicLong lastLogReceived = new AtomicLong(0);

    private int fetchNext = -1;

    public FaultLogManager(final Properties configProps) {
        // init default interval

        Long fetchIntervalLong = null;
        final String faultLogsFetchIntervalStr = configProps.getProperty("faultLogs.fetchInterval");
        if (faultLogsFetchIntervalStr != null) {
            fetchIntervalLong = Longs.tryParse(faultLogsFetchIntervalStr);
        }
        if (fetchIntervalLong != null) {
            fetchInterval = fetchIntervalLong.longValue();
        } else {
            fetchInterval = DEFAULT_INTERVAL;
        }
    }


    public long getFetchInterval() {
        return fetchInterval;
    }

    public long getLastLogReceived() {
        return lastLogReceived.get();
    }

    public void setLastLogReceived() {
        lastLogReceived.set(System.currentTimeMillis());
    }

    public synchronized int generateFetchNext() {
        int tmp = fetchNext;
        fetchNext = -1;
        return tmp;
    }

    public synchronized boolean addFaultLogEntry(final FaultLogEntry entry) {
        final String key = buildEntryKey(entry);
        boolean added = false;
        if (!cache.containsKey(key)) {
            if (cache.size() > CACHE_SIZE) {
                // removes the oldest one, sort order is by insertion
                cache.remove(cache.keySet().iterator().next());
            }

            cache.put(key, entry);
            added = true;
        }
        fetchNext = entry.getNumber() - 1;
        findFetchNext();
        return added;
    }

    /**
     * looks for biggest number that hasn't been fetched from device.
     */
    private void findFetchNext() {
        outer: while (true) {
            for (final FaultLogEntry entry : cache.values()) {
                if (entry.getNumber() == fetchNext) {
                    fetchNext--;
                    continue outer;
                }
            }
            // the number was not found in cache, leave it at that state, it will be requested
            // next time via generateFetchNext().
            break;
        }
    }

    private String buildEntryKey(final FaultLogEntry entry) {
        return new StringBuilder().append(entry.getNumber()).append('x').append(entry.getCode()).append('x').append(entry.getTimestamp()).toString();
    }

    public synchronized boolean hasUnsentFaultLogs() {
        for (final FaultLogEntry entry : cache.values()) {
            if (!entry.isSentToUplik()) {
                return true;
            }
        }
        return false;
    }

    public synchronized Bwg.Uplink.Model.FaultLogs getUnsentFaultLogs() {
        final List<FaultLogEntry> entries = new ArrayList<>(cache.size());
        for (final FaultLogEntry entry : cache.values()) {
            if (!entry.isSentToUplik()) {
                entries.add(entry);
                entry.setSentToUplik(true);
            }
        }

        if (entries.size() > 0) {
            final Bwg.Uplink.Model.FaultLogs.Builder builder = Bwg.Uplink.Model.FaultLogs.newBuilder();

            for (final FaultLogEntry entry : entries) {
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
