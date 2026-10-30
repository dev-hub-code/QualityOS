package com.qualityos.common.util;

import java.security.SecureRandom;
import java.util.UUID;

/** Time-ordered UUIDs, which keep primary key indexes compact. */
public final class UuidV7 {

    private static final SecureRandom RANDOM = new SecureRandom();

    private UuidV7() {
    }

    public static UUID generate() {
        long timestamp = System.currentTimeMillis();
        long high = (timestamp << 16) | 0x7000L | (RANDOM.nextLong() & 0x0FFFL);
        long low = (RANDOM.nextLong() & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;
        return new UUID(high, low);
    }
}
