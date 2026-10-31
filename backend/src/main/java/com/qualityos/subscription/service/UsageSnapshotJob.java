package com.qualityos.subscription.service;

import com.qualityos.common.job.JobRunner;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Keeps each organisation's usage by day. Running twice in a day replaces that day's row. */
@Component
@ConditionalOnProperty(name = "qualityos.jobs.enabled", havingValue = "true")
class UsageSnapshotJob {

    private static final Duration LEASE = Duration.ofMinutes(30);

    private final JobRunner jobs;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Duration interval;

    UsageSnapshotJob(
            JobRunner jobs,
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            @Value("${qualityos.jobs.usage-snapshots.interval}") Duration interval) {
        this.jobs = jobs;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactionManager);
        this.interval = interval;
    }

    @Scheduled(
            initialDelayString = "${qualityos.jobs.usage-snapshots.initial-delay}",
            fixedDelayString = "${qualityos.jobs.usage-snapshots.interval}")
    void record() {
        jobs.run("usage-snapshots", LEASE, interval, context -> {
            Integer written = tx.execute(status ->
                    jdbc.queryForObject("SELECT record_usage_snapshots()", Integer.class));
            context.processed(written == null ? 0 : written);
        });
    }
}
