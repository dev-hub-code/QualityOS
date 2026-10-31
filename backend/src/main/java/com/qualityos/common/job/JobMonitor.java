package com.qualityos.common.job;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Notices a job that has gone quiet, which nothing else would, because a job that never runs logs no error. */
@Component
public class JobMonitor implements HealthIndicator {

    private static final Status STALE = new Status("STALE");
    private static final int MISSED_RUNS = 3;
    private static final int RECENT_RUNS = 50;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Map<String, Duration> expected = new LinkedHashMap<>();
    private final Instant started = Instant.now();
    private final boolean enabled;

    JobMonitor(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            @Value("${qualityos.jobs.notification-delivery.interval}") Duration delivery,
            @Value("${qualityos.jobs.capa-reminders.interval}") Duration capas,
            @Value("${qualityos.jobs.calibration-reminders.interval}") Duration calibrations,
            @Value("${qualityos.jobs.subscription-notices.interval}") Duration subscriptions,
            @Value("${qualityos.jobs.usage-snapshots.interval}") Duration usage,
            @Value("${qualityos.jobs.unconfirmed-shipments.interval}") Duration shipments,
            @Value("${qualityos.jobs.account-maintenance.interval}") Duration accounts,
            @Value("${qualityos.jobs.enabled:false}") boolean enabled) {
        this.jdbc = jdbc;
        this.enabled = enabled;
        this.tx = new TransactionTemplate(transactionManager);
        expected.put("notification-delivery", delivery);
        expected.put("capa-reminders", capas);
        expected.put("calibration-reminders", calibrations);
        expected.put("subscription-notices", subscriptions);
        expected.put("usage-snapshots", usage);
        expected.put("unconfirmed-shipments", shipments);
        expected.put("account-maintenance", accounts);
    }

    public record JobOverview(
            String name, long intervalSeconds, Instant lastStartedAt, String lastStatus, Long lastDurationMs,
            long failuresToday, boolean stale) {
    }

    public record JobRun(
            Instant startedAt, long durationMs, int tenantsProcessed, int recordsProcessed, int notificationsGenerated,
            int failureCount, String status) {
    }

    /** Every job this instance expects, so that one which has never run still shows. */
    public List<JobOverview> overview(Instant now) {
        Map<String, String> quiet = quiet(now);
        return expected.entrySet().stream()
                .map(job -> tx.execute(status -> {
                    List<JobRun> latest = latest(job.getKey(), 1);
                    Long failures = jdbc.queryForObject("""
                            SELECT count(*) FROM scheduled_job_executions
                            WHERE job_name = ? AND status <> 'SUCCESS' AND started_at > now() - INTERVAL '1 day'
                            """, Long.class, job.getKey());
                    JobRun last = latest.isEmpty() ? null : latest.getFirst();
                    return new JobOverview(job.getKey(), job.getValue().toSeconds(), last == null ? null : last.startedAt(),
                            last == null ? null : last.status(), last == null ? null : last.durationMs(), failures,
                            quiet.containsKey(job.getKey()));
                }))
                .toList();
    }

    /** Only jobs this instance knows, so the name in a request is never trusted as anything else. */
    public Optional<List<JobRun>> runs(String job) {
        return expected.containsKey(job)
                ? Optional.of(tx.execute(status -> latest(job, RECENT_RUNS)))
                : Optional.empty();
    }

    private List<JobRun> latest(String job, int limit) {
        return jdbc.query("""
                SELECT started_at, duration_ms, tenants_processed, records_processed, notifications_generated,
                       failure_count, status
                FROM scheduled_job_executions WHERE job_name = ? ORDER BY started_at DESC LIMIT ?
                """, (rows, row) -> new JobRun(rows.getTimestamp("started_at").toInstant(), rows.getLong("duration_ms"),
                rows.getInt("tenants_processed"), rows.getInt("records_processed"),
                rows.getInt("notifications_generated"), rows.getInt("failure_count"), rows.getString("status")),
                job, limit);
    }

    /** @return each job that has missed three runs, and when it last ran, or {@code never} */
    public Map<String, String> stale(Instant now) {
        // An instance with jobs switched off runs none, and that says nothing is wrong.
        return enabled ? quiet(now) : Map.of();
    }

    private Map<String, String> quiet(Instant now) {
        Map<String, String> quiet = new LinkedHashMap<>();
        expected.forEach((job, interval) -> {
            Instant limit = now.minus(interval.multipliedBy(MISSED_RUNS));
            Instant last = tx.execute(status -> jdbc.queryForObject(
                    "SELECT max(started_at) FROM scheduled_job_executions WHERE job_name = ?", Instant.class, job));
            if (last == null ? started.isBefore(limit) : last.isBefore(limit)) {
                quiet.put(job, last == null ? "never" : last.toString());
            }
        });
        return quiet;
    }

    @Override
    public Health health() {
        Map<String, String> quiet = stale(Instant.now());
        return quiet.isEmpty() ? Health.up().build() : Health.status(STALE).withDetails(Map.copyOf(quiet)).build();
    }
}
