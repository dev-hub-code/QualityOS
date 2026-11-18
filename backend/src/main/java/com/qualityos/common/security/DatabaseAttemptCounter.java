package com.qualityos.common.security;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Counts in PostgreSQL, so that every instance shares one limit and a restart does not reset it. One statement per
 * attempt, with no transaction of its own beyond it. If the database cannot be reached, it counts in memory instead:
 * the limit then applies per instance again, rather than letting every attempt through.
 */
public class DatabaseAttemptCounter implements AttemptCounter {

    private static final Logger log = LoggerFactory.getLogger(DatabaseAttemptCounter.class);
    /** Windows older than this are deleted, at most once per window per instance. */
    private static final Duration KEEP = Duration.ofMinutes(10);

    private static final String INCREMENT = """
            INSERT INTO auth_attempts (bucket, window_start, attempts) VALUES (?, ?, 1)
            ON CONFLICT (bucket, window_start) DO UPDATE SET attempts = auth_attempts.attempts + 1
            RETURNING attempts
            """;

    private final JdbcTemplate jdbc;
    private final AttemptCounter fallback = new InMemoryAttemptCounter();
    private final AtomicReference<Instant> lastCleanup = new AtomicReference<>(Instant.EPOCH);

    public DatabaseAttemptCounter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public int increment(String bucket, Instant window) {
        try {
            cleanUp(window);
            Integer attempts = jdbc.queryForObject(INCREMENT, Integer.class, bucket, Timestamp.from(window));
            return attempts == null ? 1 : attempts;
        } catch (DataAccessException ex) {
            log.warn("Shared sign-in rate limit unavailable, counting on this instance: {}", ex.getMessage());
            return fallback.increment(bucket, window);
        }
    }

    private void cleanUp(Instant window) {
        Instant last = lastCleanup.get();
        if (!last.equals(window) && lastCleanup.compareAndSet(last, window)) {
            jdbc.update("DELETE FROM auth_attempts WHERE window_start < ?", Timestamp.from(window.minus(KEEP)));
        }
    }
}
