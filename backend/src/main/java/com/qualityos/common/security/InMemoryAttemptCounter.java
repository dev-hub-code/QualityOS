package com.qualityos.common.security;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Counts in this instance's memory: lost on restart, and not shared. A fallback, and for unit tests. */
public class InMemoryAttemptCounter implements AttemptCounter {

    private final Map<String, AtomicInteger> counts = new ConcurrentHashMap<>();
    private volatile Instant currentWindow = Instant.EPOCH;

    @Override
    public int increment(String bucket, Instant window) {
        if (!window.equals(currentWindow)) {
            synchronized (this) {
                if (!window.equals(currentWindow)) {
                    counts.clear();
                    currentWindow = window;
                }
            }
        }
        return counts.computeIfAbsent(bucket, key -> new AtomicInteger()).incrementAndGet();
    }
}
