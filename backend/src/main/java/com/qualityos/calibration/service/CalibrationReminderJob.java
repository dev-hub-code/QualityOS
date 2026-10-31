package com.qualityos.calibration.service;

import com.qualityos.common.job.JobRunner;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Runs hourly so that each plant's day is noticed soon after it ends, wherever that plant is. */
@Component
@ConditionalOnProperty(name = "qualityos.jobs.enabled", havingValue = "true")
class CalibrationReminderJob {

    private static final Duration LEASE = Duration.ofMinutes(30);

    private final InstrumentService instruments;
    private final JobRunner jobs;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Duration interval;

    CalibrationReminderJob(
            InstrumentService instruments,
            JobRunner jobs,
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            @Value("${qualityos.jobs.calibration-reminders.interval}") Duration interval) {
        this.instruments = instruments;
        this.jobs = jobs;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactionManager);
        this.interval = interval;
    }

    @Scheduled(
            initialDelayString = "${qualityos.jobs.calibration-reminders.initial-delay}",
            fixedDelayString = "${qualityos.jobs.calibration-reminders.interval}")
    void remind() {
        jobs.run("calibration-reminders", LEASE, interval, context -> {
            List<UUID> tenants =
                    tx.execute(status -> jdbc.queryForList("SELECT tenants_with_instruments_due()", UUID.class));
            jobs.forEachTenant(context, tenants, tenant -> {
                int sent = instruments.remindOverdue() + instruments.remindDueSoon();
                context.processed(sent);
                context.notified(sent);
            });
        });
    }
}
