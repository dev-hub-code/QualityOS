package com.qualityos.subscription.service;

import java.time.Duration;
import java.time.Instant;

/** A lapsed organisation keeps working through the grace period, then can read and export but not change. */
public enum SubscriptionState {
    CURRENT, GRACE, READ_ONLY;

    public static SubscriptionState of(Instant endsAt, int graceDays, Instant now) {
        if (now.isBefore(endsAt)) {
            return CURRENT;
        }
        return now.isBefore(endsAt.plus(Duration.ofDays(graceDays))) ? GRACE : READ_ONLY;
    }
}
