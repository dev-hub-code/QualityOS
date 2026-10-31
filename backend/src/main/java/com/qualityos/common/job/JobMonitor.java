package com.qualityos.common.job;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Notices a job that has gone quiet, which nothing else would, because a job that never runs logs no error. */
@Component
@ConditionalOnProperty(name = "qualityos.jobs.enabled", havingValue = "true")
public class JobMonitor implements HealthIndicator {

    private static final Logger log = LoggerFactory.getLogger(JobMonitor.class);
    private static final Status STALE = new Status("STALE");
    private static final int MISSED_RUNS = 3;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Map<String, Duration> expected = new LinkedHashMap<>();
    private final Instant started = Instant.now();

    JobMonitor(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            @Value("${qualityos.jobs.notification-delivery.interval}") Duration delivery,
            @Value("${qualityos.jobs.capa-reminders.interval}") Duration capas,
            @Value("${qualityos.jobs.unconfirmed-shipments.interval}") Duration shipments,
            @Value("${qualityos.jobs.account-maintenance.interval}") Duration accounts) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactionManager);
        expected.put("notification-delivery", delivery);
        expected.put("capa-reminders", capas);
        expected.put("unconfirmed-shipments", shipments);
        expected.put("account-maintenance", accounts);
    }

    @Scheduled(initialDelayString = "${qualityos.jobs.monitor.interval}", fixedDelayString = "${qualityos.jobs.monitor.interval}")
    void warnAboutStaleJobs() {
        stale(Instant.now()).forEach((job, last) -> log.warn("job={} has not run since {}", job, last));
    }

    /** @return each job that has missed three runs, and when it last ran, or {@code never} */
    public Map<String, String> stale(Instant now) {
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
