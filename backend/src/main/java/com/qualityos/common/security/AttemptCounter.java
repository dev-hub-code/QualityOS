package com.qualityos.common.security;

import java.time.Instant;

/** Counts attempts by one caller in one window of time. */
public interface AttemptCounter {

    /** Adds one attempt for {@code bucket} in the window starting at {@code window}, and returns the count so far. */
    int increment(String bucket, Instant window);
}
